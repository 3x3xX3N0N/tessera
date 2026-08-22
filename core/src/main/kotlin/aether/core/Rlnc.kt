package aether.core

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
    fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int) {
        if (c == 0) return
        for (i in dst.indices) dst[i] = ((dst[i].toInt() and 0xFF) xor mul(src[i].toInt() and 0xFF, c)).toByte()
    }
}

class RlncEncoder(private val symbolSize: Int, private val maxWindow: Int = 64) {
    private val window = ArrayDeque<Pair<Long, ByteArray>>()
    private var base = 0L

    fun push(seq: Long, data: ByteArray) {
        require(data.size == symbolSize)
        if (window.isEmpty()) base = seq
        window.addLast(seq to data)
        while (window.size > maxWindow) { window.removeFirst(); base = window.first().first }
    }

    fun repair(seed: Int): Frame.Repair {
        val rnd = Random(seed)
        val out = ByteArray(symbolSize)
        window.forEach { (_, s) -> GF256.mulAddInto(out, s, 1 + rnd.nextInt(255)) }
        return Frame.Repair(base, window.size, seed, ByteBuffer.wrap(out))
    }
}

class RlncDecoder(private val symbolSize: Int) {
    private val known = HashMap<Long, ByteArray>()
    /** Reduced-echelon rows keyed by pivot seq. coeffs: absolute seq -> GF coefficient (unknowns only). */
    private class Row(val coeffs: HashMap<Long, Int>, val payload: ByteArray)
    private val pivots = HashMap<Long, Row>()

    fun onSource(seq: Long, data: ByteArray) { learn(seq, data) }

    fun onRepair(r: Frame.Repair) {
        val rnd = Random(r.seed)
        val coeffs = HashMap<Long, Int>()
        val payload = ByteArray(symbolSize)
        r.symbol.duplicate().get(payload, 0, minOf(symbolSize, r.symbol.remaining()))
        for (i in 0 until r.windowLen) {
            val c = 1 + rnd.nextInt(255); val seq = r.windowBase + i
            val k = known[seq]
            if (k != null) GF256.mulAddInto(payload, k, c) else coeffs[seq] = c
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
