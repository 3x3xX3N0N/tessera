package tessera.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test fun frameRoundTrip() {
        val buf = ByteBuffer.allocate(256)
        Frame.Msg(7, 0, true, ByteBuffer.wrap("hi".toByteArray())).write(buf)
        Frame.Ack(PathId(1), 42, listOf(40L..42L), 0, 123).write(buf)
        Frame.Grant(PathId(1), 9000, 0).write(buf)
        Frame.MaxData(1 shl 24).write(buf)
        buf.flip()
        val m = FrameCodec.read(buf) as Frame.Msg; assertEquals(7, m.msgId); assertTrue(m.fin)
        val a = FrameCodec.read(buf) as Frame.Ack; assertEquals(42, a.largest)
        val g = FrameCodec.read(buf) as Frame.Grant; assertEquals(9000L, g.creditBytes)
        val d = FrameCodec.read(buf) as Frame.MaxData; assertEquals((1 shl 24).toLong(), d.limitBytes)
        assertNull(FrameCodec.read(buf))
    }

    @Test fun rlncRecoversTwoLossesFromTwoRepairs() {
        val enc = RlncEncoder(8); val dec = RlncDecoder(8)
        val syms = (0 until 6).map { i -> ByteArray(8) { (i * 10 + it).toByte() } }
        syms.forEachIndexed { i, s -> enc.push(i.toLong(), s) }
        listOf(0, 1, 3, 5).forEach { dec.onSource(it.toLong(), syms[it]) } // lose 2 and 4
        dec.onRepair(enc.repair(seed = 99))
        dec.onRepair(enc.repair(seed = 7))
        assertContentEquals(syms[2], dec.get(2))
        assertContentEquals(syms[4], dec.get(4))
    }

    @Test fun hybridHandshakeAgreesAndFitsOneDatagram() {
        val r = Handshake.generate()
        val i = Handshake.initiate(r.x25519Pub, r.kemPub)
        val k = Handshake.respond(r, i.ePub, i.kemCt)
        assertContentEquals(i.key, k)
        assertTrue(i.ePub.size + i.kemCt.size < Wire.MAX_DATAGRAM - Wire.HEADER_LEN)
    }

    @Test fun estimatorFecGrowsWithLoss() {
        val e = PathEstimator(PathId(0))
        repeat(50) { e.onLossObservation(0.0) }; val lo = e.fecRedundancy()
        repeat(50) { e.onLossObservation(0.1) }; val hi = e.fecRedundancy()
        assertTrue(hi > lo)
    }

    @Test fun lossTimerIsSaneBeforeFirstSampleAndBacksOff() {
        val e = PathEstimator(PathId(0))
        assertEquals(PathEstimator.INITIAL_RTT_US, e.lossTimeoutUs())
        assertEquals(PathEstimator.INITIAL_RTT_US * 4, e.ptoUs(2))
        assertEquals(PathEstimator.MAX_PTO_US, e.ptoUs(10))
        e.onRttSample(20_000)
        assertTrue(e.lossTimeoutUs() < PathEstimator.INITIAL_RTT_US)
        // with the RTT known the first two backoffs are capped: pto, 1.5 pto, 2 pto, then doubling (never stops, capped at MAX_PTO_US)
        val pto = e.lossTimeoutUs()
        assertEquals(pto, e.ptoUs(0)); assertEquals(pto * 3 / 2, e.ptoUs(1)); assertEquals(pto * 2, e.ptoUs(2))
        assertEquals(pto * 4, e.ptoUs(3)); assertEquals(pto * 8, e.ptoUs(4))
        assertEquals(PathEstimator.MAX_PTO_US, e.ptoUs(12))
        assertTrue(e.ptoUs(0) + e.ptoUs(1) + e.ptoUs(2) <= 5 * pto, "a burst that takes the data and two probe trains costs ~4.5 pto")
    }

    /** Burst statistics: runs of consecutive lost pns; the redundancy grows with burstiness at an equal loss rate. */
    @Test fun burstStatisticsRaiseRedundancyForTheSameLossRate() {
        val random = PathEstimator(PathId(0)); val bursty = PathEstimator(PathId(0))
        repeat(60) { random.onLossObservation(0.05); bursty.onLossObservation(0.05) }
        assertEquals(1.0, random.burstMean); assertEquals(1, random.burstP95)
        var pn = 0L
        repeat(40) { random.onLoss(pn); pn += 20 }                                   // isolated losses
        pn = 0L
        repeat(40) { k -> val len = if (k % 10 == 9) 9 else 4; repeat(len) { bursty.onLoss(pn++) }; pn += 20 }   // 4-bursts, every 10th is 9 long
        assertEquals(1.0, random.burstMean, 1e-9); assertEquals(1, random.burstP95)
        assertTrue(bursty.burstMean in 4.4..4.6, "mean ${bursty.burstMean}"); assertEquals(9, bursty.burstP95)
        assertEquals(39L, bursty.burstsRecorded)   // 39 completed runs; the open one counts in the mean
        assertEquals(random.lossRate, bursty.lossRate, 1e-12)
        val sigma = random.fecRedundancy() - 1.5 * random.lossRate
        assertEquals(random.lossRate * (1 + 4.5 / 2) + sigma, bursty.fecRedundancy(), 0.01)
        assertTrue(bursty.fecRedundancy() > random.fecRedundancy() + 0.05, "bursty ${bursty.fecRedundancy()} vs random ${random.fecRedundancy()}")
        assertTrue(bursty.fecRedundancy() <= 0.5)
    }

    /** Cumulative credit: a lost grant is superseded by the next, duplicates and stale grants are no-ops, ECN-CE trims the room. */
    @Test fun cumulativeGrantsSupersedeLostOnesAndAreIdempotent() {
        val sc = SenderCredit(initialWindow = 10_000)
        assertTrue(sc.canSend(10_000)); assertTrue(!sc.canSend(10_001))
        sc.onSent(6_000)
        val g1 = Frame.Grant(PathId(0), 20_000, 0); val g2 = Frame.Grant(PathId(0), 35_000, 0)
        // g1 lost on the wire: g2 alone gives the full room
        sc.onGrant(g2); assertEquals(35_000, sc.limit); assertEquals(29_000, sc.credit)
        sc.onGrant(g1); assertEquals(35_000, sc.limit, "a stale grant never lowers the limit")
        sc.onGrant(g2); sc.onGrant(g2); assertEquals(35_000, sc.limit, "re-sent / piggybacked grants are idempotent")
        sc.onSent(29_000); assertTrue(!sc.canSend(1)); assertEquals(0, sc.credit)
        sc.onGrant(Frame.Grant(PathId(0), 40_000, 0)); assertTrue(sc.canSend(5_000)); assertTrue(!sc.canSend(5_001))
        sc.onAck(Frame.Ack(PathId(0), 1, listOf(0L..1L), ecnCe = 1, rxTimeUs = 0)); assertEquals(4_500, sc.credit, "CE: room x 0.9")
        sc.onAck(Frame.Ack(PathId(0), 2, listOf(0L..2L), ecnCe = 1, rxTimeUs = 0)); assertEquals(4_500, sc.credit, "same CE count: no further cut")
        // the receiver side: every grant carries the absolute limit, the re-send is the same value, loss never shrinks the target
        val est = PathEstimator(PathId(0)).apply { onRttSample(50_000); repeat(30) { onLossObservation(0.1) } }
        var now = 1_000L
        val rc = ReceiverCredit(est, clock = { now })
        val first = rc.tick(now)!!
        assertEquals(first.creditBytes, rc.limit); assertEquals(first, rc.currentGrant())
        rc.onReceived(first.creditBytes.toInt())           // the sender used it all: drained -> the target doubles despite 10 % loss
        now += 100_000                                     // (slowly: 135 KB/s x 50 ms is below the floor, so the BDP term stays out of the way)
        val second = rc.tick(now)!!
        assertTrue(second.creditBytes > first.creditBytes, "monotone: ${second.creditBytes} > ${first.creditBytes}")
        assertTrue(rc.targetBytes >= 2 * 10L * Wire.MAX_DATAGRAM, "slow start not suppressed by loss: target ${rc.targetBytes}")
        rc.onEcnCe(); now += 100_000; rc.tick(now)
        assertTrue(rc.targetBytes < 2 * 10L * Wire.MAX_DATAGRAM, "ECN-CE shrinks the target: ${rc.targetBytes}")
    }

    /**
     * 180 ms RTT, sender offering 2 MB/s: credit must grow past the 10-packet floor to ~BDP (360 KB) and carry >90% of
     * load. Models the transport's loop: grants and data take a one-way delay, and a sender blocked on credit probes
     * (ReceiverCredit.onSenderBlocked) once per RTT — the slow-start signal since the limit slides with every tick.
     */
    @Test fun receiverCreditReachesBdpAtHighRtt() {
        val rttUs = 180_000L; val offered = 2_000_000.0 // bytes/s
        val est = PathEstimator(PathId(0)).apply { onRttSample(rttUs) }
        var now = 0L
        val rc = ReceiverCredit(est, clock = { now })
        var credit = 10L * Wire.MAX_DATAGRAM; var sentTotal = 0L
        val inFlight = ArrayDeque<Pair<Long, Long>>() // (arrivalUs, bytes)
        val grantsInFlight = ArrayDeque<Pair<Long, Long>>() // (arrivalUs at the sender, limit)
        val probesInFlight = ArrayDeque<Long>()            // arrivalUs at the receiver
        var lastProbeUs = -rttUs
        val tickUs = 1_000L; var sentAtOneSecond = 0L
        for (step in 0 until 3_000) { // 3 s
            now += tickUs
            if (now == 1_000_000L) sentAtOneSecond = sentTotal
            // deliver whatever has arrived after one-way delay
            while (inFlight.isNotEmpty() && inFlight.first().first <= now) { rc.onReceived(inFlight.removeFirst().second.toInt()) }
            while (probesInFlight.isNotEmpty() && probesInFlight.first() <= now) { probesInFlight.removeFirst(); rc.onSenderBlocked() }
            rc.tick(now)?.let { g -> grantsInFlight.addLast((now + rttUs / 2) to g.creditBytes) }   // cumulative limit, one-way delay
            while (grantsInFlight.isNotEmpty() && grantsInFlight.first().first <= now) { credit = maxOf(credit, grantsInFlight.removeFirst().second) }
            val want = (offered * tickUs / 1e6).toLong()
            val send = minOf(want, credit - sentTotal)
            if (send > 0) { sentTotal += send; inFlight.addLast((now + rttUs / 2) to send) }
            else if (now - lastProbeUs >= rttUs) { lastProbeUs = now; probesInFlight.addLast(now + rttUs / 2) }   // blocked: credit probe, once per RTT
        }
        val throughput = (sentTotal - sentAtOneSecond) / 2.0   // steady state: after the slow start (~3 RTT from a 10-packet window)
        assertTrue(rc.targetBytes >= 300_000, "target ${rc.targetBytes} should approach BDP 360KB")
        assertTrue(throughput > 0.9 * offered, "throughput $throughput < 90% of offered (total ${sentTotal / 3.0})")
        assertTrue(sentTotal / 3.0 > 0.7 * offered, "slow start must be over within the first second: ${sentTotal / 3.0}")
    }

    /**
     * Dead credit — gap credits, i.e. bytes the sender charged that died in flight — freezes slow-start doubling.
     * This is the F8-collapse fix: on a saturated tail-drop bottleneck the sender always looks blocked (its packets
     * leave; they die in the queue), and the ungated doubling granted the 8 MB ceiling within ~150 ms. The target
     * must FREEZE, never shrink (v0.5's loss-shrink starved the radio profiles), and resume when credit stops dying.
     */
    @Test fun deadCreditFreezesSlowStartDoublingAndHealingResumesIt() {
        val est = PathEstimator(PathId(0)).apply { onRttSample(50_000) }
        var now = 1_000L
        val rc = ReceiverCredit(est, clock = { now })
        rc.tick(now)
        // the sender drains its credit every round, but three quarters of it dies on the wire
        repeat(6) {
            val t = rc.targetBytes
            rc.onReceived((t / 4).toInt()); rc.onGapCredited((3 * t / 4).toInt())
            now += 100_000
            rc.tick(now)
        }
        val frozen = rc.targetBytes
        assertTrue(frozen <= 2 * 10L * Wire.MAX_DATAGRAM, "doubling must freeze at 75% dead credit: $frozen")
        assertTrue(frozen >= 10L * Wire.MAX_DATAGRAM, "the target holds; it never shrinks on loss")
        // the wire heals: everything charged arrives; growth resumes once the dead-credit EWMA has decayed under
        // the post-storm resume threshold (or the caution lapses)
        repeat(10) {
            rc.onReceived(rc.targetBytes.toInt())
            now += 100_000
            rc.tick(now)
        }
        assertTrue(rc.targetBytes > 4 * 10L * Wire.MAX_DATAGRAM, "growth must resume once credit stops dying: ${rc.targetBytes}")
    }

    @Test fun schedulerPrefersFasterPath() {
        val a = PathEstimator(PathId(0)).apply { onRttSample(80_000) }
        val b = PathEstimator(PathId(1)).apply { onRttSample(20_000) }
        val s = Scheduler().apply { add(a); add(b) }
        assertEquals(PathId(1), s.pick(1000))
        assertEquals(PathId(0), s.repairPathFor(PathId(1)))
    }
}
