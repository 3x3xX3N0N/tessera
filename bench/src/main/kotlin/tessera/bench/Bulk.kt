package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.ConnStats
import tessera.transport.NetemSim
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale

/**
 * W2 — bulk transfer: back-to-back `send()` with no pacing gap, so credit, cwnd, flow control and the
 * reliability horizon are the only clocks. Reports goodput, the ramp, wire overhead and where the sender stalled.
 *
 * Message size defaults to 1100 B: single-fragment at the base PLPMTU, so message count == source-packet
 * count and the overhead ratio is not muddied by fragmentation (compare a --size above bodyMax deliberately).
 *
 * **This bench reports an interval, not a number, and it does so because a single reading here is worthless.**
 * On transcont the same configuration measured 1.93 and 4.58 MB/s on consecutive runs — a 2.4x spread on
 * identical code, larger than any effect worth investigating. A single-run A/B of the disengaged pacer read
 * "x8 wins on all three profiles"; three runs per arm reversed the sign (BENCH-netem, "pacing the disengaged
 * path"). So `--runs` (default 5) repeats the transfer and the summary line carries the median, the full range
 * and the spread, plus an explicit statement of what that spread makes resolvable. A difference smaller than
 * the spread is not a result.
 *
 * Runs are **paired across configurations**: run `i` builds its link with seed `--seed + i`, so two arms
 * compared with the same `--seed` and `--runs` see exactly the same sequence of links and only the host varies.
 * Comparing arm medians is then a paired comparison, not two independent samples.
 *
 * usage: bench bulk [--mb 50] [--size 1100] [--netem <preset>] [--runs 5] [--seed 1] [--out csv]
 *                   [--packetRing 2048] [--bodyRing 1024] [--paceDisengaged 0]
 */
fun bulkBench(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val totalBytes = opt("mb", "50").toLong() * 1_000_000
    val size = opt("size", "1100").toInt()
    val netemName = opt("netem", "")
    val out = opt("out", "bench/results/bulk.csv")
    val runs = opt("runs", "5").toInt()
    val seed = opt("seed", "1").toLong()
    val count = (totalBytes / size).toInt()
    require(count >= 100) { "need at least 100 messages; raise --mb or lower --size" }
    require(runs >= 1) { "--runs must be >= 1" }

    val keys = Handshake.generate()
    val packetRing = opt("packetRing", "8192").toInt()
    val bodyRing = opt("bodyRing", "4096").toInt()
    val pace = opt("paceDisengaged", "0").toDouble()
    val maxDatagram = opt("maxDatagram", "1350").toInt()

    println(String.format(Locale.ROOT, "bulk     %s: %d msgs x %d B = %.1f MB, %d run(s), seed %d..%d | packetRing=%d bodyRing=%d paceDisengaged=%.1f maxDatagram=%d",
        if (netemName.isEmpty()) "loopback" else netemName, count, size, totalBytes / 1e6, runs, seed, seed + runs - 1, packetRing, bodyRing, pace, maxDatagram))

    val results = ArrayList<BulkRun>(runs)
    for (r in 0 until runs) {
        // paired across arms: run r always sees the link built from seed + r
        val netem = if (netemName.isEmpty()) null else NetemSim.preset(netemName, seed + r)
        try {
            val res = bulkRun(keys, ConnConfig(netem = netem, packetRing = packetRing, bodyRing = bodyRing, paceDisengaged = pace, maxDatagram = maxDatagram),
                count, size, if (r == runs - 1) out else null)
            results += res
            println(String.format(Locale.ROOT, "bulk     run %d/%d: %6.2f MB/s  delivered %d/%d  overhead %.3f  drops %s  stalls(credit=%dms cwnd=%dms hzn=%dms)",
                r + 1, runs, res.goodputMBs, res.delivered, count, res.overhead,
                res.dropSummary, res.creditStallMs, res.cwndStallMs, res.horizonStallMs))
        } finally { netem?.close() }
    }

    val g = results.map { it.goodputMBs }.sorted()
    val median = g[g.size / 2]
    val lo = g.first(); val hi = g.last()
    val spread = if (lo > 0) hi / lo else Double.NaN
    println(String.format(Locale.ROOT, "bulk     SUMMARY: median %.2f MB/s, range %.2f-%.2f over %d run(s), spread %.2fx",
        median, lo, hi, runs, spread))
    if (runs == 1) {
        println("bulk     one run resolves nothing: this bench has measured a 2.4x spread on identical code. Use --runs 5+ before comparing anything.")
    } else {
        // What a reader may conclude from these numbers, stated rather than left to optimism.
        val pct = (spread - 1.0) * 100
        println(String.format(Locale.ROOT, "bulk     resolution: the arms of a comparison must differ by more than the %.0f%% spread seen here before the difference means anything.", pct))
        if (spread > 1.5) println("bulk     that spread is wide: prefer the median, raise --runs, and treat any single-run claim about this link as unsupported.")
    }
    results.lastOrNull()?.let { println("bulk     client: ${it.clientStats}"); println("bulk     server: ${it.serverStats}"); it.netemLine?.let { n -> println("bulk     netem: $n") } }
}

