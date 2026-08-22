package aether.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic, single-threaded bottleneck simulator (fluid drop-tail queue, propagation delay, per-packet
 * ACKs) plus unit tests for [CubicCc] and [HybridCc].
 *
 * Scenario parameters are "typical last mile": 100 Mbit/s (12.5 MB/s) bottleneck, 20-40 ms base RTT,
 * buffer of 0.5-4 BDP. Throughput is counted at the receiver (bytes that crossed the link).
 */
class CongestionControlTest {
    private val mss = Wire.MAX_DATAGRAM

    // ---- simulator ---------------------------------------------------------------------------------------

    private class Pkt(val seq: Long, val bytes: Int, val sentUs: Long)

    private class AckEvent(val timeUs: Long, val id: Long, val sender: Int, val seq: Long, val bytes: Int)

    private class SimSender(val cc: SenderCc, val baseRttUs: Long, val startUs: Long, val pkt: Int) {
        val outstanding = ArrayDeque<Pkt>()
        var inFlight = 0L
        var nextSeq = 0L
        var nextPaceUs = startUs
        var delivered = 0L   // bytes that crossed the bottleneck
        var lost = 0L
        var sent = 0L
        var srttUs = 0.0
        var rttVarUs = 0.0
        var lastRttUs = 0L
        var maxCwnd = 0L

        fun rtoUs(): Long =
            if (srttUs == 0.0) 1_000_000L else max((srttUs + 4 * rttVarUs).toLong(), 2 * lastRttUs)

        fun onRtt(rttUs: Long) {
            lastRttUs = rttUs
            val s = rttUs.toDouble()
            if (srttUs == 0.0) { srttUs = s; rttVarUs = s / 2 } else {
                rttVarUs = 0.75 * rttVarUs + 0.25 * abs(srttUs - s); srttUs = 0.875 * srttUs + 0.125 * s
            }
        }
    }

    /** Fluid FIFO: backlog at time t is (busyUntil - t) * rate; drop-tail when backlog + size > capacity. */
    private class Link(val rateBytesPerSec: Double, val queueBytes: Long) {
        var busyUntilUs = 0.0
        var drops = 0L
        var delivered = 0L
        var firstDropUs = -1L
        var maxBacklog = 0L

        fun backlogAt(nowUs: Long): Long =
            if (busyUntilUs > nowUs) ((busyUntilUs - nowUs) * rateBytesPerSec / 1e6).toLong() else 0L

        /** Returns the time the last bit leaves the link, or -1 if the packet was dropped. */
        fun enqueue(nowUs: Long, bytes: Int): Long {
            val backlog = backlogAt(nowUs)
            if (backlog + bytes > queueBytes) {
                drops++
                if (firstDropUs < 0) firstDropUs = nowUs
                return -1
            }
            val start = max(busyUntilUs, nowUs.toDouble())
            busyUntilUs = start + bytes * 1e6 / rateBytesPerSec
            maxBacklog = max(maxBacklog, backlog + bytes)
            delivered += bytes
            return busyUntilUs.toLong()
        }
    }

    private class Sim(val link: Link, val senders: List<SimSender>) {
        private val events = PriorityQueue<AckEvent>(compareBy({ it.timeUs }, { it.id }))
        private var eventId = 0L
        var nowUs = 0L; private set
        /** Hook for per-event observation, e.g. recording queue occupancy when a phase changes. */
        var onAck: ((SimSender, Long) -> Unit)? = null

        private fun send(i: Int, s: SimSender) {
            val p = Pkt(s.nextSeq++, s.pkt, nowUs)
            s.outstanding.addLast(p)
            s.inFlight += p.bytes
            s.sent += p.bytes
            s.cc.onSent(p.bytes, nowUs)
            s.maxCwnd = max(s.maxCwnd, s.cc.cwnd)
            val done = link.enqueue(nowUs, p.bytes)
            if (done >= 0) events.add(AckEvent(done + s.baseRttUs, eventId++, i, p.seq, p.bytes))
            val gap = (p.bytes * 1e6 / s.cc.pacingRateBytesPerSec).toLong().coerceAtLeast(1L)
            s.nextPaceUs = max(s.nextPaceUs, nowUs) + gap
        }

        private fun declareLost(s: SimSender, p: Pkt) {
            s.inFlight -= p.bytes
            s.lost += p.bytes
            s.cc.onLoss(p.bytes, nowUs)
        }

