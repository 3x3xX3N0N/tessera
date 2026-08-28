package tessera.bench

import tessera.core.Handshake
import tessera.transport.TesseraClient
import tessera.transport.TesseraConnection
import tessera.transport.TesseraServer
import tessera.transport.ConnConfig
import tessera.transport.NetemSim
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.max

/**
 * Bench: sends N fixed-size messages client->server with a given gap, records per-message one-way delivery
 * time (same host, so clocks are shared) and loss. Modes:
 *   rawudp   plain DatagramSocket baseline (no FEC, no crypto) — the floor every transport must beat
 *   tessera   v0.2 connection: PQ 0-RTT connect, AEAD short packets, adaptive RLNC + reactive repair, credit CC
 *   adapt    tessera at 5% simulated loss, 5000 msgs; prints where the estimator settled the FEC redundancy
 *   connect  over-the-wire connect cost on loopback (fresh PQ vs resumed), p50/p99 over 500 iterations each
 *   bulk     W2: back-to-back send() with no pacing gap (credit/cwnd/flow are the only clock); goodput, ramp
 *            timeline and wire overhead — see Bulk.kt ([--mb 50] [--size 1100] [--netem preset] [--out csv])
 *   soak     sustained-load leak watch: post-GC heap floor, threads, reassembly, key generation — Soak.kt
 *   conns    W5: N connections on one server; per-connection footprint, accept rate, fairness — Conns.kt
 *   storm    W3: N simultaneous handshakes; --multi gives each client its own source address — Storm.kt
 *   idle     W4: idle for N seconds, then burst; what state the burst actually meets — Idle.kt
 *   coldstart cold-connect breakdown, one fresh JVM per sample — ColdStart.kt
 *   profile  where the per-message cost over plain UDP goes: crypto and RLNC microbenches against the
 *            loopback udp-vs-tessera delta, so the codec/plumbing split is measured before any XDP work — Profile.kt
 *   gate     perf-regression gate: fixed scenario set vs bench/gate-baseline.txt, exit 1 on regression;
 *            `--record` (re)writes the machine-relative baseline — see Gate.kt
 *
 * usage: bench <tessera|rawudp|adapt|connect> [--n 5000] [--gapUs 1000] [--lossSim 0.05] [--size 1200] [--warmup 500] [--out results.csv] [--netem <preset>]
 *   --netem lan-clean|transcont|starlink|lte|wifi-busy|5g-mmwave   in-process link impairment ([NetemSim], the profiles of
 *           bench/netem/profiles.sh, one-way; both directions share one queue and one loss chain like tc netem on lo).
 *           Applies to rawudp, tessera, adapt and connect; compress and native have no link to impair. With --netem the
 *           adapt mode's in-process loss model defaults to 0 (the link is the impairment); pass --lossSim to add it.
 * Summary line: delivered = arrived within max(10 s, 50 RTT) of the last send (the receiver waits for the sends to
 * actually finish: warm-up, credit stalls and send overhead used to eat into a deadline computed from n x gap alone,
 * which cut the last ~30 messages of every 50 msg/s run); late = of those, arrived more than 2 s after the last send;
 * loss = never arrived. The tessera/adapt stats line ends with ccMode / plpmtu (DPLPMTUD) / tagLen / dictId as
 * negotiated for the run; the overhead line is client wire bytes / payload bytes delivered to the server application.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "tessera"
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val n = opt("n", if (mode == "adapt") "5000" else "5000").toInt()
    val gapUs = opt("gapUs", if (mode == "adapt") "500" else "1000").toLong()
    val netemName = opt("netem", "")
    val lossSim = opt("lossSim", if (mode == "adapt" && netemName.isEmpty()) "0.05" else "0.0").toDouble()   // in-process loss model for machines without netem
    val out = opt("out", "bench/results/${mode}.csv")
    val size = opt("size", "1200").toInt()
    val warmup = opt("warmup", "500").toInt()
    val netem: NetemSim? = if (netemName.isEmpty() || mode == "bulk") null else NetemSim.preset(netemName)   // bulk owns its sim
    // A/B knob for the low-rate repair clock (ConnConfig.repairClockEquationsPerRtt): --repairClock 0 turns it off.
    val repairClock = opt("repairClock", "0").toInt()
    val packetRing = opt("packetRing", "8192").toInt(); val bodyRing = opt("bodyRing", "4096").toInt()
    val latencies = LongArray(n) { -1L }

    try {
        when (mode) {
            "tessera", "adapt" -> {
                val r = runTessera(n, gapUs, lossSim, size, cfg = ConnConfig(netem = netem, repairClockEquationsPerRtt = repairClock, packetRing = packetRing, bodyRing = bodyRing), warmup = warmup)
                r.latencies.copyInto(latencies)
                report(mode, n, latencies, r.late, out)
                if (mode == "adapt") {
                    val e = r.clientEstimator
                    val cs = r.clientStats
                    println(String.format(Locale.ROOT, "adapt    fecRedundancy=%.3f (floor 0.02; v0 constant was 0.50) estimator lossRate=%.3f burst(mean=%.1f p95=%d) wireLoss=%.3f srtt=%.0fus minRtt=%.0fus | %s",
                        e.fecRedundancy(), e.lossRate, e.burstMean, e.burstP95, cs.simDropped.toDouble() / cs.packetsSent, e.srttUs, if (e.minRttUs == Double.MAX_VALUE) 0.0 else e.minRttUs, cs))
                    println("adapt    server: ${r.serverStats}")
                    println("adapt    ccMode=${cs.ccMode} plpmtu=${cs.plpmtu} tagLen=${cs.tagLen}")
                } else {
                    println("tessera   client: ${r.clientStats}")
                    println("tessera   ccMode=${r.clientStats.ccMode} plpmtu=${r.clientStats.plpmtu} tagLen=${r.clientStats.tagLen}")
                }
                val ss = r.serverStats
                println(String.format(Locale.ROOT, "%-7s  overhead: client bytes sent %d / payload bytes delivered %d = %.3f (sources %d B, repairs pro=%d react=%d tlp=%d tail=%d, re-sends %d) | io: client=%s server=%s",
                    mode, r.clientStats.bytesSent, ss.payloadBytesOut, r.clientStats.bytesSent.toDouble() / ss.payloadBytesOut.coerceAtLeast(1),
                    r.clientStats.sourceBytesSent, r.clientStats.repairsProactive, r.clientStats.repairsReactive, r.clientStats.repairsTlp, r.clientStats.repairsTail, r.clientStats.sourceResends, r.clientIo, r.serverIo))
                if (netem != null) println(String.format(Locale.ROOT, "%-7s  netem: %s | link one-way p50=%.1fms p99=%.1fms (the floor a raw datagram sees, queueing included)",
                    mode, netem, netem.delayPercentileUs(0.5) / 1e3, netem.delayPercentileUs(0.99) / 1e3))
            }
            "rawudp" -> {
                val rnd = java.util.Random(42)
                val rxs = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()); val txs = DatagramSocket()
                val rxAddr = rxs.localSocketAddress as InetSocketAddress
                val sent = LongArray(n)
                val sink: (ByteBuffer, InetSocketAddress) -> Unit = { b, a -> txs.send(DatagramPacket(b.array(), b.arrayOffset() + b.position(), b.remaining(), a)) }
                val start = System.nanoTime()
                val t = Thread {
                    repeat(n) { i ->
                        val p = ByteArray(size); p[0] = (i shr 8).toByte(); p[1] = i.toByte()
                        sent[i] = System.nanoTime()
                        if (rnd.nextDouble() >= lossSim) { if (netem != null) netem.submit(ByteBuffer.wrap(p), rxAddr, sink) else sink(ByteBuffer.wrap(p), rxAddr) }
                        busyWait(gapUs)
                    }
                }.apply { start() }
                rxs.soTimeout = 50
                val buf = ByteArray(size); var got = 0; var late = 0
                val planned = start + n * gapUs * 1000
                var sendsDoneAt = Long.MAX_VALUE   // latched once: the old `max(planned, now - 1)` moved every iteration and never expired
                while (got < n) {
                    val now0 = System.nanoTime()
                    if (sendsDoneAt == Long.MAX_VALUE && !t.isAlive) sendsDoneAt = max(planned, now0)
                    if (sendsDoneAt != Long.MAX_VALUE && now0 > sendsDoneAt + 10_000_000_000L) break   // sends finished: 10 s grace (no RTT estimate without a reverse path)
                    try { rxs.receive(DatagramPacket(buf, size)) } catch (e: java.net.SocketTimeoutException) { continue }
                    val i = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                    if (i < n && latencies[i] < 0) { val now = System.nanoTime(); latencies[i] = now - sent[i]; got++; if (now > planned + 2_000_000_000L) late++ }
                }
                t.join(); rxs.close(); txs.close()
                report(mode, n, latencies, late, out)
                if (netem != null) println(String.format(Locale.ROOT, "%-7s  netem: %s | link one-way p50=%.1fms p99=%.1fms", mode, netem, netem.delayPercentileUs(0.5) / 1e3, netem.delayPercentileUs(0.99) / 1e3))
            }
            "bulk" -> { bulkBench(args.drop(1).toTypedArray()); return }
            "gate" -> { gateMain(args.drop(1).toTypedArray()); return }
            "soak" -> { soakMain(args.drop(1).toTypedArray()); return }
            "conns" -> { connsMain(args.drop(1).toTypedArray()); return }
            "storm" -> { stormMain(args.drop(1).toTypedArray()); return }
            "idle" -> { idleMain(args.drop(1).toTypedArray()); return }
            "connect" -> { connectBench(netem = netem); return }
            "coldstart" -> { coldStartMain(args.drop(1).toTypedArray()); return }
            "compress" -> { compressBench(); return }
            "native" -> { nativeBench(args.drop(1).toTypedArray()); return }
            "profile" -> { profileMain(args.drop(1).toTypedArray()); return }
            else -> error("mode must be tessera|rawudp|adapt|bulk|gate|soak|conns|storm|idle|coldstart|connect|compress|native|profile")
        }
    } finally { netem?.close() }
}

class TesseraRun(val latencies: LongArray, val late: Int, val clientEstimator: tessera.core.PathEstimator, val clientStats: tessera.transport.ConnStats, val serverStats: tessera.transport.ConnStats,
                val clientIo: String = "", val serverIo: String = "")

/**
 * Client sends `warmup` unmeasured messages (JIT, estimator convergence) then n messages of `size` bytes with
 * `gapUs` spacing; a server thread records one-way latency by the index carried in the first two payload bytes.
 * The receiver listens until max(10 s, 50 RTT) after the last send actually went out (RTT = the handshake round
 * trip); [TesseraRun.late] counts messages that arrived more than 2 s after the last send.
 */
