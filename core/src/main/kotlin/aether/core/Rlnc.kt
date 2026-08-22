package aether.core

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Sliding-window Random Linear Network Coding over GF(256).
 * Systematic: source symbols go as-is; repair symbols are random combos of the current window.
 * Receiver decodes incrementally on arrival — no block boundary wait, which is what keeps tails flat.
 */
object GF256 {
    private val exp = IntArray(512)
    private val log = IntArray(256)
    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x; log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }
    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    fun inv(a: Int): Int = exp[255 - log[a]]

    /**
     * The multiply-accumulate hot kernel: `dst[i] ^= src[i] * c` for `i in dst.indices` (`src.size >= dst.size`,
     * `c` in 0..255, `c == 0` is a no-op). Every implementation must produce bytes identical to [Scalar].
     */
    fun interface Kernel {
        fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int)
    }

    /**
     * A kernel that also runs on native (off-heap) memory. While one is installed, [RlncEncoder] and [RlncDecoder]
     * keep their accumulators off-heap so the hot loop does no heap<->native copies of the accumulator; the encoder
     * additionally mirrors its window off-heap, so `repair()` runs without copying anything but the result.
     * Installed by `aether.native.Gf256Native.install()`; core itself never depends on `:native`.
     */
    interface OffHeapKernel : Kernel {
        /** `dst[0, len) ^= src[0, len) * c`; both segments are native and do not overlap. */
        fun mulAddInto(dst: MemorySegment, src: MemorySegment, len: Long, c: Int)
        /** `dst[0, src.size) ^= src * c` — a heap source (copied once) accumulated into a native segment. */
        fun mulAddInto(dst: MemorySegment, src: ByteArray, c: Int)
    }

    /** Portable reference kernel (two table lookups per byte). The default, and the oracle for every other kernel. */
    object Scalar : Kernel {
        override fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int) {
            if (c == 0) return
            for (i in dst.indices) dst[i] = ((dst[i].toInt() and 0xFF) xor mul(src[i].toInt() and 0xFF, c)).toByte()
        }
    }

    /**
     * The kernel [mulAddInto] dispatches to. Defaults to [Scalar]; set once at startup via [useNative] (from
     * `:native`, which this module does not depend on) or reset with [useScalar]. Volatile: switching it while
     * coders are live is safe, only the next call sees the new kernel.
     */
    @Volatile var kernel: Kernel = Scalar

    /** Installs `k` as the kernel (call once at startup, e.g. `Gf256Native.install()`). */
    fun useNative(k: Kernel) { kernel = k }
    fun useScalar() { kernel = Scalar }

    fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int) {
        if (c == 0) return
        kernel.mulAddInto(dst, src, c)
    }
}

/**
 * The sender's sliding window: the last [maxWindow] symbols pushed, by contiguous `seq`.
 *
 * `repair(seed)` combines the whole window with coefficients `1 + Random(seed).nextInt(255)` in window order, and
 * describes the window as `[windowBase, windowBase + windowLen)`; [RlncDecoder.onRepair] regenerates the same
 * coefficients from the seed, so the window must be contiguous in `seq` — [push] checks it. The window slides inside
 * [push]; a repair always describes the window as it is at `repair()` time.
 *
 * Thread-safety: none — one thread at a time (the transport holds its connection lock around every call; its sender
 * thread pushes, its timer thread repairs). [push] keeps a reference to `data` (no copy) while the off-heap mirror
 * copies it at push time, so `data` must not be modified after the call: the scalar kernel would read the modified
 * bytes at `repair()` time and the native mirror the original ones. All off-heap state (the mirror and its
 * accumulator slot) belongs to this instance.
 */
class RlncEncoder(private val symbolSize: Int, private val maxWindow: Int = 64) {
    private class Sym(val seq: Long, val data: ByteArray, val slot: Int)
    private val window = ArrayDeque<Sym>()
    private var base = 0L
    private var nextSlot = 0
    /**
     * Off-heap mirror of the window (slot `i` at `i * symbolSize`) plus one accumulator slot after it, created by the
     * first [repair] that runs on a [GF256.OffHeapKernel]; [push] keeps it in sync from then on. Slots are assigned
     * round-robin, so a live entry is never overwritten (the window holds at most `maxWindow` entries).
     */
    private var mirror: Array<MemorySegment>? = null

    init { require(symbolSize > 0 && maxWindow > 0) }

    fun push(seq: Long, data: ByteArray) {
        require(data.size == symbolSize) { "symbol of ${data.size} bytes, expected $symbolSize" }
        require(window.isEmpty() || seq == window.last().seq + 1) { "seq $seq does not follow ${window.last().seq}: the window must be contiguous" }
        if (window.isEmpty()) base = seq
        val slot = nextSlot
        nextSlot = if (slot + 1 == maxWindow) 0 else slot + 1
        window.addLast(Sym(seq, data, slot))
        mirror?.let { MemorySegment.copy(data, 0, it[slot], JAVA_BYTE, 0L, symbolSize) }
        while (window.size > maxWindow) { window.removeFirst(); base = window.first().seq }
    }

