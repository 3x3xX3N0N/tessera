package tessera.tools

import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import tessera.transport.AddressFamily
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraConnection
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The measuring half. Every message carries [seq | sendNanos | padding]; the echo returns it verbatim and
 * the round trip is computed from this machine's clock alone — no NTP, no shared clock, no assumption
 * about one-way symmetry. What is reported is therefore RTT, not the one-way delay the netem benches show.
 */
fun probeMain(a: Args) {
    val addr = parseAddr(a.req("connect"))
    val token = a.req("token").toByteArray()
    val transport = a.opt("transport") ?: "tessera"
    val rate = a.int("rate", 50)
    val size = a.int("size", 1200)
    val count = a.int("count", 2000)
    val warmup = a.int("warmup", 200)
    val out = a.opt("out")
    require(size >= 16) { "--size must be at least 16" }

    println(String.format(Locale.ROOT, "probing %s via %s: %d msgs of %d B at %d/s (+%d warm-up)",
        addr, transport, count, size, rate, warmup))

    if (transport == "udp") {
        // --bind honoured here too: pinning the source address is how a multi-homed host picks the interface
        // under test (e.g. a hotspot Wi-Fi next to wired Ethernet), and an A/B against the tessera arm is
        // meaningless if only one of them rode the intended path.
        val r = udpProbe(addr, rate, size, count, warmup, a.opt("bind")?.let { InetSocketAddress(it, 0) })
        report("udp", r, count, out); return
    }

    val keyBytes = Base64.getDecoder().decode(a.req("peer-key"))
    require(keyBytes.size == 32 + 1184) { "peer key is ${keyBytes.size} B, expected ${32 + 1184}" }
    val x = X25519PublicKeyParameters(keyBytes.copyOfRange(0, 32))
    val kem = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, keyBytes.copyOfRange(32, keyBytes.size))

    // No address-family workaround here any more: TesseraClient's default bind (AddressFamily.defaultBind) is the
    // dual-stack wildcard, so it reaches an IPv6 or an IPv4 peer, and refuses an unreachable one with a named error
    // instead of a timeout. --bind is still honoured, to pick a specific interface.
    val client = a.opt("bind")?.let { TesseraClient(InetSocketAddress(it, 0), ConnConfig()) } ?: TesseraClient(cfg = ConnConfig())
    client.use { client ->
        // The first connect in a fresh JVM pays class loading, the signed-jar verification of bcprov and one-time
        // library init — on loopback 328 ms (pure JDK) to 580 ms (native) of pure CPU, which would swamp a WAN
        // measurement. --connect-warmup discards that. It is *not* mainly the first ML-KEM operation, which the
        // guess that stood here used to say: measurement puts the KEM's own first use at 25-35 ms and BouncyCastle's
        // first class load at ~180 ms (BENCH-netem, "Cold start, characterised").
        repeat(a.int("connect-warmup", 0)) {
            client.connect(addr, x, kem, token, timeoutMs = 10_000).also { c -> c.receive(10_000); c.close() }
        }
        // 1. Fresh post-quantum connect. Timed from the call to the moment the echoed 0-RTT payload lands:
        //    one round trip, with application data already in the first packet.
        var t0 = System.nanoTime()
        val fresh = client.connect(addr, x, kem, token, timeoutMs = 10_000)
        val freshEcho = fresh.receive(10_000) ?: error("no echo of the 0-RTT payload — wrong token, or the path is blocked")
        val freshUs = (System.nanoTime() - t0) / 1000
        require(freshEcho.copyOf(token.size).contentEquals(token)) { "echo did not match the token" }
        println(String.format(Locale.ROOT, "connect  fresh-PQ   0-RTT payload echoed in %.1f ms", freshUs / 1000.0))

        val ticket = fresh.ticket
        val secret = fresh.resumptionSecret
        var conn = fresh

        // 2. Resumed connect over the same path: no KEM, ~1.2 KB of 0-RTT budget instead of 184 B.
        if (!a.flag("no-resume") && ticket != null) {
            fresh.close()
            t0 = System.nanoTime()
            val resumed = client.resume(addr, ticket, secret, token, timeoutMs = 10_000)
            resumed.receive(10_000) ?: error("no echo on the resumed connection")
            val resumedUs = (System.nanoTime() - t0) / 1000
            println(String.format(Locale.ROOT, "connect  resumed    0-RTT payload echoed in %.1f ms  (%.0f%% of fresh)",
                resumedUs / 1000.0, 100.0 * resumedUs / freshUs))
            conn = resumed
        }

        // 3. The steady-state phase on the surviving connection.
        val r = tesseraProbe(conn, rate, size, count, warmup)
        println("  ${conn.stats}")
        conn.close()
        report("tessera", r, count, out)
    }
}

