package aether.core

import java.nio.ByteBuffer
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AckPathTest {
    private val p0 = PathId(0)
    private fun ack(largest: Long, vararg ranges: LongRange, rx: Long = 0) = Frame.Ack(p0, largest, ranges.toList(), 0, rx)

    // ---------------------------------------------------------------- receiver side

    @Test fun ackRangesMergeAndCapAt32() {
        val t = AckTracker(PathEstimator(p0), ackFreq = 100)
        listOf(0L, 2L, 1L, 3L).forEach { t.onPacketReceived(it, 100, false, 1_000 + it) }   // out of order, one range
        assertEquals(listOf(0L..3L), t.receivedRanges())
        for (i in 1..40) t.onPacketReceived(10L + 2 * i, 100, false, 2_000L + i)          // 12,14,...,90: 40 isolated ranges
        val a = assertNotNull(t.ackFrameIfDue(3_000, force = true))
        assertEquals(AckTracker.MAX_RANGES, a.ranges.size)
        assertEquals(90, a.largest)
        assertEquals(90L..90L, a.ranges.first())                                           // largest first
        assertEquals(28L..28L, a.ranges.last())                                            // the 9 lowest ranges were dropped
        assertTrue(a.ranges.zipWithNext().all { (hi, lo) -> hi.first > lo.last + 1 })      // descending, disjoint, non-adjacent
        t.onPacketReceived(5, 100, false, 4_000)                                           // below the forgotten floor: ignored
        assertEquals(AckTracker.MAX_RANGES, t.receivedRanges().size)
        assertTrue(t.receivedRanges().none { 5L in it })
        t.onPacketReceived(27, 100, false, 4_001)                                          // bridges 28 and 26? 26 was dropped: only 27..28
        assertEquals(27L..28L, t.receivedRanges().last())
        // a hole filler bridges its two neighbours into one range
        t.onPacketReceived(89, 100, false, 4_002)
        assertEquals(88L..90L, t.receivedRanges().first())
        assertEquals(AckTracker.MAX_RANGES - 1, t.receivedRanges().size)
        // Frame.Ack carries ≤32 ranges in one byte and survives the codec
        val buf = ByteBuffer.allocate(512); a.write(buf); buf.flip()
        assertEquals(a, FrameCodec.read(buf))
    }

    @Test fun ackOnGapImmediatelyAndEveryAckFreqOtherwise() {
        val t = AckTracker(PathEstimator(p0), ackFreq = 3)
        assertNull(t.ackFrameIfDue(0))
        t.onPacketReceived(0, 100, false, 1); assertNull(t.ackFrameIfDue(1))
        t.onPacketReceived(1, 100, true, 2); assertNull(t.ackFrameIfDue(2))
        t.onPacketReceived(2, 100, false, 3)
        val a1 = assertNotNull(t.ackFrameIfDue(3))                                         // third ack-eliciting packet
        assertEquals(2, a1.largest); assertEquals(listOf(0L..2L), a1.ranges); assertEquals(3, a1.rxTimeUs); assertEquals(1, a1.ecnCe)
        assertNull(t.ackFrameIfDue(3))                                                     // counter reset
        t.onPacketReceived(3, 100, false, 4); assertNull(t.ackFrameIfDue(4))
        t.onPacketReceived(5, 100, false, 6)
        val a2 = assertNotNull(t.ackFrameIfDue(6))                                         // gap: immediate
        assertEquals(5, a2.largest); assertEquals(listOf(5L..5L, 0L..3L), a2.ranges); assertEquals(6, a2.rxTimeUs)
        t.onPacketReceived(4, 100, false, 7)
        val a3 = assertNotNull(t.ackFrameIfDue(7))                                         // late hole filler: immediate
        assertEquals(listOf(0L..5L), a3.ranges); assertEquals(6, a3.rxTimeUs)              // rxTime is still the largest packet's
        t.onPacketReceived(6, 100, false, 8); t.onPacketReceived(7, 100, false, 9)
        assertNull(t.ackFrameIfDue(9))
        t.onPacketReceived(4, 100, false, 10)                                              // duplicate: not ack-eliciting
        assertNull(t.ackFrameIfDue(10))
        t.onPacketReceived(8, 100, false, 11, ackEliciting = false)                        // pure ACK: recorded, never triggers
        assertNull(t.ackFrameIfDue(11))
        assertEquals(listOf(0L..8L), t.receivedRanges())
        assertNotNull(t.ackFrameIfDue(12, force = true))
        assertNull(t.ackFrameIfDue(12))
        assertEquals(8, t.ackFrameIfDue(13, force = true)?.largest)                        // force always acks what was received
    }

    @Test fun delayedAckFiresAfterMaxAckDelay() {
        val t = AckTracker(PathEstimator(p0), ackFreq = 2, maxAckDelayUs = 25_000)
        assertNull(t.ackTimer(0))
        t.onPacketReceived(0, 100, false, 1_000)
        assertEquals(26_000, t.ackTimer(1_000))
        assertNull(t.ackFrameIfDue(25_999))
        assertNotNull(t.ackFrameIfDue(26_000))
        assertNull(t.ackTimer(26_000))
        t.onPacketReceived(1, 100, false, 30_000); t.onPacketReceived(2, 100, false, 30_001)
        assertEquals(30_001, t.ackTimer(30_001))                                           // ackFreq reached: due now
    }

    // ---------------------------------------------------------------- sender side

    @Test fun rttSampleFromLargestNewlyAcked() {
        val est = PathEstimator(p0); val t = AckTracker(est, ackFreq = 2)
        t.onPacketSent(0, 1200, 1_000, true); t.onPacketSent(1, 1200, 2_000, true)
        assertEquals(2400, t.bytesInFlight)
        val r = t.onAck(ack(1, 0L..1L, rx = 555), nowUs = 22_000)
        assertEquals(listOf(0L, 1L), r.newlyAcked); assertTrue(r.lost.isEmpty()); assertEquals(0.0, r.lostFraction)
        assertEquals(20_000, r.rttSampleUs); assertEquals(20_000.0, est.srttUs)
        assertEquals(555, r.rxTimeUs); assertEquals(2_000, r.largestSentUs)
        assertEquals(0, t.inFlightCount); assertEquals(0, t.bytesInFlight); assertEquals(2400, t.cumulativeAckedBytes)
        val dup = t.onAck(ack(1, 0L..1L), 23_000)                                          // stale ACK: no-op
        assertTrue(dup.newlyAcked.isEmpty()); assertNull(dup.rttSampleUs); assertEquals(20_000.0, est.srttUs)
        t.onPacketSent(2, 100, 30_000, true); t.onPacketSent(3, 100, 31_000, true)
        assertEquals(10_000, t.onAck(ack(3, 3L..3L), 41_000).rttSampleUs)
        val late = t.onAck(ack(3, 2L..3L), 42_000)                                         // largest not newly acked: no sample
        assertEquals(listOf(2L), late.newlyAcked); assertNull(late.rttSampleUs); assertNull(late.largestSentUs)
        assertFailsWith<IllegalArgumentException> { t.onPacketSent(3, 100, 50_000, true) } // pn must increase
    }

    @Test fun lossByPacketThresholdThenByTimer() {
        val est = PathEstimator(p0); val t = AckTracker(est, ackFreq = 2)
        for (pn in 0L..4L) t.onPacketSent(pn, 1000, pn, true)                              // sent 1µs apart
        assertNull(t.lossTimer(5))                                                         // nothing acked yet: no RACK timer (PTO territory)
        val r = t.onAck(ack(4, 4L..4L), nowUs = 10_000)
        assertEquals(listOf(4L), r.newlyAcked)
        assertEquals(listOf(0L, 1L), r.lost)                                               // 4 and 3 packets behind; 2 and 3 within tolerance
        assertEquals(2.0 / 3, r.lostFraction, 1e-12)
        assertEquals(2, t.inFlightCount); assertEquals(2000, t.bytesInFlight); assertEquals(4, t.largestAcked)
        assertTrue(est.lossRate > 0.5, "lossRate=${est.lossRate}")
        val r2 = t.onAck(ack(4, 3L..4L), 10_500)                                           // 3 arrives late: 2 is now 2 behind, still not lost
        assertEquals(listOf(3L), r2.newlyAcked); assertTrue(r2.lost.isEmpty())
        val due = assertNotNull(t.lossTimer(10_500))
        assertTrue(due > 10_500)
        assertTrue(t.onLossTimer(due - 1).isEmpty())
        assertEquals(listOf(2L), t.onLossTimer(due))                                       // time threshold via the timer
        assertEquals(0, t.inFlightCount); assertEquals(0, t.bytesInFlight); assertNull(t.lossTimer(due))
        t.onPacketSent(5, 1000, 40_000, true)
        val r3 = t.onAck(ack(5, 5L..5L), 50_000)
        assertEquals(0.5, r3.lostFraction)                                                 // timer loss folded into the next ACK window
    }

    @Test fun lossByTimeThresholdOnAck() {
        val est = PathEstimator(p0); val t = AckTracker(est, ackFreq = 2)
        var now = 0L; var pn = 0L
        repeat(10) {                                                                       // settle srtt ≈ 10 ms
            t.onPacketSent(pn, 1000, now, true); now += 10_000
            assertEquals(10_000, t.onAck(ack(pn, pn..pn), now).rttSampleUs)
            pn++; now += 1_000
        }
        val timeout = est.lossTimeoutUs()
        assertTrue(timeout in 10_000L..20_000L, "timeout=$timeout")
        val a = pn++; val b = pn++
        t.onPacketSent(a, 1000, now, true)
        t.onPacketSent(b, 1000, now + 50_000, true)                                       // b sent well over a loss timeout after a
        val r = t.onAck(ack(b, b..b), now + 60_000)
        assertEquals(listOf(b), r.newlyAcked)
        assertEquals(listOf(a), r.lost)                                                    // only 1 packet behind, but too old
        assertEquals(0.5, r.lostFraction)
        assertEquals(0, t.inFlightCount)
    }

    @Test fun estimatorLossRateRisesUnderLoss() {
        fun run(dropEvery: Int): PathEstimator {
            val est = PathEstimator(p0)
            val tx = AckTracker(est, ackFreq = 2); val rx = AckTracker(PathEstimator(p0), ackFreq = 2)
            var now = 0L; var pn = 0L
            repeat(40) {
                repeat(10) {
                    tx.onPacketSent(pn, 1200, now, true)
                    if (dropEvery == 0 || pn % dropEvery != dropEvery - 1L) rx.onPacketReceived(pn, 1200, false, now + 5_000)
                    pn++; now += 1
                }
                now += 10_000
                tx.onAck(assertNotNull(rx.ackFrameIfDue(now, force = true)), now)
                now += 1_000
            }
            return est
        }
        val clean = run(0); val lossy = run(5)
        assertEquals(0.0, clean.lossRate)
        assertTrue(lossy.lossRate > 0.1, "lossRate=${lossy.lossRate}")
        assertTrue(lossy.fecRedundancy() > clean.fecRedundancy())
        assertTrue(clean.deliveredBytesPerSec > 0)
        assertEquals(10_000.0, clean.srttUs, 10.0)
    }

    // ---------------------------------------------------------------- path validation

    @Test fun amplificationLimitBlocksFourthSendUntilValidated() {
        val pv = PathValidation(p0, Random(1))
        assertFalse(pv.canSend(1))                                                         // nothing received: nothing may be sent
        pv.onReceived(1000)
        repeat(3) { assertTrue(pv.canSend(1000)); pv.onSent(1000) }
        assertFalse(pv.canSend(1000))                                                      // 4th would exceed 3x
        assertFalse(pv.validated)
        val c = pv.challenge()
        assertEquals(p0, c.path)
        assertTrue(pv.onResponse(PathResponse(c.path, c.nonce)))
        assertTrue(pv.validated)
        assertTrue(pv.canSend(1_000_000))
    }

    @Test fun migrationResetsValidationAndBudget() {
        val pv = PathValidation(PathId(2), Random(7), address = "10.0.0.1:4433")
        pv.markValidated(); pv.onReceived(10); pv.onSent(1_000_000)
        val old = pv.challenge()
        pv.onMigration("10.0.0.1:4433")                                                    // same address: not a migration
        assertTrue(pv.validated)
        pv.onMigration("10.0.0.2:4433")
        assertEquals("10.0.0.2:4433", pv.address)
        assertFalse(pv.validated); assertEquals(0, pv.outstandingChallenges)
        assertFalse(pv.canSend(1))                                                         // fresh budget: nothing received yet
        pv.onReceived(100)
        assertTrue(pv.canSend(300)); assertFalse(pv.canSend(301))
        assertFalse(pv.onResponse(old.nonce))                                              // pre-migration nonce must not validate the new address
        assertFalse(pv.validated)
    }

    @Test fun challengeResponseNonceMismatchRejected() {
        val pv = PathValidation(p0, Random(3))
        val c1 = pv.challenge()
        assertFalse(pv.onResponse(c1.nonce xor 1)); assertFalse(pv.validated)
        val c2 = pv.challenge(); val c3 = pv.challenge(); val c4 = pv.challenge()
        assertEquals(PathValidation.MAX_OUTSTANDING, pv.outstandingChallenges)             // c1 evicted
        assertTrue(setOf(c1.nonce, c2.nonce, c3.nonce, c4.nonce).size == 4)
        assertFalse(pv.onResponse(c1.nonce))
        assertFalse(pv.onResponse(PathResponse(PathId(9), c3.nonce)))                     // wrong path
        assertTrue(pv.onResponse(c3.nonce))                                                // any outstanding nonce validates
        assertTrue(pv.validated); assertEquals(0, pv.outstandingChallenges)
        assertFalse(pv.onResponse(c4.nonce))                                               // consumed with validation
    }

    @Test fun pathResponseFrameRoundTrip() {
        val resp = PathResponse(PathId(3), 0x1234_5678_9ABC_DEF0L)
        val f: Frame = resp                                                                // member of the sealed Frame hierarchy
        val buf = ByteBuffer.allocate(32); f.write(buf); buf.flip()
        assertEquals(PathResponse.TYPE, buf.get(0).toInt()); assertEquals(10, buf.remaining())
        assertEquals(resp, PathResponse.read(buf))
        buf.rewind()
        assertFailsWith<IllegalArgumentException> { FrameCodec.read(buf) }                 // codec integration pending
        buf.rewind(); buf.put(0, 0x05)
        assertFailsWith<IllegalArgumentException> { PathResponse.read(buf) }
    }
}
