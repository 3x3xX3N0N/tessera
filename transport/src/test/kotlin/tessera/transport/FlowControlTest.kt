package tessera.transport

import tessera.core.FlowSender
import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connection flow control (`MaxData`, v0.8): the receiver advertises `consumed + recvWindowBytes` in app-payload
 * bytes and the sender charges each message against it up front, so a reader that stops calling `receive()` bounds
 * the peer instead of growing our memory. The bound is an invariant (delivered − consumed <= window), not a rate
 * clamp — the previous credit-piggyback attempt held only on the channel datapath, which is why the central test
 * here runs on both datapaths explicitly rather than relying on the `nativeTest` task re-run.
 */
class FlowControlTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 7).toByte() }

    private fun onBothDatapaths(block: (String) -> Unit) {
        val prev = System.getProperty(Datapath.NATIVE_PROPERTY)
        try {
            for (mode in listOf("off", "on")) {
                if (mode == "on" && !Datapath.nativeAvailable) { println("SKIP native datapath: tessera_native did not load"); continue }
                System.setProperty(Datapath.NATIVE_PROPERTY, mode)
                block(mode)
            }
        } finally {
            if (prev == null) System.clearProperty(Datapath.NATIVE_PROPERTY) else System.setProperty(Datapath.NATIVE_PROPERTY, prev)
        }
    }

    private fun spin(deadlineMs: Long = 5_000, until: () -> Boolean) {
        val end = System.nanoTime() + deadlineMs * 1_000_000
        while (!until() && System.nanoTime() < end) Thread.sleep(10)
    }

    @Test fun aStalledReaderBoundsTheInboxAndResumesOnDrain() = onBothDatapaths { mode ->
        val window = 256L * 1024
        val cfg = ConnConfig(recvWindowBytes = window, maxMessageBytes = 128 * 1024, idleTimeoutMs = 30_000)
        val msg = ByteArray(1000) { (it % 251).toByte() }
        val total = 8000                                     // 8 MB offered against a 256 KiB window
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        val senderError = AtomicReference<Exception>()
        val sent = AtomicInteger()
        var sender: Thread? = null
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000))
            sender = Thread {
                try { repeat(total) { conn.send(msg); sent.incrementAndGet() } } catch (e: Exception) { senderError.set(e) }
            }.also { it.isDaemon = true; it.start() }

            // Stall phase: the server app reads nothing. The sender must hit the window and block...
            spin { conn.stats.flowStalls > 0 }
            assertTrue(conn.stats.flowStalls > 0, "[$mode] the sender never stalled on the flow window")
            // ...and for as long as the reader stays stalled, delivered-but-unread payload obeys the invariant
            // delivered − consumed <= recvWindowBytes — exactly, in app bytes, with zero slack.
            val settle = System.nanoTime() + 500_000_000L
            var maxUnread = 0L
            while (System.nanoTime() < settle) {
                val s = sc.stats
                maxUnread = maxOf(maxUnread, s.payloadBytesOut - s.flowConsumedBytes)
                Thread.sleep(20)
            }
            assertTrue(maxUnread <= window, "[$mode] inbox tracked the offered load ($maxUnread of ${total * msg.size}): backpressure did not bound it")
            assertTrue(sent.get() < total, "[$mode] the sender should still be blocked mid-offer")

            // Drain: every message arrives intact, and the sender finishes — a stall is backpressure, not loss.
            var received = 0
            val deadline = System.nanoTime() + 60_000_000_000L
            while (received < total && System.nanoTime() < deadline) {
                val m = sc.receive(2_000) ?: continue
                if (received == 0 && m.size == 2) continue   // the 0-RTT "hi"
                assertContentEquals(msg, m, "[$mode] message $received arrived corrupted")
                received++
            }
            assertEquals(total, received, "[$mode] not every message arrived after the reader resumed")
            sender.join(10_000)
            assertNull(senderError.get(), "[$mode] sender failed: ${senderError.get()}")
            val s = sc.stats
            assertEquals(0L, s.oversizeDropped + s.reassemblyRefused, "[$mode] an honest flow-controlled run must not shed fragments")
        } finally {
            client.close(); server.close(); sender?.join(5_000)
        }
    }

    /**
     * The leak (v0.9, "MaxData leak-credit"): a message the receiver drops is charged against the flow window and
     * never retired — `consumed` only counts what the application reads, and a dropped message is never read. Every
     * drop shrank the window permanently, and `recvWindowBytes / maxMessageBytes` of them wedged the sender forever.
     *
     * The lever here is the reassembly byte budget, driven from the real stack rather than a hook: `maxReassemblyBytes
     * == maxMessageBytes` (the configured minimum) means the buffer doubling for a fragmented max-size message
     * overshoots the global budget before its fin can clamp the allocation, so every message is refused. Four of them
     * exhaust the 256 KiB window; the test offers forty. Pre-fix the sender dies on `flow-blocked with a silent peer`.
     */
    @Test fun receiverDroppedMessagesCreditTheFlowWindow() = onBothDatapaths { mode ->
        val window = 256L * 1024
        val msgBytes = 64 * 1024
        val cfg = ConnConfig(recvWindowBytes = window, maxMessageBytes = msgBytes, maxReassemblyBytes = msgBytes.toLong(),
            idleTimeoutMs = 20_000)
        val msg = ByteArray(msgBytes) { (it % 251).toByte() }
        val total = 40                                       // 2.5 MB against a window only four messages wide
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        val senderError = AtomicReference<Exception>()
        val sent = AtomicInteger()
        var sender: Thread? = null
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000))
            assertContentEquals("hi".toByteArray(), sc.receive(5_000))
            sender = Thread {
                try { repeat(total) { conn.send(msg); sent.incrementAndGet() } } catch (e: Exception) { senderError.set(e) }
            }.also { it.isDaemon = true; it.start() }
            sender.join(60_000)

            assertNull(senderError.get(), "[$mode] the sender starved on a window that dropped messages never gave back: ${senderError.get()}")
            assertEquals(total, sent.get(), "[$mode] the sender did not get through the offer")
            val s = sc.stats
            assertTrue(s.reassemblyRefused > 0, "[$mode] no message was dropped, so this proves nothing about the leak")
            assertTrue(s.flowAbandonedBytes > window, "[$mode] dropped ${s.reassemblyRefused} fragments but credited only ${s.flowAbandonedBytes} B")
            // The invariant, amended: limit <= consumed + abandoned + window, where flowConsumedBytes is the sum of
            // the first two. Credit is a lower bound on what was charged, so it can never advertise past the bound.
            assertTrue(conn.stats.flowChargedBytes <= conn.stats.flowLimitBytes,
                "[$mode] charged ${conn.stats.flowChargedBytes} > limit ${conn.stats.flowLimitBytes}")
            assertTrue(conn.stats.flowLimitBytes <= s.flowConsumedBytes + window,
                "[$mode] advertised ${conn.stats.flowLimitBytes} above consumed+abandoned ${s.flowConsumedBytes} + window $window")
            assertTrue(s.flowAbandonedBytes <= conn.stats.flowChargedBytes,
                "[$mode] credited ${s.flowAbandonedBytes} B for messages worth at most ${conn.stats.flowChargedBytes} B of charge")
        } finally {
            client.close(); server.close(); sender?.join(5_000)
        }
    }

    @Test fun aLostAdvertRecoversViaTheFlowProbe() {
        val cfg = ConnConfig(recvWindowBytes = 256L * 1024, maxMessageBytes = 128 * 1024, idleTimeoutMs = 30_000)
        val msg = ByteArray(100 * 1024)
        val total = 20                                       // 2 MB, forcing several window refills
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        val senderError = AtomicReference<Exception>()
        val received = AtomicInteger()
        var sender: Thread? = null
        var reader: Thread? = null
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000))
            sc.suppressMaxData = true                        // advert blackout: piggybacked and standalone
            reader = Thread {
                while (received.get() < total + 1) { sc.receive(500) ?: continue; received.incrementAndGet() }
            }.also { it.isDaemon = true; it.start() }
            sender = Thread {
                try { repeat(total) { conn.send(msg) } } catch (e: Exception) { senderError.set(e) }
            }.also { it.isDaemon = true; it.start() }

            // The reader drains, but with every advert suppressed the sender exhausts the last limit it saw and
            // stalls; the flow probe must be firing into the blackout.
            spin { conn.stats.flowStalls > 0 }
            spin { conn.stats.flowProbes > 0 }
            assertTrue(conn.stats.flowStalls > 0, "the sender never stalled during the advert blackout")
            assertTrue(conn.stats.flowProbes > 0, "a flow-blocked sender must probe")

            sc.suppressMaxData = false                       // blackout over: the next probe's ACK carries the limit
            spin(30_000) { received.get() >= total + 1 && senderError.get() == null }
            assertEquals(total + 1, received.get(), "not everything arrived after the blackout lifted (0-RTT + $total)")
            assertNull(senderError.get(), "sender failed: ${senderError.get()}")
        } finally {
            client.close(); server.close(); sender?.join(5_000); reader?.join(5_000)
        }
    }

    @Test fun theEstablishmentAdvertLiftsTheInitialLimit() {
        // A first message far above FlowSender.INITIAL_WINDOW: without the establishment advert the sender would
        // block before emitting anything ack-eliciting, and no ACK would ever exist to piggyback the limit on.
        val cfg = ConnConfig(recvWindowBytes = 512L * 1024, maxMessageBytes = 256 * 1024, idleTimeoutMs = 30_000)
        val big = ByteArray(200 * 1024) { (it % 249).toByte() }
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(server.accept(5_000))
            assertContentEquals("hi".toByteArray(), sc.receive(2_000))
            conn.send(big)
            val out = assertNotNull(sc.receive(10_000), "the first big message never arrived")
            assertContentEquals(big, out)
            assertTrue(conn.stats.flowLimitBytes > FlowSender.INITIAL_WINDOW, "the advert never lifted the initial limit")
        } finally {
            client.close(); server.close()
        }
    }
}
