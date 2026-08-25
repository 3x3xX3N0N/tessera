package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E5 reproduction: the sub-Mbit cellular uplink measured live on 2026-08-24 (BENCH-netem, "E5 first contact"),
 * modelled as [NetemSim.Preset.CELL_HOTSPOT]. At 40 msg/s x 1200 B the payload alone is ~73 % of the 0.56 Mbit
 * uplink; with the pre-shedding overhead — a tail repair per message, PTO trains — the offered load exceeded the
 * link outright, and the live run drowned in multi-second carrier bloat (p50 up to 15.6 s, cross-run queue
 * contamination). [ConnConfig.bloatShedUs] sheds that accessory load once the standing queue passes the
 * bufferbloat gate; this test pins that the profile stays deliverable with shedding on, and records the no-shed
 * arm for comparison rather than asserting it (the v0.9 credit governor bounds even that arm's damage).
 */
@Tag("timing")
class CellHotspotTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 11).toByte() }

    private class Arm(val delivered: Int, val count: Int, val elapsedMs: Long, val stats: ConnStats,
                      val srttMs: Double, val minRttMs: Double, val sim: String)

    private fun run(bloatShedUs: Long, rate: Int, count: Int, seed: Long): Arm {
        val sim = NetemSim.Preset.CELL_HOTSPOT.sim(seed)
        val cfg = ConnConfig(netem = sim, pmtud = false, idleTimeoutMs = 60_000, bloatShedUs = bloatShedUs)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        sim.uplinkPeer = server.localAddress            // client -> server rides the 0.56 Mbit uplink
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 15_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)
            val msg = ByteArray(1100)   // single fragment at the sim's base PLPMTU (pmtud off), like live steady state
            val gapNs = 1_000_000_000L / rate
            val t0 = System.nanoTime()
            val sender = Thread {
                var next = t0
                try { repeat(count) { conn.send(msg); next += gapNs; while (System.nanoTime() < next) LockSupport.parkNanos(500_000) } }
                catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            var got = 0
            val deadline = t0 + 90_000_000_000L
            while (got < count && System.nanoTime() < deadline) { if (sc.receive(1_000) != null) got++ }
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            sender.join(2_000)
            return Arm(got, count, elapsed, conn.stats, conn.estimator.srttUs / 1000, conn.estimator.minRttUs / 1000, sim.toString())
        } finally { client.close(); server.close(); sim.close() }
    }

    @Test fun sheddingKeepsTheCellularUplinkDeliverable() {
        val shed = run(bloatShedUs = 250_000, rate = 40, count = 500, seed = 7)
        val s = shed.stats
        println("CELL[shed]   ${shed.delivered}/${shed.count} in ${shed.elapsedMs}ms srtt=${"%.0f".format(shed.srttMs)}ms/min=${"%.0f".format(shed.minRttMs)} " +
            "tail=${s.repairsTail} shed=${s.repairsShed} tlp=${s.repairsTlp} src=${s.sourcesSent} | ${shed.sim}")
        val raw = run(bloatShedUs = 0, rate = 40, count = 500, seed = 7)
        val r = raw.stats
        println("CELL[no-shed] ${raw.delivered}/${raw.count} in ${raw.elapsedMs}ms srtt=${"%.0f".format(raw.srttMs)}ms/min=${"%.0f".format(raw.minRttMs)} " +
            "tail=${r.repairsTail} shed=${r.repairsShed} tlp=${r.repairsTlp} src=${r.sourcesSent} | ${raw.sim}")

        assertEquals(shed.count, shed.delivered, "with shedding, the cellular uplink must deliver everything: $s")
        assertTrue(shed.elapsedMs < 45_000, "with shedding, 500 msgs at 40/s (12.5 s nominal) must not take ${shed.elapsedMs}ms: $s")
        assertTrue(s.repairsShed > 0, "the bloat gate never engaged — either the model lost its teeth or the gate is broken: $s")
    }
}
