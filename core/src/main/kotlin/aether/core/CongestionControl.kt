package aether.core

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min

/**
 * Sender-side congestion controller: a congestion window plus a pacing rate.
 *
 * Aether's primary CC is receiver-driven credit ([ReceiverCredit]/[SenderCredit]). A [SenderCc] is the
 * sender-side *fallback*: it bounds the sender before grants arrive and keeps Aether fair with loss-based
 * (CUBIC) flows on a shared bottleneck. [HybridCc] arbitrates between the two.
 *
 * Units: bytes and microseconds throughout. The caller owns bytes-in-flight accounting (so that
 * retransmission/FEC policy stays outside the controller) and reports every sent, acked and lost byte.
 */
interface SenderCc {
    /** May `bytes` more be injected with `bytesInFlight` currently outstanding? Pure: no state change. */
    fun canSend(bytesInFlight: Long, bytes: Int): Boolean
    fun onSent(bytes: Int, nowUs: Long)
    /** `rttUs` is the sample measured for the acked data (already corrected for ack delay by the caller). */
    fun onAcked(bytes: Int, rttUs: Long, nowUs: Long)
    /** Bytes declared lost (RACK / FEC-unrecoverable). Losses within one round trip form one congestion event. */
    fun onLoss(bytes: Int, nowUs: Long)
    /** An ACK carried a new ECN-CE count. */
    fun onEcnCe(nowUs: Long)
    val cwnd: Long
    val pacingRateBytesPerSec: Double
}

/**
 * CUBIC (RFC 9438) with HyStart++ (RFC 9406) slow-start exit, fast convergence, and an L4S-style gentle
 * ECN-CE reaction kept separate from the loss reaction.
 *
 * Differences from TCP CUBIC that follow from Aether's environment:
 *  - No sequence numbers cross the interface, so "all data outstanding at the reduction has been acked"
 *    (TCP's `snd_una > high_seq`) is tracked with cumulative byte counters: a recovery epoch, and a HyStart++
 *    round, end once `accounted = acked + lost` reaches the bytes sent when the epoch began.
 *  - Loss reaction: beta 0.7, at most one reduction per recovery epoch, cwnd growth frozen while recovering.
 *  - ECN-CE reaction (L4S style, like [SenderCredit]): cwnd *= 0.9, at most once per RTT, no fast
 *    convergence, growth continues; it still starts a new cubic epoch so the cut is not immediately undone.
 *  - Pacing: cwnd / srtt * 1.25 in (conservative) slow start, * 1.0 in congestion avoidance. Pacing is
 *    assumed, so HyStart++'s per-ACK growth limit L is infinite (RFC 9406 §4.2).
 *  - cwnd never drops below 2 * mss. There is no cwnd validation / idle restart (caveat).
 *
 * `hystart = false` disables the HyStart++ exit (classic slow start until loss/CE); for A/B measurement only.
 */
class CubicCc(val mss: Int = Wire.MAX_DATAGRAM, initialWindow: Int = 10, val hystart: Boolean = true) : SenderCc {
    enum class Phase { SLOW_START, CONSERVATIVE_SLOW_START, CONGESTION_AVOIDANCE }

    companion object {
        /** RFC 9438 §4.1: window growth constant, segments/s^3. */
        const val C = 0.4
        /** RFC 9438 §4.6: multiplicative decrease on loss. */
        const val BETA = 0.7
        /** L4S-style gentle decrease on ECN-CE (matches [SenderCredit]). */
        const val BETA_CE = 0.9
        /** RFC 9438 §4.3: Reno-friendly additive-increase factor, 3(1-β)/(1+β). */
        const val ALPHA = 3.0 * (1 - BETA) / (1 + BETA)
        const val MIN_WINDOW_SEGMENTS = 2
        /** RTT assumed before the first sample (same default as [ReceiverCredit]). */
        const val INITIAL_RTT_US = 50_000L
        // HyStart++ constants, RFC 9406 §4.2 recommended values.
        const val MIN_RTT_THRESH_US = 4_000L
        const val MAX_RTT_THRESH_US = 16_000L
        const val MIN_RTT_DIVISOR = 8
        const val N_RTT_SAMPLE = 8
        const val CSS_GROWTH_DIVISOR = 4
        const val CSS_ROUNDS = 5
        const val PACING_GAIN_SLOW_START = 1.25
        const val PACING_GAIN_CONGESTION_AVOIDANCE = 1.0
    }