        private fun ack(e: AckEvent) {
            val s = senders[e.sender]
            s.delivered += e.bytes
            // FIFO path without reordering: everything older than an acked packet is lost (RACK, threshold 0).
            while (s.outstanding.isNotEmpty() && s.outstanding.first().seq < e.seq) declareLost(s, s.outstanding.removeFirst())
            val head = s.outstanding.firstOrNull()
            if (head != null && head.seq == e.seq) {
                s.outstanding.removeFirst()
                s.inFlight -= head.bytes
                val rtt = nowUs - head.sentUs
                s.onRtt(rtt)
                s.cc.onAcked(head.bytes, rtt, nowUs)
                onAck?.invoke(s, nowUs)
            } // else: already declared lost by the RTO, ignore the late ack
        }

        fun run(untilUs: Long) {
            while (true) {
                for ((i, s) in senders.withIndex()) {
                    while (nowUs >= s.nextPaceUs && s.cc.canSend(s.inFlight, s.pkt)) send(i, s)
                }
                var next = events.peek()?.timeUs ?: Long.MAX_VALUE
                for (s in senders) {
                    if (s.cc.canSend(s.inFlight, s.pkt)) next = min(next, max(s.nextPaceUs, nowUs + 1))
                    s.outstanding.firstOrNull()?.let { next = min(next, it.sentUs + s.rtoUs()) }
                }
                if (next == Long.MAX_VALUE || next > untilUs) { nowUs = untilUs; return }
                nowUs = next
                while (events.isNotEmpty() && events.peek().timeUs <= nowUs) ack(events.poll())
                for (s in senders) {
                    while (s.outstanding.isNotEmpty() && s.outstanding.first().sentUs + s.rtoUs() <= nowUs) {
                        declareLost(s, s.outstanding.removeFirst())
                    }
                }
            }
        }
    }

    private fun cubic(hystart: Boolean = true) = CubicCc(mss, 10, hystart)

    /** 100 Mbit/s, `rttMs` base RTT, buffer = `bufferBdp` BDPs. */
    private fun link(rttMs: Int, bufferBdp: Double): Link {
        val rate = 12_500_000.0
        val bdp = rate * rttMs / 1000.0
        return Link(rate, (bdp * bufferBdp).toLong())
    }

    // ---- simulator-based tests ---------------------------------------------------------------------------

    @Test fun twoCubicSendersShareBottleneckFairly() {
        // 100 Mbit/s, 20 ms RTT (BDP = 250 KB = 185 packets), 1 BDP of buffer; the second flow starts 1 s late.
        // Throughput is measured over 10 s from the second flow's start.
        val link = link(rttMs = 20, bufferBdp = 1.0)
        val a = SimSender(cubic(), 20_000, 0, mss)
        val b = SimSender(cubic(), 20_000, 1_000_000, mss)
        val sim = Sim(link, listOf(a, b))
        sim.run(1_000_000)
        val ccA = a.cc as CubicCc
        assertTrue(ccA.congestionAvoidanceEntryUs in 0 until 1_000_000, "A should own the link before B starts")
        assertTrue(a.inFlight > 100L * mss)
        val a0 = a.delivered; val b0 = b.delivered
        sim.run(11_000_000)
        val ta = a.delivered - a0; val tb = b.delivered - b0
        val shareA = ta.toDouble() / (ta + tb)
        val util = (ta + tb) / (link.rateBytesPerSec * 10.0)
        println("fairness: A=${"%.1f".format(shareA * 100)}% B=${"%.1f".format((1 - shareA) * 100)}% util=${"%.1f".format(util * 100)}% " +
            "drops=${link.drops} cwndA=${a.cc.cwnd / mss} cwndB=${b.cc.cwnd / mss} reductionsA=${ccA.lossReductions} reductionsB=${(b.cc as CubicCc).lossReductions}")
        assertTrue(shareA in 0.35..0.65, "sender A share $shareA outside 35-65%")
        assertTrue(util > 0.8, "aggregate utilization $util")
        assertEquals(link.drops * mss, a.lost + b.lost, "every loss the senders saw must be a real drop (no spurious RTO)")
    }