/** One transfer's outcome. [dropSummary] is the link's own drop accounting, or "-" without a sim. */
private class BulkRun(
    val goodputMBs: Double, val delivered: Int, val overhead: Double, val dropSummary: String,
    val creditStallMs: Long, val cwndStallMs: Long, val horizonStallMs: Long,
    val clientStats: ConnStats, val serverStats: ConnStats, val netemLine: String?,
)

/** A single bulk transfer over a fresh connection pair; writes the ramp timeline to [out] when it is non-null. */
private fun bulkRun(keys: Handshake.StaticKeys, cfg: ConnConfig, count: Int, size: Int, out: String?): BulkRun {
    TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
        TesseraClient(cfg = cfg).use { client ->
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "bulk".toByteArray(), timeoutMs = 10_000)
            val sconn = server.accept(5_000) ?: error("server did not accept"); sconn.receive(2_000)

            val bucketNs = 250_000_000L
            val buckets = LongArray(1 + (600_000_000_000L / bucketNs).toInt())   // 10 min hard ceiling
            var got = 0; var gotBytes = 0L; var lastDeliveryNs = 0L
            val t0 = System.nanoTime()
            val rx = Thread {
                while (got < count) {
                    val m = sconn.receive(2_000) ?: if (got > 0 && System.nanoTime() - lastDeliveryNs > 10_000_000_000L) break else continue
                    got++; gotBytes += m.size; lastDeliveryNs = System.nanoTime()
                    val b = ((lastDeliveryNs - t0) / bucketNs).toInt()
                    if (b < buckets.size) buckets[b] += m.size
                }
            }.apply { start() }

            val msg = ByteArray(size)
            val sender = Thread { repeat(count) { conn.send(msg) } }.apply { start() }
            sender.join(600_000); rx.join(600_000)
            val elapsed = lastDeliveryNs - t0

            val cs = conn.stats; val ss = sconn.stats
            if (out != null) {
                val used = (0..((elapsed / bucketNs).toInt().coerceAtMost(buckets.size - 1))).map { buckets[it] }
                File(out).apply { parentFile?.mkdirs() }.writeText(buildString {
                    appendLine("bucket_ms,bytes")
                    used.forEachIndexed { i, b -> appendLine("${i * 250},$b") }
                })
            }
            val netemLine = cfg.netem?.toString()
            val drops = cfg.netem?.let { sim ->
                Regex("dropped=\\d+ \\([0-9.]+%\\)").find(sim.toString())?.value ?: "-"
            } ?: "-"
            val r = BulkRun(
                goodputMBs = if (elapsed > 0) gotBytes * 1e9 / elapsed / 1e6 else 0.0,
                delivered = got,
                overhead = cs.bytesSent.toDouble() / ss.payloadBytesOut.coerceAtLeast(1),
                dropSummary = drops,
                creditStallMs = cs.creditStallUs / 1000, cwndStallMs = cs.cwndStallUs / 1000, horizonStallMs = cs.horizonStallUs / 1000,
                clientStats = cs, serverStats = ss, netemLine = netemLine,
            )
            conn.close(); sconn.close()
            return r
        }
    }
}
