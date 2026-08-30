package tessera.core

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A deterministic, wall-clock-free sweep of [ReceiverCredit]'s growth rule.
 *
 * The growth rule is the project's top open defect (TEST-PLAN, "The named funding source"): slow-start doubling is
 * funded by the *send* rate, which a tail-drop bottleneck inflates — the sender always looks blocked, because its
 * packets leave and then die in the queue. The F8b campaign measured two candidate caps and rejected both, and
 * every one of those measurements was a real-time netem run whose variance the file records at up to 17x.
 *
 * It does not have to be measured that way. `ReceiverCredit` takes its clock as a parameter and has no other
 * wall-clock dependency, so the rule can be driven under a virtual clock against a modelled bottleneck: same
 * inputs, same outputs, every time, and a sweep costs milliseconds instead of an evening. What that cannot tell
 * you is whether the *model* is the network — this is a model of a tail-drop queue, not a network — so what it is
 * used for here is the question it can answer honestly: **given this bottleneck, does the rule overshoot?**
 *
 * The model, deliberately crude and stated rather than hidden:
 *  - a bottleneck of [Link.capacityBps] with a [Link.queueBytes] tail-drop queue;
 *  - a sender that sends whatever credit allows, immediately (the F8b shape: no pacing, no CUBIC);
 *  - propagation of half an RTT each way, so a grant takes an RTT to change anything;
 *  - bytes that overflow the queue are dropped and later credited as gaps, which is what the transport does.
 */
class CreditGrowthSweepTest {

    private class Link(val capacityBps: Long, val queueBytes: Long, val rttUs: Long)

    private class Result(val cap: Int, val deliveredBytes: Long, val droppedBytes: Long, val peakTargetBytes: Long,
                         val finalTargetBytes: Long, val offeredBytes: Long) {
        val lossFrac get() = if (offeredBytes > 0) droppedBytes.toDouble() / offeredBytes else 0.0
        val goodputBps get() = deliveredBytes
        override fun toString() = "cap=${cap}x delivered=${deliveredBytes / 1024}KB dropped=${droppedBytes / 1024}KB " +
            "loss=${"%.1f".format(100 * lossFrac)}% peakTarget=${peakTargetBytes / 1024}KB finalTarget=${finalTargetBytes / 1024}KB"
    }

    /** Drives one arm for [durationUs] of virtual time and returns what the rule did. */
    private fun run(link: Link, cap: Int, durationUs: Long = 10_000_000L, mss: Int = 1200, tight: Int = 0,
                    onSettledRate: Boolean = false, additive: Boolean = false, delayGateUs: Long = 0): Result {
        var nowUs = 0L
        val est = PathEstimator(PathId(0))
        est.onRttSample(link.rttUs)
        val rc = ReceiverCredit(est, clock = { nowUs }, growthCapBdp = cap, growthCapTightBdp = tight,
                                tightenOnSettledRate = onSettledRate, additiveWhenSettled = additive, growthDelayGateUs = delayGateUs)

        val tickUs = max(1_000L, link.rttUs / 4)
        var queued = 0L                       // bytes in the bottleneck queue
        var sentTotal = 0L                    // bytes the sender has charged against credit
        var delivered = 0L; var dropped = 0L; var offered = 0L
        var peakTarget = 0L
        // in-flight pipelines: bytes arriving at the receiver, and grants arriving at the sender
        val arrivals = ArrayDeque<Pair<Long, Long>>()   // (dueUs, bytes)
        val drops = ArrayDeque<Pair<Long, Long>>()      // (dueUs, bytes) -> credited as gaps
        var senderLimit = 0L

        while (nowUs < durationUs) {
            // 1. sender: send everything credit allows, in mss units, straight into the queue
            var room = senderLimit - sentTotal
            var blocked = false
            while (room >= mss) {
                offered += mss.toLong(); sentTotal += mss.toLong(); room -= mss.toLong()
                if (queued + mss <= link.queueBytes) queued += mss.toLong()
                else { dropped += mss.toLong(); drops.addLast(nowUs + 2 * link.rttUs to mss.toLong()) }
            }
            if (senderLimit - sentTotal < mss) blocked = true

            // 2. bottleneck drains at capacity over one tick; what leaves arrives half an RTT later.
            // The estimator sees queue delay in its RTT samples — rtt = base + backlog/capacity — which is what
            // any real sample through this queue would carry, and what the delay-gate candidate reads.
            val drain = min(queued, link.capacityBps * tickUs / 8_000_000L)
            if (drain > 0) { queued -= drain; arrivals.addLast(nowUs + link.rttUs / 2 to drain) }
            est.onRttSample(link.rttUs + queued * 8_000_000L / link.capacityBps)

            // 3. receiver: arrivals and gap credits that are due
            while (arrivals.isNotEmpty() && arrivals.first().first <= nowUs) {
                val (_, b) = arrivals.removeFirst(); delivered += b; rc.onReceived(b.toInt())
            }
            while (drops.isNotEmpty() && drops.first().first <= nowUs) {
                val (_, b) = drops.removeFirst(); rc.onGapCredited(b.toInt())
            }
            if (blocked) rc.onSenderBlocked()
            rc.tick(nowUs)
            peakTarget = max(peakTarget, rc.targetBytes)
            // 4. the grant reaches the sender half an RTT later; modelled as a straight lag on the limit
            senderLimit = rc.limit   // (lag omitted: it delays the ramp, it does not change where it settles)
            nowUs += tickUs
        }
        return Result(cap, delivered, dropped, peakTarget, rc.targetBytes, offered)
    }

