package tessera.transport

import tessera.core.Handshake
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keepalive ([ConnConfig.pingIntervalMs]) and its coupling to [ConnConfig.idleTimeoutMs].
 *
 * Before this existed, ten seconds of application silence tore a connection down — which is every chat client
 * between messages, every RPC channel between calls, and every game between rounds. "No keepalive" was not a
 * neutral position awaiting a decision; it was a default, and the one that broke the most ordinary usage.
 *
 * The two values are a coupled pair rather than independent knobs, so both documented pairs are tested here:
 * the mobile-safe default (25 s / 60 s, scaled down for the test) and the wired fast-failover alternative
 * (3 s / 10 s, likewise). Testing only the default would leave the alternative an arithmetic possibility
 * rather than a supported configuration.
 */
class KeepaliveTest {
    private val keys = Handshake.generate()

    private class Pair(val client: TesseraConnection, val server: TesseraConnection, val c: TesseraClient, val s: TesseraServer) : AutoCloseable {
        override fun close() { runCatching { client.close() }; runCatching { server.close() }; c.close(); s.close() }
    }

    private fun connect(cfg: ConnConfig): Pair {
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg)
        val client = TesseraClient(cfg = cfg)
        val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "ka".toByteArray(), timeoutMs = 10_000)
        val sc = server.accept(5_000) ?: fail("server did not accept")
        sc.receive(2_000)
        return Pair(conn, sc, client, server)
    }

    /**
     * The point of the feature: an application that stops sending keeps its connection. The idle window here is
     * three times the timeout, so without keepalive this is a guaranteed teardown rather than a lucky pass.
     */
    @Test @Tag("timing") fun anIdleConnectionSurvivesFarPastTheIdleTimeout() {
        // the shipped default's shape (ping at ~0.4x the timeout), scaled so the test takes seconds not minutes
        connect(ConnConfig(pingIntervalMs = 250, idleTimeoutMs = 1_000)).use { p ->
            Thread.sleep(3_000)   // 3x the idle timeout with the application saying nothing at all
            assertTrue(!p.client.isClosed, "client tore down an idle connection despite keepalive")
            assertTrue(!p.server.isClosed, "server tore down an idle connection despite keepalive")
            assertTrue(p.client.stats.keepalivesSent > 0 || p.server.stats.keepalivesSent > 0,
                "nothing was sent, so the connection survived by luck rather than by keepalive")
            // and it must still work, not merely still be registered
            p.client.send(ByteArray(64) { it.toByte() })
            val got = p.server.receive(5_000)
            assertEquals(64, got?.size, "an idle-then-resumed connection could not carry a message")
        }
    }

    /** Without it, the same silence is fatal — the behaviour this feature exists to change. */
    @Test @Tag("timing") fun withoutKeepaliveTheSameSilenceTearsTheConnectionDown() {
        connect(ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 1_000)).use { p ->
            Thread.sleep(3_000)
            assertTrue(p.client.isClosed || p.server.isClosed,
                "with keepalive disabled a 3x-idle-timeout silence should have torn the connection down")
        }
    }

    /**
     * A busy connection must never send one, or the battery argument for the 25 s default evaporates for exactly
     * the applications that are doing work. The timer keys on the last *outbound* packet, which every send and
     * every ack refreshes.
     */
    @Test @Tag("timing") fun aBusyConnectionNeverSendsAKeepalive() {
        connect(ConnConfig(pingIntervalMs = 250, idleTimeoutMs = 1_000)).use { p ->
            // Baseline first: the setup between the handshake and this loop is itself idle time, and a keepalive
            // sent during it is the feature working, not a leak. What must be zero is the count added WHILE busy.
            val before = p.client.stats.keepalivesSent
            val end = System.nanoTime() + 2_500_000_000L
            var sent = 0
            while (System.nanoTime() < end) { p.client.send(ByteArray(200)); sent++; Thread.sleep(50) }
            val added = p.client.stats.keepalivesSent - before
            while (p.server.receive(200) != null) { /* drain */ }
            assertEquals(0L, added,
                "a connection sending every 50 ms emitted $sent messages and still added $added keepalive(s)")
        }
    }

    /**
     * The pair must be validated at construction. Without the 2x margin, one lost ping is a teardown and the
     * symptom is a disconnect nobody can explain — so the illegal pair is refused loudly instead.
     */
    @Test fun anUnsafePairIsRefusedAtConstruction() {
        val e = runCatching { ConnConfig(pingIntervalMs = 25_000, idleTimeoutMs = 30_000) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "a 30 s timeout with a 25 s ping was accepted; one lost ping is then a teardown")
        assertTrue(e.message!!.contains("2x"), "the refusal should say why: ${e.message}")
        // the two documented pairs must both be legal
        ConnConfig(pingIntervalMs = 25_000, idleTimeoutMs = 60_000)   // default: mobile-safe
        ConnConfig(pingIntervalMs = 3_000, idleTimeoutMs = 10_000)    // documented alternative: wired, fast failover
        ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 1_000)         // disabled: no constraint applies
    }

    /** The shipped defaults are themselves the mobile-safe pair, and legal by the same rule. */
    @Test fun theDefaultsAreTheMobileSafePair() {
        val d = ConnConfig()
        assertEquals(25_000L, d.pingIntervalMs)
        assertEquals(60_000L, d.idleTimeoutMs)
        assertTrue(d.idleTimeoutMs >= 2 * d.pingIntervalMs)
    }
}
