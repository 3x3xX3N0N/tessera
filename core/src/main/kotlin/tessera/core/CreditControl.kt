package tessera.core

import kotlin.math.max

/**
 * Receiver-driven congestion control (Homa lineage), **cumulative** form.
 *
 * The receiver advertises an absolute credit limit `L = received + target` in every [Frame.Grant]: the total number
 * of credit-charged bytes the sender may have sent on the path since it was set up. The sender keeps
 * `max(limit, L)` and may send while `sent + bytes <= limit`. Because the limit is absolute, any later grant
 * supersedes a lost one, a re-sent grant ([currentGrant]) is idempotent and the transport can piggyback the current
 * limit on every ACK at no risk of over-granting — v0.5's additive deltas could not be repeated, so a lost grant
 * stalled the sender until the silence-driven re-send timer (2 srtt).
 *
 * `received` is the receiver's estimate of what the sender has charged so far: the bytes of every ack-eliciting
 * packet that arrived plus, for each gap in the packet-number space, the average eliciting packet size (lost bytes
 * are no longer in flight; Homa credits them by timeout). The limit is `received + target` at every tick. The
 * target is the BDP at the receiver's own receive rate x min RTT x [overcommitFrac], slow-started: when the sender
 * reports that it is blocked on credit ([onSenderBlocked], a credit probe) or has drained three quarters of the
 * outstanding credit, the target doubles, at most once per quarter RTT (capped). Loss is deliberately *not* a reason to shrink
 * the target: on a lossy last mile with a standing queue (netem lte at 2000 msg/s: 5 % bursty loss, loaded RTT
 * 1.5 x min RTT) the v0.5 rule `lossRate > 2 % -> target x 0.9 per tick` pinned the target at the min-RTT BDP and
 * the sender stalled for ~half a target per stall (26 stalls, ~2 s of blocked sends per 5000-message run); random
 * loss is FEC's job, and congestion loss is the delay-gated CUBIC fallback's job (HybridCc). ECN-CE on arriving
 * packets ([onEcnCe]) is the receiver's own congestion signal and shrinks the target by 10 % per tick it was seen.
 */