    fun repair(seed: Int): Frame.Repair {
        val rnd = Random(seed)
        val out = ByteArray(symbolSize)
        val k = GF256.kernel
        if (k is GF256.OffHeapKernel) {
            val m = mirror ?: createMirror()
            val acc = m[maxWindow]
            acc.fill(0)
            val len = symbolSize.toLong()
            for (s in window) k.mulAddInto(acc, m[s.slot], len, 1 + rnd.nextInt(255))
            MemorySegment.copy(acc, JAVA_BYTE, 0L, out, 0, symbolSize)
        } else {
            for (s in window) { val c = 1 + rnd.nextInt(255); k.mulAddInto(out, s.data, c) }
        }
        return Frame.Repair(base, window.size, seed, ByteBuffer.wrap(out))
    }

    private fun createMirror(): Array<MemorySegment> {
        val len = symbolSize.toLong()
        val all = Arena.ofAuto().allocate(len * (maxWindow + 1), 64)
        val slots = Array(maxWindow + 1) { all.asSlice(it * len, len) }
        for (s in window) MemorySegment.copy(s.data, 0, slots[s.slot], JAVA_BYTE, 0L, symbolSize)
        mirror = slots
        return slots
    }
}

/**
 * The receiver: incremental Gaussian elimination over whatever repairs and sources arrive, in any order.
 *
 * State is `known` (seq -> symbol; never evicted, the transport rotates decoders) and `pivots`: the open rows in
 * reduced row-echelon form, keyed by pivot seq. Invariants between calls — (I1) a row has coefficient 1 at its key,
 * (I2) no row has a non-zero coefficient at another row's key, (I3) no row references a known seq, (I4) every row has
 * at least two unknowns (a row down to one unknown is a solution and is learned at once). A repair is reduced against
 * the pivots, normalized on its lowest unknown and back-substituted into the others; a source is substituted into
 * every row that references it. When the source arrives for a seq that is already a pivot key — the transport's
 * residual ARQ re-sends lost sources verbatim, and reordering does the same — that row loses its pivot and is
 * re-inserted under a new one so that (I1)/(I2) hold again. (Before this was done, the row stayed under its old key
 * with no unit coefficient; a later repair's back-substitution could reduce it to one unknown, and its payload — a
 * GF(256) multiple of the true symbol — was learned unnormalized: a wrong solve that then spread into every row it
 * touched. The transport saw it as a recovered symbol with an out-of-range length prefix, ~1 in 5000 repair-decoded
 * symbols under loss + ARQ; the harness in core's test fixtures reproduces it at 2% of decoded symbols.)
 *
 * Integrity, at no wire cost: the optional [validator] vets every solved symbol before it is learned ([rejected]
 * counts refusals; a refused symbol never reaches another row), and a repair that reduces to no unknowns must leave a
 * zero remainder ([inconsistent] counts contradictions between a repair and the known symbols — corrupt input, or an
 * encoder/decoder window or seed mismatch). The validator is a few byte compares per solve; the zero check one pass
 * over the remainder of a redundant repair. Both counters are per instance: read them before dropping a decoder.
 *
 * Thread-safety: none — one thread at a time (the transport's rx thread, under the connection lock). The off-heap
 * accumulator is per instance; the native kernel's scratch is per thread and carries nothing between calls.
 */
class RlncDecoder(private val symbolSize: Int, private val validator: SymbolValidator? = null) {
    /**
     * Accepts or rejects a symbol the decoder solved (sources are trusted; they come authenticated). The transport can
     * check the length prefix and the FEC extension frame `0x80 0x02 fecSeq16` at the start of the body against `seq`:
     * no wrong solve that is a GF(256) multiple of the true symbol passes, since `c * 0x80 == 0x80` only for `c == 1`.
     */
    fun interface SymbolValidator { fun isValid(seq: Long, symbol: ByteArray): Boolean }

    /** Solved symbols the [validator] refused; they were not learned, so they cannot reach other rows. */
    var rejected = 0L; private set
    /** Repairs that reduced to no unknowns with a non-zero remainder: the known symbols and the repair contradict each other. */
    var inconsistent = 0L; private set

    private val known = HashMap<Long, ByteArray>()
    /** An open row: coefficient per unknown seq (never a known one), and the payload it equals. */
    private class Row(val coeffs: HashMap<Long, Int>, val payload: ByteArray)
    private val pivots = HashMap<Long, Row>()
    /** Off-heap accumulator for [onRepair] while a [GF256.OffHeapKernel] is installed (lazily allocated). */
    private var acc: MemorySegment? = null

    init { require(symbolSize > 0) }

    fun onSource(seq: Long, data: ByteArray) {
        require(data.size == symbolSize) { "symbol of ${data.size} bytes, expected $symbolSize" }
        learn(seq, data)
    }

