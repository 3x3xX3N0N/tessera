package tessera.core

import java.util.TreeMap
import kotlin.math.max

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

    private class Sent(val pn: Long, val bytes: Int, val sentUs: Long, val ackEliciting: Boolean)
    private val inFlight = TreeMap<Long, Sent>()
    var largestSent = -1L; private set
    var largestAcked = -1L; private set
    /** Ack-eliciting bytes neither acknowledged nor declared lost. */
    var bytesInFlight = 0L; private set
    var cumulativeAckedBytes = 0L; private set
    val inFlightCount: Int get() = inFlight.size
    private var timerLostSinceAck = 0

    fun onPacketSent(pn: Long, bytes: Int, nowUs: Long, ackEliciting: Boolean) {
        require(pn > largestSent) { "packet numbers must increase on a path: $pn after $largestSent" }
        largestSent = pn
        inFlight[pn] = Sent(pn, bytes, nowUs, ackEliciting)
        if (ackEliciting) bytesInFlight += bytes
    }

    /** Consumes an ACK: newly acked / lost packets, RTT sample, and estimator updates. A stale ACK is a no-op. */
    fun onAck(ack: Frame.Ack, nowUs: Long): AckResult {
        val ranges: List<LongRange> = if (ack.ranges.any { ack.largest in it }) ack.ranges else ack.ranges + listOf(ack.largest..ack.largest)
        val newly = ArrayList<Long>()
        var ackedBytes = 0L
        var anyEliciting = false
        var top: Sent? = null
        for (r in ranges) {
            if (r.isEmpty()) continue
            val hit = inFlight.subMap(r.first, true, r.last, true)
            for (s in hit.values) {
                newly += s.pn; ackedBytes += s.bytes
                if (s.ackEliciting) { anyEliciting = true; bytesInFlight -= s.bytes }
                if (top == null || s.pn > top.pn) top = s
            }
            hit.clear()
        }
        val largestNewly = top ?: return AckResult(emptyList(), emptyList(), null, 0.0, ack.rxTimeUs)
        newly.sort()
        cumulativeAckedBytes += ackedBytes
        // RTT only from the ACK's own largest packet: an ACK triggered by a late-arriving hole filler would
        // otherwise inflate the sample (RFC 9002 §5.1 rule; no ack-delay subtraction, see class docs).
        val fresh = largestNewly.pn == ack.largest
        val rtt = if (fresh && anyEliciting) max(nowUs - largestNewly.sentUs, 1L) else null
        if (rtt != null) est.onRttSample(rtt)
        if (largestNewly.pn > largestAcked) largestAcked = largestNewly.pn
        val lost = detectLoss(nowUs)
        val lostN = lost.size + timerLostSinceAck
        timerLostSinceAck = 0
        val lostFraction = lostN.toDouble() / (newly.size + lostN)
        est.onLossObservation(lostFraction)
        est.onDelivered(cumulativeAckedBytes, nowUs)
        return AckResult(newly, lost, rtt, lostFraction, ack.rxTimeUs, if (fresh) largestNewly.sentUs else null)
    }

    /** Absolute time (≥ `nowUs`) at which a still-unacked packet older than a later-acked one becomes lost, or null. */
    fun lossTimer(nowUs: Long): Long? {
        if (largestAcked < 0) return null
        val oldest = inFlight.headMap(largestAcked, false).values.firstOrNull() ?: return null
        return max(oldest.sentUs + est.lossTimeoutUs(), nowUs)
    }

    /** Re-runs the time-threshold check; returns packets newly declared lost (folded into the next ACK's loss observation). */
    fun onLossTimer(nowUs: Long): List<Long> {
        val lost = detectLoss(nowUs)
        timerLostSinceAck += lost.size
        return lost
    }

    private fun detectLoss(nowUs: Long): List<Long> {
        if (largestAcked < 0) return emptyList()
        val timeout = est.lossTimeoutUs()
        val lost = ArrayList<Long>()
        val it = inFlight.headMap(largestAcked, false).values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (largestAcked - s.pn >= PACKET_THRESHOLD || s.sentUs + timeout <= nowUs) {
                lost += s.pn
                if (s.ackEliciting) bytesInFlight -= s.bytes
                it.remove()
            }
        }
        return lost
    }
}