fun runTessera(n: Int, gapUs: Long, lossSim: Double, size: Int, cfg: ConnConfig = ConnConfig(), warmup: Int = 500): TesseraRun {
    require(n < 65535 && size >= 2)
    val keys = Handshake.generate()
    TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
        TesseraClient(cfg = cfg).use { client ->
            val t0 = System.nanoTime()
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "bench".toByteArray(), timeoutMs = 10_000)
            val rttUs = (System.nanoTime() - t0) / 1000   // handshake round trip: the RTT known before any data flows
            val sconn = server.accept(5_000) ?: error("server did not accept"); sconn.receive(2_000)
            // Asymmetric profiles (cell-hotspot: 0.56 Mbit up / 20 Mbit down) only apply their uplink cap to
            // traffic addressed to uplinkPeer, and NO bench ever set it - so every cell-hotspot run measured a
            // symmetric 20 Mbit link, i.e. not a hotspot at all. The uplink saturation that breaks Tessera on a
            // real phone (its repair overhead exceeding a ~0.6 Mbit uplink) was therefore never simulated.
            cfg.netem?.uplinkPeer = server.localAddress
            conn.lossSim = lossSim
            val latencies = LongArray(n) { -1L }
            val sent = LongArray(n)
            var got = 0; var late = 0
            val sendsDone = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)   // nanoTime of the last send, once it happened
            val grace = max(10_000_000_000L, 50 * rttUs * 1000)
            val rx = Thread {
                while (got < n) {
                    val now0 = System.nanoTime(); val done = sendsDone.get()
                    if (done != Long.MAX_VALUE && now0 > done + grace) break
                    val m = sconn.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < n && latencies[i] < 0) { val now = System.nanoTime(); latencies[i] = now - sent[i]; got++; val d = sendsDone.get(); if (d != Long.MAX_VALUE && now > d + 2_000_000_000L) late++ }
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
            sendsDone.set(System.nanoTime())
            rx.join()
            val run = TesseraRun(latencies, late, conn.estimator, conn.stats, sconn.stats, client.ioStats, server.ioStats)
            conn.close(); sconn.close()
            return run
        }
    }
}

private fun report(mode: String, n: Int, latencies: LongArray, late: Int, out: String) {
    val delivered = latencies.filter { it >= 0 }.sorted()
    fun pct(p: Double) = if (delivered.isEmpty()) 0.0 else delivered[((delivered.size - 1) * p).toInt()] / 1000.0
    val lossPct = 100.0 * (n - delivered.size) / n
    println(String.format(Locale.ROOT, "%-7s n=%d delivered=%d late=%d loss=%.2f%%  p50=%.0fus p90=%.0fus p99=%.0fus p999=%.0fus",
        mode, n, delivered.size, late, lossPct, pct(0.5), pct(0.9), pct(0.99), pct(0.999)))
    File(out).apply { parentFile?.mkdirs() }.writeText(buildString {
        appendLine("seq,latency_us"); latencies.forEachIndexed { i, l -> appendLine("$i,${if (l < 0) "" else l / 1000}") }
    })
}

internal fun busyWait(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
