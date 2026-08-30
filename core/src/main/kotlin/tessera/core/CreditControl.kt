package tessera.core

import kotlin.math.max
import kotlin.math.min

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
    /**
     * Growth cap as a multiple of the measured-real BDP (see the growth rule in [tick]). A parameter rather than a
     * constant so it can be swept without recompiling the transport: the F8b campaign measured 2x and 8x and
     * rejected both (TEST-PLAN, "F8b fix campaign"), and the value that shipped was never itself swept.
     */
    private val growthCapBdp: Int = GROWTH_CAP_BDP,
    /**
     * **Experimental, 0 = off.** A second, tighter growth cap applied once this path has shown *any* dead credit
     * ([DEAD_CREDIT_TIGHTEN]). The fixed cap is a single number serving two incompatible jobs: on a clean
     * high-RTT path it must be loose, because during slow start the measured rate is small precisely because the
     * credit is small (2x fails the bootstrap contract at 64 % of offered), and on a shallow bottleneck it must
     * be tight, because a loose cap funds a spray (8x loses 54 % of what it offers). Dead credit tells those two
     * situations apart — it is zero on the first and non-zero on the second — so the cap need not be a constant.
     * Evaluated by `CreditGrowthSweepTest`; not on by default until it has been measured on a link.
     *
     * Two triggers, both experimental, selected by [tightenOnSettledRate]:
     *  - **dead credit** ([DEAD_CREDIT_TIGHTEN]) — measured and **refuted**: on a queue deep enough to absorb the
     *    probe, the evidence arrives only once the buffer is full, by which point the target has already sprayed.
     *    `tick()`'s own design note says exactly this; the sweep put 49 % loss on it.
     *  - **a settled rate estimate** — the cap has to be loose only while the measured rate is still climbing,
     *    which is the whole reason a tight constant breaks bootstrap. Once the rate stops growing the estimate is
     *    no longer lagging and the loose cap has no remaining justification.
     */
    private val growthCapTightBdp: Int = 0,
    /** Selects the [growthCapTightBdp] trigger: true = the rate estimate has settled, false = dead credit seen. */
    private val tightenOnSettledRate: Boolean = false,
    /**
     * **Experimental, 0 = off.** Queue-delay gate on slow-start doubling: while `srtt - minRtt` exceeds
     * `max(this, minRtt/16)`, the target holds instead of doubling. The floor was minRtt/4 first and the model
     * refuted it immediately: on transcont (180 ms RTT) a FULL 585 KB queue is only 47 ms of delay, under the
     * 45 ms floor — the gate could never trigger on exactly the links that spray hardest. minRtt/16 keeps the
     * noise floor for short-RTT links (2.5 ms at a 40 ms RTT) without blinding the high-RTT case. The physics: a filling queue raises delay
     * BEFORE it drops packets — shallow and deep queues alike — so delay is the one congestion signal that
     * arrives ahead of the spray, where dead credit (the shipped gate) only arrives after it. The three refuted
     * fixes above all failed on evidence timing; this is the candidate whose evidence is early by construction.
     * Contracts it must keep: bootstrap (a clean path has no standing queue, so doubling is untouched) and
     * grant-blackout recovery (the gate can only HOLD the target, never shrink it). Modelled first in
     * `CreditGrowthSweepTest` — that model demonstrably transfers to hardware.
     */
    private val growthDelayGateUs: Long = 0,
    /**
     * **Experimental, off by default.** Once the rate estimate has settled, probe the target *additively* (one BDP
     * per growth step) instead of doubling.
     *
     * Both tight-cap triggers above failed for the same reason, and the sweep is what showed it: a ceiling only
     * binds after the target has reached it, and by then the overshoot has already been sprayed into the queue.
     * The overshoot is a property of the growth *step*, not of the ceiling — doubling from a settled operating
     * point offers a whole extra BDP of credit in one move, which a shallow queue cannot absorb. Doubling is only
     * justified while the rate estimate is still climbing, which is exactly when slow start needs it.
     */
    private val additiveWhenSettled: Boolean = false,
) {
    companion object {
        /** [deadCreditFrac] at or above this freezes slow-start doubling (see the growth rule in [tick]). */
        const val DEAD_CREDIT_FREEZE = 0.25
        /** A single window at or above this much dead credit is instant storm evidence: the EWMA needs 2-3 windows the spray does not grant. */
        const val DEAD_CREDIT_STORM = 0.5
        /** How long storm caution (gentle x1.25 probing instead of x2 doubling) lasts past the last evidence. */
        const val CAUTION_US = 2_000_000L
        /** Growth cap as a multiple of the measured-real BDP (see the growth rule in [tick]). */
        const val GROWTH_CAP_BDP = 4
        /** Dead-credit fraction at which the experimental tight cap ([growthCapTightBdp]) takes over: any real evidence at all. */
        const val DEAD_CREDIT_TIGHTEN = 0.05
        /** Rate-window growth below this multiple counts as "the estimate has settled" (see [tightenOnSettledRate]). */
        const val RATE_CLIMB_FACTOR = 1.25
        /** Held-back dead credit is released per rate window at real arrivals / this — capping credited death at ~25 % of flow. */
        const val DEAD_RELEASE_DIVISOR = 3
        /** Healthy-branch drain rate of the held-gap pool: heldGap/this per window (famine fix, see tick()). */
        const val HELD_DRAIN_DIVISOR = 8
        /** Windows with no new gap charge before the caught-up drain arms: fresh deaths mean contested, not famine. */
        const val GAP_QUIET_WINDOWS = 3
        /** A gap revealed after this many silent rate windows is an outage, not congestion: credited at once. */
        const val OUTAGE_SILENCE_WINDOWS = 3
    }
    /** The advertised cumulative limit (monotone non-decreasing). */
    private var granted = 0L
    private var received = 0L
    /** Bytes that actually arrived (excludes gap credits): what [rxBytesPerSec] and [deadCreditFrac] are built from. */
    private var realReceived = 0L
    /** Dead credit not yet handed back (see [onGapCredited]); released from [tick], health-gated. */
    private var heldGap = 0L
    private var lastRealUs = 0L
    private var cautionUntilUs = 0L
    private var target = floorBytes
    private var lastTickUs = 0L
    private var rateWindowStartUs = 0L
    private var rateWindowReal = 0L
    private var rateWindowGap = 0L
    private var caughtUpSinceWindow = false
    private var windowsSinceGap = 0
    private var lastGrowthUs = 0L
    private var lastIssued = 0L
    private var rateClimbing = true
    private var ceSinceTick = false
    private var blockedSinceTick = false
    /** EWMA of bytes actually arriving on this path (the receiver's own measurement, not the peer's acks). */
    var rxBytesPerSec = 0.0; private set
    /**
     * EWMA of the fraction of credited bytes that never arrived — gap credits over gap + real arrivals, per rate
     * window. Gap credits are bytes the sender charged that died in flight: on a lossy radio link this tracks the
     * loss rate (a few percent), and on a saturated bottleneck it is the credit vanishing into a full queue
     * (measured 50-80 % in the F8 collapse). It is the receiver's one direct, uninflatable congestion observable.
     */
    var deadCreditFrac = 0.0; private set
    val targetBytes: Long get() = target
    val outstanding: Long get() = granted - received
    /** The absolute credit limit every grant carries; 0 until the first grant was issued. */
    val limit: Long get() = granted
    /** True once a grant has been issued (the transport piggybacks [limit] on ACKs from then on). */
    val hasGranted: Boolean get() = granted > 0
    /** Bytes the sender is estimated to have charged so far (received + credited gaps). */
    val receivedBytes: Long get() = received

    fun onReceived(bytes: Int) { received += bytes; realReceived += bytes; rateWindowReal += bytes; lastRealUs = clock() }

    /**
     * Credit for bytes that never arrived (the transport's per-gap estimate when packet numbers skip). Lost bytes
     * are no longer in flight and the sender must not stall on them (Homa credits them by timeout) — but crediting
     * them *instantly* made the sliding limit a pure rate-passthrough under congestion: the faster credit died in
     * the queue, the faster the limit slid, and no target policy could bind the sender (the F8 collapse's second
     * leg, after the growth doubling). So death is credited on a delay, gated by health:
     * - a gap revealed after [OUTAGE_SILENCE_WINDOWS] of silence is an outage (handover, radio gap) — the link went
     *   away, nothing was competing for a queue: credited at once, exactly the old behaviour, so a post-blackout
     *   drain burst stays funded (F9). Congestion never looks like this: its queue delivers continuously.
     * - a gap amid continuous arrivals is credited from [tick], at most real/[DEAD_RELEASE_DIVISOR] per rate window.
     *   On a lossy radio link (a few % of flow) that is full credit one window late — Homa's timeout, literally.
     *   Under collapse it caps the limit's slide at ~1.33x the true delivery rate, which starves the overload out.
     */
    fun onGapCredited(bytes: Int) {
        val windowUs = max(if (est.minRttUs == Double.MAX_VALUE) 0.0 else est.minRttUs, 10_000.0).toLong()
        if (lastRealUs != 0L && clock() - lastRealUs > OUTAGE_SILENCE_WINDOWS * windowUs) { received += bytes }
        else { heldGap += bytes; rateWindowGap += bytes }
    }

    /**
     * A gap turned out to be reordering, not death: a late packet filled it (its own bytes went through
     * [onReceived] as real). The estimate charged in [onGapCredited] is reversed, because on a reordering link
     * (wifi-busy: 5 %) phantom gaps read as ~29 % dead credit and froze a perfectly healthy path's growth.
     */
    fun onGapFilled(bytes: Int) {
        val b = bytes.toLong()
        heldGap = max(0L, heldGap - b)
        rateWindowGap -= b   // may go negative: the fill often lands a window after the charge; the fraction clamps
    }

    /** ECN-CE was seen on an arriving packet: the target shrinks at the next tick. */
    fun onEcnCe() { ceSinceTick = true }

    /** The sender said it is blocked on credit (a credit probe): the target doubles at the next tick, as if drained. */
    fun onSenderBlocked() { blockedSinceTick = true }

    /**
     * The transport reports the receive side fully caught up: every source seen is delivered, nothing is
     * mid-reassembly. The held-gap pool's release key (see tick()): with nothing left to wait for there is no
     * reason to keep withholding died-credit from a blocked sender.
     */
    fun onCaughtUp() { caughtUpSinceWindow = true }

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
        if (rateWindowStartUs == 0L) { rateWindowStartUs = nowUs; rateWindowReal = 0; rateWindowGap = 0 }
        else if (nowUs - rateWindowStartUs >= windowUs) {
            // The rate is REAL arrivals only, per this class's own doc ("bytes actually arriving") — gap credits
            // used to leak in through `received` and inflated the BDP floor with the *offered* rate under loss.
            val inst = rateWindowReal * 1e6 / (nowUs - rateWindowStartUs)
            // 0.5/0.5, not 0.8/0.2: the growth cap rides this EWMA, and the heavier smoothing lagged a doubling
            // ramp by enough to cost the high-RTT slow start ~4 % of its first second (two windows still smooth).
            val prevRate = rxBytesPerSec
            rxBytesPerSec = if (rxBytesPerSec == 0.0) inst else 0.5 * rxBytesPerSec + 0.5 * inst
            // Is the rate estimate still climbing? While it is, it lags the truth and a tight cap pins slow start.
            rateClimbing = prevRate == 0.0 || rxBytesPerSec > prevRate * RATE_CLIMB_FACTOR
            val gap = max(0L, rateWindowGap)
            val credited = rateWindowReal + gap
            if (credited > 0) {
                val raw = gap.toDouble() / credited
                deadCreditFrac = 0.7 * deadCreditFrac + 0.3 * raw
                // storm evidence, instant or accumulated: growth stays cautious until it lapses
                if (raw >= DEAD_CREDIT_STORM || deadCreditFrac >= DEAD_CREDIT_FREEZE) cautionUntilUs = nowUs + CAUTION_US
            }
            // A negative balance (fills that landed a window after their charges — jitter reorders across window
            // boundaries) CARRIES over instead of being discarded, or the reversal never reaches the fraction and
            // pure reordering reads as ~35 % dead (measured on wifi-busy; the decay then starved a healthy link).
            rateWindowGap = min(0L, rateWindowGap)
            // a silent window (blackout / idle) updates neither: silence is not evidence in either direction.
            // Held-back dead credit: released at real/divisor while HEALTHY (fast forgiveness — an unconditional
            // full release funded a permanent (1 + 1/divisor)x overload), and at a small floor trickle while not.
            // The trickle is load-bearing: with zero release a storm deadlocked — the blocked sender produced no
            // flow, silent windows never decayed the evidence, and nothing could ever release again. The trickle
            // keeps a bounded dribble moving; its deliveries regenerate the healthy windows that unlock the rest.
            // The trickle is a full floor quantum: the repair machinery (PTO trains, tail repairs) charges credit
            // without blocking on it, and a smaller trickle was consumed entirely by that background before send()
            // ever unblocked.
            // The HEALTHY branch needs an escape for the STALL shape (the 2026-08-25 high-BDP credit famine,
            // BENCH): the sender's repair machinery can overspend the limit by megabytes (uncharged-but-
            // counted, SenderCredit's documented overshoot), and once the gaps are then repaired the dead
            // fraction decays below FREEZE while the sender is still deep in the red — healthy-but-STALLED.
            // With release = real/3 and no real arrivals, release rounds to the control-packet dribble
            // (~hundreds of B/s measured) and a multi-MB hole takes an hour: the exact deadlock the unhealthy
            // trickle was built to prevent, one branch over. So a healthy window whose real arrivals are below
            // one floor quantum — the stall shape — drains the held pool at max(floor, heldGap/HELD_DRAIN_
            // DIVISOR): a measured -5.4 MB hole refills in ~8 windows. TWO guards on the drain, both measured
            // load-bearing (BENCH "The high-BDP credit famine"): (a) draining on FLOWING healthy windows
            // re-armed the contested overload v0.9 killed — healthy/storm windows alternate under contested
            // loss and each healthy one released a pool slice that funded the next flood (LEDBAT crushed from
            // 57% of solo to 17%, drops 26%); (b) the stall shape alone (real < floor) could not tell the
            // famine from a contested-blocked sender — a blocked sender creates no gaps, so its quiet windows
            // looked identical. The discriminator is [onCaughtUp], transport-fed: the drain arms only in a
            // window where the receive side was FULLY caught up (every source delivered, nothing reassembling)
            // — the famine's exact state, and one a contested receiver is almost never in (gaps perpetually in
            // flight). And (c) STALE deaths only (windowsSinceGap): in the shallow-contested regime the
            // caught-up state recurs at the trickle and each drained slice funded a burst whose deaths
            // REFILLED the pool — a self-sustaining ~150 KB/s recycle (measured: LEDBAT at 14% of solo). The
            // famine's pool is stale by definition (the link healed long before the stall), so the drain also
            // waits for GAP_QUIET_WINDOWS windows with no new gap charge. Self-limiting either way: an
            // over-funded burst that re-kills credit both resets that counter and (if sustained) trips
            // deadCreditFrac >= FREEZE, dropping release to the bare floor.
            windowsSinceGap = if (gap > 0) 0 else windowsSinceGap + 1
            val stallBoost = if (caughtUpSinceWindow && rateWindowReal < floorBytes && windowsSinceGap >= GAP_QUIET_WINDOWS)
                max(floorBytes, heldGap / HELD_DRAIN_DIVISOR) else 0L
            val release = min(heldGap, if (deadCreditFrac < DEAD_CREDIT_FREEZE)
                max(rateWindowReal / DEAD_RELEASE_DIVISOR, stallBoost) else floorBytes)
            received += release; heldGap -= release
            rateWindowStartUs = nowUs; rateWindowReal = 0; caughtUpSinceWindow = false
        }
        val bdp = (rxBytesPerSec * rttUs / 1e6 * overcommitFrac).toLong()
        val out = granted - received
        // sender used >75% of what we gave it: credit-limited. Doubling at most once per quarter RTT (a quarter of the
        // assumed initial RTT before a sample exists): evaluated per tick while the sender waits a whole RTT for the
        // grant to arrive, the target used to double on every one of those ticks and hit the 8 MB cap within one RTT.
        val growthUs = (if (rttUs > 0.0) rttUs else PathEstimator.INITIAL_RTT_US.toDouble()) / 4
        val blocked = blockedSinceTick
        val drained = lastTickUs != 0L && (out < target / 4 || blocked) && nowUs - lastGrowthUs >= growthUs
        // ECN-CE, or sustained dead credit: both mean the path is congested NOW, and the target decays 10 % per
        // tick until the `coerceAtLeast(bdp)` floor below catches it — i.e. exactly to the healthy operating point,
        // 1.1x the measured-real-arrivals BDP. This is the shrink v0.5 could not have: v0.5 shrank on the LOSS
        // RATE, which a lossy radio link keeps high forever (it starved lte/wifi); dead-credit fraction is a
        // congestion discriminator those links never trip (~their loss rate, a few percent, vs 25 %), and outage
        // gaps bypass it entirely (see onGapCredited).
        val congested = ceSinceTick || deadCreditFrac >= DEAD_CREDIT_FREEZE
        ceSinceTick = false; blockedSinceTick = false
        // Slow-start doubling is gated by dead credit, the F8-collapse fix. "Blocked sender" alone is not evidence
        // the path can carry more: on a saturated tail-drop bottleneck the sender always looks blocked — its
        // packets leave, they just die in the queue — and the ungated doubling granted the 8 MB ceiling within
        // ~150 ms of saturation, funding a 3x-overload spray. When most of the drained credit died in flight
        // ([deadCreditFrac] >= DEAD_CREDIT_FREEZE), growth FREEZES: the target holds (never shrinks — shrinking on
        // loss was v0.5's disaster on the radio profiles, and the earlier rate-cap attempt broke grant-blackout
        // recovery precisely by cutting the target after a stall) and resumes once credit stops dying. A lossy
        // radio link never trips the gate (dead fraction ~ its loss rate, a few percent vs the 25 % threshold, and
        // burst spikes are absorbed by the EWMA); a fresh path has no dead credit at all, so bootstrap doubling is
        // untouched. Measured campaign and rejected variants: TEST-PLAN F8b.
        // Growth control, three layers (each one measured in on the F8 campaign, TEST-PLAN F8b):
        // - GROWTH_CAP_BDP x the measured-real BDP, growth-only. A deep bottleneck queue absorbs an evidence-free
        //   probe silently — dead credit appears only once the buffer is FULL, far too late — so evidence alone
        //   cannot prevent the spray; the cap can, because it never lets the target near queue capacity. The value
        //   is a compromise between two requirements that pull opposite ways, and a deterministic sweep
        //   (CreditGrowthSweepTest, 2026-08-28) puts numbers on both: slow start must outrun the lagging rate EWMA
        //   (the BDP-at-high-RTT contract — 2x delivers 64 % of offered on a clean 180 ms path and fails it), while
        //   a shallow bottleneck punishes looseness (8x loses 54 % of what it offers, 2x loses 24 %). NOTE: this
        //   comment previously argued for 8x, which is not what ships and which the campaign had already rejected
        //   (TEST-PLAN F8b) — corrected 2026-08-28. 4x has never itself been measured against a link; it is the
        //   midpoint that keeps the bootstrap contract, and the open item is a rule that does not need a midpoint.
        // - DEAD_CREDIT_STORM / FREEZE: instant and accumulated evidence freeze growth for the shallow-queue case
        //   the cap cannot see (there the queue is small and evidence IS timely).
        // - While the storm caution stands, the probe step is x1.25, not x2 — re-probing a barely-settled queue at
        //   full doubling is how each storm used to re-flood it.
        val tighten = if (tightenOnSettledRate) !rateClimbing else deadCreditFrac >= DEAD_CREDIT_TIGHTEN
        val capBdp = if (growthCapTightBdp > 0 && tighten) growthCapTightBdp else growthCapBdp
        val growthCap = if (rxBytesPerSec > 0.0) maxOf(floorBytes, capBdp * bdp) else maxBytes
        target = when {
            congested -> (target * 0.9).toLong().coerceAtLeast(floorBytes)
            drained && deadCreditFrac < DEAD_CREDIT_FREEZE
                && !(growthDelayGateUs > 0 && est.minRttUs != Double.MAX_VALUE && est.lastRttUs > 0
                     // the LAST sample, not srtt: the EWMA lags a doubling ramp by more doublings than the
                     // queue survives — on transcont the spray finished before srtt moved (model finding)
                     && maxOf(est.lastRttUs.toDouble(), est.srttUs) - est.minRttUs
                        > maxOf(growthDelayGateUs.toDouble(), est.minRttUs / 16)) -> {
                lastGrowthUs = nowUs
                val step = when {
                    nowUs < cautionUntilUs -> target * 5 / 4
                    additiveWhenSettled && !rateClimbing -> target + maxOf(floorBytes, bdp)
                    else -> target * 2
                }
                maxOf(target, step.coerceAtMost(growthCap))
            }
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
