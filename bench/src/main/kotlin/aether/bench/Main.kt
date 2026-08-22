package aether.bench

import aether.core.Handshake
import aether.transport.AetherClient
import aether.transport.AetherConnection
import aether.transport.AetherServer
import aether.transport.ConnConfig
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.Locale

/**
 * Bench: sends N fixed-size messages client->server with a given gap, records per-message one-way delivery
 * time (same host, so clocks are shared) and loss. Modes:
 *   rawudp   plain DatagramSocket baseline (no FEC, no crypto) — the floor every transport must beat
 *   aether   v0.2 connection: PQ 0-RTT connect, AEAD short packets, adaptive RLNC + reactive repair, credit CC
 *   adapt    aether at 5% simulated loss, 5000 msgs; prints where the estimator settled the FEC redundancy
 *   connect  over-the-wire connect cost on loopback (fresh PQ vs resumed), p50/p99 over 500 iterations each
 *
 * usage: bench <aether|rawudp|adapt|connect> [--n 5000] [--gapUs 1000] [--lossSim 0.05] [--size 1200] [--out results.csv]
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "aether"
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val n = opt("n", if (mode == "adapt") "5000" else "5000").toInt()
    val gapUs = opt("gapUs", if (mode == "adapt") "500" else "1000").toLong()
    val lossSim = opt("lossSim", if (mode == "adapt") "0.05" else "0.0").toDouble()   // in-process loss model for machines without netem
    val out = opt("out", "bench/results/${mode}.csv")
    val size = opt("size", "1200").toInt()
    val latencies = LongArray(n) { -1L }

    when (mode) {
        "aether", "adapt" -> {
            val r = runAether(n, gapUs, lossSim, size)
            r.latencies.copyInto(latencies)
            report(mode, n, latencies, out)
            if (mode == "adapt") {
                val e = r.clientEstimator
                val cs = r.clientStats
                println(String.format(Locale.ROOT, "adapt    fecRedundancy=%.3f (floor 0.02; v0 constant was 0.50) estimator lossRate=%.3f wireLoss=%.3f srtt=%.0fus | %s",
                    e.fecRedundancy(), e.lossRate, cs.simDropped.toDouble() / cs.packetsSent, e.srttUs, cs))
                println("adapt    server: ${r.serverStats}")
            } else println("aether   client: ${r.clientStats}")
        }
        "rawudp" -> {
            val rnd = java.util.Random(42)
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
            report(mode, n, latencies, out)
        }
        "connect" -> { connectBench(); return }
        "compress" -> { compressBench(); return }
        else -> error("mode must be aether|rawudp|adapt|connect|compress")
    }
}

class AetherRun(val latencies: LongArray, val clientEstimator: aether.core.PathEstimator, val clientStats: aether.transport.ConnStats, val serverStats: aether.transport.ConnStats)

/**
 * Client sends `warmup` unmeasured messages (JIT, estimator convergence) then n messages of `size` bytes with
 * `gapUs` spacing; a server thread records one-way latency by the index carried in the first two payload bytes.
 */
fun runAether(n: Int, gapUs: Long, lossSim: Double, size: Int, cfg: ConnConfig = ConnConfig(), warmup: Int = 500): AetherRun {
    require(n < 65535 && size >= 2)
    val keys = Handshake.generate()
    AetherServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
        AetherClient(cfg = cfg).use { client ->
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "bench".toByteArray())
            val sconn = server.accept(2_000) ?: error("server did not accept"); sconn.receive(1_000)
            conn.lossSim = lossSim
            val latencies = LongArray(n) { -1L }
            val sent = LongArray(n)
            var got = 0
            val rx = Thread {
                val deadline = System.nanoTime() + ((warmup + n) * gapUs * 1000) + 2_000_000_000L
                while (got < n && System.nanoTime() < deadline) {
                    val m = sconn.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < n && latencies[i] < 0) { latencies[i] = System.nanoTime() - sent[i]; got++ }
                }
            }.apply { start() }
            repeat(warmup + n) { k ->
                val i = k - warmup
                val idx = if (i < 0) 0xFFFF else i // warm-up messages carry an index the receiver ignores
                val p = ByteArray(size); p[0] = (idx shr 8).toByte(); p[1] = idx.toByte()
                if (i >= 0) sent[i] = System.nanoTime()
                conn.send(p)
                busyWait(gapUs)
            }
            rx.join()
            val run = AetherRun(latencies, conn.estimator, conn.stats, sconn.stats)
            conn.close(); sconn.close()
            return run
        }
    }
}

private fun report(mode: String, n: Int, latencies: LongArray, out: String) {
    val delivered = latencies.filter { it >= 0 }.sorted()
    fun pct(p: Double) = if (delivered.isEmpty()) 0.0 else delivered[((delivered.size - 1) * p).toInt()] / 1000.0
    val lossPct = 100.0 * (n - delivered.size) / n
    println(String.format(Locale.ROOT, "%-7s n=%d delivered=%d loss=%.2f%%  p50=%.0fus p90=%.0fus p99=%.0fus p999=%.0fus",
        mode, n, delivered.size, lossPct, pct(0.5), pct(0.9), pct(0.99), pct(0.999)))
    File(out).apply { parentFile?.mkdirs() }.writeText(buildString {
        appendLine("seq,latency_us"); latencies.forEachIndexed { i, l -> appendLine("$i,${if (l < 0) "" else l / 1000}") }
    })
}

internal fun busyWait(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
