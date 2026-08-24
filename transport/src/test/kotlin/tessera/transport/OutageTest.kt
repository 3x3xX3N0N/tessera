package tessera.transport

import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F9 — **scheduled outage**. Every other impairment in [NetemSim] is stochastic; a LEO satellite handover is not. The
 * corrected [NetemSim.Preset.STARLINK] takes the link away for 200 ms every 15 s (see the preset's comment for where
 * those numbers come from), which is roughly 2.8 RTT of this profile: no FEC window covers it, so delivery depends
 * entirely on the transport noticing the gap, retransmitting after it, and not mistaking it for congestion.
 *
 * A modest, latency-sensitive stream (50 msg/s, 400 B) runs for just over 60 s of real time — four handovers plus the
 * GE loss the profile already had. The assertions are the reliability guarantee (everything arrives) and liveness
 * across each handover (no five-second window of the run is empty). The latency distribution is reported, not
 * asserted: what matters is how far past one outage length the tail runs, and that is the number to watch.
 *
 * Real time, ~70 s. It is the only test here that is long by design; the gap it covers cannot be compressed without a
 * virtual clock in the sim.
 */
class OutageTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 5).toByte() }

    private fun busySpin(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
    private fun pct(sorted: List<Long>, p: Double) = if (sorted.isEmpty()) -1L else sorted[((sorted.size - 1) * p).toInt()]

    /** The outage schedule is a pure function of (seed, time): same seed, same handovers, independent of call order. */
    @Test fun outageScheduleIsDeterministicAndOnCadence() {
        val a = NetemSim.Preset.STARLINK.sim(seed = 21)
        val b = NetemSim.Preset.STARLINK.sim(seed = 21)
        val p = NetemSim.Preset.STARLINK
        // walk 60 s of virtual time in 5 ms steps from each sim's own epoch; the two schedules must agree exactly
        var down = 0; var windows = 0; var wasDown = false
        for (t in 0 until 60_000_000L step 5_000) {
            val x = a.outageAt(a.epochUs + t); val y = b.outageAt(b.epochUs + t)
            assertTrue(x == y, "outage schedule differs at t=${t}us for the same seed")
            // and re-asking out of order gives the same answer: no hidden random stream
            assertTrue(x == a.outageAt(a.epochUs + t), "outageAt is not pure at t=${t}us")
            if (x) { down++; if (!wasDown) windows++ }
            wasDown = x
        }
        a.close(); b.close()
        assertTrue(windows in 3..5, "60 s at a 15 s cadence should hold 3-5 handovers, saw $windows")
        val downUs = down * 5_000L
        val expect = windows * p.outageDurationUs
        assertTrue(Math.abs(downUs - expect) < 50_000, "downtime ${downUs}us vs $windows x ${p.outageDurationUs}us")
        assertTrue(!NetemSim.Preset.STARLINK_LOSSY_ONLY.sim(seed = 21).use { s -> (0 until 60_000_000L step 5_000).any { s.outageAt(s.epochUs + it) } },
            "the preserved loss-only profile must have no outage at all")
    }

    /** 60+ s of a paced stream across four satellite handovers: everything arrives, and nothing stalls in between. */
    @Test fun pacedStreamSurvivesRepeatedStarlinkHandovers() {
        val preset = NetemSim.Preset.STARLINK
        val seed = 31L
        val sim = preset.sim(seed)
        val cfg = ConnConfig(netem = sim)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        sim.uplinkPeer = server.localAddress          // client -> server is the 12 Mbit uplink
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)

            val gapUs = 20_000L                        // 50 msg/s
            val count = 3_250                          // 65 s of sending
            val size = 400
            val sent = LongArray(count); val lat = LongArray(count) { -1L }; val arrivedAt = LongArray(count)
            var got = 0
            val start = System.nanoTime()
            val generous = start + count * gapUs * 1000 + 20_000_000_000L
            val rx = Thread {
                while (got < count && System.nanoTime() < generous) {
                    val m = sc.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < count && lat[i] < 0) { val t = System.nanoTime(); lat[i] = (t - sent[i]) / 1000; arrivedAt[i] = (t - start) / 1000; got++ }
                }
            }.apply { start() }
            var sendErrors = 0; var firstSendError: String? = null
            repeat(count) { i ->
                val p = ByteArray(size); p[0] = (i shr 8).toByte(); p[1] = i.toByte()
                sent[i] = System.nanoTime()
                try { conn.send(p) } catch (e: Exception) {
                    sendErrors++; if (firstSendError == null) firstSendError = "${e.javaClass.simpleName}: ${e.message} at msg $i (+${(System.nanoTime() - start) / 1_000_000}ms)"
                }
                busySpin(gapUs)
            }
            rx.join()

            val delivered = lat.filter { it >= 0 }.sorted()
            val overOneOutage = delivered.count { it > preset.outageDurationUs }
            val missing = (0 until count).filter { lat[it] < 0 }
            val cs = conn.stats; val ss = sc.stats
            println(String.format(Locale.ROOT,
                "outage   starlink seed=%d 50 msg/s x %d (%.0f s): delivered=%d/%d handovers=%d outageDropped=%d sendErrors=%d\n" +
                "         p50=%.1fms p95=%.1fms p99=%.1fms p99.9=%.1fms max=%.1fms | >%dms (one outage): %d messages\n" +
                "         srtt=%.1fms minRtt=%.1fms | client: %s | server: %s | %s",
                seed, count, count * gapUs / 1e6, got, count, sim.outages, sim.outageDropped, sendErrors,
                pct(delivered, .50) / 1e3, pct(delivered, .95) / 1e3, pct(delivered, .99) / 1e3, pct(delivered, .999) / 1e3,
                (delivered.lastOrNull() ?: -1L) / 1e3, preset.outageDurationUs / 1000, overOneOutage,
                conn.estimator.srttUs / 1e3, conn.estimator.minRttUs / 1e3, cs, ss, sim))
            if (firstSendError != null) println("         first send error: $firstSendError")

            // (b) liveness: no 5 s window of the run may be empty. A handover that wedged the connection shows up here
            //     as a run of empty buckets, which distinguishes "slow after the gap" from "never came back".
            val bucketUs = 5_000_000L
            val buckets = ((count * gapUs) / bucketUs).toInt() + 1
            val perBucket = IntArray(buckets)
            for (i in 0 until count) if (lat[i] >= 0) perBucket[(arrivedAt[i] / bucketUs).toInt().coerceIn(0, buckets - 1)]++
            val empty = perBucket.indices.filter { perBucket[it] == 0 && it < buckets - 1 }
            println("         per-5s deliveries: ${perBucket.toList()}")

            assertTrue(sim.outages >= 3, "the run should have crossed at least 3 handovers, saw ${sim.outages}: $sim")
            assertTrue(empty.isEmpty(), "no delivery at all in 5 s window(s) $empty (each x5 s from the start): the connection stalled across a handover | $cs | $ss | $sim")
            assertTrue(sendErrors == 0, "$sendErrors of $count send() calls failed, first: $firstSendError | $cs | $sim")
            assertTrue(missing.isEmpty(), "${missing.size} of $count messages never delivered ${missing.take(10)} | client=$cs | server=$ss | $sim")
            assertTrue(cs.rxErrors + ss.rxErrors <= 0, "rx parse errors client=${cs.firstRxError} server=${ss.firstRxError}")
            conn.close(); sc.close()
        } finally { client.close(); server.close(); sim.close() }
    }
}
