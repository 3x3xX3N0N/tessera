package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Repro hunt for the E5 rematch mystery (BENCH-netem "E5 REMATCH"): two live runs at 35-50 msg/s died
 * with `IllegalStateException: closed` on the probe right after connect. Suspect chain: the echo server's
 * reply send() starves — its credit grants ride the drowned sub-Mbit uplink — until awaitSendAllowed's
 * creditWaitMs (5 s) bound throws; the echo tool's serve loop treats any send exception as fatal, closes,
 * and the CLOSE lands on the probe as "closed". This test replays the echo tool's exact serve loop
 * (receive(30_000) -> send back) over CELL_HOTSPOT at the failing rate.
 *
 * Reproduced 2026-08-25: a 6 s scheduler stall (survivable by design — under the 10 s idle timeout) tripped
 * the old unconditional 5 s creditWaitMs bound on BOTH ends; the echo's close became the probe's "closed".
 * The fix scopes the bound to unvalidated paths (amplification anomaly) and lets credit/cwnd stalls wait
 * while the peer is audible. This test now asserts the whole run survives the stall.
 */
@Tag("timing")
class EchoCloseReproTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 7).toByte() }

    @Test fun echoLoopOverCellHotspotAtFiftyPerSecond() {
        // CELL_HOTSPOT's numbers, but with the deep carrier buffer the live radio actually has: limit=64
        // caps the standing queue at ~1.1 s, while the live rematch measured multi-second bloat (p50 15.6 s
        // pre-shedding). The 5 s creditWaitMs bound can only trip against a queue deeper than 5 s.
        val sim = NetemSim("cell-deep", delayUs = 25_000, jitterUs = 8_000, jitterDist = NetemSim.Dist.NORMAL,
            lossP = 0.005, rateBps = 20_000_000L, rateUpBps = 560_000L, limit = 1_024, seed = 11,
            // ...plus the radio's other real behaviour: a scheduler stall. 6 s of silence starting 3 s in —
            // longer than creditWaitMs (5 s) but shorter than idleTimeoutMs (10 s), i.e. a stall the
            // connection is supposed to survive.
            outageOnceAtUs = 3_000_000, outageDurationUs = 6_000_000)
        val cfg = ConnConfig(netem = sim, pmtud = false, bloatShedUs = 0)   // tool defaults: idleTimeoutMs=10s, creditWaitMs=5s
        // The live box ran v0.1.1 (6aa7b4c): v0.9 governor present, but no bloat shedding and no rebind.
        // bloatShedUs=0 reproduces that server's send-side behaviour.
        val serverCfg = ConnConfig(netem = sim, pmtud = false, bloatShedUs = 0)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, serverCfg)
        sim.uplinkPeer = server.localAddress
        val client = TesseraClient(cfg = cfg)
        var serverDeath: Exception? = null
        var clientDeath: Exception? = null
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 15_000)
            val sc = assertNotNull(server.accept(5_000))
            sc.receive(2_000)?.let { sc.send(it) }        // echo the 0-RTT payload, like the tool does
            var echoed = 0
            val serverThread = Thread {
                try {
                    while (true) { val m = sc.receive(30_000) ?: break; sc.send(m); echoed++ }
                } catch (e: Exception) { serverDeath = e } finally { sc.close() }
            }.apply { isDaemon = true; start() }

            val msg = ByteArray(1100)
            var sent = 0; var got = 0
            val rx = Thread {
                try { while (got < 300) { if (conn.receive(15_000) == null) break; got++ } }
                catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val gapNs = 1_000_000_000L / 50
            var next = System.nanoTime()
            try {
                repeat(300) { conn.send(msg); sent++; next += gapNs; while (System.nanoTime() < next) LockSupport.parkNanos(500_000) }
            } catch (e: Exception) { clientDeath = e }
            rx.join(30_000)
            serverThread.join(5_000)
            println("REPRO sent=$sent echoedBack=$got serverEchoed=$echoed")
            println("REPRO client death: ${clientDeath?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "none"}")
            println("REPRO server death: ${serverDeath?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "none"}")
            println("REPRO client stats: ${conn.stats}")
            println("REPRO server stats: ${sc.stats}")
            if (clientDeath != null) throw AssertionError("client died of a survivable stall", clientDeath)
            if (serverDeath != null) throw AssertionError("echo server died of a survivable stall", serverDeath)
            kotlin.test.assertEquals(300, got, "every message must come back after the stall: ${conn.stats}")
            conn.close()
        } finally { client.close(); server.close(); sim.close() }
    }
}
