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
    // Config passthrough, so a live A/B can exercise what the in-process benches tune. Without these the probe
    // can only measure paced send at a fixed rate — the one behaviour none of the 2026-08-27 work changed.
    val cfg = ConnConfig(
        // may be a list for an interleaved A/B (--repairClock 0,12); the handshake connection uses the first arm
        repairClockEquationsPerRtt = (a.opt("repairClock") ?: "0").split(",")[0].trim().toInt(),
        paceDisengaged = a.opt("paceDisengaged")?.toDouble() ?: 0.0,
        packetRing = a.int("packetRing", 8192),
        bodyRing = a.int("bodyRing", 4096),
    )
    val client = a.opt("bind")?.let { TesseraClient(InetSocketAddress(it, 0), cfg) } ?: TesseraClient(cfg = cfg)
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

        // 3a. Close-loop mode: the live test of the send-then-close guarantee. Each repetition sends `count`
        //     messages back-to-back and closes AT ONCE, with no wait for the echoes — exactly the shape that
        //     loses a tail in NetemTest. The client cannot see the loss itself (it has gone), so the ECHO side
        //     is the witness — but read the RIGHT number there. Its "echoed N msgs" line counts what it managed
        //     to echo BACK before the closing client stopped listening, which is legitimately far below `count`;
        //     the delivery figure is `msgs=` on the stats line under it, and that is what must equal `count` + 1
        //     (the extra is the 0-RTT token). Measured locally: echoed 96/185/200 across three reps that every
        //     one of them received in full.
        //
        //     After close() the connection lingers on its timer thread until the peer's acks and FEC feedback
        //     say nothing is outstanding, so the probe MUST wait for isClosed before moving on; exiting early
        //     would kill the very linger the fix relies on and fake a failure.
        if ((a.opt("mode") ?: "rtt") == "close") {
            val reps = a.int("reps", 20)
            println(String.format(Locale.ROOT, "close-loop: %d reps x %d msgs of %d B, closing at once. Witness is the ECHO side: match the ids below to its stats lines and read msgs= (NOT \"echoed N\", which counts echoes sent back before the client left). Expect msgs=%d.", reps, count, size, count + 1))
            var c = conn
            for (rep in 0 until reps) {
                if (rep > 0) c = client.connect(addr, x, kem, token, timeoutMs = 10_000)
                val id = c.connId.raw.toString(16)
                val payload = ByteArray(size)
                repeat(count) { i -> ByteBuffer.wrap(payload).putInt(i).putLong(System.nanoTime()); c.send(payload) }
                val t = System.nanoTime()
                c.close()
                while (!c.isClosed && System.nanoTime() - t < 30_000_000_000L) Thread.sleep(20)
                println(String.format(Locale.ROOT, "close-loop rep %2d: id=%s sent=%d lingered=%.1fs closed=%b",
                    rep + 1, id, count, (System.nanoTime() - t) / 1e9, c.isClosed))
            }
            return
        }

        // 3. The steady-state phase, repeated and INTERLEAVED.
        //
        //    One run of this probe cannot resolve anything on a link whose own variance exceeds the effect being
        //    measured. On a real 5G radio the same binary at the same setting measured 0 %, 0 %, 15 %, 43 % and
        //    66.5 % message loss minutes apart, and a "7x improvement" published from single runs had to be
        //    withdrawn the same day. So --runs repeats, and when an A/B dimension is given as a list
        //    (--repairClock 0,12) the arms ALTERNATE inside this one process, on the same link, run by run:
        //    a paired comparison rather than two blocks of measurements taken at different times.
        //
        //    The summary reports median, range and spread per arm, and states what that spread can resolve.
        //    A difference smaller than the spread is not a result.
        val runs = a.int("runs", 1)
        val arms = (a.opt("repairClock") ?: "0").split(",").map { it.trim().toInt() }
        if (runs == 1 && arms.size == 1) {
            val r = tesseraProbe(conn, rate, size, count, warmup)
            println("  " + conn.stats)
            conn.close()
            report("tessera", r, count, out)
            return
        }
        conn.close()
        val byArm = LinkedHashMap<Int, MutableList<Result>>()
        arms.forEach { byArm[it] = mutableListOf() }
        for (run in 1..runs) {
            for (arm in arms) {
                val armCfg = ConnConfig(
                    repairClockEquationsPerRtt = arm,
                    paceDisengaged = a.opt("paceDisengaged")?.toDouble() ?: 0.0,
                    packetRing = a.int("packetRing", 8192),
                    bodyRing = a.int("bodyRing", 4096),
                )
                (if (a.opt("bind") != null) TesseraClient(InetSocketAddress(a.opt("bind"), 0), armCfg) else TesseraClient(cfg = armCfg)).use { c2 ->
                    val cx = c2.connect(addr, x, kem, token, timeoutMs = 10_000)
                    cx.receive(10_000)
                    val r = tesseraProbe(cx, rate, size, count, warmup)
                    byArm.getValue(arm).add(r)
                    println(String.format(Locale.ROOT, "run %d/%d  repairClock=%-3d  delivered=%d/%d (%.2f%% lost)  p50=%.1fms p99=%.1fms",
                        run, runs, arm, r.rtts.count { it >= 0 }, count,
                        100.0 * (count - r.rtts.count { it >= 0 }) / count,
                        percentile(r.rtts.filter { it >= 0 }.sorted().toLongArray(), 0.5) / 1000.0,
                        percentile(r.rtts.filter { it >= 0 }.sorted().toLongArray(), 0.99) / 1000.0))
                    cx.close()
                }
            }
        }
        println()
        for ((arm, rs) in byArm) {
            val meds = rs.map { percentile(it.rtts.filter { v -> v >= 0 }.sorted().toLongArray(), 0.5) / 1000.0 }.sorted()
            val loss = rs.map { 100.0 * (count - it.rtts.count { v -> v >= 0 }) / count }.sorted()
            val spread = if (meds.first() > 0) meds.last() / meds.first() else Double.NaN
            println(String.format(Locale.ROOT,
                "SUMMARY repairClock=%-3d  p50 median %.1fms, range %.1f-%.1f, spread %.2fx  |  loss median %.2f%%, worst %.2f%%  (n=%d)",
                arm, meds[meds.size / 2], meds.first(), meds.last(), spread, loss[loss.size / 2], loss.last(), rs.size))
        }
        if (byArm.size > 1) {
            val worst = byArm.values.map { rs ->
                val m = rs.map { percentile(it.rtts.filter { v -> v >= 0 }.sorted().toLongArray(), 0.5) / 1000.0 }.sorted()
                if (m.first() > 0) m.last() / m.first() else 1.0
            }.max()
            println(String.format(Locale.ROOT,
                "RESOLUTION the arms must differ by more than the widest arm's %.0f%% spread before the difference means anything.",
                (worst - 1.0) * 100))
        }
        return
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
