package aether.transport

import aether.core.Handshake
import aether.native.Gf256Native
import aether.native.NativeLib
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The EndpointTest scenarios (fresh connect, resume, 10 KB message, 10 % loss) pinned to one datapath: `mode` is
 * forced into `-Daether.native` for the duration of each test and restored afterwards, so [NativeIoTest] exercises
 * [NativeUdpIo] and [ChannelIoTest] exercises [ChannelUdpIo] no matter which task runs them (`:transport:test` runs
 * with the default `auto`; `:transport:nativeTest` with `-Daether.native=on`, which also puts EndpointTest itself on
 * the native path). GSO run coalescing is forced on so that code path is covered on Windows/macOS too, where the
 * library segments in user space.
 */
abstract class DatapathScenarios(private val mode: String) {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 3).toByte() }
    private var prevNative: String? = null
    private var prevGso: String? = null
    private val native get() = mode == "on"

    @BeforeTest fun pinDatapath() {
        prevNative = System.getProperty(Datapath.NATIVE_PROPERTY); prevGso = System.getProperty(NativeUdpIo.GSO_PROPERTY)
        System.setProperty(Datapath.NATIVE_PROPERTY, mode); System.setProperty(NativeUdpIo.GSO_PROPERTY, "on")
        if (native) assertTrue(Datapath.nativeAvailable, "aether_native failed to load: ${NativeLib.loadError}")
    }

    @AfterTest fun restoreProperties() { restore(Datapath.NATIVE_PROPERTY, prevNative); restore(NativeUdpIo.GSO_PROPERTY, prevGso) }
    private fun restore(k: String, v: String?) { if (v == null) System.clearProperty(k) else System.setProperty(k, v) }

    private fun server(cfg: ConnConfig = ConnConfig()) = AetherServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)

    /** Proves the selection: both endpoint sockets are NativeUdpIo (or neither), and native selection installs the kernel. */
    private fun assertDatapath(before: Int) {
        assertEquals(before + if (native) 2 else 0, Datapath.openNativeSockets, "server + client sockets on the expected datapath")
        if (native) assertTrue(Gf256Native.installed, "selecting the native datapath installs the native GF256 kernel")
    }

    @Test fun freshConnectDeliversZeroRttPayloadAndEchoes() {
        val before = Datapath.openNativeSockets
        server().use { s -> AetherClient().use { c ->
            assertDatapath(before)
            val first = "GET /index 0-rtt".toByteArray()
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, first)
            val sc = assertNotNull(s.accept(2_000))
            assertContentEquals(first, sc.receive(1_000))
            assertNotNull(conn.ticket, "fresh connect must yield a resumption ticket")
            assertEquals(sc.connId, conn.connId)
            sc.send("pong".toByteArray())
            assertContentEquals("pong".toByteArray(), conn.receive(1_000))
        } }
        assertEquals(before, Datapath.openNativeSockets, "close() releases the native sockets")
    }

    @Test fun resumeWithTicketFromFirstConnection() {
        val before = Datapath.openNativeSockets
        server().use { s -> AetherClient().use { c ->
            assertDatapath(before)
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
        val before = Datapath.openNativeSockets
        server().use { s -> AetherClient().use { c ->
            assertDatapath(before)
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
        val before = Datapath.openNativeSockets
        server().use { s -> AetherClient().use { c ->
            assertDatapath(before)
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
            val sst = sc.stats
            assertTrue(sst.recovered > 0, "the receiver must have recovered lost packets from repair symbols (native GF256 kernel: ${Gf256Native.installed}); server stats=$sst")
        } }
    }

    private fun busySpin(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
}

/** EndpointTest scenarios on [NativeUdpIo] (+ [Gf256Native] kernel), plus wire-identity and batching checks. */
class NativeIoTest : DatapathScenarios("on") {
    private class Rx(val from: InetSocketAddress, val bytes: ByteArray)

    /** Installs a collecting handler on `rx`; call before sending. */
    private fun collector(rx: Datapath): ConcurrentLinkedQueue<Rx> = ConcurrentLinkedQueue<Rx>().also { q ->
        rx.onDatagram { buf, from -> q.add(Rx(from, ByteArray(buf.remaining()).also { buf.get(it) })) }
    }

    private fun await(q: ConcurrentLinkedQueue<Rx>, expected: Int, timeoutMs: Long = 3_000): List<Rx> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (q.size < expected && System.nanoTime() < deadline) Thread.sleep(1)
        return q.toList().also { q.clear() }
    }

    private fun datagram(i: Int, size: Int) = ByteArray(size) { j -> (i * 37 + j).toByte() }.also { it[0] = 0x80.toByte() }

    /** The same datagrams through ChannelUdpIo and NativeUdpIo arrive byte-identical, in order, from the right port. */
    @Test fun wireBytesIdenticalToChannelAndInOrder() {
        Datapath.open(InetSocketAddress("127.0.0.1", 0), native = true, name = "rx").use { rx ->
            val q = collector(rx)
            val sizes = listOf(1350, 1350, 1350, 1350, 700, 40, 40, 1200, 1200, 1200, 16, 5)
            val payloads = sizes.mapIndexed { i, s -> datagram(i, s) }
            val viaChannel = Datapath.open(InetSocketAddress("127.0.0.1", 0), native = false, name = "ch").use { tx ->
                assertEquals("channel", tx.implementation)
                payloads.forEach { tx.send(ByteBuffer.wrap(it), rx.localAddress) }
                await(q, payloads.size).also { got -> assertEquals(tx.localAddress.port, got.first().from.port) }
            }
            val viaNative = Datapath.open(InetSocketAddress("127.0.0.1", 0), native = true, name = "nt").use { tx ->
                assertEquals("native", tx.implementation)
                tx.deferSends(true) // coalesce: runs of 1350 x4, 40 x2 and 1200 x3 become GSO super-datagrams (forced on)
                payloads.forEach { tx.send(ByteBuffer.wrap(it), rx.localAddress) }
                tx.flush()
                await(q, payloads.size).also { got ->
                    assertEquals(tx.localAddress.port, got.first().from.port)
                    assertEquals(3L, tx.gsoRuns, "three equal-size runs expected; ${tx.stats}")
                }
            }
            assertEquals(payloads.size, viaChannel.size); assertEquals(payloads.size, viaNative.size)
            for (i in payloads.indices) {
                assertContentEquals(payloads[i], viaChannel[i].bytes, "channel datagram $i")
                assertContentEquals(payloads[i], viaNative[i].bytes, "native datagram $i")
            }
            assertTrue(rx.stats.contains("addrMiss=2 "), "one InetSocketAddress per distinct sender: ${rx.stats}")
        }
    }

    /** A deferred batch larger than TX_BATCH flushes itself when full; nothing is lost or reordered. */
    @Test fun fullBatchFlushesItself() {
        Datapath.open(InetSocketAddress("127.0.0.1", 0), native = true, name = "rx").use { rx ->
            Datapath.open(InetSocketAddress("127.0.0.1", 0), native = true, name = "tx").use { tx ->
                val n = NativeUdpIo.TX_BATCH * 3 + 7
                val q = ConcurrentLinkedQueue<Int>()
                rx.onDatagram { buf, _ -> q.add(buf.getInt(1)) }
                tx.deferSends(true)
                val b = ByteBuffer.allocateDirect(1200)
                for (i in 0 until n) { b.clear(); b.put(0, 0x80.toByte()); b.putInt(1, i); b.limit(1200).position(0); tx.send(b, rx.localAddress) }
                tx.flush()
                val deadline = System.nanoTime() + 3_000_000_000L
                while (q.size < n && System.nanoTime() < deadline) Thread.sleep(1)
                assertEquals((0 until n).toList(), q.toList(), "all $n datagrams, in order; ${tx.stats}")
            }
        }
    }
}

/** The same scenarios on [ChannelUdpIo], so the DatagramChannel path stays covered whatever `-Daether.native` says. */
class ChannelIoTest : DatapathScenarios("off")
