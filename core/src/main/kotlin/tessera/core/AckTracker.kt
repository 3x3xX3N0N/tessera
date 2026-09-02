package tessera.core

import kotlin.math.max
import kotlin.math.min

/** Outcome of feeding one [Frame.Ack] to [AckTracker.onAck]. Packet-number lists are ascending. */
data class AckResult(
    val newlyAcked: List<Long>,
    val lost: List<Long>,
    /** `now - sentTime(largest)` when the ACK's largest packet was newly acknowledged; null otherwise. */
    val rttSampleUs: Long?,
    /** Fraction lost over this ACK's resolution window (newly acked + newly lost, incl. timer losses since the last ACK). */
    val lostFraction: Double,
    /** Peer receive timestamp of its largest packet, copied from the ACK (peer clock domain, never used for RTT). */
    val rxTimeUs: Long = 0,
    /** Local send time of the ACK's largest packet when it was newly acked; pair with [rxTimeUs] for OWD experiments. */
    val largestSentUs: Long? = null,
)

/**
 * Per-path ACK state machine, both directions. Single-threaded, like the rest of core.
 *
 * Receiver side ([onPacketReceived], [ackFrameIfDue], [ackTimer]): received packet numbers are kept as a bounded set
 * of ranges (≤ [MAX_RANGES]; adjacent ranges merge; the lowest range is dropped on overflow and packets below it are
 * ignored from then on) plus a cumulative ECN-CE count. An ACK is due every [ackFreq] ack-eliciting packets, at once
 * on any gap/reordering hint, after [maxAckDelayUs] with anything pending, or on demand (`force`).
 *
 * Sender side ([onPacketSent], [onAck], [lossTimer], [onLossTimer]): in-flight packets are turned into RTT samples
 * and RACK-style loss decisions, and the [PathEstimator] is fed from here so FEC redundancy, the scheduler and CC
 * adapt without anyone else measuring the path. A packet is lost once a later packet (by pn) has been acknowledged
 * and either [PACKET_THRESHOLD] or more packets beyond it were acked, or it is older than
 * [PathEstimator.lossTimeoutUs] — which covers the case where the acknowledged packet itself was sent that much
 * later. The time rule is re-evaluated by [onLossTimer] when no further ACK arrives.
 *
 * Clock domains: `nowUs` is the local monotonic clock; [Frame.Ack.rxTimeUs] is the peer's clock. RTT is
 * `now - sentTime(largest)` only; `rxTimeUs` is passed through in [AckResult] for one-way-delay experiments.
 * Not covered here: tail loss with no ACK at all needs a probe timeout (PTO) in the connection layer.
 */
class AckTracker(private val est: PathEstimator, ackFreq: Int, maxAckDelayUs: Long = 25_000) {
    companion object {
        const val MAX_RANGES = 32
        /** Reordering tolerance in packets: lost once this many later packets were acknowledged. */
        const val PACKET_THRESHOLD = 3
        /**
         * In-flight ring size (a power of two). Must exceed anything a sender can have outstanding: the credit
         * ceiling of 8 MB is under 14 000 packets at the smallest datagram, and the transport's packet ring is 8192.
         */
        const val RING = 16384
    }

    private var ackFreq = max(ackFreq, 1)
    private var maxAckDelayUs = max(maxAckDelayUs, 0)

    /**
     * Adopts a cadence the peer asked for ([Frame.AckFrequency]). Takes effect on the next decision only: anything
     * already pending stays pending, and the immediate-ACK triggers (reordering, gaps, `force`) are untouched, so a
     * peer cannot use this to suppress the feedback its own loss detection depends on.
     */
    fun setAckPolicy(freq: Int, delayUs: Long) {
        ackFreq = max(freq, 1); maxAckDelayUs = max(delayUs, 0)
    }

    // ---------------------------------------------------------------- receiver side

    private class Range(var first: Long, var last: Long)
    private val ranges = ArrayList<Range>()   // ascending, disjoint, never adjacent
    private var ignoreBelow = 0L               // everything below was dropped from tracking
    var largestReceived = -1L; private set
    private var largestRxTimeUs = 0L
    var ecnCeCount = 0L; private set
    var bytesReceived = 0L; private set
    private var elicitingSinceAck = 0
    private var oldestPendingRxUs = 0L
    private var immediateAck = false

