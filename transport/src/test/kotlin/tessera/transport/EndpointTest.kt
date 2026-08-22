package tessera.transport

import tessera.core.Handshake
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EndpointTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 3).toByte() }
    private fun server(cfg: ConnConfig = ConnConfig()) = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)

    @Test fun freshConnectDeliversZeroRttPayloadAndEchoes() {
        server().use { s -> TesseraClient().use { c ->
            val first = "GET /index 0-rtt".toByteArray()
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, first)
            val sc = assertNotNull(s.accept(2_000))
            assertContentEquals(first, sc.receive(1_000))
            assertNotNull(conn.ticket, "fresh connect must yield a resumption ticket")
            assertEquals(sc.connId, conn.connId)
            sc.send("pong".toByteArray())
            assertContentEquals("pong".toByteArray(), conn.receive(1_000))
        } }
    }

    @Test fun resumeWithTicketFromFirstConnection() {
        server().use { s -> TesseraClient().use { c ->
            val c1 = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, byteArrayOf(1))
            val s1 = assertNotNull(s.accept(2_000)); s1.receive(1_000)
            val ticket = assertNotNull(c1.ticket); val secret = c1.resumptionSecret
            c1.close(); s1.close()
            val big = ByteArray(1100) { it.toByte() } // resumed first flight carries > 1 KB
            val c2 = c.resume(s.localAddress, ticket, secret, big)
            val s2 = assertNotNull(s.accept(2_000))
            assertContentEquals(big, s2.receive(1_000))
            s2.send("resumed-ok".toByteArray())
            assertContentEquals("resumed-ok".toByteArray(), c2.receive(1_000))
        } }
    }

    @Test fun tenKilobyteMessageRoundTrips() {
        server().use { s -> TesseraClient().use { c ->
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, byteArrayOf())
            val sc = assertNotNull(s.accept(2_000)); assertContentEquals(byteArrayOf(), sc.receive(1_000))
            val msg = ByteArray(10 * 1024) { (it * 31 + 7).toByte() }
            conn.send(msg)
            val got = assertNotNull(sc.receive(2_000)); assertContentEquals(msg, got)
            sc.send(got)
            assertContentEquals(msg, conn.receive(2_000))
        } }
    }

    @Test fun tenPercentLossAllMessagesArriveAndEstimatorTracksLoss() {
        server().use { s -> TesseraClient().use { c ->
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, byteArrayOf())
            val sc = assertNotNull(s.accept(2_000)); sc.receive(1_000)
            conn.lossSim = 0.10
            val n = 2000
            val seen = BooleanArray(n)
            val rx = Thread {
                var got = 0; val deadline = System.nanoTime() + 15_000_000_000L
                while (got < n && System.nanoTime() < deadline) {
                    val m = sc.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < n && !seen[i]) { seen[i] = true; got++ }
                }
            }.apply { start() }
            repeat(n) { i -> conn.send(ByteArray(64).also { it[0] = (i shr 8).toByte(); it[1] = i.toByte() }); busySpin(300) }
            rx.join()
            val missing = seen.count { !it }
            val st = conn.stats
            assertEquals(0, missing, "missing messages with 10% loss: $missing; stats=$st")
            val lr = conn.estimator.lossRate
            assertTrue(lr in 0.05..0.15, "estimator lossRate=$lr should be near 0.10; stats=$st")
            assertTrue(st.repairsReactive + st.repairsProactive > 0)
        } }
    }

    private fun busySpin(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
}
