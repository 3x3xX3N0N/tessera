package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraConnection
import tessera.transport.TesseraServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * W3 — connect storm. Per-accept ML-KEM-768 decapsulation has only ever been timed SERIALLY, and the address
 * validation of v0.7 was built against a *hostile* flood (garbage initials, Retry, token buckets). The honest
 * case — a crowd of legitimate clients arriving at the same instant, as after a server restart or a network
 * blip — has never been measured, and it is a different question: no attacker, no Retry, just contention on the
 * accept path and on the one CPU-bound step in the handshake.
 *
 * All clients are released together from a latch, so the storm is simultaneous rather than merely fast. What is
 * reported is per-connect latency across the storm (the tail is the interesting part: it is queueing for the
 * KEM), completion, and whether the server refused anyone.
 *
 * usage: bench storm [--n 200] [--resume false] [--netem <preset>]
 */
fun stormMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val n = opt("n", "200").toInt()
    // One client socket means one source address, which is exactly what the per-source token bucket exists to
    // throttle — that measures the DEFENCE, not an honest crowd. --multi gives each client its own socket, so
    // each gets its own bucket, which is what N real clients arriving at once actually looks like.
    val multi = opt("multi", "false").toBoolean()
    val netemName = opt("netem", "")
    val netem = if (netemName.isEmpty()) null else tessera.transport.NetemSim.preset(netemName)
    val keys = Handshake.generate()
    val cfg = ConnConfig(netem = netem)

    println(String.format(Locale.ROOT, "storm    %s: %d simultaneous fresh PQ connects, %s", if (netem == null) "loopback" else netemName, n, if (multi) "one socket EACH (N source addresses)" else "one shared socket (single source address)"))
    try {
        TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
            TesseraClient(cfg = cfg).use { client ->
                // Warm the JIT and the KEM path so the storm measures contention, not class loading.
                repeat(5) { client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "w".toByteArray(), timeoutMs = 10_000).also { it.close() } }
                repeat(5) { server.accept(5_000) }

                val start = CountDownLatch(1)
                val done = CountDownLatch(n)
                val latencies = ConcurrentLinkedQueue<Long>()
                val failures = AtomicInteger()
                val conns = ConcurrentLinkedQueue<TesseraConnection>()
                val extraClients = ConcurrentLinkedQueue<TesseraClient>()
                repeat(n) {
                    Thread {
                        start.await()
                        val t = System.nanoTime()
                        try {
                            val c = if (multi) TesseraClient(cfg = cfg).also { extraClients += it } else client
                            conns += c.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "w3".toByteArray(), timeoutMs = 30_000)
                            latencies += System.nanoTime() - t
                        } catch (e: Exception) { failures.incrementAndGet() }
                        done.countDown()
                    }.apply { isDaemon = true; start() }
                }
                val accepterStop = java.util.concurrent.atomic.AtomicBoolean(false)
                val acceptedCount = AtomicInteger()
                val accepter = Thread {
                    while (!accepterStop.get()) { if (server.accept(200) != null) acceptedCount.incrementAndGet() }
                }.apply { isDaemon = true; start() }

                val t0 = System.nanoTime()
                start.countDown()
                done.await()
                val wallMs = (System.nanoTime() - t0) / 1_000_000
                Thread.sleep(2_000); accepterStop.set(true); accepter.join(2_000)

                val ok = latencies.sorted()
                fun p(q: Double) = if (ok.isEmpty()) 0L else ok[((ok.size - 1) * q).toInt()] / 1_000_000
                println(String.format(Locale.ROOT,
                    "storm    connected=%d/%d failed=%d accepted=%d in %d ms = %.0f connects/s | connect latency p50=%dms p90=%dms p99=%dms max=%dms",
                    ok.size, n, failures.get(), acceptedCount.get(), wallMs, ok.size * 1000.0 / wallMs.coerceAtLeast(1),
                    p(0.5), p(0.9), p(0.99), (ok.lastOrNull() ?: 0) / 1_000_000))
                println("storm    server: retriesSent=${server.retriesSent} " +
                    "validator(admitted=${server.validator.admitted} retried=${server.validator.retried} dropped=${server.validator.dropped} underPressure=${server.validator.underPressure})")
                conns.forEach { runCatching { it.close() } }; extraClients.forEach { runCatching { it.close() } }
            }
        }
    } finally { netem?.close() }
}
