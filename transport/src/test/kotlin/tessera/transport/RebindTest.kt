package tessera.transport

import tessera.core.Handshake
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rebind on rx-silence: the client half of NAT-mapping death. Measured live on a 5G hotspot (BENCH-netem E5),
 * ~1/3 of connections delivered nothing after a successful handshake — the carrier CGNAT dropped the flow's
 * mapping, the server's packets died at the stale entry, and the client retransmitted into it for the rest of the
 * run: the idle timeout keys on max(lastRx, lastTx), so a sending client never times out either. The fix is a
 * client-side trigger for machinery that already existed (F4 migration): after `rebindSilenceMs` of hearing
 * nothing while still sending, the client rebinds to a fresh self-owned socket (fresh source port = fresh
 * mapping), the server migrates on the first eliciting packet and revalidates via challenge/response.
 *
 * The dead mapping is simulated with two io wrappers and no production seams: the client's sends are dropped at
 * its (old) socket, and the server's sends to the client's old address are dropped at the server — a symmetric
 * black hole keyed to the old 5-tuple, exactly what a dropped CGNAT entry does. The client's rebind replaces its
 * wrapped io wholesale (new socket = live mapping), and the server's filter passes the new port by construction.
 */
class RebindTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 3).toByte() }

    /** Drops every send while [dead] (the client side of a dead mapping: packets leave the app, die in the NAT). */
    private class DeadUplink(private val inner: UdpIo) : UdpIo by inner {
        val dead = AtomicBoolean(false)
        override fun send(buf: ByteBuffer, to: InetSocketAddress) { if (!dead.get()) inner.send(buf, to) }
    }

    /** Drops sends to one address while active (the server side: replies to the stale mapping go nowhere). */
    private class DeadDownlink(private val inner: UdpIo, private val stale: InetSocketAddress) : UdpIo by inner {
        val dead = AtomicBoolean(false)
        override fun send(buf: ByteBuffer, to: InetSocketAddress) { if (!(dead.get() && to == stale)) inner.send(buf, to) }
    }

    @Test fun aDeadNatMappingIsRecoveredByRebindOnSilence() {
        val cfg = ConnConfig(pingIntervalMs = 0, rebindSilenceMs = 400, idleTimeoutMs = 30_000)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)
            conn.send("before".toByteArray()); assertEquals("before", String(assertNotNull(sc.receive(2_000))))
            sc.send("back".toByteArray()); assertEquals("back", String(assertNotNull(conn.receive(2_000))))
            assertEquals(0L, conn.stats.rebinds, "a healthy connection must not rebind")
            val oldPeer = sc.peer

            // the mapping dies: old 5-tuple black-holed both ways
            var up: DeadUplink? = null
            conn.wrapIo { io -> DeadUplink(io).also { up = it } }
            var down: DeadDownlink? = null
            sc.wrapIo { io -> DeadDownlink(io, oldPeer).also { down = it } }
            up!!.dead.set(true); down!!.dead.set(true)

            // in-flight traffic during the black hole: these sends leave the app and die in the "NAT"
            for (i in 0 until 5) conn.send(byteArrayOf(i.toByte()))

            // the client must notice the silence, rebind, and the server must migrate + revalidate
            val deadline = System.nanoTime() + 10_000_000_000L
            while (conn.stats.rebinds == 0L && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(conn.stats.rebinds >= 1, "the client never rebound: ${conn.stats}")
            while ((sc.stats.migrations == 0L || !sc.pathValidated) && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(sc.stats.migrations >= 1, "the server never migrated: ${sc.stats}")
            assertTrue(sc.pathValidated, "the new path must revalidate: ${sc.stats}")
            assertTrue(sc.peer != oldPeer, "the server must track the new source (${sc.peer} vs stale $oldPeer)")

            // the black-holed messages recover via normal retransmission on the new path, and both directions work
            val got = HashSet<Byte>()
            while (got.size < 5 && System.nanoTime() < deadline) { val m = sc.receive(500) ?: continue; if (m.size == 1) got += m[0] }
            assertEquals(5, got.size, "messages sent into the dead mapping must arrive after the rebind: ${sc.stats}")
            conn.send("after".toByteArray()); assertEquals("after", String(assertNotNull(sc.receive(2_000))))
            sc.send("reverse".toByteArray()); assertEquals("reverse", String(assertNotNull(conn.receive(2_000))))
            assertEquals(conn.stats.rebinds, 1L, "one rebind should have sufficed: ${conn.stats}")
        } finally {
            client.close(); server.close()
        }
    }

    @Test fun quietButAliveConnectionsNeverRebind() {
        // Silence alone is not evidence: an idle client (nothing sent since last rx) must not churn sockets, and a
        // client whose sends are being ACKED (lastRx fresh) must not either. Run past several silence intervals.
        val cfg = ConnConfig(pingIntervalMs = 0, rebindSilenceMs = 300, idleTimeoutMs = 30_000)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)
            conn.send("warm".toByteArray()); assertNotNull(sc.receive(2_000))
            Thread.sleep(1_500)                                   // idle: 5x the silence threshold
            assertEquals(0L, conn.stats.rebinds, "an idle connection must not rebind: ${conn.stats}")
            repeat(10) { conn.send(ByteArray(200)); Thread.sleep(50) }   // steady acked traffic
            Thread.sleep(400)
            assertEquals(0L, conn.stats.rebinds, "an acked sender must not rebind: ${conn.stats}")
        } finally {
            client.close(); server.close()
        }
    }
}