    /**
     * The F8b shape: a bottleneck far below what the sender would like, with a shallow queue. The failure being
     * guarded against is the one the campaign found — the target running away to the 8 MB ceiling and funding a
     * multiple-of-capacity spray. What is asserted is a *bound*, not an optimum: the shipped cap must keep the
     * target within a small multiple of the true BDP and must not lose most of what it offers.
     */
    /**
     * The delay-gate candidate (`growthDelayGateUs`): the fourth fix attempt, and the first whose evidence
     * arrives BEFORE the spray — a filling queue raises delay before it drops packets.
     *
     * What the sweep established, including two refutations inside this one candidate:
     *  - the minRtt/4 noise floor blinded it exactly on high-RTT links (transcont's FULL queue is 47 ms of
     *    delay against a 45 ms floor) — refuted, floor is minRtt/16;
     *  - gating on srtt blinded it everywhere the ramp is fast (the EWMA lags a doubling cadence) — refuted,
     *    it reads the LAST raw sample;
     *  - and the honest limit: on transcont the gate never fires AT ALL, because the queue fully drains
     *    between growth events (562 KB/tick at 100 Mbit) — the overflow is a sub-RTT burst no RTT sample ever
     *    sees as standing delay. Delay-gating cannot see burst spray; only pacing (spreading the dump) can,
     *    and pacing measured 2.3x on kernel-netem transcont. **The redesign is the pair**, not either alone.
     *
     * So the assertions compare gated-8x against UNGATED-8x — the gate's own effect: a category win where
     * delay is visible (shallow: 54 % loss -> 0), and exactly zero harm where it is not.
     */
    @Test fun theDelayGateAcrossShapes() {
        val shapes = listOf(
            "shallow-20mbit" to Link(20_000_000L, 64L * 1200, 40_000),
            "deep-20mbit" to Link(20_000_000L, 1000L * 1200, 40_000),
            "high-bdp-transcont" to Link(100_000_000L, 500L * 1200, 180_000),
        )
        for ((name, link) in shapes) {
            val bdp = link.capacityBps / 8 * link.rttUs / 1_000_000
            val ungated8 = run(link, 8)
            val gated8 = run(link, 8, delayGateUs = 2_000)
            println("delaygate --- $name (BDP ${bdp / 1024} KB): ungated-8x $ungated8 | gated-8x $gated8")
            assertTrue(gated8.lossFrac <= ungated8.lossFrac + 0.01,
                "$name: the gate made loss WORSE: ${"%.1f".format(100 * gated8.lossFrac)}% vs ${"%.1f".format(100 * ungated8.lossFrac)}%")
            assertTrue(gated8.deliveredBytes >= ungated8.deliveredBytes * 9 / 10,
                "$name: the gate strangled throughput: ${gated8.deliveredBytes} vs ${ungated8.deliveredBytes}")
        }
        // the shape delay-gating exists for: shallow must be a category win, not a wash
        val shallow = Link(20_000_000L, 64L * 1200, 40_000)
        val u = run(shallow, 8); val g = run(shallow, 8, delayGateUs = 2_000)
        assertTrue(g.lossFrac < u.lossFrac / 4 && g.deliveredBytes > u.deliveredBytes * 2,
            "shallow: expected a category win; got loss ${"%.1f".format(100 * g.lossFrac)}% vs ${"%.1f".format(100 * u.lossFrac)}%, " +
            "delivered ${g.deliveredBytes} vs ${u.deliveredBytes}")
        // the bootstrap contract with the gate ON: a clean path has no standing queue, so it must not pin
        val clean = Link(100_000_000L, 4000L * 1200, 180_000)
        val cleanBdp = clean.capacityBps / 8 * clean.rttUs / 1_000_000
        val r = run(clean, 8, durationUs = 20_000_000L, delayGateUs = 2_000)
        println("delaygate --- clean bootstrap, gated-8x: $r (BDP ${cleanBdp / 1024} KB)")
        assertTrue(r.peakTargetBytes > cleanBdp / 2,
            "gated-8x pinned bootstrap: peak ${r.peakTargetBytes / 1024} KB against ${cleanBdp / 1024} KB BDP")
    }

