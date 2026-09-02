package tessera.transport

import tessera.core.Frame
import tessera.core.Handshake
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A message the receiver's reassembler destroys must fail the connection, not vanish (TODO item 12, 2026-08-30).
 *
 * The defect these cover was found on hardware, not here: scl->syd under a 20 mbit tbf, 4/6 reps of a 5 MB push in
 * 32 KB chunks, both processes hung — the sender blocked on a receipt, the receiver on bytes its own reassembler had
 * thrown away. It was silent because every layer thought it had succeeded: the packets were received and acked, the
 * fec seqs were complete (`lowestUndelivered > largest`, so nothing looked missing), and the flow credit was
 * returned. Nothing retransmits a message the sender has no reason to believe was lost.
 *
 * So the assertions here are about *noise*, deliberately: that the failure is raised at both ends and names itself.
 * The cap being reachable at all is a separate defect — the flow window funds 8x more concurrent messages than the
 * reassembler will hold — and is fixed by making the caps consistent, not here.
 */
class UndeliverableTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 7).toByte() }

    /**
     * The cap and the latch, straight on the reassembler: N+1 distinct message ids held partial at once.
     *
     * Driven directly because the interesting state is one the network cannot be asked for on demand — a set of
     * messages that are *simultaneously* incomplete. Over a link that takes loss and reordering to arrange, and the
     * RLNC decoder spends its time undoing exactly that.
     */
    @Test fun theConcurrencyCapDestroysMessagesAndLatchesTheFirst() {
        val cap = 4
        val r = TesseraConnection.Reassembler(maxMessageBytes = 1 shl 20, maxConcurrent = cap, maxBytes = 1L shl 20)
        // Each id gets a first fragment and no fin, so all of them stay partial and occupy a slot.
        for (id in 0 until cap) {
            assertNull(r.onFragment(id.toLong(), 0, ByteBuffer.allocate(16), fin = false),
                "message $id completed on a fragment with no fin")
        }
        assertEquals(-1L, r.undeliverable, "nothing was destroyed while the cap had room")
        assertEquals(cap, r.pending)

        // One past the cap: a new id cannot be held, so it is abandoned outright.
        assertNull(r.onFragment(99L, 0, ByteBuffer.allocate(16), fin = false))
        assertEquals(99L, r.undeliverable, "the destroyed message was not recorded")
        assertEquals(1L, r.undeliverableCount)

        // Latched: a second casualty does not move the id, because the first is the one that broke the contract.
        assertNull(r.onFragment(100L, 0, ByteBuffer.allocate(16), fin = false))
        assertEquals(99L, r.undeliverable, "the latch moved off the first destroyed message")
        assertEquals(2L, r.undeliverableCount)

        // And the destruction is permanent: the rest of message 99 arriving changes nothing. This is the property
        // that makes it unrecoverable rather than merely late — a retransmit would land here and be dropped too.
        assertNull(r.onFragment(99L, 16, ByteBuffer.allocate(16), fin = true),
            "an abandoned message completed on a later fragment")
    }

    /**
     * End to end, both ends. The lever is an asymmetric `maxMessageBytes`: the sender is configured to allow a
     * message the receiver is configured to refuse, so the receiver abandons it on arrival with no loss, no
     * reordering and no timing involved. (v0 has no parameter negotiation — `ConnConfig` says the contract assumes
     * compatible configs, and this is what an incompatible pair does.)
     *
     * Pre-fix both `receive()` calls below simply time out and return null, which is the hang: no exception, no
     * close, no counter the application can see.
     */
    @Test fun anUndeliverableMessageFailsBothEndsInsteadOfHanging() {
        val big = 256 * 1024
        // The receiver will not hold a message this large; the sender will happily send one.
        val serverCfg = ConnConfig(pingIntervalMs = 0, maxMessageBytes = 64 * 1024, idleTimeoutMs = 20_000)
        val clientCfg = ConnConfig(pingIntervalMs = 0, maxMessageBytes = big, idleTimeoutMs = 20_000)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, serverCfg)
        val client = TesseraClient(cfg = clientCfg)
        val senderError = AtomicReference<Exception>()
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000))
            assertContentEquals("hi".toByteArray(), sc.receive(5_000))

            // A message that already arrived stays readable: the failure is about what can never arrive.
            conn.send("first".toByteArray())
            assertContentEquals("first".toByteArray(), sc.receive(5_000))

            try { conn.send(ByteArray(big) { (it % 251).toByte() }) } catch (e: Exception) { senderError.set(e) }

            // The receiver: its own reassembler destroyed the message, so its next receive throws rather than
            // blocking out the timeout on bytes that no longer exist.
            val onReceiver = assertFailsWith<UndeliverableMessageException>("the receiver hung instead of failing") {
                sc.receive(10_000)
            }
            assertTrue(onReceiver.message!!.contains("undeliverable"), "unnamed failure: ${onReceiver.message}")

            val s = sc.stats
            assertTrue(s.undeliverableMsgs > 0, "the receiver did not count the message it destroyed")

            // The sender: it is told, and by a named code rather than an idle timeout. This is the half that cannot
            // be had locally — the sender has no way to know, since everything it sent was acknowledged.
            val onSender = assertFailsWith<UndeliverableMessageException>("the sender was never told") {
                conn.receive(10_000)
            }
            assertTrue(onSender.message!!.contains("${Frame.Close.CODE_UNDELIVERABLE}"),
                "the sender's report does not carry the close code: ${onSender.message}")
            assertEquals(Frame.Close.CODE_UNDELIVERABLE, conn.stats.peerCloseCode,
                "the peer's close code was not recorded on the sender")
        } finally {
            client.close(); server.close()
        }
    }

    /**
     * The other half of item 12 - the invariant, so the cap above is unreachable by honest traffic. The shipped
     * defaults funded 512 concurrent 32 KB messages against a cap of 64 (the scl->syd stall, 4/6 reps); now the cap
     * derives from the window, the old default is rejected as an inconsistent config at construction, and a whole
     * window's worth of partial messages is held and completed without a single abandon.
     */
    @Test fun theWindowCannotOutrunTheReassemblerAnyMore() {
        val cfg = ConnConfig()
        val perWindow = (cfg.recvWindowBytes / TesseraConnection.MIN_PARTIAL_MESSAGE_BYTES).toInt()
        assertTrue(cfg.concurrentReassemblyLimit >= perWindow, "derived cap ${cfg.concurrentReassemblyLimit} < the window's $perWindow messages")
        assertTrue(cfg.concurrentReassemblyLimit >= 512, "the hardware case: 16 MB of 32 KB chunks in flight")
        val r = TesseraConnection.Reassembler(cfg.maxMessageBytes, cfg.concurrentReassemblyLimit, cfg.maxReassemblyBytes)
        // the scl->syd shape, in-process: a window's worth of 32 KB chunks all partial at once (first fragment, no fin)
        val chunks = (cfg.recvWindowBytes / (32 * 1024)).toInt()
        for (id in 0 until chunks) assertNull(r.onFragment(id.toLong(), 0, ByteBuffer.allocate(1200), fin = false), "message $id completed early")
        assertEquals(chunks, r.pending)
        assertEquals(-1L, r.undeliverable, "an honest window's worth of partial messages was abandoned")
        for (id in 0 until chunks) assertNotNull(r.onFragment(id.toLong(), 1200, ByteBuffer.allocate(100), fin = true), "message $id did not complete")
        assertEquals(0, r.pending)
        // the old shipped default is refused at construction now, rather than hanging applications later
        assertFailsWith<IllegalArgumentException> { ConnConfig(maxConcurrentReassembly = 64) }
        // an explicit cap the window cannot outrun is accepted
        ConnConfig(maxConcurrentReassembly = 1024, maxMessageBytes = 64 * 1024, recvWindowBytes = 1024L * 1024)
    }
}
