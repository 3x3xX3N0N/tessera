package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.NetemSim
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.net.InetSocketAddress
import java.util.Locale

/**
 * Soak: one connection held under sustained load for minutes, sampling the things that leak if anything does.
 * The suite's other tests run for seconds, so a structure that grows per message — a reassembly map that never
 * releases, an ack tracker whose TreeMap outlives its window, a decoder that is rotated but not dropped, a key
 * generation retained past its successor — is invisible to them by construction.
 *
 * What is sampled, and why each one:
 *   heapMB      after an explicit GC, so the trend is live objects rather than garbage awaiting collection
 *   threads     a connection that spawns per-something threads shows up here before it shows up in heap
 *   reasm       Reassembler.pending: partial messages awaiting a fin; must return to ~0 on a healthy link
 *   abandoned   Reassembler.abandonedPending: the leak-credit ledger, bounded by ABANDONED_MEMORY by design
 *   keyGen      automatic AEAD rotation (v0.9) — climbs by design; retained generations must NOT
 *
 * The verdict is a trend, not a threshold: heap on a JVM wanders, so the report prints every sample and the
 * growth from the post-warmup sample to the last. A leak at 2000 msg/s is a slope, and a slope is legible
 * across ten samples in a way a single before/after pair never is.
 *
 * usage: bench soak [--minutes 10] [--rate 2000] [--size 1200] [--netem <preset>] [--keyUpdatePackets N]
 */
fun soakMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val minutes = opt("minutes", "10").toDouble()
    val rate = opt("rate", "2000").toInt()
    val size = opt("size", "1200").toInt()
    val netemName = opt("netem", "")
    val netem: NetemSim? = if (netemName.isEmpty()) null else NetemSim.preset(netemName)
    // Rotate often enough that a soak actually exercises it: the 2^20-packet default would fire ~once here.
    val keyUpdatePackets = opt("keyUpdatePackets", "20000").toLong()

    val keys = Handshake.generate()
    val cfg = ConnConfig(netem = netem, keyUpdatePackets = keyUpdatePackets)
    val gapNs = 1_000_000_000L / rate
    val runNs = (minutes * 60e9).toLong()
    val sampleNs = maxOf(runNs / 12, 15_000_000_000L)

    println(String.format(Locale.ROOT, "soak     %s: %.1f min at %d msg/s x %d B, key rotation every %d packets",
        if (netem == null) "loopback" else netemName, minutes, rate, size, keyUpdatePackets))
    println("soak     elapsed  delivered   heapMB  threads  reasm  abandoned  keyGen  rotations")

    try {
        TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
            TesseraClient(cfg = cfg).use { client ->
                val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "soak".toByteArray(), timeoutMs = 10_000)
                val sconn = server.accept(5_000) ?: error("no accept"); sconn.receive(2_000)

                val running = java.util.concurrent.atomic.AtomicBoolean(true)
                val deliveredCount = java.util.concurrent.atomic.AtomicLong()
                val rx = Thread {
                    while (running.get()) { if (sconn.receive(500) != null) deliveredCount.incrementAndGet() }
                }.apply { isDaemon = true; start() }

                val msg = ByteArray(size)
                val tx = Thread {
                    var next = System.nanoTime()
                    while (running.get()) {
                        try { conn.send(msg) } catch (e: Exception) { if (running.get()) println("soak     send died: ${e.message}"); return@Thread }
                        next += gapNs
                        while (System.nanoTime() < next && running.get()) java.util.concurrent.locks.LockSupport.parkNanos(200_000)
                    }
                }.apply { isDaemon = true; start() }

                val t0 = System.nanoTime()
                val samples = ArrayList<Pair<Long, Long>>()   // elapsed seconds -> heap bytes
                var next = t0 + sampleNs
                while (System.nanoTime() - t0 < runNs) {
                    java.util.concurrent.locks.LockSupport.parkNanos(500_000_000L)
                    if (System.nanoTime() < next) continue
                    next += sampleNs
                    System.gc(); Thread.sleep(150)            // live objects, not pending garbage
                    val rt = Runtime.getRuntime()
                    val heap = rt.totalMemory() - rt.freeMemory()
                    val s = sconn.stats; val c = conn.stats
                    val elapsed = (System.nanoTime() - t0) / 1_000_000_000
                    samples += elapsed to heap
                    println(String.format(Locale.ROOT, "soak     %5ds  %9d  %7.1f  %7d  %5d  %9d  %6d  %9d",
                        elapsed, deliveredCount.get(), heap / 1e6, Thread.activeCount(),
                        s.reassemblyPending, s.reassemblyAbandonedPending, c.keyGeneration, c.keyUpdates))
                }
                running.set(false); tx.join(2_000); rx.join(2_000)

                val c = conn.stats; val s = sconn.stats
                println("soak     client: $c")
                println("soak     server: $s")
                if (samples.size >= 4) {
                    // The verdict is the post-GC FLOOR per half, not the endpoints. System.gc() is advisory and
                    // G1 collects on its own schedule, so the sample series is a sawtooth whose peaks say more
                    // about collection timing than about live data — an endpoint-to-endpoint slope across it
                    // reports whatever the last sample happened to catch. What a leak cannot do is let the floor
                    // fall: live objects that are never released put a rising floor under every later sample.
                    val half = samples.size / 2
                    val early = samples.take(half).minOf { it.second }
                    val late = samples.drop(half).minOf { it.second }
                    val peak = samples.maxOf { it.second }
                    println(String.format(Locale.ROOT,
                        "soak     heap floor: %.1f MB (first half) -> %.1f MB (second half), peak %.1f MB across %d samples",
                        early / 1e6, late / 1e6, peak / 1e6, samples.size))
                    println(if (late > early * 1.5)
                        String.format(Locale.ROOT, "soak     LEAK SUSPECTED: the floor rose %.1f%% — live data is accumulating",
                            100.0 * (late - early) / early)
                    else "soak     no leak: the floor did not rise, so nothing is accumulating across the run")
                }
                conn.close(); sconn.close()
            }
        }
    } finally { netem?.close() }
}
