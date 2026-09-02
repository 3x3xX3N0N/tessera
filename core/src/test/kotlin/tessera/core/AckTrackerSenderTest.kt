package tessera.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sender side of [AckTracker], pinned directly. Until 2026-09-02 it was covered only through the transport's
 * recovery tests; the in-flight set then moved from a `TreeMap<Long, Sent>` to a pn-indexed ring for throughput
 * (BENCH-netem, "The throughput profile"), and every semantic the map version had is asserted here so the ring
 * cannot drift from it: newly-acked packets ascending, byte accounting, an RTT sample only from a freshly acked
 * largest, packet-threshold loss oldest first, time loss through [AckTracker.lossTimer] and
 * [AckTracker.onLossTimer], a stale ACK as a no-op, a hostile range clamped to one ring pass, and the eviction
 * counter that the credit invariants are supposed to keep at zero.
 *
 * Time-loss thresholds are read back from the estimator rather than assumed, so the assertions hold whatever
 * the loss timeout's floor is.
 */
class AckTrackerSenderTest {
    private val p0 = PathId(0)
    private fun ack(largest: Long, vararg ranges: LongRange, rx: Long = 0) = Frame.Ack(p0, largest, ranges.toList(), 0, rx)
    private fun tracker() = PathEstimator(p0).let { it to AckTracker(it, ackFreq = 100) }

    @Test fun acksRemoveInFlightSortNewlyAndAccountBytes() {
        val (_, t) = tracker()
        for (pn in 1L..5L) t.onPacketSent(pn, 100, 1_000 + pn, ackEliciting = true)
        assertEquals(5, t.inFlightCount); assertEquals(500, t.bytesInFlight); assertEquals(5, t.largestSent)
        // ranges arrive largest-first on the wire; the result is ascending regardless
        val r = t.onAck(ack(5, 4L..5L, 1L..2L, rx = 77), nowUs = 2_000)
        assertEquals(listOf(1L, 2L, 4L, 5L), r.newlyAcked)
        assertEquals(1, t.inFlightCount); assertEquals(100, t.bytesInFlight); assertEquals(400, t.cumulativeAckedBytes)
        assertEquals(5, t.largestAcked)
        assertEquals(2_000 - 1_005, r.rttSampleUs, "RTT is now minus the send time of the ACK's own largest packet")
        assertEquals(1_005, r.largestSentUs); assertEquals(77, r.rxTimeUs)
        // pn 3 is two behind the largest ack: not lost by packet threshold, whatever the time rule says
        assertTrue(r.lost.all { it == 3L }, "only pn 3 could be declared lost here, got ${r.lost}")
    }

    @Test fun packetThresholdLossIsOldestFirstAndBelowLargestAckedOnly() {
        val (est, t) = tracker()
        for (pn in 1L..6L) t.onPacketSent(pn, 10, 1_000, ackEliciting = true)   // all sent at once: time cannot separate them
        val r = t.onAck(ack(6, 6L..6L), nowUs = 1_001)
        assertEquals(listOf(6L), r.newlyAcked)
        val timeLossToo = 1_000 + est.lossTimeoutUs() <= 1_001
        val expected = if (timeLossToo) listOf(1L, 2L, 3L, 4L, 5L) else listOf(1L, 2L, 3L)
        assertEquals(expected, r.lost, "packets >= PACKET_THRESHOLD behind the largest ack are lost, oldest first")
        assertEquals(10L * (6 - 1 - expected.size), t.bytesInFlight)
        assertEquals(6 - 1 - expected.size, t.inFlightCount)
        assertEquals(expected.size.toDouble() / (1 + expected.size), r.lostFraction)
    }