    @Test fun singleCubicSenderReachesHighUtilization() {
        // 100 Mbit/s, 40 ms RTT, shallow 0.25 BDP buffer (the hard case for a loss-based CC: the window trough
        // after a 0.7 reduction is 0.875 BDP); steady state measured over t = 2..10 s.
        val link = link(rttMs = 40, bufferBdp = 0.25)
        val s = SimSender(cubic(), 40_000, 0, mss)
        val sim = Sim(link, listOf(s))
        sim.run(2_000_000)
        val d0 = s.delivered
        sim.run(10_000_000)
        val util = (s.delivered - d0) / (link.rateBytesPerSec * 8.0)
        val cc = s.cc as CubicCc
        println("single: util=${"%.1f".format(util * 100)}% cwnd=${cc.cwnd / mss} pkts maxCwnd=${s.maxCwnd / mss} lossReductions=${cc.lossReductions} drops=${link.drops} srtt=${cc.srttUs.toLong()}us")
        assertTrue(util >= 0.8, "steady-state utilization $util < 80%")
        assertEquals(CubicCc.Phase.CONGESTION_AVOIDANCE, cc.phase)
        assertTrue(cc.lossReductions >= 1)
        assertEquals(link.drops * mss, s.lost, "every loss the sender saw must be a real drop (no spurious RTO)")
    }

    private class SlowStartRun(val cc: CubicCc, val link: Link, val backlogAtCssEntry: Long, val maxCwnd: Long)

    private fun runSlowStart(hystart: Boolean, rttMs: Int, bufferBdp: Double): SlowStartRun {
        val link = link(rttMs, bufferBdp)
        val s = SimSender(cubic(hystart), rttMs * 1000L, 0, mss)
        val cc = s.cc as CubicCc
        val sim = Sim(link, listOf(s))
        var backlogAtCssEntry = -1L
        sim.onAck = { _, now ->
            if (backlogAtCssEntry < 0 && cc.phase != CubicCc.Phase.SLOW_START) backlogAtCssEntry = link.backlogAt(now)
        }
        sim.run(3_000_000)
        return SlowStartRun(cc, link, backlogAtCssEntry, s.maxCwnd)
    }

    @Test fun hystartExitsSlowStartBeforeQueueOverflows() {
        // Typical last-mile case: 100 Mbit/s, 40 ms base RTT (BDP = 500 KB = 370 packets), 2 BDP of buffer
        // (80 ms). HyStart++ must leave exponential growth (enter CSS) before the first drop.
        //
        // Why not less buffer: RFC 9406 compares the minimum RTT of the current round with that of the previous
        // round, and a round's samples describe the queue as it was one round earlier. With slow start paced at
        // 1.25 * cwnd/srtt, the queue grows by roughly one BDP per round, so the exit lands ~1-1.6 BDP of queue
        // after the RTT increase first became visible. With a 1 BDP buffer the overflow therefore precedes the
        // exit (as with TCP), and the loss ends slow start instead.
        val h = runSlowStart(hystart = true, rttMs = 40, bufferBdp = 2.0)
        val plain = runSlowStart(hystart = false, rttMs = 40, bufferBdp = 2.0)
        println("hystart: ssExit=${h.cc.slowStartExitUs}us caEntry=${h.cc.congestionAvoidanceEntryUs}us firstDrop=${h.link.firstDropUs}us " +
            "backlog@css=${h.backlogAtCssEntry / mss} pkts cap=${h.link.queueBytes / mss} pkts drops=${h.link.drops} maxCwnd=${h.maxCwnd / mss} | " +
            "plain slow start: firstDrop=${plain.link.firstDropUs}us drops=${plain.link.drops} maxCwnd=${plain.maxCwnd / mss}")
        assertTrue(h.cc.slowStartExitUs >= 0, "HyStart++ never exited slow start")
        assertTrue(h.link.firstDropUs < 0 || h.cc.slowStartExitUs < h.link.firstDropUs, "slow start exited after first drop")
        assertTrue(h.backlogAtCssEntry in 0 until h.link.queueBytes)
        // The conservative phase bounds the overshoot: far fewer drops than classic slow start on the same link.
        assertTrue(h.link.drops * 2 < plain.link.drops, "HyStart++ drops ${h.link.drops} vs plain ${plain.link.drops}")
        assertTrue(h.maxCwnd < plain.maxCwnd)
    }