private class Result(val rtts: LongArray, val delivered: Int)

private fun tesseraProbe(conn: TesseraConnection, rate: Int, size: Int, count: Int, warmup: Int): Result {
    val rtts = LongArray(count) { -1L }
    var delivered = 0
    val done = java.util.concurrent.CountDownLatch(1)
    val rx = thread(isDaemon = true, name = "probe-rx") {
        while (true) {
            val m = conn.receive(2_000) ?: if (done.count == 0L) break else continue
            if (m.size < 12) continue
            val b = ByteBuffer.wrap(m)
            val seq = b.int
            val sent = b.long
            if (seq in 0 until count && rtts[seq] < 0) { rtts[seq] = (System.nanoTime() - sent) / 1000; delivered++ }
        }
    }
    pace(rate, warmup + count) { i ->
        val seq = i - warmup
        val p = ByteArray(size)
        val b = ByteBuffer.wrap(p)
        b.putInt(if (seq < 0) -1 else seq).putLong(System.nanoTime())
        conn.send(p)
    }
    // Give the tail of the stream a chance to come back before declaring it lost.
    val deadline = System.nanoTime() + 10_000_000_000L
    while (delivered < count && System.nanoTime() < deadline) Thread.sleep(50)
    done.countDown(); rx.join(3_000)
    return Result(rtts, delivered)
}

private fun udpProbe(addr: InetSocketAddress, rate: Int, size: Int, count: Int, warmup: Int, bind: InetSocketAddress? = null): Result {
    val rtts = LongArray(count) { -1L }
    var delivered = 0
    val sock = DatagramSocket(bind ?: AddressFamily.defaultBind())   // plain JDK socket, not the transport: same dual-stack wildcard
    sock.soTimeout = 2_000
    val rx = thread(isDaemon = true, name = "udp-rx") {
        val buf = ByteArray(65535)
        val p = DatagramPacket(buf, buf.size)
        while (true) {
            try { sock.receive(p) } catch (e: Exception) { if (delivered >= count) break else continue }
            if (p.length < 12) continue
            val b = ByteBuffer.wrap(p.data, p.offset, p.length)
            val seq = b.int
            val sent = b.long
            if (seq in 0 until count && rtts[seq] < 0) { rtts[seq] = (System.nanoTime() - sent) / 1000; delivered++ }
        }
    }
    pace(rate, warmup + count) { i ->
        val seq = i - warmup
        val p = ByteArray(size)
        ByteBuffer.wrap(p).putInt(if (seq < 0) -1 else seq).putLong(System.nanoTime())
        sock.send(DatagramPacket(p, size, addr))
    }
    val deadline = System.nanoTime() + 10_000_000_000L
    while (delivered < count && System.nanoTime() < deadline) Thread.sleep(50)
    rx.join(3_000); sock.close()
    return Result(rtts, delivered)
}

/** Paced send loop: schedule-relative, so a slow send does not drift the whole run. */
private inline fun pace(rate: Int, total: Int, send: (Int) -> Unit) {
    val gapNs = 1_000_000_000L / rate
    val start = System.nanoTime()
    for (i in 0 until total) {
        send(i)
        val due = start + (i + 1) * gapNs
        var now = System.nanoTime()
        while (now < due) {
            val left = due - now
            if (left > 2_000_000) Thread.sleep(left / 1_000_000 - 1) else Thread.onSpinWait()
            now = System.nanoTime()
        }
    }
}

private fun report(label: String, r: Result, count: Int, out: String?) {
    val ok = r.rtts.filter { it >= 0 }.sorted().toLongArray()
    fun p(q: Double) = percentile(ok, q) / 1000.0
    println(String.format(Locale.ROOT,
        "%-8s delivered=%d/%d (%.2f%% lost)  rtt p50=%.1fms p90=%.1fms p99=%.1fms p999=%.1fms min=%.1fms",
        label, ok.size, count, 100.0 * (count - ok.size) / count,
        p(0.5), p(0.9), p(0.99), p(0.999), (ok.firstOrNull() ?: 0) / 1000.0))
    if (out != null) {
        File(out).apply { parentFile?.mkdirs() }.writeText(buildString {
            appendLine("seq,rtt_us")
            r.rtts.forEachIndexed { i, v -> appendLine("$i,${if (v < 0) "" else v}") }
        })
        println("  wrote $out")
    }
}
