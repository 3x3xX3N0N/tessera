package tessera.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-path state: RTT (EWMA + variance, RACK-style), loss rate (Kalman-smoothed), loss-burst statistics, delivery
 * rate. Everything downstream (FEC ratio, scheduler, CC) reads this; nothing else measures the path.
 */
class PathEstimator(val path: PathId) {
    companion object {
        /** Used until the first RTT sample; conservative enough to clear a loss burst, cheap since 1 RTT replaces it. */
        const val INITIAL_RTT_US = 100_000L
        const val MAX_PTO_US = 2_000_000L
        /** Completed loss runs kept for the burst statistics (~3 s of bursts at 2000 pkt/s with 1 % burst events). */
        const val BURST_HISTORY = 64
        /** Kalman gain floor of the loss estimator as 2.3 sigma: what [fecRedundancy] adds on a loss-free path. */
        const val FEC_SIGMA_FACTOR = 2.3
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

    // ---- loss-burst statistics: runs of consecutive lost packet numbers (the ack gap pattern) ----
    private val bursts = IntArray(BURST_HISTORY)
    private var burstNext = 0
    private var burstCount = 0
    private var burstSum = 0L
    private var lastLostPn = Long.MIN_VALUE
    private var openRun = 0
    private var cachedP95 = 1
    /** Completed loss runs recorded so far (diagnostics). */
    var burstsRecorded = 0L; private set

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

    /**
     * A packet confirmed lost (fed in the order losses are confirmed, ascending within an ack). Consecutive packet
     * numbers form one run — the burst length in the sender's packet space, which is what a repair window has to
     * cover (a link burst of 5 that also took two of the peer's acks is a run of 3 here).
     */
    fun onLoss(pn: Long) {
        if (openRun > 0 && pn == lastLostPn + 1) openRun++
        else { if (openRun > 0) recordBurst(openRun); openRun = 1 }
        lastLostPn = pn
    }

    private fun recordBurst(len: Int) {
        if (burstCount == BURST_HISTORY) burstSum -= bursts[burstNext] else burstCount++
        bursts[burstNext] = len; burstSum += len
        burstNext = if (burstNext + 1 == BURST_HISTORY) 0 else burstNext + 1
        burstsRecorded++
        val sorted = bursts.copyOf(burstCount).also { it.sort() }
        cachedP95 = sorted[((burstCount - 1) * 0.95).toInt()]
    }

    /** Mean loss-burst length in packets (the run still open counts); 1.0 with no loss seen. */
    val burstMean: Double
        get() {
            val n = burstCount + (if (openRun > 0) 1 else 0)
            return if (n == 0) 1.0 else (burstSum + openRun).toDouble() / n
        }

    /** 95th-percentile loss-burst length in packets over the recorded runs (and the open one); 1 with no loss seen. */
    val burstP95: Int get() = max(if (burstCount == 0) 1 else cachedP95, openRun)

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
     * Probe timeout for the n-th consecutive unanswered probe (n = 0, 1, 2, ...), capped at [MAX_PTO_US]. Callers
     * keep probing with this backoff until the idle timeout; they never stop after a fixed count.
     *
     * Once the path RTT is known the first backoffs are gentle — `pto, 1.5 pto, 2 pto`, then doubling — so that a
     * loss burst that takes the data and the first probe train costs ~1.5 RTT rather than several (netem 5g low
     * rate: p999 of 375 ms was three doublings). Before the first sample (a fresh connection facing a burst, on a
     * 100 ms assumed RTT) the classic `2^n` schedule applies.
     */
    fun ptoUs(backoff: Int): Long {
        val base = lossTimeoutUs()
        val n = backoff.coerceIn(0, 10)
        val v = if (srttUs == 0.0) base shl n else when (n) {
            0 -> base
            1 -> base * 3 / 2
            2 -> base * 2
            else -> base shl (n - 1)
        }
        return min(v, MAX_PTO_US)
    }

    /** Expected completion time for `bytes` on this path; the scheduler minimizes this. */
    fun expectedCompletionUs(bytes: Int): Double {
        val bw = if (deliveredBytesPerSec > 0) deliveredBytesPerSec else 1e6
        val lossPenalty = 1 + lossRate * 2
        return (srttUs / 2 + bytes / bw * 1e6) * lossPenalty
    }

    /**
     * Proactive FEC redundancy ratio (repair symbols per source symbol), burst-aware:
     * `lossRate x (1 + burstMean / 2) + 2.3 sigma`, capped at 0.5 so goodput is never halved.
     *
     * Why the burst term: with a sliding window of W sources a burst of b consecutive losses needs b independent
     * repair equations emitted while the burst is still inside the window, and every repair emitted in that span
     * also carries the window's other ~W x lossRate unknowns, so the recovery delay is roughly (unknowns / ratio)
     * sources. Sized for the average loss alone (v0.5: `lossRate + 2.3 sigma`, 0.12 on the netem lte profile), the
     * one repair per ~8 sources could not cover its 5-packet bursts and 62 % of the lost sources fell through to a
     * round-trip re-send. Simulated with the real codec at W = 128 (bench notes, v0.6): lte (4.8 %, 5-bursts) needs
     * ~0.20, 5g-mmwave (4.8 %, 2.5-bursts) ~0.16-0.20, starlink (1.6 %, 3-bursts) ~0.12 for no round trips; this
     * formula yields 0.20 / 0.16 / 0.11 from their measured run lengths and reduces to 1.5 lossRate + 2.3 sigma
     * for random loss. Bytes: overhead = ratio x source bytes (see SPEC v0.6 for the per-profile table).
     *
     * Not scaled by queueing delay: damping the burst term where srtt - minRtt was large (the idea: shed repair bytes
     * at a full bottleneck) starved the 5g-mmwave and lte profiles, whose queueing is netem's jitter ratchet rather
     * than our load, and every loss that then needed a round trip cost more than the bytes saved (5g p99 121 -> 223 ms).
     */
    fun fecRedundancy(): Double {
        val sigma = sqrt(lossP)
        val burst = max(burstMean, 1.0)
        return (lossRate * (1 + burst / 2) + FEC_SIGMA_FACTOR * sigma).coerceIn(0.0, 0.5)
    }
}
