package tessera.transport

import tessera.core.Handshake
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tail-repair loss gate ([ConnConfig.tailRepairMinLoss]).
 *
 * The tail repair follows a source that no other source followed within T. Below roughly `1 / T` messages per
 * second **every** source is a tail, so each one draws its own repair symbol and the wire carries about two
 * packets per message: measured by `bench amp` at 598 tail repairs for 603 sources at 10 msg/s. That is the
 * low-rate amplification the live IPv6 run ran into from the wrong side — it offered ~175 pkt/s for 50 msg/s,
 * which is exactly where that provider's IPv6 path collapsed (TEST-PLAN, the IPv6 entry and its correction).
 *
 * What the repair buys is a round trip on a link that is losing packets: the last packet of a burst is the one
 * loss RACK cannot detect. On a link that is *not* losing packets it insures against nothing, at 100 % of the
 * source rate. So the gate is the same shape as the repair clock's: engage on measured loss, and only there.
 *
 * Like the repair clock, it is **off by default** and what is pinned here is where it engages, not that it is
 * faster — the price is in BENCH-netem and it depends on the link.
 */
class TailRepairGateTest {
    private val keys = Handshake.generate()

    private class Run(val tail: Long, val gated: Long, val packets: Long, val sources: Long, val delivered: Int)

    /** One arm: `n` messages at `gapUs` over `preset`, returning the client's repair accounting. */
    private fun run(preset: NetemSim.Preset?, minLoss: Double, n: Int, gapUs: Long, seed: Long = 5L): Run {
        val netem = preset?.let { NetemSim.preset(it.name.lowercase().replace('_', '-'), seed) }
        val cfg = ConnConfig(netem = netem, tailRepairMinLoss = minLoss)
        try {
            TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
                TesseraClient(cfg = cfg).use { client ->
                    val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "tr".toByteArray(), timeoutMs = 10_000)
                    val sc = server.accept(5_000) ?: error("no accept"); sc.receive(2_000)
                    var got = 0
                    val rx = Thread { while (got < n) { sc.receive(200) ?: continue; got++ } }.apply { isDaemon = true; start() }
                    repeat(n) {
                        conn.send(ByteArray(1200) { b -> b.toByte() })
                        val end = System.nanoTime() + gapUs * 1000
                        while (System.nanoTime() < end) Thread.onSpinWait()
                    }
                    rx.join(10_000)
                    val cs = conn.stats
                    val r = Run(cs.repairsTail, cs.repairsGated, cs.packetsSent, cs.sourcesSent, got)
                    conn.close(); sc.close()
                    return r
                }
            }
        } finally { netem?.close() }
    }

    /** Off unless asked for: no existing deployment's wire behaviour changes silently. */
    @Test fun isOffByDefault() {
        assertEquals(0.0, ConnConfig().tailRepairMinLoss, "the tail-repair gate must default to off")
    }

    /**
     * The cost being removed, and the reason it is worth removing: on a clean link driven slowly, every source is
     * a tail. The ungated arm is measured in the same test rather than asserted from memory, so a change in the
     * tail-repair trigger cannot leave this passing vacuously.
     */
    @Test @Tag("timing") fun aCleanSlowLinkStopsPayingForInsuranceItCannotUse() {
        val n = 150
        val ungated = run(NetemSim.Preset.LAN_CLEAN, minLoss = 0.0, n = n, gapUs = 20_000)
        assertTrue(ungated.tail > n / 2,
            "premise failed: a 50 msg/s clean link produced only ${ungated.tail} tail repairs for ${ungated.sources} sources")

        val gated = run(NetemSim.Preset.LAN_CLEAN, minLoss = 0.005, n = n, gapUs = 20_000)
        assertTrue(gated.tail * 4 < ungated.tail,
            "the gate left ${gated.tail} tail repairs against ${ungated.tail} ungated; it is not engaging on a clean link")
        assertTrue(gated.gated > 0, "nothing was recorded as gated, so the suppression is invisible in the stats")
        assertEquals(n, gated.delivered, "the gate cost delivery, which no amount of saved bandwidth would justify")
        assertTrue(gated.packets < ungated.packets,
            "packets on the wire did not fall: ${gated.packets} vs ${ungated.packets}")
    }

    /** And where the insurance does pay, the gate must get out of the way. */
    @Test @Tag("timing") fun alossyLinkStillGetsItsTailRepairs() {
        val r = run(NetemSim.Preset.LTE, minLoss = 0.005, n = 150, gapUs = 20_000)
        assertTrue(r.tail > 0, "the gate suppressed tail repairs on a lossy link, where they are the point")
        assertEquals(150, r.delivered)
    }
}
