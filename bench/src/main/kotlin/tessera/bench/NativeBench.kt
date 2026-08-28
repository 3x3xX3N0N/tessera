package tessera.bench

import tessera.transport.Datapath
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Datapath bench: `rawudp`-style streaming of fixed-size datagrams through the transport's socket layer — the same
 * rx thread, demux and tx path the connections use, no protocol on top — comparing ChannelUdpIo (DatagramChannel,
 * one syscall per datagram each way) with NativeUdpIo (tessera_native batch I/O). Each datagram carries its send
 * timestamp (same host, shared clock) for one-way latency. The sender keeps at most `--inflight` datagrams
 * unreceived so the 4 MB socket buffer never overflows and the numbers describe the datapath rather than queue
 * drops (`--inflight 0` = unthrottled, exactly like rawudp). CPU time is per thread via ThreadMXBean: the sending
 * thread plus the receiving socket's rx thread, divided by datagrams (only meaningful at gap 0).
 *
 * usage: bench native [--n 200000] [--size 1200] [--gapUs 0] [--inflight 1024] [--txBatch 64] [--impl both|channel|native]
 *   --txBatch N   native sender: coalesce up to N datagrams per flush (1 = send immediately, as the app path does)
 * Standalone entry point (until Main.kt wires the `native` mode), with every jar of bench/build/install/bench/lib on the
 * class path: `java --enable-native-access=ALL-UNNAMED -cp <lib jars> tessera.bench.NativeBenchKt --n 200000`
 */
fun main(args: Array<String>) = nativeBench(args)

fun nativeBench(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val n = opt("n", "200000").toInt()
    val size = opt("size", "1200").toInt()
    val gapUs = opt("gapUs", "0").toLong()
    val inflight = opt("inflight", "1024").toInt()
    val txBatch = opt("txBatch", "64").toInt()
    val impls = when (val i = opt("impl", "both")) {
        "channel" -> listOf(false); "native" -> listOf(true); "both" -> listOf(false, true)
        else -> error("--impl must be both|channel|native, got $i")
    }
    require(size in 13..2048) { "--size must be 13..2048" }
    require(n in 1..(1 shl 24))
    if (impls.contains(true)) require(Datapath.nativeAvailable) { "tessera_native is not available on this platform" }

    val results = impls.map { native -> streamDatagrams(native, n, size, gapUs, inflight, if (native) txBatch else 1) }
    results.forEach { println(it.line()) }
    if (results.size == 2) {
        val (ch, nt) = results
        println(String.format(Locale.ROOT,
            // The p99 term was printed as "%.2fx lower" of channel/native, which renders a WORSE native tail as
            // "0.09x lower" - a number no reader parses as "eleven times higher". Batching trades latency for
            // throughput and that trade is the whole point of the comparison, so it is now named in the direction
            // it actually went.
            "native   summary: native vs channel  throughput %.2fx  p99 %.2fx %s  cpu/pkt %.2fx lower  (%s, %d B, n=%d, gap=%dus, inflight=%d, txBatch=%d)",
            nt.pps / ch.pps,
            if (nt.p99Us <= ch.p99Us) ch.p99Us / nt.p99Us else nt.p99Us / ch.p99Us,
            if (nt.p99Us <= ch.p99Us) "lower" else "HIGHER (batching trades tail latency for throughput)",
            ch.cpuPerPktUs / nt.cpuPerPktUs,
            System.getProperty("os.name"), size, n, gapUs, inflight, txBatch))
    }
}

class DatapathResult(
    val impl: String, val n: Int, val delivered: Int, val pps: Double,
    val p50Us: Double, val p99Us: Double, val p999Us: Double, val txCpuUs: Double, val rxCpuUs: Double, val stats: String,
) {
    val cpuPerPktUs: Double get() = txCpuUs + rxCpuUs
    fun line(): String = String.format(Locale.ROOT,
        "native   %-7s n=%d delivered=%d loss=%.2f%%  %.0f pkt/s  p50=%.0fus p99=%.0fus p999=%.0fus  cpu/pkt=%.2fus (tx %.2f + rx %.2f)  [%s]",
        impl, n, delivered, 100.0 * (n - delivered) / n, pps, p50Us, p99Us, p999Us, cpuPerPktUs, txCpuUs, rxCpuUs, stats)
}

