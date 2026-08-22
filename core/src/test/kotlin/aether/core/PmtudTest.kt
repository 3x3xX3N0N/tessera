package aether.core

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
        p.onPacketLoss(1500) // third consecutive big loss while small packets get through: black hole
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