class ReceiverCredit(
    private val est: PathEstimator,
    private val overcommitFrac: Double = 1.1,
    private val floorBytes: Long = 10L * Wire.MAX_DATAGRAM,
    private val maxBytes: Long = 8L shl 20,
    private val clock: () -> Long = { System.nanoTime() / 1000 },
) {
    /** The advertised cumulative limit (monotone non-decreasing). */
    private var granted = 0L
    private var received = 0L
    private var target = floorBytes
    private var lastTickUs = 0L
    private var rateWindowStartUs = 0L
    private var rateWindowReceived = 0L
    private var lastGrowthUs = 0L
    private var lastIssued = 0L
    private var ceSinceTick = false
    private var blockedSinceTick = false
    /** EWMA of bytes actually arriving on this path (the receiver's own measurement, not the peer's acks). */
    var rxBytesPerSec = 0.0; private set
    val targetBytes: Long get() = target
    val outstanding: Long get() = granted - received
    /** The absolute credit limit every grant carries; 0 until the first grant was issued. */
    val limit: Long get() = granted
    /** True once a grant has been issued (the transport piggybacks [limit] on ACKs from then on). */
    val hasGranted: Boolean get() = granted > 0
    /** Bytes the sender is estimated to have charged so far (received + credited gaps). */
    val receivedBytes: Long get() = received

    fun onReceived(bytes: Int) { received += bytes }

    /** ECN-CE was seen on an arriving packet: the target shrinks at the next tick. */
    fun onEcnCe() { ceSinceTick = true }

    /** The sender said it is blocked on credit (a credit probe): the target doubles at the next tick, as if drained. */
    fun onSenderBlocked() { blockedSinceTick = true }

    /** The current limit, re-sendable verbatim at any time (idempotent): the peer asked via a credit probe, or we saw silence. */
    fun currentGrant(): Frame.Grant = Frame.Grant(est.path, granted, 0)

    fun tick(): Frame.Grant? = tick(clock())

    /**
     * Call every ~min(srtt/4, 1ms) AND on a timer independent of receive progress. Sizes the target from the
     * receive rate x RTT (BDP), slow-starts it while the sender is credit-limited, shrinks it on ECN-CE, and issues
     * a new limit `received + target` once less than half the target is outstanding.
     */
    fun tick(nowUs: Long): Frame.Grant? {
        val rttUs = if (est.minRttUs == Double.MAX_VALUE) 0.0 else est.minRttUs // no sample yet: floor only (100 ms x rate over-granted ~600 KB)
        // receive rate over windows of an RTT (at least 10 ms): per tick, one burst of a stalled sender's backlog read as
        // megabytes per millisecond, and the BDP floor below pinned the target at the 8 MB cap for good
        val windowUs = max(rttUs, 10_000.0).toLong()
        if (rateWindowStartUs == 0L) { rateWindowStartUs = nowUs; rateWindowReceived = received }
        else if (nowUs - rateWindowStartUs >= windowUs) {
            val inst = (received - rateWindowReceived) * 1e6 / (nowUs - rateWindowStartUs)
            rxBytesPerSec = if (rxBytesPerSec == 0.0) inst else 0.8 * rxBytesPerSec + 0.2 * inst
            rateWindowStartUs = nowUs; rateWindowReceived = received
        }
        val bdp = (rxBytesPerSec * rttUs / 1e6 * overcommitFrac).toLong()
        val out = granted - received
        // sender used >75% of what we gave it: credit-limited. Doubling at most once per quarter RTT (a quarter of the
        // assumed initial RTT before a sample exists): evaluated per tick while the sender waits a whole RTT for the
        // grant to arrive, the target used to double on every one of those ticks and hit the 8 MB cap within one RTT.
        val growthUs = (if (rttUs > 0.0) rttUs else PathEstimator.INITIAL_RTT_US.toDouble()) / 4
        val blocked = blockedSinceTick
        val drained = lastTickUs != 0L && (out < target / 4 || blocked) && nowUs - lastGrowthUs >= growthUs
        val congested = ceSinceTick
        ceSinceTick = false; blockedSinceTick = false
        // KNOWN OPEN (F8 collapse, see TEST-PLAN F8b): this doubling is the collapse's funding source — "blocked
        // sender" is not evidence the path can carry more (on a saturated tail-drop bottleneck the sender always
        // looks blocked: its packets leave, they just die in the queue), and the uncapped doubling grants the 8 MB
        // ceiling within ~150 ms. Capping growth by the measured receive rate fixed the collapse outright
        // (2 x BDP-measured: 2.01 MB/s of a 2.5 MB/s link, zero drops, no CUBIC needed) but broke the two contracts
        // this class must keep: doubling must outrun the rate measurement during slow start (the rate is small
        // BECAUSE the credit is small — BDP-at-high-RTT test), and it must survive a grant blackout whose stall
        // collapses the rate EWMA (RecoveryTest.grantBlackout flaked ~50 % under an 8 x cap). Reconciling those
        // needs a redesign of this growth rule, not a constant — measured evidence and the failed variants are
        // recorded in TEST-PLAN F8b.
        target = when {
            congested -> (target * 0.9).toLong().coerceAtLeast(floorBytes)
            drained -> { lastGrowthUs = nowUs; (target * 2).coerceAtMost(maxBytes) }
            else -> target
        }.coerceAtLeast(maxOf(floorBytes, bdp)).coerceAtMost(maxBytes)
        lastTickUs = nowUs
        // The limit slides with every tick (received + target, like a TCP receive window) and rides on every ACK;
        // a standalone grant goes out once it has advanced by a quarter of the target since the last one, or when the
        // sender said it is blocked. v0.5 issued a new limit only once half the target was used up: the sender learns
        // a limit one-way delay later and has meanwhile sent another one-way delay's worth, so its room was
        // target - BDP at best and hit zero between grants once the target settled at twice the BDP (lte at
        // 2000 msg/s: 21 short stalls per 5000 messages).
        val limitNow = received + target
        if (limitNow > granted) granted = limitNow
        if (granted - lastIssued >= target / 4 || blocked) {
            lastIssued = granted
            return Frame.Grant(est.path, granted, 0)
        }
        return null
    }
}