    init {
        require(mss > 0) { "mss must be positive" }
        require(initialWindow >= MIN_WINDOW_SEGMENTS) { "initialWindow must be >= $MIN_WINDOW_SEGMENTS segments" }
    }

    private val minCwnd = (MIN_WINDOW_SEGMENTS * mss).toDouble()
    private var cwndBytes = (initialWindow.toLong() * mss).toDouble()
    private var ssthresh = Double.MAX_VALUE

    var phase = Phase.SLOW_START; private set

    // RTT state (own EWMA so the controller is self-contained; mirrors PathEstimator's constants).
    var srttUs = 0.0; private set
    var rttVarUs = 0.0; private set
    var minRttUs = Long.MAX_VALUE; private set
    private var lastRttUs = 0L

    // Cubic epoch (RFC 9438 §4.2).
    private var wMax = 0.0
    private var cwndEpoch = 0.0
    private var cwndPrior = 0.0
    private var kSec = 0.0
    private var tEpochUs = 0L
    private var wEst = 0.0

    // Cumulative accounting (bytes). Epoch/round ends are expressed against these.
    private var sentTotal = 0L
    private var accountedTotal = 0L

    // Loss recovery epoch.
    var inRecovery = false; private set
    private var recoveryEndBytes = 0L
    private var recoveryMinEndUs = 0L
    private var recoveryDeadlineUs = 0L

    // ECN-CE hold-off.
    private var ceHoldUntilUs = Long.MIN_VALUE

    // HyStart++ round state.
    private var roundEndBytes = 0L
    private var lastRoundMinRttUs = Long.MAX_VALUE
    private var currentRoundMinRttUs = Long.MAX_VALUE
    private var rttSampleCount = 0
    private var cssBaselineMinRttUs = Long.MAX_VALUE
    private var cssRounds = 0

    // Diagnostics.
    var lossReductions = 0L; private set
    var ceReductions = 0L; private set
    /** Time HyStart++ first left exponential slow start (entered CSS), or -1. */
    var slowStartExitUs = -1L; private set
    /** Time congestion avoidance was first entered, or -1. */
    var congestionAvoidanceEntryUs = -1L; private set

    override val cwnd: Long get() = cwndBytes.toLong()

    override val pacingRateBytesPerSec: Double
        get() {
            val gain = if (phase == Phase.CONGESTION_AVOIDANCE) PACING_GAIN_CONGESTION_AVOIDANCE else PACING_GAIN_SLOW_START
            return cwndBytes / (rttForPacingUs() / 1e6) * gain
        }

    override fun canSend(bytesInFlight: Long, bytes: Int): Boolean = bytesInFlight + bytes <= cwndBytes

    override fun onSent(bytes: Int, nowUs: Long) {
        if (bytes <= 0) return
        sentTotal += bytes
    }

    override fun onAcked(bytes: Int, rttUs: Long, nowUs: Long) {
        if (bytes <= 0) return
        accountedTotal += bytes
        if (rttUs > 0) updateRtt(rttUs)
        val roundEnded = accountedTotal >= roundEndBytes
        when (phase) {
            Phase.SLOW_START, Phase.CONSERVATIVE_SLOW_START -> hystartOnAck(bytes, rttUs, nowUs)
            Phase.CONGESTION_AVOIDANCE -> cubicOnAck(bytes, nowUs)
        }
        if (roundEnded) endRound(nowUs)
        maybeEndRecovery(nowUs)
    }

