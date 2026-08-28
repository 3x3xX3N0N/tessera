package tessera.transport

import tessera.core.Handshake
import tessera.core.StatelessReset
import tessera.core.Wire
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stateless reset end to end (RFC 9000 §10.3 shape; token derivation is in core/StatelessResetTest). The lost-keys
 * complement to CONNECTION_CLOSE: a restarted/crashed server holds no keys for an old connection, so it cannot send an
 * authenticated CLOSE and the client would retransmit into a black hole until its idle timeout. The server instead
 * re-derives the connection's token from its (restart-surviving) ticket key and emits it in a reset packet; the client
 * recognises the token it was handed at handshake and tears down at once.
 *
 * The packets are crafted from a raw [DatagramSocket] so the test does not depend on actually restarting a server: a
 * reset from a restarted server is, on the wire, exactly a short-header-shaped datagram whose trailing 16 bytes are the
 * token, arriving for an id no live connection owns.
 */
class StatelessResetTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 11 + 3).toByte() }
    private val loop: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun server(cfg: ConnConfig = ConnConfig()) = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
    private fun client(cfg: ConnConfig = ConnConfig()) = TesseraClient(InetSocketAddress("127.0.0.1", 0), cfg)

    /** A reset-shaped datagram: [len] random bytes, short header (F_INITIAL clear), the given 4-byte id, the given trailer. */
    private fun resetPacket(trailer: ByteArray, connId: Int, len: Int = TesseraServer.RESET_PACKET_LEN): ByteArray {
        val p = ByteArray(len); Random(1234).nextBytes(p)
        p[0] = (p[0].toInt() and 0x7F).toByte()                 // clear F_INITIAL so the demux treats it as a short packet
        ByteBuffer.wrap(p).putInt(1, connId)                    // 4-byte connId at offset 1, as the demux reads it
        System.arraycopy(trailer, 0, p, len - trailer.size, trailer.size)
        return p
    }

    @Test fun clientTearsDownOnAValidResetButNotAWrongOne() {
        // Long idle timeout: were the reset ignored, the client would hold the connection for 30 s. Every wait below is
        // far shorter, so a pass proves the reset — not the timeout — tore it down.
        val cfg = ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 30_000)
        val s = server(cfg); val c = client(cfg)
        try {
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(s.accept(5_000)); sc.receive(2_000)
            conn.send("data".toByteArray()); assertNotNull(sc.receive(2_000))   // a live, established connection

            val token = assertNotNull(conn.peerResetToken, "server must deliver a reset token at handshake")
            assertEquals(StatelessReset.TOKEN_LEN, token.size)
            // A restarted server does not know our short id, so its reset carries a random one; use an id that cannot
            // collide with our own registered id, or the packet would route to the connection instead of the miss hook.
            val bogusId = conn.localShortId xor 0x5555_5555.toInt()

            DatagramSocket().use { raw ->
                // Wrong token: must NOT tear the connection down.
                val wrong = resetPacket(ByteArray(StatelessReset.TOKEN_LEN) { 0x11 }, bogusId)
                raw.send(DatagramPacket(wrong, wrong.size, loop, c.localAddress.port))
                Thread.sleep(200)
                assertEquals(0L, conn.stats.resetsReceived, "a wrong trailing token must not reset the connection")
                assertFalse(conn.isClosed, "connection still up after a wrong-token packet")

                // Right token: tears it down, well within idleTimeoutMs.
                val good = resetPacket(token, bogusId)
                raw.send(DatagramPacket(good, good.size, loop, c.localAddress.port))
                val deadline = System.nanoTime() + 3_000_000_000L
                while (conn.stats.resetsReceived == 0L && System.nanoTime() < deadline) Thread.sleep(20)
                assertEquals(1L, conn.stats.resetsReceived, "client did not recognise the stateless reset")
            }
            val gone = System.nanoTime() + 3_000_000_000L
            while (!conn.isClosed && System.nanoTime() < gone) Thread.sleep(20)
            assertTrue(conn.isClosed, "client tore the connection down on the reset (not on the idle timeout)")
        } finally { c.close(); s.close() }
    }

    @Test fun serverStatelesslyEmitsTheRightTokenForAnUnknownId() {
        val s = server()
        val resetSecret = StatelessReset.deriveSecret(ticketKey)   // what a restarted instance would re-derive
        try {
            DatagramSocket().use { raw ->
                raw.soTimeout = 3_000
                val unknownId = 0x12AB_CDEF
                // A plausible short packet for an id the server never issued (stands in for a client's black-hole
                // retransmit after the server restarted): the server must answer with a reset.
                val probe = ByteArray(TesseraServer.RESET_PACKET_LEN); Random(7).nextBytes(probe)
                probe[0] = (probe[0].toInt() and 0x7F).toByte()
                ByteBuffer.wrap(probe).putInt(1, unknownId)
                raw.send(DatagramPacket(probe, probe.size, loop, s.localAddress.port))

                val reply = DatagramPacket(ByteArray(2048), 2048)
                raw.receive(reply)
                val body = reply.data.copyOf(reply.length)
                assertTrue(body.size <= probe.size, "reset must not exceed the packet that provoked it (no amplification)")
                assertEquals(0, body[0].toInt() and Wire.F_INITIAL, "reset is short-header shaped (F_INITIAL clear)")
                val trailer = body.copyOfRange(body.size - StatelessReset.TOKEN_LEN, body.size)
                assertContentEquals(StatelessReset.token(resetSecret, unknownId), trailer,
                    "server statelessly emitted the token for exactly that id")
                assertEquals(1L, s.resetsSent)
            }
        } finally { s.close() }
    }

    @Test fun aTooShortPacketDrawsNoResetSoThereIsNoAmplification() {
        val s = server()
        try {
            DatagramSocket().use { raw ->
                raw.soTimeout = 400
                val tiny = ByteArray(TesseraServer.RESET_PACKET_LEN - 1); Random(3).nextBytes(tiny)
                tiny[0] = (tiny[0].toInt() and 0x7F).toByte()
                ByteBuffer.wrap(tiny).putInt(1, 0x0BAD_F00D)
                raw.send(DatagramPacket(tiny, tiny.size, loop, s.localAddress.port))
                var answered = false
                try { raw.receive(DatagramPacket(ByteArray(2048), 2048)); answered = true } catch (e: SocketTimeoutException) {}
                assertFalse(answered, "a packet shorter than a reset must draw no reset")
                assertEquals(0L, s.resetsSent)
            }
        } finally { s.close() }
    }
}
