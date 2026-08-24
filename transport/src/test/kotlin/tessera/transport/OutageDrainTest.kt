package tessera.transport

import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F9, the part that hurts: **draining a blackout at rate**.
 *
 * [OutageTest] shows a 50 msg/s stream crossing satellite handovers with a tail of about one outage length. Raise the
 * rate and the picture changes completely, because the number of packets lost in a fixed-length gap scales with the
 * send rate while the mechanism that re-sends them does not.
 *
 * The ack-driven repair path spends a token bucket ([ConnConfig.gapRepairFraction], a quarter of a token per source
 * packet). That throttle is right for a lossy link, where a repair is a *guess* and guessing loudly is how a congested
 * path collapses. After a link outage it is exactly wrong: the peer's feedback map has already named every missing
 * sequence, so nothing is being guessed, and the throttle simply meters out a recovery that could have been immediate.
 *
 * One deterministic 200 ms dropout at 2000 msg/s drops on the order of 400 packets. This test measures how long the
 * connection takes to get them back, and asserts a bound on the tail rather than describing the behaviour in prose —
 * a finding that lives only in a document decays into folklore.
 *
 * Real time, ~10 s.
 */
class OutageDrainTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 7).toByte() }

    private fun busySpin(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
    private fun pct(sorted: List<Long>, p: Double) = if (sorted.isEmpty()) -1L else sorted[((sorted.size - 1) * p).toInt()]

    private class Run(val p50: Long, val p95: Long, val p99: Long, val p999: Long, val max: Long,
                      val delivered: Int, val dropped: Long, val drains: Long, val throttled: Long, val stats: String)

    /** Paired A/B in one process, same seed and same scenario back to back, so machine drift cancels. */
    @Test fun aBlackoutAtRateDrainsWithoutBeingThrottled() {
        val metered = scenario(outageDrainMinRun = Long.MAX_VALUE)   // the throttle alone
        val drained = scenario(outageDrainMinRun = 64L)              // burst a confirmed hole
        println(String.format(Locale.ROOT,
            "drain    A/B on one 200ms starlink handover at 800 msg/s (same seed)%n" +
            "  metered  p50=%.1f p95=%.1f p99=%.1f p99.9=%.1f max=%.1f ms | delivered=%d dropped=%d drains=%d throttled=%d%n" +
            "  drained  p50=%.1f p95=%.1f p99=%.1f p99.9=%.1f max=%.1f ms | delivered=%d dropped=%d drains=%d throttled=%d",
            metered.p50/1e3, metered.p95/1e3, metered.p99/1e3, metered.p999/1e3, metered.max/1e3,
            metered.delivered, metered.dropped, metered.drains, metered.throttled,
            drained.p50/1e3, drained.p95/1e3, drained.p99/1e3, drained.p999/1e3, drained.max/1e3,
            drained.delivered, drained.dropped, drained.drains, drained.throttled))

        assertTrue(metered.delivered == 16_000 && drained.delivered == 16_000, "both arms must deliver everything")
        assertTrue(drained.drains > 0, "the outage discriminator never fired: ${drained.stats}")
        assertTrue(metered.drains == 0L, "the metered arm should never burst: ${metered.stats}")
        assertTrue(drained.throttled < metered.throttled,
            "bursting a confirmed hole should reduce throttling: ${drained.throttled} vs ${metered.throttled}")
        assertTrue(drained.p99 <= metered.p99,
            "p99 got worse with the burst: ${drained.p99/1000} ms vs ${metered.p99/1000} ms — see F9 in docs/TEST-PLAN.md")
    }

    private fun scenario(outageDrainMinRun: Long): Run {
        // A clean, fast link with one scheduled dropout: no GE loss, so every lost packet is attributable to the
        // outage and the recovery cost cannot be confused with ordinary loss recovery.
        // The reported failure was on the STARLINK preset, not a synthetic clean link: 35 ms one-way, GE loss on top
        // of the handover, and an asymmetric uplink. All three matter — ordinary loss is already consuming the repair
        // budget when the blackout lands, and the longer path slows every feedback round.
        val preset = NetemSim.Preset.STARLINK
        val sim = preset.sim(seed = 77)
        val cfg = ConnConfig(netem = sim, outageDrainMinRun = outageDrainMinRun)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        sim.uplinkPeer = server.localAddress
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)

            // Offered load must sit inside the preset's 12 Mbit uplink or this measures congestion collapse rather
            // than outage recovery: 800 msg/s x 1000 B is 6.4 Mbit of payload, about 8 Mbit with FEC and headers.
            val gapUs = 1_250L                     // 800 msg/s
            val count = 16_000                     // 20 s of sending, so the run crosses a 15 s handover
            val size = 1_000
            val sent = LongArray(count); val lat = LongArray(count) { -1L }
            var got = 0
            val start = System.nanoTime()
            val generous = start + count * gapUs * 1000 + 20_000_000_000L
            val rx = Thread {
                while (got < count && System.nanoTime() < generous) {
                    val m = sc.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < count && lat[i] < 0) { lat[i] = (System.nanoTime() - sent[i]) / 1000; got++ }
                }
            }.apply { start() }
            repeat(count) { i ->
                val p = ByteArray(size); p[0] = (i shr 8).toByte(); p[1] = i.toByte()
                sent[i] = System.nanoTime()
                conn.send(p)
                busySpin(gapUs)
            }
            rx.join()

            val delivered = lat.filter { it >= 0 }.sorted()
            val cs = conn.stats
            return Run(pct(delivered, .50), pct(delivered, .95), pct(delivered, .99), pct(delivered, .999),
                delivered.lastOrNull() ?: -1L, got, sim.outageDropped, cs.outageDrains, cs.gapThrottled, cs.toString())
        } finally {
            client.close(); server.close(); sim.close()
        }
    }
}