    override fun onLoss(bytes: Int, nowUs: Long) {
        if (bytes <= 0) return
        // Decide membership before accounting: a loss that completes the current epoch still belongs to it.
        maybeEndRecovery(nowUs)
        val sameEvent = inRecovery
        accountedTotal += bytes
        if (sameEvent) { // RFC 9438 §4.6: one reduction per round trip of data
            maybeEndRecovery(nowUs)
            return
        }
        lossReductions++
        val before = cwndBytes
        // Fast convergence, RFC 9438 §4.7.
        val newWMax = if (before < wMax) before * (1 + BETA) / 2 else before
        reduce(before * BETA, newWMax, nowUs)
        inRecovery = true
        recoveryEndBytes = sentTotal
        val horizon = rttHorizonUs()
        recoveryMinEndUs = nowUs + horizon
        recoveryDeadlineUs = nowUs + 2 * horizon
    }

    override fun onEcnCe(nowUs: Long) {
        if (nowUs < ceHoldUntilUs) return
        ceHoldUntilUs = nowUs + rttHorizonUs()
        ceReductions++
        val before = cwndBytes
        reduce(before * BETA_CE, before, nowUs)
    }

    // ---- internals -------------------------------------------------------------------------------------

    private fun updateRtt(rttUs: Long) {
        lastRttUs = rttUs
        minRttUs = min(minRttUs, rttUs)
        val s = rttUs.toDouble()
        if (srttUs == 0.0) {
            srttUs = s; rttVarUs = s / 2
        } else {
            rttVarUs = 0.75 * rttVarUs + 0.25 * abs(srttUs - s)
            srttUs = 0.875 * srttUs + 0.125 * s
        }
    }

    private fun rttForPacingUs(): Double = if (srttUs > 0.0) srttUs else INITIAL_RTT_US.toDouble()

    /** One round trip as currently experienced: the larger of smoothed and latest sample, or the initial guess. */
    private fun rttHorizonUs(): Long =
        if (srttUs > 0.0) max(srttUs.toLong(), lastRttUs) else INITIAL_RTT_US

    private fun maybeEndRecovery(nowUs: Long) {
        if (!inRecovery) return
        val accounted = accountedTotal >= recoveryEndBytes && nowUs >= recoveryMinEndUs
        if (accounted || nowUs >= recoveryDeadlineUs) inRecovery = false
    }

    /** Multiplicative decrease (RFC 9438 §4.6) into a fresh congestion-avoidance epoch. */
    private fun reduce(newCwnd: Double, newWMax: Double, nowUs: Long) {
        cwndPrior = cwndBytes
        val target = max(newCwnd, minCwnd)
        ssthresh = target
        enterCongestionAvoidance(target, newWMax, nowUs)
    }

    private fun enterCongestionAvoidance(newCwnd: Double, newWMax: Double, nowUs: Long) {
        cwndBytes = max(newCwnd, minCwnd)
        cwndEpoch = cwndBytes
        tEpochUs = nowUs
        // RFC 9438 §4.2: K = cbrt((W_max - cwnd_epoch) / C); if cwnd already exceeds W_max, K = 0 and W_max = cwnd.
        if (newWMax > cwndEpoch) {
            wMax = newWMax
            kSec = cbrt((wMax - cwndEpoch) / (C * mss))
        } else {
            wMax = cwndEpoch
            kSec = 0.0
        }
        wEst = cwndEpoch
        if (cwndPrior < cwndEpoch) cwndPrior = cwndEpoch
        if (phase != Phase.CONGESTION_AVOIDANCE) {
            phase = Phase.CONGESTION_AVOIDANCE
            if (slowStartExitUs < 0) slowStartExitUs = nowUs
            if (congestionAvoidanceEntryUs < 0) congestionAvoidanceEntryUs = nowUs
        }
        resetHystartRound()
    }