    // ---- unit tests --------------------------------------------------------------------------------------

    @Test fun ceReactionAtMostOncePerRtt() {
        val cc = cubic()
        // Establish an RTT of 20 ms.
        cc.onSent(mss, 0); cc.onAcked(mss, 20_000, 20_000)
        val w0 = cc.cwnd
        cc.onEcnCe(20_000)
        val w1 = cc.cwnd
        assertEquals((w0 * 0.9).toLong(), w1)
        cc.onEcnCe(25_000); cc.onEcnCe(39_000)
        assertEquals(w1, cc.cwnd, "second CE within the same RTT must be ignored")
        cc.onEcnCe(40_000)
        assertEquals((w1 * 0.9).toLong(), cc.cwnd)
        assertEquals(2, cc.ceReductions)
        assertEquals(CubicCc.Phase.CONGESTION_AVOIDANCE, cc.phase)
    }

    @Test fun cwndNeverBelowTwoMss() {
        val cc = cubic()
        var t = 0L
        repeat(20) { cc.onLoss(mss, t); t += 200_000 }
        assertEquals(2L * mss, cc.cwnd)
        repeat(20) { cc.onEcnCe(t); t += 200_000 }
        assertEquals(2L * mss, cc.cwnd)
        assertTrue(cc.pacingRateBytesPerSec > 0)
    }

    @Test fun lossesWithinOneRoundTripAreOneCongestionEvent() {
        val cc = cubic()
        repeat(10) { cc.onSent(mss, 0) }
        cc.onAcked(mss, 30_000, 30_000)
        val before = cc.cwnd
        cc.onLoss(mss, 31_000); cc.onLoss(mss, 32_000); cc.onLoss(mss, 33_000)
        assertEquals(1, cc.lossReductions)
        assertEquals((before * 0.7).toLong(), cc.cwnd)
        assertTrue(cc.inRecovery)
        assertFalse(cc.canSend(cc.cwnd, 1))
        assertTrue(cc.canSend(cc.cwnd - mss, mss))
    }

    @Test fun slowStartDoublesPerRoundAndPacesAboveCwndRate() {
        val cc = cubic()
        repeat(10) { cc.onSent(mss, 0) }
        repeat(10) { cc.onAcked(mss, 50_000, 50_000) }
        assertEquals(20L * mss, cc.cwnd)
        assertEquals(CubicCc.Phase.SLOW_START, cc.phase)
        assertEquals(cc.cwnd / 0.05 * 1.25, cc.pacingRateBytesPerSec, 1.0)
    }

    @Test fun hybridReportsModeAndCounters() {
        val est = PathEstimator(PathId(0))
        val credit = SenderCredit(initialWindow = 0)
        val fallback = cubic()
        val h = HybridCc(est, credit, fallback)
        assertFalse(h.canSend(0, mss))
        assertEquals(HybridCc.Mode.GRANT_LIMITED, h.mode)
        assertEquals(1, h.grantLimitedCount)

        h.onGrant(Frame.Grant(PathId(0), 100 * mss, 0))
        assertTrue(h.canSend(0, mss))
        assertEquals(HybridCc.Mode.UNLIMITED, h.mode)
        assertEquals(1, h.unlimitedCount)

        // Credit-primary: without congestion evidence cwnd never gates. ECN-CE engages the fallback.
        repeat(10) { h.onSent(mss, 0) }
        assertTrue(h.canSend(10L * mss, mss)); assertEquals(HybridCc.Mode.UNLIMITED, h.mode)
        h.onEcnCe(0)
        assertTrue(h.engaged)
        assertFalse(h.canSend(10L * mss, mss))
        assertEquals(HybridCc.Mode.CWND_LIMITED, h.mode)
        assertEquals(1, h.cwndLimitedCount)
        assertEquals(fallback.cwnd, h.cwnd)

        // Credit exhausted too: both bind, credit wins the label, both counters advance.
        repeat(90) { h.onSent(mss, 0) }
        assertFalse(h.canSend(100L * mss, mss))
        assertEquals(HybridCc.Mode.GRANT_LIMITED, h.mode)
        assertEquals(2, h.grantLimitedCount)
        assertEquals(2, h.cwndLimitedCount)
        h.resetCounters()
        assertEquals(0, h.grantLimitedCount + h.cwndLimitedCount + h.unlimitedCount)
    }

