package tessera.transport

import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Loss-recovery latency under bursty (Gilbert-Elliott) loss at 2000 msg/s — the open item of BENCH-netem run 3
 * (starlink / lte / 5g-mmwave p99 sat 1–2 RTT above the floor) — and the cumulative credit advertisement. Same
 * [NetemSim] presets on both endpoints, fixed seeds (every message quotes the seed), the bench's harness in miniature.
 */
class RecoveryTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 5).toByte() }

    private class Net(val sim: NetemSim, val s: TesseraServer, val c: TesseraClient) : AutoCloseable {
        override fun close() { c.close(); s.close(); sim.close() }
        override fun toString() = "$sim | datapath=${if (Datapath.nativeSelected()) "native" else "channel"} client-io=${c.ioStats} server-io=${s.ioStats}"
    }

    private fun net(preset: NetemSim.Preset, seed: Long): Net {
        val sim = preset.sim(seed)
        val conf = ConnConfig(netem = sim)
        return Net(sim, TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, conf), TesseraClient(cfg = conf))
    }

    private fun busySpin(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
    private fun awaitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) { if (cond()) return true; LockSupport.parkNanos(200_000) }
        return cond()
    }

    private class Stream(val delivered: Int, val late: Int, lat: LongArray, val cs: ConnStats, val ss: ConnStats, val missingWarmup: List<Int>) {
        private val sorted = lat.filter { it >= 0 }.sorted()
        val missing: List<Int> = lat.indices.filter { lat[it] < 0 }
        fun pct(p: Double) = if (sorted.isEmpty()) -1L else sorted[((sorted.size - 1) * p).toInt()]
        val p50 get() = pct(.5); val p90 get() = pct(.9); val p99 get() = pct(.99); val p999 get() = pct(.999); val max get() = sorted.lastOrNull() ?: -1L
    }

    /**
     * The bench's `runTessera` in miniature: `warmup` unmeasured messages then `n` of `size` bytes every `gapUs`; the
     * server records one-way latency (us) by the index in the first two payload bytes and keeps listening until
     * max(10 s, 50 RTT) after the last send actually went out; `late` = arrived more than 2 s after the last send.
     */
    private fun stream(conn: TesseraConnection, sc: TesseraConnection, n: Int, gapUs: Long, size: Int, warmup: Int, rttUs: Long): Stream {
        val lat = LongArray(n) { -1L }; val sent = LongArray(n)
        val gotWarmup = BooleanArray(warmup)
        var got = 0; var late = 0
        val sendsDone = AtomicLong(Long.MAX_VALUE)
        val grace = max(10_000_000_000L, 50 * rttUs * 1000)
        val rx = Thread {
            while (got < n) {
                val now0 = System.nanoTime(); val done = sendsDone.get()
                if (done != Long.MAX_VALUE && now0 > done + grace) break
                val m = sc.receive(50) ?: continue
                val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                if (i < n && lat[i] < 0) { val t = System.nanoTime(); lat[i] = (t - sent[i]) / 1000; got++; val d = sendsDone.get(); if (d != Long.MAX_VALUE && t > d + 2_000_000_000L) late++ }
                else if (i >= n && i - n < warmup) gotWarmup[i - n] = true
            }
        }.apply { start() }
        repeat(warmup + n) { k ->
            val i = k - warmup
            val idx = if (i < 0) n + k else i   // warm-up messages carry indices n.., so their loss is visible too
            val p = ByteArray(size); p[0] = (idx shr 8).toByte(); p[1] = idx.toByte()
            if (i >= 0) sent[i] = System.nanoTime()
            conn.send(p)
            busySpin(gapUs)
        }
        sendsDone.set(System.nanoTime())
        rx.join()
        return Stream(got, late, lat, conn.stats, sc.stats, gotWarmup.indices.filter { !gotWarmup[it] })
    }

    /**
     * 5000 messages of 1200 B at 2000 msg/s on the three bursty-loss presets: everything delivered, nothing late, and
     * the p99 within one RTT (starlink, lte) / half an RTT (5g-mmwave) of the link's own one-way p99 — the delay the
     * link imposed on its packets (queueing included, i.e. what a raw datagram sees), from the same simulator instance.
     * Run 3 of the netem matrix had p99 = floor + 1–2 RTT: bursts of 3–5 losses beat the 32-packet window's one
     * proactive repair per ~8 sources, and lost additive grants stalled the sender.
     */
    @Test fun burstyProfilesAtTwoThousandMessagesPerSecondStayWithinOneRttOfTheLinkFloor() {
        val failures = ArrayList<String>()
        val cases = listOf(Triple(NetemSim.Preset.STARLINK, 41L, 1.0), Triple(NetemSim.Preset.LTE, 42L, 1.0), Triple(NetemSim.Preset.FIVEG_MMWAVE, 43L, 0.5))
        for ((preset, seed, rttFraction) in cases) net(preset, seed).use { n ->
            val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(n.s.accept(5_000)); sc.receive(2_000)
            val rtt = 2 * preset.delayUs
            // credit / stall timeline (250 ms samples) for the diagnosis of slow-start and grant dynamics
            val timeline = StringBuilder(); val sampling = AtomicBoolean(true); val tStart = System.nanoTime()
            val sampler = Thread {
                while (sampling.get()) {
                    val cs0 = conn.stats; val ss0 = sc.stats
                    timeline.append(String.format(Locale.ROOT, "[%dms stalls=%d/%dms target=%d room=%d] ", (System.nanoTime() - tStart) / 1_000_000, cs0.creditStalls, cs0.creditStallUs / 1000, ss0.creditTargetBytes, cs0.creditLimit - cs0.creditSent))
                    Thread.sleep(250)
                }
            }.apply { isDaemon = true; start() }
            val r = stream(conn, sc, n = 5_000, gapUs = 500, size = 1200, warmup = 500, rttUs = rtt)
            sampling.set(false)
            println("timeline $timeline")
            val linkP50 = n.sim.delayPercentileUs(0.5); val linkP99 = n.sim.delayPercentileUs(0.99)
            val bound = linkP99 + (rttFraction * rtt).toLong()
            val cs = r.cs; val ss = r.ss
            println(String.format(Locale.ROOT, "recover  %-10s seed=%d n=5000 delivered=%d late=%d p50=%.1fms p90=%.1fms p99=%.1fms p999=%.1fms max=%.1fms | link one-way p50=%.1fms p99=%.1fms; bound p99 <= link p99 + %.1f RTT = %.1fms | overhead=%.3f | missing=%s warm-up missing=%s | client: %s | server: %s | %s",
                preset.profile, seed, r.delivered, r.late, r.p50 / 1e3, r.p90 / 1e3, r.p99 / 1e3, r.p999 / 1e3, r.max / 1e3, linkP50 / 1e3, linkP99 / 1e3, rttFraction, bound / 1e3,
                cs.bytesSent.toDouble() / ss.payloadBytesOut.coerceAtLeast(1), r.missing.take(20), r.missingWarmup.take(20), cs, ss, n))
            val tag = "${preset.profile} (seed=$seed)"
            if (r.delivered != 5_000) failures += "$tag: delivered ${r.delivered}/5000 (late=${r.late}) missing=${r.missing.take(30)} | client=$cs | server=$ss | $n"
            if (r.late != 0) failures += "$tag: ${r.late} messages arrived more than 2 s after the last send | client=$cs | $n"
            if (r.p99 > bound) failures += String.format(Locale.ROOT, "%s: p99=%.1fms > link one-way p99 %.1fms + %.1f RTT (%.0fms) = %.1fms (p50=%.1fms, link p50=%.1fms) | client=%s | %s",
                tag, r.p99 / 1e3, linkP99 / 1e3, rttFraction, rtt / 1e3, bound / 1e3, r.p50 / 1e3, linkP50 / 1e3, cs, n.sim)
            if (cs.rxErrors + ss.rxErrors > 0) failures += "$tag: rx parse errors client=${cs.firstRxError} server=${ss.firstRxError} | $n"
            conn.close(); sc.close()
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * Cumulative credit: every Grant frame the server would send (standalone packets and the limit piggybacked on its
     * ACKs) is dropped for 1 s while the client streams at 2000 msg/s on the starlink preset; the sender must run out
     * of credit and stall, resume within one re-send interval (the credit-probe / grant re-send backoff is capped at
     * ConnConfig.probeBackoffMaxUs = 1 s) once grants flow again, and — because any later grant carries the absolute
     * limit and every ACK re-advertises it — never stall again afterwards even though the link keeps losing 1.6 % of
     * the standalone grants. With additive grants each lost grant cost a stall of ~2 srtt.
     */
    @Test fun grantBlackoutResumesWithinOneResendIntervalAndNeverStallsAgain() {
        net(NetemSim.Preset.STARLINK, 44L).use { n ->
            val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(n.s.accept(5_000)); sc.receive(2_000)
            val running = AtomicBoolean(true)
            val sentCount = AtomicInteger(); val gotCount = AtomicInteger()
            val seen = java.util.BitSet()
            val sender = Thread {
                while (running.get()) {
                    val i = sentCount.get()
                    conn.send(ByteArray(1200) { 7 }.also { it[0] = (i shr 8).toByte(); it[1] = i.toByte() }); sentCount.incrementAndGet(); busySpin(500)
                }
            }.apply { start() }
            val rx = Thread {
                while (running.get() || gotCount.get() < sentCount.get()) {
                    val m = sc.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    synchronized(seen) { if (!seen.get(i)) { seen.set(i); gotCount.incrementAndGet() } }
                }
            }.apply { start() }
            Thread.sleep(1_500)
            val stallsBefore = conn.stats.creditStalls
            // ---- blackout
            val atStart = "server target=${sc.stats.creditTargetBytes} client credit(limit=${conn.stats.creditLimit} sent=${conn.stats.creditSent})"
            sc.suppressGrants = true
            val t0 = System.nanoTime()
            Thread.sleep(1_000)
            println("grants   at blackout start: $atStart")
            val sentAtEnd = sentCount.get()
            val stalledDuring = conn.stats.creditStalls - stallsBefore
            sc.suppressGrants = false
            val restored = System.nanoTime()
            // ---- resume: sends progress again within one re-send interval
            val resumed = awaitUntil(3_000) { sentCount.get() >= sentAtEnd + 20 }
            val resumeMs = (System.nanoTime() - restored) / 1_000_000
            Thread.sleep(500)   // the grant machinery settles (probe / re-send backoffs reset on the first grant)
            val stallsSettled = conn.stats.creditStalls
            Thread.sleep(3_000)
            val stallsEnd = conn.stats.creditStalls
            running.set(false); sender.join(5_000)
            val allArrived = awaitUntil(10_000) { gotCount.get() >= sentCount.get() }
            rx.join(2_000)
            val cs = conn.stats; val ss = sc.stats
            val missing = synchronized(seen) { (0 until sentCount.get()).filter { !seen.get(it) } }
            println(String.format(Locale.ROOT, "grants   starlink seed=44 blackout=%dms stalled=%d resume=%dms stalls(settled=%d end=%d) sent=%d got=%d missing=%s | server target=%d limit=%d | client credit(limit=%d sent=%d) | client: %s | server: %s | %s",
                (restored - t0) / 1_000_000, stalledDuring, resumeMs, stallsSettled, stallsEnd, sentCount.get(), gotCount.get(), missing.take(20), ss.creditTargetBytes, ss.creditLimit, cs.creditLimit, cs.creditSent, cs, ss, n))
            assertTrue(allArrived, "every message sent must arrive: sent=${sentCount.get()} got=${gotCount.get()} | $cs | server=$ss | $n")
            assertTrue(stalledDuring >= 1, "the blackout must exhaust the sender's credit (stalls during=$stalledDuring): $cs")
            assertTrue(resumed && resumeMs <= 1_100, "sends must resume within one re-send interval (<= 1 s) after grants return, took ${resumeMs}ms: $cs | server=$ss | $n")
            assertTrue(stallsEnd == stallsSettled, "no credit stalls once grants flow again (settled=$stallsSettled end=$stallsEnd): $cs | server=$ss | $n")
            assertTrue(ss.grantsPiggybacked > 0, "ACKs carry the cumulative limit: $ss")
            conn.close(); sc.close()
        }
    }
}