    private fun wCubic(tSec: Double): Double {
        val d = tSec - kSec
        return C * mss * d * d * d + wMax
    }

    /** RFC 9438 §4.2-4.3 window increase on a new ACK in congestion avoidance. */
    private fun cubicOnAck(bytes: Int, nowUs: Long) {
        if (inRecovery) return
        val t = (nowUs - tEpochUs).coerceAtLeast(0L) / 1e6
        // Reno-friendly estimate; alpha becomes 1 once W_est reaches cwnd_prior (RFC 9438 §4.3).
        val alpha = if (wEst >= cwndPrior) 1.0 else ALPHA
        wEst += alpha * mss * bytes / cwndBytes
        if (wCubic(t) < wEst) {
            // Reno-friendly region: track W_est (never shrink cwnd on an ACK).
            if (wEst > cwndBytes) cwndBytes = wEst
            return
        }
        // Concave / convex region: aim for W_cubic one RTT ahead, bounded to [cwnd, 1.5 cwnd].
        val rttSec = rttForPacingUs() / 1e6
        val target = wCubic(t + rttSec).coerceIn(cwndBytes, 1.5 * cwndBytes)
        cwndBytes += (target - cwndBytes) * bytes / cwndBytes
    }

    /** RFC 9406 §4.2 per-ACK processing while in slow start or conservative slow start. */
    private fun hystartOnAck(bytes: Int, rttUs: Long, nowUs: Long) {
        if (rttUs > 0) {
            currentRoundMinRttUs = min(currentRoundMinRttUs, rttUs)
            rttSampleCount++
        }
        if (inRecovery) return
        val enoughSamples = rttSampleCount >= N_RTT_SAMPLE && currentRoundMinRttUs != Long.MAX_VALUE
        if (phase == Phase.SLOW_START) {
            cwndBytes += bytes.toDouble() // L = infinity when paced
            if (hystart && enoughSamples && lastRoundMinRttUs != Long.MAX_VALUE) {
                val thresh = (lastRoundMinRttUs / MIN_RTT_DIVISOR).coerceIn(MIN_RTT_THRESH_US, MAX_RTT_THRESH_US)
                if (currentRoundMinRttUs >= lastRoundMinRttUs + thresh) {
                    cssBaselineMinRttUs = currentRoundMinRttUs
                    cssRounds = 0
                    phase = Phase.CONSERVATIVE_SLOW_START
                    if (slowStartExitUs < 0) slowStartExitUs = nowUs
                }
            }
        } else {
            cwndBytes += bytes.toDouble() / CSS_GROWTH_DIVISOR
            if (enoughSamples && currentRoundMinRttUs < cssBaselineMinRttUs) {
                // The RTT increase was spurious: resume exponential growth.
                cssBaselineMinRttUs = Long.MAX_VALUE
                phase = Phase.SLOW_START
            }
        }
        if (cwndBytes >= ssthresh) {
            cwndPrior = cwndBytes
            enterCongestionAvoidance(cwndBytes, wMax, nowUs)
        }
    }

    private fun endRound(nowUs: Long) {
        lastRoundMinRttUs = currentRoundMinRttUs
        currentRoundMinRttUs = Long.MAX_VALUE
        rttSampleCount = 0
        roundEndBytes = sentTotal
        if (phase == Phase.CONSERVATIVE_SLOW_START && ++cssRounds >= CSS_ROUNDS) {
            // RFC 9406: after CSS_ROUNDS rounds in CSS the sender enters congestion avoidance.
            ssthresh = cwndBytes
            cwndPrior = cwndBytes
            enterCongestionAvoidance(cwndBytes, cwndBytes, nowUs)
        }
    }

    private fun resetHystartRound() {
        cssBaselineMinRttUs = Long.MAX_VALUE
        cssRounds = 0
    }
}