    @Test fun theShippedCapDoesNotOvershootAShallowBottleneck() {
        val link = Link(capacityBps = 20_000_000L, queueBytes = 64L * 1200, rttUs = 40_000)
        val bdp = link.capacityBps / 8 * link.rttUs / 1_000_000   // bytes
        val r = run(link, ReceiverCredit.GROWTH_CAP_BDP)
        println("growth sweep, shallow bottleneck (BDP ${bdp / 1024} KB): $r")
        assertTrue(r.finalTargetBytes < 16 * bdp,
            "the target settled at ${r.finalTargetBytes / 1024} KB against a ${bdp / 1024} KB BDP — the runaway the cap exists to stop")
        assertTrue(r.lossFrac < 0.5,
            "${"%.0f".format(100 * r.lossFrac)}% of offered bytes were dropped: the sender is spraying a queue it cannot fill")
    }

    /**
     * The sweep itself. Printed rather than asserted for the ranking — the model is not the network and a ranking
     * from it would be a claim this harness cannot support — but one property IS asserted: a larger cap must let
     * the target reach at least as high, since the cap is an upper bound on growth and nothing else changes.
     *
     * The assertion is on the *peak*, not the settled value, and the first draft of this test got that wrong.
     * Final targets are not monotone in the cap and should not be: a bigger cap lets the target climb further,
     * overshoot harder, and then get cut by the congestion branch — cap 8x settles at the 13 KB floor on the
     * shallow shape precisely because it was allowed to spray first. That is the collapse, not a violation.
     */
    @Test fun sweepTheCapAcrossBottleneckShapes() {
        val shapes = listOf(
            "shallow-20mbit" to Link(20_000_000L, 64L * 1200, 40_000),
            "deep-20mbit" to Link(20_000_000L, 1000L * 1200, 40_000),
            "high-bdp-transcont" to Link(100_000_000L, 500L * 1200, 180_000),
            "narrow-uplink" to Link(560_000L, 64L * 1200, 50_000),
        )
        for ((name, link) in shapes) {
            val bdp = link.capacityBps / 8 * link.rttUs / 1_000_000
            println("--- $name (BDP ${bdp / 1024} KB, queue ${link.queueBytes / 1024} KB)")
            var prevPeak = 0L
            for (cap in listOf(2, 4, 8, 16)) {
                val r = run(link, cap)
                println("    $r")
                assertTrue(r.peakTargetBytes >= prevPeak,
                    "cap ${cap}x peaked BELOW a smaller cap (${r.peakTargetBytes} < $prevPeak): the cap is not an upper bound")
                prevPeak = r.peakTargetBytes
            }
        }
    }

