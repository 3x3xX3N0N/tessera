package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W5 — many connections on one server. Every other test in this suite uses exactly one connection, so what is
 * pinned here is what only shows up in bulk: that a server accepts a crowd, that no connection is starved by
 * its neighbours, and that per-connection footprint does not silently grow.
 *
 * One client socket and one server socket carry all of them (both demux on connId), so a regression here is a
 * regression in the cost of a CONNECTION, not of a socket or a thread.
 *
 * The memory bound is deliberately loose. Measured 2026-08-26 at ~1 MB per connection *pair* idle (both ends
 * live in this JVM), dominated by fixed-size rings sized for the 2000 msg/s worst case — PathState's
 * RING = 8192 packet-tracking arrays are ~300 KB of it and the connection's BODY_RING side-tables ~110 KB, per
 * endpoint. The assertion is set at 3 MB/pair so it catches a multiple, not a drift; `bench conns` is where the
 * real number is tracked. See BENCH-netem, "W5 — many connections".
 */
@Tag("timing")
class ManyConnectionsTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 23).toByte() }

    private fun heapFloor(): Long {
        repeat(3) { System.gc(); Thread.sleep(100) }
        val rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory()
    }

    @Test fun aCrowdOfConnectionsIsAcceptedServedFairlyAndBounded() {
        val n = 100
        val cfg = ConnConfig()
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        val running = AtomicBoolean(true)
        try {
            val base = heapFloor()
            val conns = ArrayList<TesseraConnection>(n)
            val accepted = ArrayList<TesseraConnection>(n)
            val t0 = System.nanoTime()
            repeat(n) {
                conns += client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "w5".toByteArray(), timeoutMs = 15_000)
                accepted += assertNotNull(server.accept(15_000), "server stopped accepting at connection ${conns.size}")
            }
            val establishMs = (System.nanoTime() - t0) / 1_000_000
            accepted.forEach { it.receive(2_000) }
            val idlePerConn = (heapFloor() - base) / n

            // Every connection sends the same amount at the same time: nobody may be starved by the crowd.
            val perConn = 40
            val delivered = Array(n) { AtomicLong() }
            val rx = accepted.mapIndexed { i, sc ->
                Thread { while (running.get()) { if (sc.receive(200) != null) delivered[i].incrementAndGet() } }
                    .apply { isDaemon = true; start() }
            }
            val msg = ByteArray(1000)
            val tx = conns.map { c -> Thread { repeat(perConn) { c.send(msg); Thread.sleep(5) } }.apply { isDaemon = true; start() } }
            tx.forEach { it.join(60_000) }
            val settle = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < settle && delivered.any { it.get() < perConn }) Thread.sleep(100)
            running.set(false); rx.forEach { it.join(2_000) }

            val counts = delivered.map { it.get() }
            println("W5 established $n in ${establishMs}ms (${n * 1000 / establishMs.coerceAtLeast(1)}/s) " +
                "idle=${idlePerConn / 1024}KB/conn-pair delivered min=${counts.min()} max=${counts.max()} of $perConn")
            assertEquals(n, conns.size)
            assertTrue(counts.all { it == perConn.toLong() },
                "every connection must deliver everything; starved connections: ${counts.withIndex().filter { it.value < perConn }.take(5)}")
            assertTrue(idlePerConn < 3L * 1024 * 1024,
                "per-connection footprint grew past 3 MB/pair (was ~1 MB when measured): ${idlePerConn / 1024}KB")
            conns.forEach { runCatching { it.close() } }; accepted.forEach { runCatching { it.close() } }
        } finally { running.set(false); client.close(); server.close() }
    }
}