    @Test fun timeLossGoesThroughTheLossTimer() {
        val (est, t) = tracker()
        for (pn in 1L..3L) t.onPacketSent(pn, 10, 5_000, ackEliciting = true)
        t.onAck(ack(3, 3L..3L), nowUs = 5_001)                 // pns 1 and 2 are within the packet threshold
        val timeout = est.lossTimeoutUs()
        if (5_000 + timeout <= 5_001) return                   // a zero-floor estimator declared them at the ack already
        assertEquals(2, t.inFlightCount)
        val due = assertNotNull(t.lossTimer(5_001), "two packets older than an acked one arm the loss timer")
        assertEquals(5_000 + timeout, due)
        assertEquals(emptyList(), t.onLossTimer(due - 1), "not yet")
        assertEquals(listOf(1L, 2L), t.onLossTimer(due), "both cross the time threshold together, oldest first")
        assertEquals(0, t.inFlightCount); assertEquals(0, t.bytesInFlight)
        assertNull(t.lossTimer(due), "nothing left below the largest ack")
        // the timer losses are folded into the next ACK's loss fraction
        t.onPacketSent(4, 10, due, ackEliciting = true)
        val r = t.onAck(ack(4, 4L..4L), nowUs = due + 1)
        assertEquals(2.0 / 3.0, r.lostFraction)
    }

    @Test fun aStaleAckIsANoOpAndAnUnfreshLargestGivesNoRtt() {
        val (_, t) = tracker()
        for (pn in 1L..4L) t.onPacketSent(pn, 10, 100 + pn, ackEliciting = true)
        val first = t.onAck(ack(4, 4L..4L), nowUs = 200)
        // pn 1 is PACKET_THRESHOLD behind the acked 4: declared lost right here, as the map version did
        assertEquals(listOf(1L), first.lost)
        val stale = t.onAck(ack(4, 4L..4L, rx = 9), nowUs = 300)
        assertEquals(AckResult(emptyList(), emptyList(), null, 0.0, 9), stale)
        // largest 4 is already acked; this ACK newly acks what is still in flight below it (2 and 3 — 1 is gone):
        // no RTT sample, no largestSentUs
        val late = t.onAck(ack(4, 1L..4L), nowUs = 400)
        assertEquals(listOf(2L, 3L), late.newlyAcked)
        assertNull(late.rttSampleUs); assertNull(late.largestSentUs)
        assertEquals(0, t.inFlightCount); assertEquals(0, t.bytesInFlight)
    }

    @Test fun nonElicitingPacketsNeitherCountAsBytesInFlightNorGiveAnRttAlone() {
        val (_, t) = tracker()
        t.onPacketSent(1, 50, 1_000, ackEliciting = false)
        assertEquals(0, t.bytesInFlight); assertEquals(1, t.inFlightCount)
        val r = t.onAck(ack(1, 1L..1L), nowUs = 1_500)
        assertEquals(listOf(1L), r.newlyAcked); assertNull(r.rttSampleUs, "an ACK of nothing ack-eliciting is not an RTT sample")
        assertEquals(50, t.cumulativeAckedBytes)
    }

    @Test fun aHostileRangeIsClampedToWhatIsInFlight() {
        val (_, t) = tracker()
        for (pn in 1L..8L) t.onPacketSent(pn, 10, 1_000 + pn, ackEliciting = true)
        val r = t.onAck(ack(8, 0L..Long.MAX_VALUE / 2), nowUs = 2_000)   // far wider than the ring
        assertEquals((1L..8L).toList(), r.newlyAcked)
        assertEquals(0, t.inFlightCount); assertEquals(0, t.bytesInFlight)
    }

    @Test fun overrunningTheRingEvictsTheOldestAsLostAndCountsIt() {
        val (_, t) = tracker()
        for (pn in 0L until AckTracker.RING) t.onPacketSent(pn, 1, pn, ackEliciting = true)
        assertEquals(AckTracker.RING, t.inFlightCount); assertEquals(0, t.ringEvictions)
        t.onPacketSent(AckTracker.RING.toLong(), 1, AckTracker.RING.toLong(), ackEliciting = true)   // lands on pn 0's slot
        assertEquals(1, t.ringEvictions)
        assertEquals(AckTracker.RING, t.inFlightCount, "the evicted packet left, the new one arrived")
        assertEquals(AckTracker.RING.toLong(), t.bytesInFlight)
        val r = t.onAck(ack(AckTracker.RING.toLong(), 0L..0L, AckTracker.RING.toLong()..AckTracker.RING.toLong()), nowUs = 100_000)
        assertEquals(listOf(AckTracker.RING.toLong()), r.newlyAcked, "pn 0 is gone; acking it again is a no-op")
    }
}