    /**
     * The contract the 2x cap broke and the reason it was rejected (TEST-PLAN F8b): during slow start the measured
     * rate is small *because* the credit is small, so a cap tied to that rate must still let the target reach a
     * high-RTT BDP. Asserted on a clean link with plenty of capacity, where there is no congestion excuse.
     */
    @Test fun theCapStillLetsSlowStartReachAHighBdp() {
        val link = Link(capacityBps = 100_000_000L, queueBytes = 4000L * 1200, rttUs = 180_000)
        val bdp = link.capacityBps / 8 * link.rttUs / 1_000_000
        val r = run(link, ReceiverCredit.GROWTH_CAP_BDP, durationUs = 20_000_000L)
        println("growth sweep, high-BDP clean (BDP ${bdp / 1024} KB): $r")
        assertTrue(r.peakTargetBytes > bdp / 2,
            "the target peaked at ${r.peakTargetBytes / 1024} KB on a clean ${bdp / 1024} KB-BDP path: the cap is pinning slow start")
    }

    /**
     * The campaign's stated reason for rejecting the 2x cap, re-checked. TEST-PLAN records that 2x "fixed the
     * collapse outright" but "breaks slow-start's bootstrap contract (`receiverCreditReachesBdpAtHighRtt`)".
     * That contract is itself a deterministic virtual-clock harness (`CoreTest`), so the claim can be checked
     * exactly rather than re-argued — this is the same scenario, parameterised by the cap.
     *
     * The clean high-RTT path has no bottleneck and no loss: nothing here is congestion, so any shortfall is the
     * cap pinning growth against its own lagging rate estimate, which is precisely the mechanism the campaign
     * named.
     */
    @Test fun theBootstrapContractUnderEachCap() {
        val rttUs = 180_000L; val offered = 2_000_000.0
        for (cap in listOf(2, 4, 8)) {
            val est = PathEstimator(PathId(0)).apply { onRttSample(rttUs) }
            var now = 0L
            val rc = ReceiverCredit(est, clock = { now }, growthCapBdp = cap)
            var credit = 10L * Wire.MAX_DATAGRAM; var sentTotal = 0L
            val inFlight = ArrayDeque<Pair<Long, Long>>()
            val grantsInFlight = ArrayDeque<Pair<Long, Long>>()
            val probesInFlight = ArrayDeque<Long>()
            var lastProbeUs = -rttUs
            val tickUs = 1_000L; var sentAtOneSecond = 0L
            for (step in 0 until 3_000) {
                now += tickUs
                if (now == 1_000_000L) sentAtOneSecond = sentTotal
                while (inFlight.isNotEmpty() && inFlight.first().first <= now) rc.onReceived(inFlight.removeFirst().second.toInt())
                while (probesInFlight.isNotEmpty() && probesInFlight.first() <= now) { probesInFlight.removeFirst(); rc.onSenderBlocked() }
                rc.tick(now)?.let { g -> grantsInFlight.addLast((now + rttUs / 2) to g.creditBytes) }
                while (grantsInFlight.isNotEmpty() && grantsInFlight.first().first <= now) credit = maxOf(credit, grantsInFlight.removeFirst().second)
                val want = (offered * tickUs / 1e6).toLong()
                val send = minOf(want, credit - sentTotal)
                if (send > 0) { sentTotal += send; inFlight.addLast((now + rttUs / 2) to send) }
                else if (now - lastProbeUs >= rttUs) { lastProbeUs = now; probesInFlight.addLast(now + rttUs / 2) }
            }
            val steady = (sentTotal - sentAtOneSecond) / 2.0
            val firstSecond = sentTotal / 3.0
            println("bootstrap cap=${cap}x: target=${rc.targetBytes / 1024}KB steady=${(steady / 1000).toInt()}KB/s " +
                "(${(100 * steady / offered).toInt()}% of offered) mean=${(firstSecond / 1000).toInt()}KB/s")
            if (cap == ReceiverCredit.GROWTH_CAP_BDP) {
                // the shipped cap must keep the contract CoreTest pins; this is the same assertion, restated here
                // so a sweep that moved the default would fail in the sweep too, not only in a distant file
                assertTrue(rc.targetBytes >= 300_000, "shipped cap ${cap}x: target ${rc.targetBytes} below the 360 KB BDP")
                assertTrue(steady > 0.9 * offered, "shipped cap ${cap}x: steady $steady below 90 % of offered")
            }
        }
    }