    fun onPacketReceived(pn: Long, bytes: Int, ecnCe: Boolean, nowUs: Long, ackEliciting: Boolean = true) {
        require(pn >= 0) { "negative pn $pn" }
        bytesReceived += bytes
        if (ecnCe) ecnCeCount++
        if (pn < ignoreBelow || !insert(pn)) return   // duplicate or already-forgotten packet
        val outOfOrder = if (pn > largestReceived) {
            val gap = largestReceived >= 0 && pn > largestReceived + 1
            largestReceived = pn; largestRxTimeUs = nowUs
            gap
        } else true   // arrived late and filled a hole: tell the sender before it declares the packet lost
        if (!ackEliciting) return
        if (elicitingSinceAck++ == 0) oldestPendingRxUs = nowUs
        if (outOfOrder) immediateAck = true
    }

    /** Returns the ACK to send now, or null. `force` acks whenever anything has been received. */
    fun ackFrameIfDue(nowUs: Long, force: Boolean = false): Frame.Ack? {
        if (largestReceived < 0) return null
        if (!force && !immediateAck && elicitingSinceAck < ackFreq &&
            (elicitingSinceAck == 0 || nowUs - oldestPendingRxUs < maxAckDelayUs)) return null
        elicitingSinceAck = 0; immediateAck = false
        return Frame.Ack(est.path, largestReceived, receivedRanges(), ecnCeCount, largestRxTimeUs)
    }

    /** Absolute time at which [ackFrameIfDue] will next return an ACK without `force` (≥ `nowUs`), or null. */
    fun ackTimer(nowUs: Long): Long? = when {
        elicitingSinceAck == 0 -> null
        immediateAck || elicitingSinceAck >= ackFreq -> nowUs
        else -> max(oldestPendingRxUs + maxAckDelayUs, nowUs)
    }

    /** Tracked received ranges, largest first — the order [Frame.Ack.ranges] carries. */
    fun receivedRanges(): List<LongRange> = ranges.asReversed().map { it.first..it.last }

    /** Records pn; false if it was already present. Keeps the list sorted, merged and capped. */
    private fun insert(pn: Long): Boolean {
        var i = ranges.size - 1
        while (i >= 0 && ranges[i].first > pn + 1) i--
        if (i < 0) ranges.add(0, Range(pn, pn)) else {
            val r = ranges[i]
            when {
                pn < r.first -> {   // pn == r.first - 1: grow downward, maybe bridge to the range below
                    r.first = pn
                    if (i > 0 && ranges[i - 1].last == pn - 1) { r.first = ranges[i - 1].first; ranges.removeAt(i - 1) }
                }
                pn <= r.last -> return false
                pn == r.last + 1 -> {   // grow upward, maybe bridge to the range above
                    r.last = pn
                    if (i + 1 < ranges.size && ranges[i + 1].first == pn + 1) { r.last = ranges[i + 1].last; ranges.removeAt(i + 1) }
                }
                else -> ranges.add(i + 1, Range(pn, pn))
            }
        }
        if (ranges.size > MAX_RANGES) ignoreBelow = ranges.removeAt(0).last + 1
        return true
    }

    // ---------------------------------------------------------------- sender side

    /*
     * In-flight packets live in a ring indexed by pn, not a map: a path's packet numbers are dense and increasing,
     * so `pn and (RING - 1)` is the slot and `pnAt[slot] == pn` is presence. The previous `TreeMap<Long, Sent>`
     * allocated a `Sent` and a boxed entry per packet sent and deleted them one at a time per ack, with `headMap`
     * views on every timer tick — a top jdk-util frame and allocation site in the bulk profile (BENCH-netem, "The
     * throughput profile"). `lowest` is a cursor below which nothing is in flight, advanced lazily past acked and
     * lost slots (amortised O(1) per packet ever sent), so every scan is over the hole region only, and an ack
     * range is clamped to [lowest, largestSent], which bounds even a hostile range to one pass over the ring.
     *
     * A pn arriving for a slot that still holds an unacked packet RING pns older evicts it as lost and counts it
     * in [ringEvictions]; reaching that means the sender outran the tracker, which the credit ceiling and the
     * transport's packet-ring invariants make unreachable (see [RING]).
     */
    private val pnAt = LongArray(RING) { -1L }
    private val bytesAt = IntArray(RING)
    private val sentAt = LongArray(RING)
    private val elicitAt = BooleanArray(RING)
    private var lowest = 0L
    var largestSent = -1L; private set
    var largestAcked = -1L; private set
    /** Ack-eliciting bytes neither acknowledged nor declared lost. */
    var bytesInFlight = 0L; private set
    var cumulativeAckedBytes = 0L; private set
    var inFlightCount = 0; private set
    /** Unacked packets overwritten by a pn [RING] later, counted as lost; non-zero means an invariant broke. */
    var ringEvictions = 0L; private set
    private var timerLostSinceAck = 0

