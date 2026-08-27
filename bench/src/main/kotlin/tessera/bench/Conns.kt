package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraConnection
import tessera.transport.TesseraServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * W5 — many connections, one server. Everything else this project has measured used exactly one connection,
 * so per-connection cost, accept throughput and inter-connection fairness are all unmeasured assumptions.
 *
 * One client socket and one server socket carry every connection (both demux on connId), so what is measured
 * is the cost of a CONNECTION, not of a socket or a thread. Heap is read as a post-GC floor, the statistic the
 * soak settled on — a sawtooth peak says more about collection timing than about live data.
 *
 * usage: bench conns [--n 200] [--rate 20] [--seconds 15] [--size 1200] [--netem <preset>]
 *                     [--packetRing 8192] [--bodyRing 4096]   ConnConfig ring sizes: the idle footprint is
 *                     almost entirely these arrays, so this is the A/B for shrinking them
 */
fun connsMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val n = opt("n", "200").toInt()
    val rate = opt("rate", "20").toInt()
    val seconds = opt("seconds", "15").toInt()
    val size = opt("size", "1200").toInt()
    val netemName = opt("netem", "")
    val netem = if (netemName.isEmpty()) null else tessera.transport.NetemSim.preset(netemName)

    fun heapFloor(): Long {
        repeat(3) { System.gc(); Thread.sleep(120) }
        val rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory()
    }

    val keys = Handshake.generate()
    // W5 follow-up: the ring arrays are the bulk of the idle footprint, so the bench can size them.
    val cfg = ConnConfig(netem = netem, packetRing = opt("packetRing", "2048").toInt(), bodyRing = opt("bodyRing", "1024").toInt())
    println(String.format(Locale.ROOT, "conns    %s: %d connections, %d msg/s each x %d B for %d s",
        if (netem == null) "loopback" else netemName, n, rate, size, seconds))

    try {
        TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
            TesseraClient(cfg = cfg).use { client ->
                val base = heapFloor()
                val conns = ArrayList<TesseraConnection>(n)
                val accepted = ArrayList<TesseraConnection>(n)
                var failed = 0
                val t0 = System.nanoTime()
                for (i in 0 until n) {
                    try {
                        conns += client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "w5".toByteArray(), timeoutMs = 15_000)
                        accepted += server.accept(15_000) ?: error("accept timed out at connection $i")
                    } catch (e: Exception) { failed++; println("conns    connect $i failed: ${e.message}"); break }
                }
                val establishMs = (System.nanoTime() - t0) / 1_000_000
                accepted.forEach { it.receive(2_000) }          // drain each 0-RTT payload
                val idle = heapFloor()
                println(String.format(Locale.ROOT,
                    "conns    established %d/%d in %d ms = %.0f connects/s (failed=%d) | heap %.1f -> %.1f MB = %.1f KB per connection idle",
                    conns.size, n, establishMs, conns.size * 1000.0 / establishMs.coerceAtLeast(1), failed,
                    base / 1e6, idle / 1e6, (idle - base) / 1024.0 / conns.size.coerceAtLeast(1)))

                // Traffic on every connection at once: fairness and per-connection retained state.
                val running = AtomicBoolean(true)
                val delivered = Array(accepted.size) { AtomicLong() }
                val rxThreads = accepted.mapIndexed { i, sc ->
                    Thread { while (running.get()) { if (sc.receive(200) != null) delivered[i].incrementAndGet() } }
                        .apply { isDaemon = true; start() }
                }
                val sent = AtomicLong()
                val gapNs = if (rate > 0) 1_000_000_000L / rate else 0
                val txThreads = conns.map { c ->
                    Thread {
                        val msg = ByteArray(size); var next = System.nanoTime()
                        while (running.get()) {
                            try { c.send(msg); sent.incrementAndGet() } catch (e: Exception) { return@Thread }
                            next += gapNs
                            while (System.nanoTime() < next && running.get()) java.util.concurrent.locks.LockSupport.parkNanos(200_000)
                        }
                    }.apply { isDaemon = true; start() }
                }
                Thread.sleep(seconds * 1000L)
                val busy = heapFloor()
                running.set(false)
                txThreads.forEach { it.join(2_000) }; Thread.sleep(1_500); rxThreads.forEach { it.join(2_000) }

                val counts = delivered.map { it.get() }.sorted()
                val total = counts.sum()
                println(String.format(Locale.ROOT,
                    "conns    traffic: sent=%d delivered=%d (%.2f%%) | per-connection min=%d p50=%d max=%d (max/min=%.2fx) | heap %.1f MB = %.1f KB per connection under load",
                    sent.get(), total, 100.0 * total / sent.get().coerceAtLeast(1),
                    counts.first(), counts[counts.size / 2], counts.last(),
                    counts.last().toDouble() / counts.first().coerceAtLeast(1), busy / 1e6,
                    (busy - base) / 1024.0 / conns.size.coerceAtLeast(1)))
                println("conns    threads=${Thread.activeCount()}")
                conns.forEach { runCatching { it.close() } }; accepted.forEach { runCatching { it.close() } }
            }
        }
    } finally { netem?.close() }
}
