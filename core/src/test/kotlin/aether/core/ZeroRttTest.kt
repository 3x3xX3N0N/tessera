package aether.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZeroRttTest {
    @Test fun firstPacketCarriesDataAndFitsMtu() {
        val server = Handshake.generate()
        val client = ZeroRtt.Client(Handshake.initiate(server.x25519Pub, server.kemPub))
        val msg = "GET /index 0-rtt".toByteArray()
        val body = client.initial(msg, nowMs = 1000, nonce = 42)
        assertTrue(Wire.HEADER_LEN + body.size <= Wire.MAX_DATAGRAM)
        val acc = ZeroRtt.Server(server).accept(body, nowMs = 1001)
        assertNotNull(acc); assertContentEquals(msg, acc.data); assertContentEquals(client.key, acc.key)
    }
    @Test fun replayAndStaleRejected() {
        val server = Handshake.generate(); val srv = ZeroRtt.Server(server)
        val body = ZeroRtt.Client(Handshake.initiate(server.x25519Pub, server.kemPub)).initial(byteArrayOf(1), 1000, 7)
        assertNotNull(srv.accept(body, 1000))
        assertNull(srv.accept(body, 1000), "replay must fail")
        val stale = ZeroRtt.Client(Handshake.initiate(server.x25519Pub, server.kemPub)).initial(byteArrayOf(1), 1000, 8)
        assertNull(srv.accept(stale, 1000 + 60_000), "stale must fail")
    }
    @Test fun tamperRejected() {
        val server = Handshake.generate()
        val body = ZeroRtt.Client(Handshake.initiate(server.x25519Pub, server.kemPub)).initial(byteArrayOf(1, 2, 3), 1000, 7)
        body[body.size - 1] = (body.last().toInt() xor 1).toByte()
        assertNull(ZeroRtt.Server(server).accept(body, 1000))
    }
}