    private fun slot(pn: Long) = (pn and (RING - 1L)).toInt()
    private fun present(pn: Long) = pnAt[slot(pn)] == pn
    private fun remove(pn: Long) { val i = slot(pn); pnAt[i] = -1L; inFlightCount--; if (elicitAt[i]) bytesInFlight -= bytesAt[i] }
    private fun advanceLowest() { while (lowest <= largestSent && !present(lowest)) lowest++ }

    fun onPacketSent(pn: Long, bytes: Int, nowUs: Long, ackEliciting: Boolean) {
        require(pn > largestSent) { "packet numbers must increase on a path: $pn after $largestSent" }
        largestSent = pn
        val i = slot(pn)
        if (pnAt[i] >= 0) { remove(pnAt[i]); ringEvictions++ }
        pnAt[i] = pn; bytesAt[i] = bytes; sentAt[i] = nowUs; elicitAt[i] = ackEliciting
        inFlightCount++
        if (ackEliciting) bytesInFlight += bytes
    }

    /** Consumes an ACK: newly acked / lost packets, RTT sample, and estimator updates. A stale ACK is a no-op. */
    fun onAck(ack: Frame.Ack, nowUs: Long): AckResult {
        val ranges: List<LongRange> = if (ack.ranges.any { ack.largest in it }) ack.ranges else ack.ranges + listOf(ack.largest..ack.largest)
        val newly = ArrayList<Long>()
        var ackedBytes = 0L
        var anyEliciting = false
        var top = -1L; var topSentUs = 0L
        advanceLowest()
        for (r in ranges) {
            var pn = max(r.first, lowest); val hi = min(r.last, largestSent)
            while (pn <= hi) {
                if (present(pn)) {
                    val i = slot(pn)
                    newly += pn; ackedBytes += bytesAt[i]
                    if (elicitAt[i]) anyEliciting = true
                    if (pn > top) { top = pn; topSentUs = sentAt[i] }
                    remove(pn)
                }
                pn++
            }
        }
        if (top < 0) return AckResult(emptyList(), emptyList(), null, 0.0, ack.rxTimeUs)
        newly.sort()
        cumulativeAckedBytes += ackedBytes
        // RTT only from the ACK's own largest packet: an ACK triggered by a late-arriving hole filler would
        // otherwise inflate the sample (RFC 9002 §5.1 rule; no ack-delay subtraction, see class docs).
        val fresh = top == ack.largest
        val rtt = if (fresh && anyEliciting) max(nowUs - topSentUs, 1L) else null
        if (rtt != null) est.onRttSample(rtt)
        if (top > largestAcked) largestAcked = top
        val lost = detectLoss(nowUs)
        val lostN = lost.size + timerLostSinceAck
        timerLostSinceAck = 0
        val lostFraction = lostN.toDouble() / (newly.size + lostN)
        est.onLossObservation(lostFraction)
        est.onDelivered(cumulativeAckedBytes, nowUs)
        return AckResult(newly, lost, rtt, lostFraction, ack.rxTimeUs, if (fresh) topSentUs else null)
    }

    /** Absolute time (≥ `nowUs`) at which a still-unacked packet older than a later-acked one becomes lost, or null. */
    fun lossTimer(nowUs: Long): Long? {
        if (largestAcked < 0) return null
        advanceLowest()
        var pn = lowest
        while (pn < largestAcked) {
            if (present(pn)) return max(sentAt[slot(pn)] + est.lossTimeoutUs(), nowUs)
            pn++
        }
        return null
    }

    /** Re-runs the time-threshold check; returns packets newly declared lost (folded into the next ACK's loss observation). */
    fun onLossTimer(nowUs: Long): List<Long> {
        val lost = detectLoss(nowUs)
        timerLostSinceAck += lost.size
        return lost
    }

    /** Oldest first, below [largestAcked] only: lost by packet threshold or by time; removed as they are declared. */
    private fun detectLoss(nowUs: Long): List<Long> {
        if (largestAcked < 0) return emptyList()
        val timeout = est.lossTimeoutUs()
        val lost = ArrayList<Long>()
        advanceLowest()
        var pn = lowest
        while (pn < largestAcked) {
            if (present(pn)) {
                val i = slot(pn)
                if (largestAcked - pn >= PACKET_THRESHOLD || sentAt[i] + timeout <= nowUs) { lost += pn; remove(pn) }
            }
            pn++
        }
        return lost
    }
}
