package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W3 — connect storm. The per-accept ML-KEM-768 decapsulation had only ever been timed serially, and the v0.7
 * address validation was built and tested against a *hostile* flood. The honest case — a crowd of legitimate
 * clients arriving at the same instant, as after a server restart — is a different question, and this pins its
 * contract: **Retry is allowed, refusal is not.** The adaptive defence may make honest clients pay a round trip
 * when it cannot tell them apart from an attack, but it must never turn one away.
 *
 * Each client gets its own socket, so each gets its own source address and its own token bucket — N connects
 * down one shared socket look like a single very aggressive source, which is the thing the bucket exists to
 * throttle, and measures the defence rather than the workload (BENCH-netem, "W3 — connect storm", has both).
 */
@Tag("timing")
class ConnectStormTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 29).toByte() }

    @Test fun anHonestCrowdArrivingAtOnceIsNeverRefused() {
        val n = 64
        val cfg = ConnConfig()
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val clients = ConcurrentLinkedQueue<TesseraClient>()
        val conns = ConcurrentLinkedQueue<TesseraConnection>()
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(n)
            val failures = AtomicInteger()
            val firstError = java.util.concurrent.atomic.AtomicReference<String>()
            repeat(n) {
                Thread {
                    start.await()
                    try {
                        val c = TesseraClient(cfg = cfg).also { clients += it }
                        conns += c.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "w3".toByteArray(), timeoutMs = 30_000)
                    } catch (e: Exception) { failures.incrementAndGet(); firstError.compareAndSet(null, e.toString()) }
                    done.countDown()
                }.apply { isDaemon = true; start() }
            }
            val accepted = AtomicInteger()
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val accepter = Thread { while (!stop.get()) { if (server.accept(200) != null) accepted.incrementAndGet() } }
                .apply { isDaemon = true; start() }

            val t0 = System.nanoTime()
            start.countDown()
            assertTrue(done.await(60, java.util.concurrent.TimeUnit.SECONDS), "the storm did not finish in 60 s")
            val ms = (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(2_000); stop.set(true); accepter.join(3_000)

            val v = server.validator
            println("W3 storm n=$n in ${ms}ms connected=${conns.size} accepted=${accepted.get()} failed=${failures.get()} " +
                "retriesSent=${server.retriesSent} validator(admitted=${v.admitted} retried=${v.retried} dropped=${v.dropped} underPressure=${v.underPressure})")

            assertEquals(0, failures.get(), "a legitimate client was refused: ${firstError.get()}")
            assertEquals(n, conns.size, "every honest client must connect")
            assertEquals(n, accepted.get(), "the server must accept every connection it completed")
            // Retry is permitted — it is the designed response when the server cannot yet tell this crowd from an
            // attack, and it costs a round trip, not a connection. Dropping one is the thing that must never happen.
            assertEquals(0L, v.dropped, "the validator dropped a legitimate initial")
        } finally {
            conns.forEach { runCatching { it.close() } }; clients.forEach { runCatching { it.close() } }; server.close()
        }
    }
}
