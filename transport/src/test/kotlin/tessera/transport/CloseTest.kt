package tessera.transport

import tessera.core.Frame
import tessera.core.FrameCodec
import tessera.core.Handshake
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CONNECTION_CLOSE (frame 0x08). Before this, a closing peer sent no signal: the other side only learned the
 * connection was gone when [ConnConfig.idleTimeoutMs] (10 s) elapsed, holding its state the whole time. A CLOSE
 * frame lets the receiver free at once. (This is the in-band, both-sides-have-keys case; a stateless reset for the
 * lost-keys case — a restarted server — is a separate, still-unimplemented mechanism.)
 */
class CloseTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 9).toByte() }

    @Test fun closeFrameRoundTrips() {
        val buf = ByteBuffer.allocate(64)
        Frame.Close(7, "bye").write(buf); buf.flip()
        val c = FrameCodec.read(buf) as Frame.Close
        assertEquals(7, c.code); assertEquals("bye", c.reason)
    }

    @Test fun closingAPeerFreesTheOtherSidePromptly() {
        // Deliberately short idle timeout: if the CLOSE frame did nothing, the server would still hold the connection
        // for this long. The assertion below waits far less than that, so a pass means the CLOSE actually freed it.
        val cfg = ConnConfig(idleTimeoutMs = 30_000)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)
            conn.send("data".toByteArray()); assertNotNull(sc.receive(2_000))
            assertTrue(server.connections.isNotEmpty(), "server should hold the connection before close")

            conn.close()   // sends CONNECTION_CLOSE

            // Poll for the server to observe the close and drop the connection — well within idleTimeoutMs.
            val deadline = System.nanoTime() + 3_000_000_000L
            while (sc.stats.closeReceived == 0L && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(1L, sc.stats.closeReceived, "server did not receive the CONNECTION_CLOSE")
            assertEquals(0, sc.stats.peerCloseCode, "normal close carries code 0")

            val gone = System.nanoTime() + 3_000_000_000L
            while (server.connections.isNotEmpty() && System.nanoTime() < gone) Thread.sleep(20)
            assertTrue(server.connections.isEmpty(), "server freed its connection state on close, not on idle timeout")
        } finally {
            client.close(); server.close()
        }
    }
}
