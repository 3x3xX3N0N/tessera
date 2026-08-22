package tessera.transport

import tessera.core.Handshake
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The netem matrix of docs/BENCH-netem.md, in process: [NetemSim] presets on both endpoints. The first three tests
 * reproduce the connect failures of the real matrix with the connect bench's server pattern (answer, then close at
 * once), the PMTUD test reproduces "plpmtu stays at 1200 (BASE) for the whole run", the stream tests pin the
 * 2000 msg/s behaviour and the tail-repair cost on a steady stream.
 */
class NetemTest {
    private val keys = Handshake.generate()
    /** Default NetemSim seed for the tests here (each failure message carries the seed actually used). */
    private val SEED = 1L
    private val ticketKey = ByteArray(32) { (it * 3).toByte() }

    private class Net(val sim: NetemSim, val s: TesseraServer, val c: TesseraClient, val datapath: String) : AutoCloseable {
        override fun close() { c.close(); s.close(); sim.close() }
        /** Everything a failure message needs to be reproduced and diagnosed: preset + seed, datapath, socket-layer counters. */
        override fun toString() = "$sim | datapath=$datapath client-io=${c.ioStats} server-io=${s.ioStats}"
    }

    /** Seeds are explicit and appear in every message: a failing run reproduces with the same preset + seed (+ datapath). */
    private fun net(preset: NetemSim.Preset, seed: Long = SEED, cfg: ConnConfig = ConnConfig(), datapath: String? = null): Net {
        val sim = preset.sim(seed)
        val conf = ConnConfig(maxDatagram = cfg.maxDatagram, ackFreq = cfg.ackFreq, pmtud = cfg.pmtud, netem = sim)
        val prev = System.getProperty(Datapath.NATIVE_PROPERTY)
        if (datapath != null) System.setProperty(Datapath.NATIVE_PROPERTY, datapath)
        try {
            val s = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, conf)
            val c = TesseraClient(cfg = conf)
            return Net(sim, s, c, if (Datapath.nativeSelected()) "native" else "channel")
        } finally { if (datapath != null) { if (prev == null) System.clearProperty(Datapath.NATIVE_PROPERTY) else System.setProperty(Datapath.NATIVE_PROPERTY, prev) } }
    }

    /** The connect bench's server loop: take the 0-RTT payload, answer, close the connection right away. */
    private fun responder(s: TesseraServer, running: AtomicBoolean, response: ByteArray, beforeSend: (TesseraConnection) -> Unit = {}) =
        Thread {
            while (running.get()) {
                val sc = s.accept(100) ?: continue
                val m = sc.receive(3_000)
                beforeSend(sc)
                if (m != null) sc.send(response)
                sc.close()
            }
        }.apply { isDaemon = true; start() }

    private fun busySpin(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }

    /**
     * Reliability at close: the app sends N messages as fast as credit allows and closes at once; close() lingers until
     * everything is acknowledged, with residual ARQ / PTO driving the lost ones, so the peer receives all N. Both
     * datapaths, two lossy profiles, fixed seeds. Before the fix a stream's tail could strand a few messages for good:
     * a source below the largest acked pn that RACK confirmed lost was re-sent only while the token bucket had a token,
     * and the PTO never looks below the largest acked pn.
     */
    @Test fun sendThenCloseDeliversEveryMessageOnBothDatapaths() {
        val failures = ArrayList<String>()
        for (datapath in listOf("off", "on")) {
            if (datapath == "on" && !Datapath.nativeAvailable) continue
            for ((preset, seed) in listOf(NetemSim.Preset.FIVEG_MMWAVE to 11L, NetemSim.Preset.WIFI_BUSY to 12L)) net(preset, seed, datapath = datapath).use { n ->
                val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
                val sc = assertNotNull(n.s.accept(5_000)); sc.receive(2_000)
                val count = 600; val size = 1200
                fun payload(i: Int) = ByteArray(size) { j -> (i * 131 + j * 7).toByte() }.also { it[0] = (i shr 8).toByte(); it[1] = i.toByte() }
                repeat(count) { i -> conn.send(payload(i)); busySpin(500) }
                conn.close()   // at once: no waiting for acks in the app
                assertTrue(conn.isClosed)
                val got = BooleanArray(count); var n0 = 0; var corrupt = 0
                val deadline = System.nanoTime() + 15_000_000_000L
                while (n0 < count && System.nanoTime() < deadline) {
                    val m = sc.receive(100) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < count && !got[i]) { got[i] = true; n0++; if (!m.contentEquals(payload(i))) corrupt++ }
                }
                val missing = (0 until count).filter { !got[it] }
                val cs = conn.stats; val ss = sc.stats
                println(String.format(Locale.ROOT, "close    %-10s %-7s delivered=%d/%d corrupt=%d missing=%s decodeErr(c/s)=%d/%d | client: %s | server: %s | %s",
                    preset.profile, n.datapath, n0, count, corrupt, missing.take(10), cs.decodeErrors, ss.decodeErrors, cs, ss, n))
                // every message the app sent before close must arrive, intact: the reliability guarantee
                if (missing.isNotEmpty()) failures += "${preset.profile} on ${n.datapath} (seed=$seed): ${missing.size} of $count messages never delivered ${missing.take(10)} | client=$cs | server=$ss | $n"
                if (corrupt > 0) failures += "${preset.profile} on ${n.datapath} (seed=$seed): $corrupt messages delivered with wrong content | client=$cs | server=$ss | $n"
                // an exception parsing an authenticated packet's own frames is a bug; a rare wrong RLNC solve (core decoder,
                // arrival-order dependent) is tolerated - the symbol is discarded undelivered and residual ARQ re-sends the source
                if (cs.rxErrors + ss.rxErrors > 0) failures += "${preset.profile} on ${n.datapath} (seed=$seed): rx parse errors client=${cs.firstRxError} server=${ss.firstRxError} | $n"
                sc.close()
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    private fun pct(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    // ---------------------------------------------------------------- (a) connect under loss, bench-style server

    /** Reply lost, server already answered and closed: before the fix the retransmitted initial hit the 0-RTT replay
     *  filter (no connection left for its ConnId) and connect timed out after 3000 ms; now the closed connection lingers
     *  and re-sends its reply. Deterministic (one dropped reply) on the 180 ms link. */
    @Test fun lostReplyToAServerThatAnswersAndClosesAtOnceStillConnects() {
        net(NetemSim.Preset.TRANSCONT).use { n ->
            val running = AtomicBoolean(true)
            responder(n.s, running, "hello".toByteArray())
            n.s.dropReplies = 1
            val t0 = System.nanoTime()
            val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "ff".toByteArray(), timeoutMs = 3_000)
            val resp = conn.receive(3_000)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals("hello", String(assertNotNull(resp, "first response after the lost reply: ${conn.stats} | ${n.sim}")))
            assertTrue(ms in 250..1_500, "one retransmit round on a 180 ms link, took ${ms}ms")
            conn.close(); running.set(false)
        }
    }

    /** The server's first data packet lost, server closed right after sending it: before the fix nothing ever
     *  retransmitted it ("no response" in the connect bench); now the closing connection keeps probing until acked. */
    @Test fun lostFirstResponseFromAServerThatClosesAtOnceIsRepaired() {
        net(NetemSim.Preset.TRANSCONT).use { n ->
            val running = AtomicBoolean(true)
            val dropped = AtomicInteger()
            responder(n.s, running, "hello".toByteArray()) { sc ->
                sc.txFilter = { kind, _, _ -> kind == TesseraConnection.KIND_SOURCE && dropped.getAndIncrement() == 0 }
            }
            val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "ff".toByteArray(), timeoutMs = 3_000)
            val resp = conn.receive(3_000)
            assertEquals("hello", String(assertNotNull(resp, "first response after its first packet was lost: client=${conn.stats} | ${n.sim}")))
            assertEquals(1, dropped.get())
            conn.close(); running.set(false)
        }
    }

    /** 20 fresh + 20 resumed connects per preset against the bench-style server, 0 failures allowed. */
    @Test fun freshAndResumedConnectsSucceedOnEveryPreset() {
        val report = ArrayList<String>()
        for (preset in NetemSim.Preset.entries) net(preset).use { n ->
            val running = AtomicBoolean(true)
            responder(n.s, running, "hello".toByteArray())
            val failures = CopyOnWriteArrayList<String>()
            val times = ArrayList<Long>()
            var ticket: Pair<ByteArray, ByteArray>? = null
            fun once(resumed: Boolean) {
                val t0 = System.nanoTime()
                try {
                    val conn = if (resumed) { val (t, sec) = ticket!!; n.c.resume(n.s.localAddress, t, sec, "ff".toByteArray(), timeoutMs = 10_000) }
                               else n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "ff".toByteArray(), timeoutMs = 10_000)
                    if (!resumed && ticket == null) ticket = assertNotNull(conn.ticket) to conn.resumptionSecret
                    val resp = conn.receive(5_000)
                    if (resp == null) failures += "${if (resumed) "resumed" else "fresh"}: no response (${conn.stats})"
                    else times += (System.nanoTime() - t0) / 1000
                    conn.close()
                } catch (e: Exception) { failures += "${if (resumed) "resumed" else "fresh"}: ${e.javaClass.simpleName}: ${e.message}" }
            }
            repeat(20) { once(false) }
            repeat(20) { once(true) }
            running.set(false)
            val sorted = times.sorted()
            val line = String.format(Locale.ROOT, "connect  %-10s fresh+resumed n=%d fail=%d p50=%.1fms p99=%.1fms | %s",
                preset.profile, times.size, failures.size, pct(sorted, .5) / 1e3, pct(sorted, .99) / 1e3, n.sim)
            println(line); report += line
            assertTrue(failures.isEmpty(), "${preset.profile}: ${failures.size} of 40 connects failed: ${failures.take(5)} | $n")
        }
    }

    /**
     * The stream's tail must never be stranded in a datapath's send batch (netem finding, WSL run 3 native path): at
     * 50 msg/s the last ~one batch of sources + tail repairs left the sender's TxBatch unflushed for good. Sends N
     * messages on a clean link, stops, and requires every one at the peer within 100 ms of the last send - on both the
     * channel and the native datapath. Prints the datapaths' own out/in datagram counts so a strand (out < expected)
     * is distinguishable from a transit drop.
     */
    @Test fun stopSendingLeavesNothingStrandedInTheSendBatchOnEitherDatapath() {
        val failures = ArrayList<String>()
        for (datapath in listOf("off", "on")) {
            if (datapath == "on" && !Datapath.nativeAvailable) continue
            net(NetemSim.Preset.LAN_CLEAN, seed = 7, datapath = datapath).use { n ->
                val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 5_000)
                val sc = assertNotNull(n.s.accept(5_000)); sc.receive(2_000)
                val count = 300; val size = 1200
                fun payload(i: Int) = ByteArray(size) { j -> (i * 91 + j * 13).toByte() }.also { it[0] = (i shr 8).toByte(); it[1] = i.toByte() }
                repeat(count) { i -> conn.send(payload(i)); LockSupport.parkNanos(20_000_000L) }   // 50 msg/s
                val lastSendUs = System.nanoTime() / 1000
                val got = BooleanArray(count); var n0 = 0; var corrupt = 0; var withinUs = 0L
                val deadline = System.nanoTime() + 5_000_000_000L   // generous: the assertion is on arrival time, not this
                while (n0 < count && System.nanoTime() < deadline) {
                    val m = sc.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < count && !got[i]) { got[i] = true; n0++; withinUs = System.nanoTime() / 1000 - lastSendUs; if (!m.contentEquals(payload(i))) corrupt++ }
                }
                val missing = (0 until count).filter { !got[it] }
                val cs = conn.stats; val ss = sc.stats
                println(String.format(Locale.ROOT, "quiesce  lan-clean  %-7s delivered=%d/%d corrupt=%d lastArrival=+%.1fms missing=%s | %s",
                    datapath, n0, count, corrupt, withinUs / 1e3, missing.take(10), n))
                if (missing.isNotEmpty()) failures += "lan-clean on ${n.datapath} (seed=7): ${missing.size} of $count stranded after send stopped ${missing.take(10)} | client=$cs | server=$ss | $n"
                else if (withinUs > 100_000) failures += "lan-clean on ${n.datapath} (seed=7): last message arrived +${withinUs / 1000}ms after the final send (batch not flushed promptly) | $n"
                if (corrupt > 0) failures += "lan-clean on ${n.datapath} (seed=7): $corrupt messages with wrong content | $n"
                conn.close(); sc.close()
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---------------------------------------------------------------- (b) DPLPMTUD under burst loss

    /** At 2000 msg/s under Gilbert-Elliott loss the PLPMTU must reach the negotiated 1350 within 2 s and stay there:
     *  before the fix a burst of three lost full-size packets plus one acked small packet (a credit probe, an ack) looked
     *  like a black hole and parked PMTUD at 1200 (BASE) for the rest of the run. */
    @Test fun plpmtuReachesMaxWithinTwoSecondsUnderBurstLoss() {
        val failures = ArrayList<String>()
        for (preset in listOf(NetemSim.Preset.LTE, NetemSim.Preset.FIVEG_MMWAVE)) net(preset).use { n ->
            val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(n.s.accept(5_000)); sc.receive(2_000)
            val r = stream(conn, sc, n = 5_000, gapUs = 500, size = 1200, warmup = 200, rttUs = 2 * preset.delayUs, chatter = true)
            val cs = r.clientStats
            println(String.format(Locale.ROOT, "pmtud    %-10s plpmtu@2s=%d final=%d(%s) probes=%d lost=%d delivered=%d/%d late=%d p50=%.1fms p99=%.1fms | %s | %s",
                preset.profile, r.plpmtuAt2s, cs.plpmtu, cs.pmtudState, cs.probesSent, cs.probesLost, r.delivered, 5_000, r.late, r.p50 / 1e3, r.p99 / 1e3, cs, n.sim))
            if (r.plpmtuAt2s < 1350) failures += "${preset.profile}: plpmtu=${r.plpmtuAt2s} after 2 s (probes=${cs.probesSent} lost=${cs.probesLost}): $cs | ${n.sim}"
            if (cs.plpmtu < 1350) failures += "${preset.profile}: plpmtu fell back to ${cs.plpmtu}(${cs.pmtudState}) by the end of the run: $cs | ${n.sim}"
            // 2000 x 1200 B is 78 % of the lte profile's 30 Mbit/s once the packets are whole (oversubscribed while they are fragmented)
            val minDelivered = if (preset == NetemSim.Preset.LTE) 4_950 else 5_000
            if (r.delivered < minDelivered) failures += "${preset.profile}: delivered ${r.delivered}/5000 late=${r.late}: $cs | server=${r.serverStats} | $n"
            if (cs.rxErrors + r.serverStats.rxErrors > 0) failures += "${preset.profile}: rx parse errors client=${cs.firstRxError} server=${r.serverStats.firstRxError} | $n"
            conn.close(); sc.close()
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---------------------------------------------------------------- 2000 msg/s on the lossy, jittery profiles

    /** 2000 messages at 2000 msg/s: everything delivered, nothing after the nominal deadline (sends done + 2 s), p99
     *  within 3x the profile's one-way budget (delay + 2 jitter; the loaded tail includes netem's rate ratchet). */
    @Test fun twoThousandMessagesPerSecondDeliverEverythingOnTime() {
        val failures = ArrayList<String>()
        for (preset in listOf(NetemSim.Preset.WIFI_BUSY, NetemSim.Preset.FIVEG_MMWAVE)) net(preset).use { n ->
            val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(n.s.accept(5_000)); sc.receive(2_000)
            val r = stream(conn, sc, n = 2_000, gapUs = 500, size = 1200, warmup = 200, rttUs = 2 * preset.delayUs)
            val cs = r.clientStats
            val linkP50 = n.sim.delayPercentileUs(0.5); val linkP99 = n.sim.delayPercentileUs(0.99)
            println(String.format(Locale.ROOT, "stream   %-10s n=%d delivered=%d late=%d p50=%.1fms p90=%.1fms p99=%.1fms max=%.1fms | link one-way p50=%.0fms p99=%.0fms srtt=%.1fms minRtt=%.1fms | %s | server: %s | %s",
                preset.profile, 2_000, r.delivered, r.late, r.p50 / 1e3, r.p90 / 1e3, r.p99 / 1e3, r.max / 1e3, linkP50 / 1e3, linkP99 / 1e3,
                conn.estimator.srttUs / 1e3, conn.estimator.minRttUs / 1e3, cs, r.serverStats, n.sim))
            if (r.delivered != 2_000) failures += "${preset.profile}: delivered ${r.delivered}/2000 (late=${r.late}): $cs | server=${r.serverStats} | $n"
            if (cs.rxErrors + r.serverStats.rxErrors > 0) failures += "${preset.profile}: rx parse errors client=${cs.firstRxError} server=${r.serverStats.firstRxError} | $n"
            if (r.late != 0) failures += "${preset.profile}: ${r.late} messages after the nominal deadline: $cs | ${n.sim}"
            // the transport may add up to 3 one-way delays (detection + a round trip of repair) on top of what the loaded link
            // itself imposed on its packets (BENCH-netem: "Idle RTT is not loaded RTT" - with a rate cap, jitter ratchets into a
            // standing queue, and here data and acks share that queue like on lo)
            val bound = linkP99 + 3 * preset.oneWayUs
            if (r.p99 > bound) failures += String.format(Locale.ROOT, "%s: p99=%.1fms > link p99 %.0fms + 3 x one-way %.0fms = %.0fms (p50=%.1fms, link p50=%.0fms): %s | %s",
                preset.profile, r.p99 / 1e3, linkP99 / 1e3, preset.oneWayUs / 1e3, bound / 1e3, r.p50 / 1e3, linkP50 / 1e3, cs, n.sim)
            conn.close(); sc.close()
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---------------------------------------------------------------- (d) tail repair on a steady stream

    /** At 2000 msg/s the 500 us floor of the tail timer equals the send gap: before the fix a sizeable share of the
     *  sources got a trailing repair symbol (+bytes for nothing — the next source follows within T anyway). Now the
     *  tail repair waits for the stream to stop, so only the last packet gets one. */
    @Test fun steadyStreamGetsNoPerPacketTailRepairs() {
        net(NetemSim.Preset.LAN_CLEAN).use { n ->
            val conn = n.c.connect(n.s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray())
            val sc = assertNotNull(n.s.accept(2_000)); sc.receive(1_000)
            val r = stream(conn, sc, n = 1_000, gapUs = 500, size = 1200, warmup = 200, rttUs = 1_000)
            val cs = r.clientStats
            println("tail     lan-clean 2000 msg/s: sources=${cs.sourcesSent} tail=${cs.repairsTail} pro=${cs.repairsProactive} react=${cs.repairsReactive} tlp=${cs.repairsTlp} bytes=${cs.bytesSent}")
            assertEquals(1_000, r.delivered)
            assertTrue(cs.repairsTail <= cs.sourcesSent / 25, "tail repairs on a steady stream: tail=${cs.repairsTail} of ${cs.sourcesSent} sources (was 9 % before the fix): $cs")
            // isolated packets (20 ms apart, far above 2 T) still get their trailing repair symbol
            repeat(5) { conn.send(ByteArray(64)); Thread.sleep(20) }
            repeat(5) { assertNotNull(sc.receive(1_000)) }
            val cs2 = conn.stats
            assertTrue(cs2.repairsTail - cs.repairsTail >= 3, "isolated packets must get tail repairs: before=${cs.repairsTail} after=${cs2.repairsTail}: $cs2")
            conn.close(); sc.close()
        }
    }

    // ---------------------------------------------------------------- the simulator itself

    /** The loss chain over 4000 packets (timing-independent), then delay / reorder at 200 pkt/s where the rate ratchet is negligible. */
    @Test fun presetsProduceTheDocumentedLossDelayAndReorder() {
        val to = InetSocketAddress("127.0.0.1", 9)
        for (preset in listOf(NetemSim.Preset.LTE, NetemSim.Preset.WIFI_BUSY, NetemSim.Preset.TRANSCONT)) {
            val lossSim = NetemSim(preset.profile, lossP = preset.lossP, lossR = preset.lossR, seed = 3)   // the chain alone: no queue to overflow
            val nl = 4_000; val got = AtomicInteger()
            for (i in 0 until nl) lossSim.submit(ByteBuffer.allocate(100), to) { _, _ -> got.incrementAndGet() }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (lossSim.queued > 0 && System.nanoTime() < deadline) Thread.sleep(5)
            lossSim.close()
            val lost = nl - got.get()
            val sim = preset.sim(seed = 5)
            val n = 300
            val submitted = LongArray(n); val arrived = LongArray(n) { -1L }
            val order = ArrayList<Int>()
            val sink: (ByteBuffer, InetSocketAddress) -> Unit = { b, _ -> val i = b.getInt(0); arrived[i] = NetemSim.nowUs(); synchronized(order) { order += i } }
            for (i in 0 until n) { val b = ByteBuffer.allocate(100).putInt(0, i); submitted[i] = NetemSim.nowUs(); sim.submit(b, to, sink); busySpin(5_000) }
            val deadline2 = System.nanoTime() + 3_000_000_000L
            while (sim.queued > 0 && System.nanoTime() < deadline2) Thread.sleep(5)
            sim.close()
            val delays = (0 until n).filter { arrived[it] >= 0 }.map { arrived[it] - submitted[it] }.sorted()
            val mean = delays.average()
            var inversions = 0
            synchronized(order) { for (k in 1 until order.size) if (order[k] < order[k - 1]) inversions++ }
            println(String.format(Locale.ROOT, "sim      %-10s loss=%.2f%% of %d (documented %.2f%%) delay mean=%.1fms min=%.1fms p50=%.1fms p99=%.1fms inversions=%d/%d | %s",
                preset.profile, 100.0 * lost / nl, nl, 100 * preset.lossAvg, mean / 1e3, delays.first() / 1e3, pct(delays, .5) / 1e3, pct(delays, .99) / 1e3, inversions, n, sim))
            assertTrue(abs(lost.toDouble() / nl - preset.lossAvg) < max(0.012, preset.lossAvg / 2), "${preset.profile}: loss ${lost}/$nl vs ${preset.lossAvg}")
            if (preset.dist == NetemSim.Dist.PARETO) {
                // heavy tail clamped at zero delay (netem: a third of `delay 8ms 20ms distribution pareto` goes out at once)
                assertTrue(delays.first() < 1_000, "${preset.profile}: the clamped tail must reach zero delay, min=${delays.first()}us")
                assertTrue(pct(delays, .5) in (preset.delayUs / 2)..(preset.delayUs + 2 * preset.jitterUs), "${preset.profile}: p50 ${pct(delays, .5)}us")
            } else assertTrue(abs(mean - preset.delayUs) < preset.delayUs * 0.15 + 3_000, "${preset.profile}: mean delay ${mean}us vs ${preset.delayUs}us")
            if (preset.reorderProb > 0) assertTrue(inversions in (n / 100)..(n / 5), "${preset.profile}: reordered packets should overtake the queue: $inversions")
            else assertEquals(0, inversions, "${preset.profile}: a rate-limited netem never reorders")
        }
    }

    // ---------------------------------------------------------------- harness (the bench's runTessera, with late/lost split)

    private class StreamResult(val delivered: Int, val late: Int, val lat: LongArray, val clientStats: ConnStats, val serverStats: ConnStats, val plpmtuAt2s: Int) {
        private val sorted = lat.filter { it >= 0 }.sorted()
        val p50 get() = sorted[(sorted.size - 1) / 2]; val p90 get() = sorted[((sorted.size - 1) * 0.9).toInt()]
        val p99 get() = sorted[((sorted.size - 1) * 0.99).toInt()]; val max get() = sorted.last()
    }

    /**
     * Client sends `warmup` + `n` messages of `size` bytes every `gapUs`; the server records one-way latency per index.
     * Nominal deadline = sends done + 2 s (anything after it is `late`); the receiver keeps listening until the generous
     * deadline n * gap + max(10 s, 50 RTT) (anything still missing then is lost). `chatter` = the server app sends a
     * 4-byte message every 10 ms (request/response-like traffic: the client acks it with small packets).
     */
    private fun stream(conn: TesseraConnection, sc: TesseraConnection, n: Int, gapUs: Long, size: Int, warmup: Int, rttUs: Long, chatter: Boolean = false): StreamResult {
        val lat = LongArray(n) { -1L }; val sent = LongArray(n)
        var got = 0; var late = 0; var plpmtuAt2s = -1
        val start = System.nanoTime()
        val nominal = start + (warmup + n) * gapUs * 1000 + 2_000_000_000L
        val generous = start + n * gapUs * 1000 + max(10_000_000_000L, 50 * rttUs * 1000)
        val rx = Thread {
            while (got < n && System.nanoTime() < generous) {
                val now = System.nanoTime()
                if (plpmtuAt2s < 0 && now >= start + 2_000_000_000L) plpmtuAt2s = conn.plpmtu
                val m = sc.receive(50) ?: continue
                val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                if (i < n && lat[i] < 0) { val t = System.nanoTime(); lat[i] = (t - sent[i]) / 1000; got++; if (t > nominal) late++ }
            }
            if (plpmtuAt2s < 0) plpmtuAt2s = conn.plpmtu
        }.apply { start() }
        val chat = if (!chatter) null else Thread {
            var k = 0
            while (rx.isAlive) { try { sc.send(byteArrayOf(1, 2, 3, k++.toByte())) } catch (e: IllegalStateException) { break }; Thread.sleep(10) }
        }.apply { isDaemon = true; start() }
        val drain = if (!chatter) null else Thread { while (rx.isAlive) conn.receive(50) }.apply { isDaemon = true; start() }
        repeat(warmup + n) { k ->
            val i = k - warmup
            val idx = if (i < 0) 0xFFFF else i
            val p = ByteArray(size); p[0] = (idx shr 8).toByte(); p[1] = idx.toByte()
            if (i >= 0) sent[i] = System.nanoTime()
            conn.send(p)
            busySpin(gapUs)
        }
        rx.join(); chat?.join(500); drain?.join(500)
        return StreamResult(got, late, lat, conn.stats, sc.stats, plpmtuAt2s)
    }
}
