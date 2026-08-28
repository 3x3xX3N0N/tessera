package tessera.transport

import tessera.core.Frame
import tessera.core.Handshake
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Mid-connection ACK cadence ([Frame.AckFrequency], frame 0x0A).
 *
 * `ConnParams.ackFreq` already negotiated a cadence at setup, and that was recorded as "ACK frequency
 * negotiation, done". It is done once. The cadence that is right for a connection is a property of its path —
 * an ack every 2 packets is 1000 ACKs/s at the netem matrix's 2000 pkt/s, which is free on ethernet and is
 * *reverse-direction airtime* on a metered uplink — and the path changes underneath a connection: slow start
 * moves the rate two orders of magnitude, and a rebind can move the flow onto a radio entirely (`RebindTest`).
 * So the request has to be re-expressible after setup, which is this frame.
 *
 * What is asserted here is the mechanism, not a performance claim: the request arrives, it is adopted, it
 * actually changes how many ACKs come back, its bounds hold against a hostile value, and — the property that
 * makes raising it safe — a gap still draws an immediate ACK regardless of cadence.
 */
class AckFrequencyTest {
    private val keys = Handshake.generate()

    private class Pair(val client: TesseraConnection, val server: TesseraConnection, val c: TesseraClient, val s: TesseraServer) : AutoCloseable {
        override fun close() { runCatching { client.close() }; runCatching { server.close() }; c.close(); s.close() }
    }

    private fun connect(cfg: ConnConfig = ConnConfig()): Pair {
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg)
        val client = TesseraClient(cfg = cfg)
        val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "af".toByteArray(), timeoutMs = 10_000)
        val sc = server.accept(5_000) ?: fail("server did not accept")
        sc.receive(2_000)
        return Pair(conn, sc, client, server)
    }

    /** Sends `n` messages from the client and reads them all on the server, so the run is not cut short by a stall. */
    private fun pump(p: Pair, n: Int, bytes: Int = 400) {
        val payload = ByteArray(bytes) { it.toByte() }
        var read = 0
        for (i in 0 until n) {
            p.client.send(payload)
            while (p.server.receive(0) != null) read++
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (read < n && System.nanoTime() < deadline) { if (p.server.receive(50) != null) read++ }
        assertEquals(n, read, "the workload itself did not complete; the ACK counts would be meaningless")
    }

    /**
     * The request reaches the peer and is adopted. Asserted on the peer's own view (`peerRequestedAckFreq`) rather
     * than on a rate, so a failure here says "the frame did not arrive" and not "the cadence did not move".
     */
    @Test fun aRequestReachesThePeerAndIsAdopted() {
        connect().use { p ->
            assertTrue(p.client.requestPeerAckFrequency(32, 20_000), "the client could not emit the request")
            // the frame is un-eliciting, so nothing schedules its arrival but ordinary traffic
            pump(p, 100)
            val deadline = System.nanoTime() + 2_000_000_000L
            while (p.server.stats.ackFreqReceived == 0L && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1L, p.server.stats.ackFreqReceived, "the server never saw the request")
            assertEquals(32, p.server.stats.peerRequestedAckFreq)
            assertEquals(20_000L, p.server.stats.peerRequestedAckDelayUs)
            assertEquals(1L, p.client.stats.ackFreqSent)
        }
    }

    /**
     * The point of the frame: it changes the reverse-direction ACK rate. Compared against the same workload on a
     * default-cadence connection in the same JVM, because an absolute ACK count depends on timers, loss and the
     * delayed-ack deadline — the *ratio* between two identical runs is the claim.
     *
     * `ackDelayUs` is raised alongside the frequency in the raised arm: the delayed-ack timer is a second,
     * independent trigger, and leaving it at 1 ms would have the timer emit the ACKs the counter no longer does,
     * which is precisely the trap this test would otherwise pass through.
     */
    @Test fun raisingTheCadenceCutsTheAcksThePeerSendsBack() {
        val n = 400
        connect().use { base ->
            pump(base, n)
            val baseline = base.server.stats.acksSent
            connect().use { raised ->
                // Sent more than once, because that is the frame's own contract: un-eliciting, never
                // retransmitted, "a lost one is superseded by the next". A full-suite run dropped the single
                // copy on loopback (loaded UDP socket buffers drop even there) and failed this test with
                // "the request never landed" — which was the test ignoring the unreliability it documents.
                assertTrue(raised.client.requestPeerAckFrequency(64, 50_000))
                pump(raised, n / 2)
                if (raised.server.stats.ackFreqReceived == 0L) assertTrue(raised.client.requestPeerAckFrequency(64, 50_000))
                pump(raised, n / 2)
                val after = raised.server.stats.acksSent
                assertTrue(raised.server.stats.ackFreqReceived > 0, "the request never landed, so this compares nothing")
                println("ackFreq: $n msgs, peer acks default=$baseline raised(64/50ms)=$after")
                assertTrue(after < baseline,
                    "raising the cadence to 64 did not reduce the peer's ACKs: $after vs baseline $baseline over $n messages")
            }
        }
    }

    /**
     * A cadence request must not be able to switch feedback off. Both fields are clamped where they are read, so
     * the hostile values here are asked for through the ordinary API and the peer's own report is checked.
     */
    @Test fun hostileValuesAreClampedNotHonoured() {
        connect().use { p ->
            assertTrue(p.client.requestPeerAckFrequency(1_000_000, 60_000_000L))
            pump(p, 100)
            val deadline = System.nanoTime() + 2_000_000_000L
            while (p.server.stats.ackFreqReceived == 0L && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(Frame.AckFrequency.MAX_FREQ, p.server.stats.peerRequestedAckFreq,
                "an ack-every-million request was honoured")
            assertEquals(Frame.AckFrequency.MAX_DELAY_US, p.server.stats.peerRequestedAckDelayUs,
                "a 60 s ack delay was honoured; a sender would be left without feedback for a minute")
            // and the connection still works, which is the reason the clamp is a clamp and not a close
            p.client.send(ByteArray(128))
            assertTrue(p.server.receive(5_000) != null, "the clamped connection stopped carrying messages")
        }
    }

    /** Before the handshake completes there is nothing to send it on, and the API must say so rather than throw. */
    @Test fun aClosedConnectionRefusesTheRequest() {
        val p = connect()
        p.close()
        assertTrue(!p.client.requestPeerAckFrequency(8), "a closed connection accepted a cadence request")
    }
}