/**
 * Sender side of the cumulative credit: [limit] is the largest advertised limit seen, [sent] the credit-charged
 * bytes sent so far; a packet may go when `sent + bytes <= limit`. Stale, duplicate and piggybacked grants are
 * no-ops by construction. The initial window is a limit of `initialWindow` bytes, which the receiver's first grant
 * (`received + target`, target >= its floor of the same size) always exceeds.
 */
class SenderCredit(initialWindow: Int = 10 * Wire.MAX_DATAGRAM) {
    /** Absolute credit limit (cumulative charged bytes allowed). */
    var limit: Long = initialWindow.toLong(); private set
    /** Cumulative credit-charged bytes sent. */
    var sent: Long = 0L; private set
    var ecnCeSeen = 0L; private set
    /** Bytes that may still be sent (negative when uncharged-but-counted packets overshot the limit). */
    val credit: Long get() = limit - sent

    fun onGrant(g: Frame.Grant) { if (g.creditBytes > limit) limit = g.creditBytes }

    /** L4S-style gentle multiplicative reaction to ECN-CE: the remaining room shrinks by 10 % (until the next grant lifts the limit again). */
    fun onAck(a: Frame.Ack) {
        if (a.ecnCe > ecnCeSeen) { limit = sent + ((limit - sent).coerceAtLeast(0) * 9 / 10); ecnCeSeen = a.ecnCe }
    }

    fun canSend(bytes: Int) = sent + bytes <= limit
    fun onSent(bytes: Int) { sent += bytes }
}

/**
 * Sender side of connection-level flow control ([Frame.MaxData]) — the receiver-memory bound the congestion credit
 * deliberately does not provide ([SenderCredit] counts charged *wire* bytes and its limit tracks the network, not
 * the peer's application). All units here are app-payload bytes. [limit] is the largest advertised limit seen; the
 * receiver computes every advert as consumed + window, so taking the max makes stale, duplicate and piggybacked
 * adverts no-ops. [charged] is the payload committed so far: each message once, up front, at its full size, plus
 * the 0-RTT first flight. Re-sends and repair are never re-charged — they carry payload already counted. [refund]
 * takes back the charge of a message whose send aborted before its fin fragment went out; such a message can never
 * complete at the receiver, so keeping the charge would leak window for the connection's life. [INITIAL_WINDOW]
 * covers the 0-RTT flight and the first flight of sends until the receiver's establishment advert arrives; the
 * transport requires the receive window to be at least this, or the initial allowance could exceed the peer's bound.
 */
class FlowSender {
    /** Absolute limit: cumulative app-payload bytes the peer allows (monotone non-decreasing). */
    var limit: Long = INITIAL_WINDOW; private set
    /** Cumulative app-payload bytes charged (committed messages + the 0-RTT flight). */
    var charged: Long = 0L; private set

    fun onMaxData(limitBytes: Long) { if (limitBytes > limit) limit = limitBytes }
    fun canCharge(bytes: Int) = charged + bytes <= limit
    fun charge(bytes: Int) { charged += bytes }
    fun refund(bytes: Int) { charged -= bytes }

    companion object {
        /** Mirrors [SenderCredit]'s initial window: never binds before the congestion credit does in the first RTT. */
        const val INITIAL_WINDOW = 10L * Wire.MAX_DATAGRAM
    }
}