    /**
     * The experimental evidence-gated cap ([ReceiverCredit.growthCapTightBdp]): loose until this path shows dead
     * credit, tight afterwards. Both halves of the trade are measured here in one test, because the whole claim
     * is that one setting can satisfy both — reporting either half alone is how a knob gets adopted on a partial
     * result.
     */
    @Test fun theEvidenceGatedCapIsMeasuredOnBothHalvesOfTheTrade() {
        // half 1: the shallow bottleneck, where a loose cap sprays
        val link = Link(20_000_000L, 64L * 1200, 40_000)
        val fixed4 = run(link, 4)
        val fixed2 = run(link, 2)
        val gated = run(link, 8, tight = 2)
        val settled = run(link, 8, tight = 2, onSettledRate = true)
        println("gated cap, shallow bottleneck:")
        println("    fixed 4x (shipped): $fixed4")
        println("    fixed 2x:           $fixed2")
        println("    8x -> 2x on evidence: $gated")
        println("    8x -> 2x on settled rate: $settled")
        println("    additive once settled (8x ceiling): ${run(link, 8, additive = true)}")
        println("    additive once settled (4x ceiling): ${run(link, 4, additive = true)}")

        // half 2: the clean high-RTT bootstrap contract, where a tight cap pins slow start
        val rttUs = 180_000L; val offered = 2_000_000.0
        for ((label, capPair) in listOf("fixed 4x" to Triple(4, 0, false), "fixed 2x" to Triple(2, 0, false),
                                        "8x -> 2x on evidence" to Triple(8, 2, false),
                                        "8x -> 2x on settled rate" to Triple(8, 2, true))) {
            val est = PathEstimator(PathId(0)).apply { onRttSample(rttUs) }
            var now = 0L
            val rc = ReceiverCredit(est, clock = { now }, growthCapBdp = capPair.first, growthCapTightBdp = capPair.second,
                                    tightenOnSettledRate = capPair.third)
            var credit = 10L * Wire.MAX_DATAGRAM; var sentTotal = 0L
            val inFlight = ArrayDeque<Pair<Long, Long>>(); val grantsInFlight = ArrayDeque<Pair<Long, Long>>()
            val probesInFlight = ArrayDeque<Long>(); var lastProbeUs = -rttUs
            val tickUs = 1_000L; var sentAtOneSecond = 0L
            for (step in 0 until 3_000) {
                now += tickUs
                if (now == 1_000_000L) sentAtOneSecond = sentTotal
                while (inFlight.isNotEmpty() && inFlight.first().first <= now) rc.onReceived(inFlight.removeFirst().second.toInt())
                while (probesInFlight.isNotEmpty() && probesInFlight.first() <= now) { probesInFlight.removeFirst(); rc.onSenderBlocked() }
                rc.tick(now)?.let { g -> grantsInFlight.addLast((now + rttUs / 2) to g.creditBytes) }
                while (grantsInFlight.isNotEmpty() && grantsInFlight.first().first <= now) credit = maxOf(credit, grantsInFlight.removeFirst().second)
                val send = minOf((offered * tickUs / 1e6).toLong(), credit - sentTotal)
                if (send > 0) { sentTotal += send; inFlight.addLast((now + rttUs / 2) to send) }
                else if (now - lastProbeUs >= rttUs) { lastProbeUs = now; probesInFlight.addLast(now + rttUs / 2) }
            }
            val steady = (sentTotal - sentAtOneSecond) / 2.0
            println("gated cap, bootstrap $label: target=${rc.targetBytes / 1024}KB steady=${(100 * steady / offered).toInt()}% of offered")
        }
    }
}
