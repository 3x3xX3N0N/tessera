package aether.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-path state: RTT (EWMA + variance, RACK-style), loss rate (Kalman-smoothed), delivery rate.
 * Everything downstream (FEC ratio, scheduler, CC) reads this; nothing else measures the path.
 */
class PathEstimator(val path: PathId) {
    companion object {
        /** Used until the first RTT sample; conservative enough to clear a loss burst, cheap since 1 RTT replaces it. */
        const val INITIAL_RTT_US = 100_000L
        const val MAX_PTO_US = 2_000_000L
    }
    var srttUs = 0.0; private set
    var rttVarUs = 0.0; private set
    var minRttUs = Double.MAX_VALUE; private set
    var lossRate = 0.0; private set
    private var lossP = 1.0
    private val q = 1e-4
    private val r = 1e-2
    var deliveredBytesPerSec = 0.0; private set
    private var lastDeliveryUs = 0L
    private var lastDeliveredBytes = 0L

    fun onRttSample(rttUs: Long) {
        val s = rttUs.toDouble()
        minRttUs = minOf(minRttUs, s)
        if (srttUs == 0.0) { srttUs = s; rttVarUs = s / 2 } else {
            rttVarUs = 0.75 * rttVarUs + 0.25 * abs(srttUs - s)
            srttUs = 0.875 * srttUs + 0.125 * s
        }
    }

    /** Observation: fraction lost in the last ACK window. */
    fun onLossObservation(lostFrac: Double) {
        lossP += q
        val k = lossP / (lossP + r)
        lossRate += k * (lostFrac - lossRate)
        lossP *= (1 - k)
    }

    fun onDelivered(cumulativeBytes: Long, nowUs: Long) {
        if (lastDeliveryUs != 0L && nowUs > lastDeliveryUs) {
            val inst = (cumulativeBytes - lastDeliveredBytes) * 1e6 / (nowUs - lastDeliveryUs)
            deliveredBytesPerSec = if (deliveredBytesPerSec == 0.0) inst else 0.8 * deliveredBytesPerSec + 0.2 * inst
        }
        lastDeliveryUs = nowUs; lastDeliveredBytes = cumulativeBytes
    }

    /**
     * RACK-style reordering-tolerant loss timer; never below 1ms. Before the first RTT sample it returns
     * [INITIAL_RTT_US] — the 1ms floor alone made fresh connections fire all tail-loss probes inside one loss
     * burst (found on the netem lte profile) and then go silent.
     */
    fun lossTimeoutUs(): Long = if (srttUs == 0.0) INITIAL_RTT_US else max((srttUs + 4 * rttVarUs).toLong(), 1_000L)

    /**
     * Probe timeout for the n-th consecutive unanswered probe (n = 0, 1, 2, ...): lossTimeout * 2^n, capped.
     * Callers keep probing with this backoff until the idle timeout; they never stop after a fixed count.
     */
    fun ptoUs(backoff: Int): Long = (lossTimeoutUs() shl backoff.coerceIn(0, 10)).coerceAtMost(MAX_PTO_US)

    /** Expected completion time for `bytes` on this path; the scheduler minimizes this. */
    fun expectedCompletionUs(bytes: Int): Double {
        val bw = if (deliveredBytesPerSec > 0) deliveredBytesPerSec else 1e6
        val lossPenalty = 1 + lossRate * 2
        return (srttUs / 2 + bytes / bw * 1e6) * lossPenalty
    }

    /** FEC redundancy ratio: cover ~p99 of estimated loss; capped so we never halve goodput. */
    fun fecRedundancy(): Double {
        val sigma = sqrt(lossP)
        return (lossRate + 2.3 * sigma).coerceIn(0.0, 0.5)
    }
}