    fun onRepair(r: Frame.Repair) {
        val rnd = Random(r.seed)
        val coeffs = HashMap<Long, Int>()
        val payload = ByteArray(symbolSize)
        r.symbol.duplicate().get(payload, 0, minOf(symbolSize, r.symbol.remaining()))
        val k = GF256.kernel
        if (k is GF256.OffHeapKernel) {
            // substitute every known source into an off-heap accumulator: one copy per known symbol, none of the payload per step
            val a = acc ?: Arena.ofAuto().allocate(symbolSize.toLong(), 64).also { acc = it }
            MemorySegment.copy(payload, 0, a, JAVA_BYTE, 0L, symbolSize)
            for (i in 0 until r.windowLen) {
                val c = 1 + rnd.nextInt(255); val seq = r.windowBase + i
                val s = known[seq]
                if (s != null) k.mulAddInto(a, s, c) else coeffs[seq] = c
            }
            MemorySegment.copy(a, JAVA_BYTE, 0L, payload, 0, symbolSize)
        } else {
            for (i in 0 until r.windowLen) {
                val c = 1 + rnd.nextInt(255); val seq = r.windowBase + i
                val s = known[seq]
                if (s != null) k.mulAddInto(payload, s, c) else coeffs[seq] = c
            }
        }
        insert(Row(coeffs, payload))
    }

    /** Reduces `row` (no known seqs in it) against the pivots and makes it one; then takes out whatever got solved. */
    private fun insert(row: Row) {
        // forward-eliminate against existing pivots; by (I2) this introduces no pivot seq, so the order is irrelevant
        for ((pseq, prow) in pivots) {
            val c = row.coeffs[pseq] ?: continue
            eliminate(row, prow, c)
        }
        if (row.coeffs.isEmpty()) { if (!isZero(row.payload)) inconsistent++; return }   // redundant: must reduce to nothing
        // normalize on lowest seq, make pivot, back-substitute into others
        val pseq = row.coeffs.keys.min()
        scale(row, GF256.inv(row.coeffs[pseq]!!))
        for (other in pivots.values) other.coeffs[pseq]?.let { eliminate(other, row, it) }
        pivots[pseq] = row
        solveSingles()
    }

    /** A pivot row down to one unknown is a solution: take it out (by its own key) and learn it; repeat while that completes others. */
    private fun solveSingles() {
        while (true) {
            val e = pivots.entries.firstOrNull { it.value.coeffs.size == 1 } ?: return
            pivots.remove(e.key)
            solved(e.value)
        }
    }

    /** `row` (out of the pivot set) has one unknown left: normalize it and learn it — unless the [validator] refuses it. */
    private fun solved(row: Row) {
        val s = row.coeffs.keys.first()
        val c = row.coeffs[s]!!
        if (c != 1) scale(row, GF256.inv(c))
        val have = known[s]
        when {
            have != null -> if (!have.contentEquals(row.payload)) inconsistent++   // solved twice: both ways must agree
            validator != null && !validator.isValid(s, row.payload) -> rejected++
            else -> learn(s, row.payload)
        }
    }

    /** `seq` is known: substitute it into every row that references it, then learn what that solved and re-pivot what lost its pivot. */
    private fun learn(seq: Long, data: ByteArray) {
        known[seq] = data
        val it = pivots.entries.iterator()
        var ready: ArrayList<Row>? = null     // down to one unknown: solutions
        var rePivot: ArrayList<Row>? = null   // `seq` was their pivot: they need a new one
        while (it.hasNext()) {
            val e = it.next()
            val row = e.value
            val c = row.coeffs.remove(seq) ?: continue
            GF256.mulAddInto(row.payload, data, c)
            when {
                row.coeffs.isEmpty() -> { it.remove(); if (!isZero(row.payload)) inconsistent++ }
                row.coeffs.size == 1 -> { it.remove(); ready = (ready ?: ArrayList()).also { l -> l += row } }
                e.key == seq -> { it.remove(); rePivot = (rePivot ?: ArrayList()).also { l -> l += row } }
            }
        }
        ready?.forEach { solved(it) }
        rePivot?.forEach { row -> substituteKnown(row); insert(row) }
    }

    /** Folds in whatever became known while `row` was out of the pivot set (I3 before it goes back in). */
    private fun substituteKnown(row: Row) {
        val it = row.coeffs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val d = known[e.key] ?: continue
            GF256.mulAddInto(row.payload, d, e.value)
            it.remove()
        }
    }

    /** target -= c * source  (pivot row is normalized, so this zeroes target's coefficient at source pivot) */
    private fun eliminate(target: Row, source: Row, c: Int) {
        for ((seq, sc) in source.coeffs) {
            val v = (target.coeffs[seq] ?: 0) xor GF256.mul(sc, c)
            if (v == 0) target.coeffs.remove(seq) else target.coeffs[seq] = v
        }
        GF256.mulAddInto(target.payload, source.payload, c)
    }
    private fun scale(row: Row, c: Int) {
        for (k in row.coeffs.keys.toList()) row.coeffs[k] = GF256.mul(row.coeffs[k]!!, c)
        val tmp = row.payload.copyOf(); java.util.Arrays.fill(row.payload, 0); GF256.mulAddInto(row.payload, tmp, c)
    }
    private fun isZero(a: ByteArray): Boolean { for (b in a) if (b != 0.toByte()) return false; return true }

    fun get(seq: Long): ByteArray? = known[seq]
}
