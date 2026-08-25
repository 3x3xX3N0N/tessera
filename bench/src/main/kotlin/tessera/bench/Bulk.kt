package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.NetemSim
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale

/**
 * W2 bulk transfer: send() back-to-back with no pacing gap, so the transport's own machinery — credit
 * slow-start, cwnd, flow window — is the only clock. This is the workload the paced benches never exercise:
 * every byte pays the FEC overhead, and the first half-second is all credit ramp. Reports goodput (payload
 * bytes delivered / wall time from first send to last delivery), the wire-overhead ratio, a 250 ms delivery
 * timeline (the slow-start shape, written to --out as CSV), and the ramp time to 90 % of the steady rate.
 *
 * Message size defaults to 1100 B: single-fragment at the base PLPMTU, so message count == source-packet
 * count and the overhead ratio is not muddied by fragmentation (compare a --size above bodyMax deliberately).
 */
fun bulkBench(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val totalBytes = opt("mb", "50").toLong() * 1_000_000
    val size = opt("size", "1100").toInt()
    val netemName = opt("netem", "")
    val out = opt("out", "bench/results/bulk.csv")
    val netem: NetemSim? = if (netemName.isEmpty()) null else NetemSim.preset(netemName)
    val count = (totalBytes / size).toInt()
    require(count >= 100) { "need at least 100 messages; raise --mb or lower --size" }

    val keys = Handshake.generate()
    val cfg = ConnConfig(netem = netem)
    try {
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
                var sent = 0
                val sender = Thread { repeat(count) { conn.send(msg); sent++ } }.apply { start() }
                sender.join(600_000); rx.join(600_000)
                val elapsed = lastDeliveryNs - t0

                val used = (0..((elapsed / bucketNs).toInt().coerceAtMost(buckets.size - 1))).map { buckets[it] }
                val steady = used.drop(used.size / 2).let { if (it.isEmpty()) 0.0 else it.average() }   // second half = past any ramp
                val rampBucket = used.indexOfFirst { it >= 0.9 * steady }
                val goodput = gotBytes * 1e9 / elapsed / 1e6
                val cs = conn.stats; val ss = sconn.stats

                println(String.format(Locale.ROOT, "bulk     %s: %d msgs x %d B = %.1f MB in %.2f s -> %.2f MB/s goodput (delivered %d/%d)",
                    if (netem == null) "loopback" else netemName, count, size, totalBytes / 1e6, elapsed / 1e9, goodput, got, count))
                println(String.format(Locale.ROOT, "bulk     ramp: 90%% of steady rate (%.2f MB/s) reached in bucket %d (~%d ms); timeline -> %s",
                    steady * 4 / 1e6, rampBucket, rampBucket * 250, out))
                println(String.format(Locale.ROOT, "bulk     overhead: wire %d / payload %d = %.3f (sources=%d repairs pro=%d tail=%d shed=%d re-sends=%d) stalls(credit=%dms cwnd=%dms flow=%dms)",
                    cs.bytesSent, ss.payloadBytesOut, cs.bytesSent.toDouble() / ss.payloadBytesOut.coerceAtLeast(1),
                    cs.sourcesSent, cs.repairsProactive, cs.repairsTail, cs.repairsShed, cs.sourceResends,
                    cs.creditStallUs / 1000, cs.cwndStallUs / 1000, cs.flowStallUs / 1000))
                println("bulk     client: $cs")
                println("bulk     server: $ss")
                if (netem != null) println("bulk     netem: $netem")
                File(out).apply { parentFile?.mkdirs() }.writeText(buildString {
                    appendLine("bucket_ms,bytes")
                    used.forEachIndexed { i, b -> appendLine("${i * 250},$b") }
                })
                conn.close(); sconn.close()
            }
        }
    } finally { netem?.close() }
}
