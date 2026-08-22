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

    fun push(seq: Long, data: ByteArray) {
        require(data.size == symbolSize)
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

class RlncDecoder(private val symbolSize: Int) {
    private val known = HashMap<Long, ByteArray>()
    /** Reduced-echelon rows keyed by pivot seq. coeffs: absolute seq -> GF coefficient (unknowns only). */
    private class Row(val coeffs: HashMap<Long, Int>, val payload: ByteArray)
    private val pivots = HashMap<Long, Row>()
    /** Off-heap accumulator for [onRepair] while a [GF256.OffHeapKernel] is installed (lazily allocated). */
    private var acc: MemorySegment? = null

    fun onSource(seq: Long, data: ByteArray) { learn(seq, data) }

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

    private fun insert(row: Row) {
        // forward-eliminate against existing pivots
        for ((pseq, prow) in pivots) {
            val c = row.coeffs[pseq] ?: continue
            eliminate(row, prow, c)
        }
        if (row.coeffs.isEmpty()) return
        // normalize on lowest seq, make pivot, back-substitute into others
        val pseq = row.coeffs.keys.min()
        scale(row, GF256.inv(row.coeffs[pseq]!!))
        for (other in pivots.values) other.coeffs[pseq]?.let { eliminate(other, row, it) }
        pivots[pseq] = row
        // any pivot row reduced to one unknown is solved
        var solved = true
        while (solved) {
            solved = false
            val done = pivots.values.firstOrNull { it.coeffs.size == 1 } ?: break
            val seq = done.coeffs.keys.first()
            pivots.remove(seq); learn(seq, done.payload); solved = true
        }
    }

    private fun learn(seq: Long, data: ByteArray) {
        known[seq] = data
        val it = pivots.entries.iterator()
        val ready = ArrayList<Pair<Long, ByteArray>>()
        while (it.hasNext()) {
            val (pseq, row) = it.next()
            val c = row.coeffs.remove(seq) ?: continue
            GF256.mulAddInto(row.payload, data, c)
            if (row.coeffs.size == 1) { it.remove(); val s = row.coeffs.keys.first(); if (row.coeffs[s] != 1) scale(row, GF256.inv(row.coeffs[s]!!)); ready += s to row.payload }
            else if (row.coeffs.isEmpty()) it.remove()
        }
        ready.forEach { (s, p) -> if (s !in known) learn(s, p) }
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

    fun get(seq: Long): ByteArray? = known[seq]
}