/**
 * Streams `warmup + n` datagrams of `size` bytes rx <- tx on loopback through two [Datapath] sockets of the same
 * implementation. Layout: `0x80 | seq(4) | sendNanos(8) | filler`; warm-up datagrams carry seq = -1.
 */
fun streamDatagrams(native: Boolean, n: Int, size: Int, gapUs: Long, inflight: Int, txBatch: Int): DatapathResult {
    val mx = ManagementFactory.getThreadMXBean()
    val cpuOk = mx.isThreadCpuTimeSupported && mx.isThreadCpuTimeEnabled
    val rx = Datapath.open(InetSocketAddress("127.0.0.1", 0), native, "bench-rx")
    val tx = Datapath.open(InetSocketAddress("127.0.0.1", 0), native, "bench-tx")
    try {
        val to = rx.localAddress
        val warmup = minOf(n / 4, 50_000)
        val latencies = LongArray(n) { -1L }
        val received = AtomicInteger()
        val rxThreadId = AtomicLong(-1)
        val lastRxNs = AtomicLong()
        rx.onDatagram { buf, _ ->
            val seq = buf.getInt(1)
            val now = System.nanoTime()
            if (seq in 0 until n && latencies[seq] < 0) latencies[seq] = now - buf.getLong(5)
            if (rxThreadId.get() < 0) rxThreadId.set(Thread.currentThread().threadId())
            lastRxNs.lazySet(now)
            received.lazySet(received.get() + 1) // single writer (the rx thread)
        }
        val buf = ByteBuffer.allocateDirect(size)
        buf.put(0, 0x80.toByte())
        var sent = 0
        val batching = native && txBatch > 1
        if (batching) tx.deferSends(true)

        fun sendOne(seq: Int) {
            if (inflight > 0) {
                var spins = 0
                while (sent - received.get() >= inflight) {
                    if (batching) tx.flush()
                    if (spins++ < 64) Thread.onSpinWait() else LockSupport.parkNanos(50_000)
                }
            }
            buf.clear()
            buf.putInt(1, seq); buf.putLong(5, System.nanoTime())
            buf.limit(size).position(0)
            tx.send(buf, to)
            sent++
            if (batching && sent % txBatch == 0) tx.flush()
            if (gapUs > 0) busyWait(gapUs)
        }

        repeat(warmup) { sendOne(-1) }
        if (batching) tx.flush()
        awaitReceived(received, sent, 2_000)

        val txCpu0 = if (cpuOk) mx.currentThreadCpuTime else 0L
        val rxCpu0 = if (cpuOk && rxThreadId.get() >= 0) mx.getThreadCpuTime(rxThreadId.get()) else 0L
        val t0 = System.nanoTime()
        for (i in 0 until n) sendOne(i)
        if (batching) tx.flush()
        awaitReceived(received, sent, 2_000)
        val tEnd = maxOf(lastRxNs.get(), t0 + 1)
        val txCpu = if (cpuOk) mx.currentThreadCpuTime - txCpu0 else 0L
        val rxCpu = if (cpuOk && rxThreadId.get() >= 0) mx.getThreadCpuTime(rxThreadId.get()) - rxCpu0 else 0L
        if (batching) tx.deferSends(false)

        val delivered = latencies.filter { it >= 0 }.sorted()
        fun pct(p: Double) = if (delivered.isEmpty()) 0.0 else delivered[((delivered.size - 1) * p).toInt()] / 1000.0
        val pps = delivered.size / ((tEnd - t0) / 1e9)
        val stats = if (native) "tx: ${tx.stats.substringAfter("| ")} | rx: ${rx.stats.substringBefore(" |")}" else "channel"
        return DatapathResult(
            tx.implementation, n, delivered.size, pps, pct(0.5), pct(0.99), pct(0.999),
            txCpu / 1000.0 / n, rxCpu / 1000.0 / maxOf(1, delivered.size), stats,
        )
    } finally {
        tx.close(); rx.close()
    }
}

private fun awaitReceived(received: AtomicInteger, target: Int, timeoutMs: Long) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (received.get() < target && System.nanoTime() < deadline) LockSupport.parkNanos(200_000)
}
