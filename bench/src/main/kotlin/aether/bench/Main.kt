package aether.bench

import aether.core.ConnId
import aether.transport.UdpEndpoint
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.Locale

/**
 * Bench: sends N fixed-size messages sender->receiver with a given gap, records per-message one-way delivery
 * time (same host, so clocks are shared) and loss. Compares `aether` against a `rawudp` baseline (no FEC),
 * the floor every transport must beat. Run under netem (see bench/netem) for real numbers.
 *
 * usage: bench <aether|rawudp> [--n 5000] [--gapUs 1000] [--lossSim 0.05] [--out results.csv]
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "aether"
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val n = opt("n", "5000").toInt()
    val gapUs = opt("gapUs", "1000").toLong()
    val lossSim = opt("lossSim", "0.0").toDouble()   // in-process loss model for machines without netem
    val out = opt("out", "bench/results/${mode}.csv")
    val size = 1200
    val rnd = java.util.Random(42)
    val latencies = LongArray(n) { -1L }

    when (mode) {
        "aether" -> {
            val conn = ConnId(0xA7E7)
            UdpEndpoint(InetSocketAddress("127.0.0.1", 0), conn).use { rx ->
                UdpEndpoint(InetSocketAddress("127.0.0.1", 0), conn).use { tx ->
                    val sent = LongArray(n)
                    val t = Thread {
                        repeat(n) { i ->
                            val p = ByteArray(size); p[0] = (i shr 8).toByte(); p[1] = i.toByte()
                            sent[i] = System.nanoTime()
                            if (rnd.nextDouble() >= lossSim) tx.send(rx.localAddress, p)
                            else tx.send(InetSocketAddress("127.0.0.1", 9), p) // drop: send to discard
                            busyWait(gapUs)
                        }
                    }.apply { start() }
                    var got = 0
                    val deadline = System.nanoTime() + (n * gapUs * 1000) + 2_000_000_000L
                    while (got < n && System.nanoTime() < deadline) {
                        val (seq, _) = rx.receive(50) ?: continue
                        val i = seq.toInt(); if (i < n && latencies[i] < 0) { latencies[i] = System.nanoTime() - sent[i]; got++ }
                    }
                    t.join()
                }
            }
        }
        "rawudp" -> {
            val rxs = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()); val txs = DatagramSocket()
            val sent = LongArray(n)
            val t = Thread {
                repeat(n) { i ->
                    val p = ByteArray(size); p[0] = (i shr 8).toByte(); p[1] = i.toByte()
                    sent[i] = System.nanoTime()
                    if (rnd.nextDouble() >= lossSim) txs.send(DatagramPacket(p, size, rxs.localSocketAddress))
                    busyWait(gapUs)
                }
            }.apply { start() }
            rxs.soTimeout = 50
            val buf = ByteArray(size); var got = 0
            val deadline = System.nanoTime() + (n * gapUs * 1000) + 2_000_000_000L
            while (got < n && System.nanoTime() < deadline) {
                try { rxs.receive(DatagramPacket(buf, size)) } catch (e: java.net.SocketTimeoutException) { continue }
                val i = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                if (i < n && latencies[i] < 0) { latencies[i] = System.nanoTime() - sent[i]; got++ }
            }
            t.join(); rxs.close(); txs.close()
        }
        "connect" -> { connectBench(); return }
        else -> error("mode must be aether|rawudp|connect")
    }

    val delivered = latencies.filter { it >= 0 }.sorted()
    fun pct(p: Double) = if (delivered.isEmpty()) 0.0 else delivered[((delivered.size - 1) * p).toInt()] / 1000.0
    val lossPct = 100.0 * (n - delivered.size) / n
    println(String.format(Locale.ROOT, "%-7s n=%d delivered=%d loss=%.2f%%  p50=%.0fus p90=%.0fus p99=%.0fus p999=%.0fus",
        mode, n, delivered.size, lossPct, pct(0.5), pct(0.9), pct(0.99), pct(0.999)))
    File(out).apply { parentFile.mkdirs() }.writeText(buildString {
        appendLine("seq,latency_us"); latencies.forEachIndexed { i, l -> appendLine("$i,${if (l < 0) "" else l / 1000}") }
    })
}

private fun busyWait(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
