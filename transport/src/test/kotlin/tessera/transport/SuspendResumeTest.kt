package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Suspend / resume: the device stops, then comes back. A laptop lid, a phone entering doze, a VM paused — the
 * process keeps its connection state, but for some seconds it sends nothing and receives nothing. Distinct from
 * F9's outage, where the *link* drops packets while both ends keep running: here the endpoint itself is gone, so
 * on resume it must not mistake its own absence for congestion or for a peer that vanished.
 *
 * Modelled as a symmetric blackhole (both directions dead, as when the NIC is powered down) with the application
 * not sending meanwhile — which is what a suspend actually looks like to the transport. The one thing this cannot
 * model in-process is the peer's timers also stopping; here the server keeps running, which is the *harsher* case
 * and the right one to test: it keeps probing a client that is not answering.
 *
 * The contract is deliberately two-sided:
 *  - **shorter than `idleTimeoutMs`** — the connection survives and delivery resumes. The credit governor must
 *    not have collapsed to its floor either: our own absence is not congestion evidence, which is F9's finding
 *    ("a blackout is not congestion") applied to the endpoint rather than the link.
 *  - **longer than `idleTimeoutMs`** — the connection fails *cleanly*, telling the application, rather than
 *    hanging or silently delivering nothing. A suspend past the timeout is unsurvivable by design; being told
 *    is the requirement.
 */
@Tag("timing")
class SuspendResumeTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 17).toByte() }

    /** Drops every send while suspended: the endpoint's radio is off. */
    private class Suspended(private val inner: UdpIo) : UdpIo by inner {
        val off = AtomicBoolean(false)
        override fun send(buf: ByteBuffer, to: InetSocketAddress) {
            if (off.get()) { buf.position(buf.limit()); return }
            inner.send(buf, to)
        }
    }

    private class Arm(val delivered: Int, val stats: ConnStats, val death: Exception?, val creditBefore: Long,
                      val creditAfter: Long)

    private fun run(suspendMs: Long, idleTimeoutMs: Long): Arm {
        // A delay-only link, not loopback. The credit target tracks the BDP, and on loopback (~0.2 ms RTT) the
        // BDP sits far below the 10-datagram floor, so the target can never leave it and the "did the suspend
        // collapse credit" check would be unfalsifiable. 45 ms one-way gives a BDP of hundreds of KB. No loss:
        // this test is about suspend semantics, and loss recovery would muddy the delivery count.
        val sim = NetemSim("suspend-link", delayUs = 45_000, seed = 23)
        // Rebind-on-silence OFF. A rebind opens a fresh socket that the Suspended wrapper below does not cover,
        // so it would tunnel straight out of the blackhole — which a genuinely powered-down device cannot do.
        // (Left on, the 6 s arm survives a 3 s idle timeout precisely that way: the client rebinds twice and each
        // announcement refreshes lastTxUs. Worth knowing, and recorded in BENCH, but it is not this test's subject.)
        val cfg = ConnConfig(pingIntervalMs = 0, netem = sim, idleTimeoutMs = idleTimeoutMs, pmtud = false, rebindSilenceMs = 0)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 15_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)
            var up: Suspended? = null
            var down: Suspended? = null
            conn.wrapIo { io -> Suspended(io).also { up = it } }
            sc.wrapIo { io -> Suspended(io).also { down = it } }

            val msg = ByteArray(1100)
            var got = 0
            val rx = Thread { while (!Thread.currentThread().isInterrupted) { if (sc.receive(500) != null) got++ } }
                .apply { isDaemon = true; start() }

            // Enough traffic to lift the credit target clear of its floor before the suspend — otherwise the
            // "did the suspend collapse it" check below is vacuous: a 20-message trickle never grows it at all,
            // so the assertion would pass against a governor that had collapsed.
            repeat(600) { conn.send(msg) }
            Thread.sleep(1_500)
            // The SERVER's receiver credit is the one governing this flow: creditTargetBytes is what an
            // endpoint grants its peer, and the client receives almost nothing, so its own target never leaves the floor.
            val creditBefore = sc.stats.creditTargetBytes
            assertTrue(creditBefore > 10L * 1350, "the pre-suspend workload must lift credit off its floor, or the check below proves nothing: $creditBefore")
            repeat(20) { conn.send(msg); Thread.sleep(20) }        // settle into steady state

            up!!.off.set(true); down!!.off.set(true)               // the device goes away, both directions
            Thread.sleep(suspendMs)
            up!!.off.set(false); down!!.off.set(false)             // ...and comes back

            var death: Exception? = null
            try { repeat(20) { conn.send(msg); Thread.sleep(20) } } catch (e: Exception) { death = e }
            Thread.sleep(1_500)                                    // let the tail and any recovery land
            rx.interrupt()
            val stats = conn.stats
            println("SUSPEND[${suspendMs}ms idle=${idleTimeoutMs}ms] delivered=$got/640 creditTarget ${creditBefore}->${sc.stats.creditTargetBytes} " +
                "rebinds=${stats.rebinds} death=${death?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "none"} | $stats")
            conn.close(); sc.close()
            return Arm(got, stats, death, creditBefore, sc.stats.creditTargetBytes)
        } finally { client.close(); server.close(); sim.close() }
    }

    @Test fun aSuspendShorterThanTheIdleTimeoutIsSurvived() {
        val a = run(suspendMs = 4_000, idleTimeoutMs = 20_000)
        assertEquals(null, a.death, "a survivable suspend must not kill send(): ${a.death?.message}")
        assertEquals(640, a.delivered, "every message must arrive across the suspend: ${a.stats}")
        assertTrue(a.creditAfter >= a.creditBefore / 2,
            "our own absence is not congestion evidence — the suspend must not have collapsed the peer's credit target " +
                "(${a.creditBefore} -> ${a.creditAfter}): ${a.stats}")
    }

    @Test fun aSuspendPastTheIdleTimeoutFailsCleanly() {
        val a = run(suspendMs = 6_000, idleTimeoutMs = 3_000)
        assertNotNull(a.death, "a suspend past the idle timeout must tell the application, not hang or silently drop")
    }
}
