package aether.bench

import aether.core.DictTrainer
import aether.core.ZstdDictCodec
import java.util.Locale
import java.util.Random

/**
 * Payload-compression bench: ~2000 small JSON game/telemetry messages (80–200 B). A 16 KB zstd dictionary is trained
 * on the first half; the second half is held out and reported: mean bytes on the wire (incl. the codec's 1-byte
 * prefix) raw vs zstd without a dictionary vs zstd with the dictionary, and per-message encode/decode latency of
 * the dictionary codec (p50/p99 over 5 timed passes after warm-up).
 */
fun compressBench() {
    val n = 2000; val level = 3; val dictSize = 16 * 1024
    val rnd = Random(2026)
    val corpus = List(n) { telemetryJson(it, rnd) }
    val train = corpus.subList(0, n / 2); val test = corpus.subList(n / 2, n)

    val t0 = System.nanoTime()
    val dict = DictTrainer.train(train, dictSize)
    val trainMs = (System.nanoTime() - t0) / 1e6
    val withDict = ZstdDictCodec(dict, level); val plain = ZstdDictCodec.noDict(level)

    val rawB = test.sumOf { it.size }.toDouble() / test.size
    val plainB = test.sumOf { plain.encode(it).size }.toDouble() / test.size
    val dictB = test.sumOf { withDict.encode(it).size }.toDouble() / test.size

    repeat(20) { test.forEach { m -> check(withDict.decode(withDict.encode(m)).contentEquals(m)) } }   // JIT + native warm-up
    val rounds = 5
    val enc = LongArray(test.size * rounds); val dec = LongArray(test.size * rounds); var k = 0
    repeat(rounds) {
        test.forEach { m ->
            val a = System.nanoTime(); val e = withDict.encode(m)
            val b = System.nanoTime(); val d = withDict.decode(e)
            val c = System.nanoTime()
            enc[k] = b - a; dec[k] = c - b; k++
            check(d.contentEquals(m)) { "roundtrip mismatch" }
        }
    }
    fun p(a: LongArray, q: Double) = a.sorted()[((a.size - 1) * q).toInt()] / 1000.0
    println(String.format(Locale.ROOT, "compress n=%d dict=%dB(train %.0fms) raw=%.1fB zstd=%.1fB zstd+dict=%.1fB (-%.0f%% vs zstd, -%.0f%% vs raw) | dict enc p50=%.1fus p99=%.1fus dec p50=%.1fus p99=%.1fus",
        test.size, dict.size, trainMs, rawB, plainB, dictB, 100 * (1 - dictB / plainB), 100 * (1 - dictB / rawB), p(enc, .5), p(enc, .99), p(dec, .5), p(dec, .99)))
}

private val STATES = arrayOf("idle", "run", "jump", "shoot", "dead", "crouch", "swim", "reload")
private val ZONES = arrayOf("forest", "desert", "harbor", "ruins", "bunker")
private val BUFFS = arrayOf("haste", "shield", "regen", "stealth")

/** One player-state message, e.g. `{"t":1000123,"id":"player-42","pos":[12.3,45.6,7.8],"vel":[1.20,-0.40,0.00],"hp":97,"state":"run","seq":17}`. */
private fun telemetryJson(i: Int, rnd: Random): ByteArray {
    fun f(x: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", x)
    return buildString(200) {
        append("{\"t\":").append(1_000_000 + i * 50 + rnd.nextInt(50))
        append(",\"id\":\"player-").append(rnd.nextInt(400)).append('"')
        append(",\"pos\":[").append(f(rnd.nextDouble() * 2000, 1)).append(',').append(f(rnd.nextDouble() * 2000, 1)).append(',').append(f(rnd.nextDouble() * 120, 1)).append(']')
        if (rnd.nextInt(4) != 0) append(",\"vel\":[").append(f(rnd.nextGaussian() * 6, 2)).append(',').append(f(rnd.nextGaussian() * 6, 2)).append(',').append(f(rnd.nextGaussian() * 2, 2)).append(']')
        append(",\"hp\":").append(rnd.nextInt(101))
        append(",\"state\":\"").append(STATES[rnd.nextInt(STATES.size)]).append('"')
        if (rnd.nextInt(3) == 0) append(",\"zone\":\"").append(ZONES[rnd.nextInt(ZONES.size)]).append('-').append(rnd.nextInt(40)).append('"')
        if (rnd.nextInt(3) == 0) append(",\"ammo\":{\"rifle\":").append(rnd.nextInt(120)).append(",\"pistol\":").append(rnd.nextInt(40)).append('}')
        else if (rnd.nextInt(3) == 0) append(",\"target\":\"player-").append(rnd.nextInt(400)).append('"')
        if (rnd.nextInt(5) == 0) append(",\"buffs\":[\"").append(BUFFS[rnd.nextInt(BUFFS.size)]).append("\"]")
        append(",\"seq\":").append(i).append('}')
    }.toByteArray()
}