    @Test fun hybridExposesMoreRestrictivePacingRate() {
        val est = PathEstimator(PathId(0))
        val credit = SenderCredit()
        val fallback = cubic()
        val h = HybridCc(est, credit, fallback)
        // No delivery-rate estimate yet: the fallback's pacing rate applies.
        assertEquals(fallback.pacingRateBytesPerSec, h.pacingRateBytesPerSec, 1e-6)

        // Ack-observed delivery of 100 KB/s: credit can only arrive at ~110 KB/s, which is tighter than
        // CUBIC's 10 packets per 50 ms. (PathEstimator treats time 0 as "no sample yet", so start at 1 s.)
        est.onDelivered(0, 1_000_000); est.onDelivered(100_000, 2_000_000)
        assertEquals(100_000.0, est.deliveredBytesPerSec, 1e-6)
        assertEquals(100_000.0 * 1.1, h.pacingRateBytesPerSec, 1e-6)
        assertTrue(h.pacingRateBytesPerSec < fallback.pacingRateBytesPerSec)

        // A collapsed delivery-rate estimate must not stall the pacer: floor of 2 packets per RTT.
        for (i in 1..40) est.onDelivered(100_000L + i, 2_000_000 + i * 1_000_000L)
        assertTrue(est.deliveredBytesPerSec < 100.0)
        assertEquals(2.0 * mss / 0.05, h.pacingRateBytesPerSec, 1e-6)

        // Events reach the fallback; sent bytes also consume credit.
        h.onSent(mss, 0)
        h.onAcked(mss, 20_000, 20_000)
        assertEquals(11L * mss, h.cwnd)
        // A loss with no queueing delay is ignored by the hybrid (credit-primary) and leaves cwnd alone.
        h.onLoss(mss, 100_000)
        assertEquals(11L * mss, h.cwnd); assertEquals(1, h.ignoredLosses)
        // ECN-CE engages: cwnd is re-based at the observed in-flight high-water mark, then CUBIC's CE cut applies.
        h.canSend(11L * mss, mss)
        h.onEcnCe(200_000)
        assertEquals((11L * mss * 0.9).toLong(), h.cwnd)
    }

    @Test fun hybridIgnoresRandomLossWithoutQueueing() {
        val est = PathEstimator(PathId(0)).apply { repeat(5) { onRttSample(80_000) } } // srtt == minRtt: no queue
        val credit = SenderCredit(initialWindow = 1_000_000)
        val cubic = CubicCc()
        val h = HybridCc(est, credit, cubic)
        var now = 0L
        repeat(200) { now += 1_000; h.onSent(1350, now); h.onAcked(1350, 80_000, now) }
        repeat(50) { now += 1_000; h.onLoss(1350, now) }          // 50 random losses, no delay growth
        assertEquals(50, h.ignoredLosses); assertEquals(0, h.engagements); assertFalse(h.engaged)
        assertTrue(h.canSend(bytesInFlight = 2_000_000, bytes = 1350), "credit-only: cwnd must not gate without congestion evidence")
        assertEquals(HybridCc.Mode.UNLIMITED, h.mode)
    }

    @Test fun hybridEngagesOnQueueingLossAndReleasesLater() {
        val est = PathEstimator(PathId(0)).apply { onRttSample(80_000); repeat(10) { onRttSample(130_000) } } // +50 ms queue
        val credit = SenderCredit(initialWindow = 10_000_000)
        val cubic = CubicCc()
        val h = HybridCc(est, credit, cubic)
        var now = 0L
        repeat(100) { now += 1_000; h.canSend(400_000, 1350); h.onSent(1350, now); h.onAcked(1350, 130_000, now) }
        now += 1_000; h.onLoss(1350, now)
        assertEquals(1, h.engagements); assertTrue(h.engaged)
        assertTrue(cubic.cwnd <= 400_000L, "clamped to observed in-flight then reduced, got ${cubic.cwnd}")
        assertFalse(h.canSend(bytesInFlight = 10_000_000, bytes = 1350), "engaged: cwnd gates")
        now += 4 * 130_000 + 1; h.onAcked(1350, 130_000, now)
        assertFalse(h.engaged, "releases after 4 RTT without new evidence")
        assertTrue(h.canSend(bytesInFlight = 10_000_000, bytes = 1350))
    }
}
