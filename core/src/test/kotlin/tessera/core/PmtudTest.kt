package tessera.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmtudTest {
    /** Drives a Pmtud against a simulated path: probes up to `pathMtu` bytes are acked, larger ones are lost. */
    private class Sim(val p: Pmtud, var t: Long = 1_000_000L) {
        var pn = 1L
        val sent = mutableListOf<Int>()
        fun run(pathMtu: Int, limit: Int = 64): List<Int> {
            val before = sent.size
            while (p.state == Pmtud.State.BASE || p.state == Pmtud.State.SEARCHING) {
                val probe = p.nextProbe(t) ?: break
                assertNull(probe.pn)
                p.onProbeSent(probe.size, pn, t); sent += probe.size
                if (probe.size <= pathMtu) p.onProbeAcked(pn) else p.onProbeLost(pn)
                pn++; t += 10_000
                assertTrue(sent.size - before <= limit, "search did not converge: $sent")
            }
            return sent.subList(before, sent.size)
        }
    }

    @Test fun convergesToMaxWhenAllProbesAcked() {
        val p = Pmtud()
        assertEquals(Pmtud.State.BASE, p.state); assertEquals(1200, p.plpmtu)
        val sent = Sim(p).run(pathMtu = 1500)
        assertEquals(Pmtud.State.SEARCH_COMPLETE, p.state)
        assertEquals(1500, p.plpmtu)
        assertTrue(sent.size <= 5, "took ${sent.size} probes: $sent")
        assertEquals(listOf(1200, 1500), sent)
    }

    @Test fun convergesTo1400WhenLargerProbesLost() {
        val p = Pmtud()
        val sim = Sim(p)
        val sent = sim.run(pathMtu = 1400)
        assertEquals(Pmtud.State.SEARCH_COMPLETE, p.state)
        assertEquals(1400, p.plpmtu)
        assertEquals(3, sent.count { it == 1500 }, "max tried exactly maxProbes times: $sent")
        assertTrue(sent.filter { it > 1400 }.groupingBy { it }.eachCount().values.all { it == 3 }, "3 attempts per failing size: $sent")
        assertTrue(sent.size <= 20, "$sent")
        // SEARCH_COMPLETE: quiet until the raise timer, then an upward re-probe starts at max again.
        assertNull(p.nextProbe(sim.t))
        val raiseAt = assertNotNull(p.nextTimerUs())
        assertTrue(raiseAt > sim.t && raiseAt <= sim.t + p.raiseTimerUs)
        assertNull(p.nextProbe(raiseAt - 1)); assertEquals(Pmtud.State.SEARCH_COMPLETE, p.state)
        val re = assertNotNull(p.nextProbe(raiseAt))
        assertEquals(1500, re.size); assertEquals(Pmtud.State.SEARCHING, p.state); assertEquals(1400, p.plpmtu)
    }

    @Test fun blackHoleDropsToBaseAndResearchesAfterTimer() {
        val p = Pmtud(raiseTimerUs = 5_000_000L)
        val sim = Sim(p)
        sim.run(pathMtu = 1500)
        assertEquals(1500, p.plpmtu)
        // Big losses alone are not enough (could be an outage), and a big ack resets the run.
        repeat(3) { p.onPacketLoss(1500) }
        assertEquals(1500, p.plpmtu)
        p.onPacketAcked(1500)
        repeat(2) { p.onPacketLoss(1500) }; p.onPacketAcked(1200)
        assertEquals(1500, p.plpmtu)
        p.onPacketLoss(1500) // third consecutive big loss while small packets get through: suspected black hole -> verify
        assertEquals(1500, p.plpmtu); assertEquals(1, p.blackHoleSuspicions); assertTrue(p.verifying)
        repeat(p.maxProbes) { val v = assertNotNull(p.nextProbe(sim.t)); assertEquals(1500, v.size); p.onProbeSent(v.size, sim.pn++, sim.t); p.onProbeLost(sim.pn - 1); sim.t += 10_000 }
        assertEquals(Pmtud.State.BASE, p.state); assertEquals(1200, p.plpmtu); assertEquals(1, p.blackHoles)
        // Backoff: no probes until raiseTimerUs has elapsed.
        val holdUntil = assertNotNull(p.nextTimerUs())
        assertTrue(holdUntil > sim.t && holdUntil <= sim.t + p.raiseTimerUs)
        assertNull(p.nextProbe(sim.t)); assertNull(p.nextProbe(holdUntil - 1))
        assertEquals(Pmtud.State.BASE, p.state); assertEquals(1200, p.plpmtu)
        sim.t = holdUntil
        val sent = sim.run(pathMtu = 1500)
        assertEquals(listOf(1200, 1500), sent)
        assertEquals(Pmtud.State.SEARCH_COMPLETE, p.state); assertEquals(1500, p.plpmtu)
    }

    /**
     * netem finding (5g-mmwave / lte at 2000 msg/s): a Gilbert-Elliott burst loses three full-size packets in a row and
     * the next acked packet is a small one (a credit probe, an ack) — exactly the black-hole signature, but the path is
     * fine. That must not park PMTUD at BASE for PMTU_RAISE_TIMER (600 s): the suspicion is verified with a probe at the
     * current PLPMTU, and only when that fails [maxProbes] times does the size fall back to base.
     */
    @Test fun burstLossWithASmallAckIsVerifiedBeforeDroppingToBase() {
        val p = Pmtud()
        val sim = Sim(p)
        sim.run(pathMtu = 1500)
        assertEquals(Pmtud.State.SEARCH_COMPLETE, p.state); assertEquals(1500, p.plpmtu)
        repeat(3) { p.onPacketLoss(1500) }; p.onPacketAcked(60)    // burst + small ack: suspicion, not a verdict
        assertEquals(1500, p.plpmtu, "a single burst with one small ack must not drop the PLPMTU")
        assertEquals(0, p.blackHoles)
        val verify = assertNotNull(p.nextProbe(sim.t), "a verification probe must be scheduled")
        assertEquals(1500, verify.size, "verify the current PLPMTU, not a new size")
        p.onProbeSent(verify.size, sim.pn++, sim.t); p.onProbeAcked(sim.pn - 1)
        assertEquals(1500, p.plpmtu); assertEquals(0, p.blackHoles)
        assertTrue(p.state == Pmtud.State.SEARCH_COMPLETE || p.state == Pmtud.State.SEARCHING, "${p.state}")
        // the PLPMTU keeps working through repeated suspicions
        repeat(5) {
            repeat(3) { p.onPacketLoss(1500) }; p.onPacketAcked(40)
            sim.t += 10_000
            val v = p.nextProbe(sim.t)
            if (v != null) { p.onProbeSent(v.size, sim.pn++, sim.t); p.onProbeAcked(sim.pn - 1) }
        }
        assertEquals(1500, p.plpmtu, "repeated loss bursts with small acks must not shrink the PLPMTU on a working path")
        assertEquals(0, p.blackHoles)
        // a real black hole: the verification probes all fail -> BASE, and the search resumes after the (short) hold
        repeat(3) { p.onPacketLoss(1500) }; p.onPacketAcked(40)
        sim.t += 10_000
        repeat(p.maxProbes) { val v = assertNotNull(p.nextProbe(sim.t)); assertEquals(1500, v.size); p.onProbeSent(v.size, sim.pn++, sim.t); p.onProbeLost(sim.pn - 1); sim.t += 10_000 }
        assertEquals(Pmtud.State.BASE, p.state); assertEquals(1200, p.plpmtu); assertEquals(1, p.blackHoles)
        val hold = assertNotNull(p.nextTimerUs())
        assertTrue(hold - sim.t <= p.raiseTimerUs && hold > sim.t, "hold ${hold - sim.t}us")
        sim.t = hold
        sim.run(pathMtu = 1500)
        assertEquals(1500, p.plpmtu)
    }

    /** A size that "fails" under random loss is retried upward after a short, growing backoff rather than after 600 s. */
    @Test fun incompleteSearchRetriesUpwardWithBackoff() {
        val p = Pmtud(raiseMinUs = 1_000_000L)
        val sim = Sim(p)
        sim.run(pathMtu = 1400)             // 1500 "fails" three times (loss), converges at 1400
        assertEquals(Pmtud.State.SEARCH_COMPLETE, p.state); assertEquals(1400, p.plpmtu)
        // timers are stamped from the last timestamped call (the probe send, one sim step before the untimestamped ack)
        val raise1 = assertNotNull(p.nextTimerUs())
        assertTrue(raise1 - sim.t in 980_000L..1_000_000L, "first raise after raiseMinUs, got ${raise1 - sim.t}")
        sim.t = raise1; p.onTimer(sim.t)           // fires the raise timer (Sim.run only probes while BASE / SEARCHING)
        sim.run(pathMtu = 1400)             // still 1400: backoff doubles
        val raise2 = assertNotNull(p.nextTimerUs())
        assertTrue(raise2 - sim.t in 1_980_000L..2_000_000L, "second raise after 2 x raiseMinUs, got ${raise2 - sim.t}")
        sim.t = raise2; p.onTimer(sim.t)
        sim.run(pathMtu = 1500)             // the path now carries 1500: complete at max, the full raise timer applies
        assertEquals(1500, p.plpmtu)
        val raise3 = assertNotNull(p.nextTimerUs())
        assertTrue(raise3 - sim.t in (p.raiseTimerUs - 20_000L)..p.raiseTimerUs, "full raise timer at the maximum, got ${raise3 - sim.t}")
    }

    @Test fun noProbeWhileOneIsOutstanding() {
        val p = Pmtud()
        val first = assertNotNull(p.nextProbe(0))
        assertEquals(1200, first.size); assertNull(first.pn); assertEquals(p.probeTimeoutUs, first.deadlineUs)
        assertEquals(first, p.nextProbe(0)) // same probe until it is sent
        assertEquals(1100, p.padTo(100)); assertEquals(0, p.padTo(1200))
        p.onProbeSent(1200, pn = 1, nowUs = 0)
        assertEquals(0, p.padTo(100))
        assertEquals(1L, assertNotNull(p.outstanding).pn)
        assertNull(p.nextProbe(1)); assertNull(p.nextProbe(p.probeTimeoutUs - 1))
        p.onProbeAcked(1)
        assertNull(p.outstanding)
        val second = assertNotNull(p.nextProbe(2_000))
        assertEquals(1500, second.size)
        p.onProbeSent(1500, pn = 2, nowUs = 2_000)
        assertNull(p.nextProbe(2_001))
        // Deadline passes with neither ack nor loss report: the probe counts as lost and the size is retried.
        val retry = assertNotNull(p.nextProbe(2_000 + p.probeTimeoutUs))
        assertEquals(1500, retry.size); assertEquals(1, p.probeAttempts); assertNull(p.outstanding)
        // Stale pn is ignored.
        p.onProbeAcked(2); assertEquals(Pmtud.State.SEARCHING, p.state); assertEquals(1200, p.plpmtu)
    }

    @Test fun probeAttemptCapIsHonoured() {
        val p = Pmtud()
        var t = 0L
        p.onProbeSent(assertNotNull(p.nextProbe(t)).size, 1, t); p.onProbeAcked(1) // base confirmed
        assertEquals(Pmtud.State.SEARCHING, p.state)
        // Three attempts at 1500: two reported lost, one times out. Never a fourth.
        for (attempt in 1..3) {
            val probe = assertNotNull(p.nextProbe(t))
            assertEquals(1500, probe.size)
            p.onProbeSent(1500, 10L + attempt, t)
            assertEquals(attempt, p.probeAttempts)
            if (attempt < 3) { p.onProbeLost(10L + attempt); t += 10_000 } else { p.onTimer(probe.deadlineUs); t = probe.deadlineUs + 1 }
        }
        val next = assertNotNull(p.nextProbe(t))
        assertEquals(1348, next.size, "aligned midpoint of (1200, 1500) after the cap")
        assertEquals(0, p.probeAttempts); assertEquals(1200, p.plpmtu); assertEquals(Pmtud.State.SEARCHING, p.state)
        // Same cap for the base probe: three failures mean ERROR at minPlpmtu, and the backoff re-confirms base later.
        val q = Pmtud(minPlpmtu = 1000, raiseTimerUs = 2_000_000L)
        repeat(3) { i -> q.onProbeSent(assertNotNull(q.nextProbe(0)).size, i.toLong(), 0); q.onProbeLost(i.toLong()) }
        assertEquals(Pmtud.State.ERROR, q.state); assertEquals(1000, q.plpmtu)
        assertNull(q.nextProbe(1_999_999))
        val retry = assertNotNull(q.nextProbe(2_000_000))
        assertEquals(1200, retry.size); assertEquals(Pmtud.State.BASE, q.state); assertEquals(1200, q.plpmtu)
    }
}