/**
 * Arbitrates between receiver-driven credit (primary) and a sender-side fallback window.
 *
 * A send is allowed iff the credit allows it AND the fallback window allows it. [mode] reports which limiter
 * bound at the most recent [canSend] evaluation (credit wins the label when both bind, since it is the primary
 * controller); the counters record how many evaluations each limiter blocked.
 *
 * Pacing is the more restrictive of the fallback's rate and the credit-side rate. The receiver grants
 * `overcommitFrac` × BDP per RTT at the delivery rate it observes, so the sender's own ack-observed delivery
 * rate × `overcommitFrac` is the rate at which credit can be expected to arrive; sending faster only queues.
 * Until a delivery rate exists, only the fallback's rate applies. A floor of 2 * MAX_DATAGRAM per RTT keeps
 * the pacer from stalling after an app-limited period.
 *
 * Event routing: [onSent] is forwarded to both controllers; [onAcked]/[onLoss]/[onEcnCe] to the fallback.
 * Grant and Ack frames belong to the credit side and are forwarded by [onGrant]/[onAckFrame]; the owner must
 * route each frame exactly once (either through these or directly to the [SenderCredit]). [est] is read only;
 * the connection keeps feeding it.
 */
class HybridCc(
    private val est: PathEstimator,
    private val sender: SenderCredit,
    val fallback: SenderCc,
    private val overcommitFrac: Double = 1.1,
) : SenderCc {
    enum class Mode { GRANT_LIMITED, CWND_LIMITED, UNLIMITED }

    /** Limiter that bound at the last [canSend] evaluation. */
    var mode = Mode.UNLIMITED; private set
    var grantLimitedCount = 0L; private set
    var cwndLimitedCount = 0L; private set
    var unlimitedCount = 0L; private set

    override val cwnd: Long get() = fallback.cwnd

    override val pacingRateBytesPerSec: Double
        get() {
            val rttUs = if (est.srttUs > 0.0) est.srttUs else CubicCc.INITIAL_RTT_US.toDouble()
            val floor = 2.0 * Wire.MAX_DATAGRAM / (rttUs / 1e6)
            val creditRate = if (est.deliveredBytesPerSec > 0.0) est.deliveredBytesPerSec * overcommitFrac else Double.MAX_VALUE
            return max(min(fallback.pacingRateBytesPerSec, creditRate), floor)
        }

    override fun canSend(bytesInFlight: Long, bytes: Int): Boolean {
        val creditOk = sender.canSend(bytes)
        val cwndOk = fallback.canSend(bytesInFlight, bytes)
        if (!creditOk) grantLimitedCount++
        if (!cwndOk) cwndLimitedCount++
        mode = when {
            !creditOk -> Mode.GRANT_LIMITED
            !cwndOk -> Mode.CWND_LIMITED
            else -> { unlimitedCount++; Mode.UNLIMITED }
        }
        return creditOk && cwndOk
    }

    override fun onSent(bytes: Int, nowUs: Long) {
        sender.onSent(bytes)
        fallback.onSent(bytes, nowUs)
    }

    override fun onAcked(bytes: Int, rttUs: Long, nowUs: Long) = fallback.onAcked(bytes, rttUs, nowUs)
    override fun onLoss(bytes: Int, nowUs: Long) = fallback.onLoss(bytes, nowUs)
    override fun onEcnCe(nowUs: Long) = fallback.onEcnCe(nowUs)

    /** Forward a received Grant frame to the credit controller. */
    fun onGrant(g: Frame.Grant) = sender.onGrant(g)

    /** Forward a received Ack frame to the credit controller (its own ECN-CE reaction lives there). */
    fun onAckFrame(a: Frame.Ack) = sender.onAck(a)

    fun resetCounters() {
        grantLimitedCount = 0; cwndLimitedCount = 0; unlimitedCount = 0
    }
}
