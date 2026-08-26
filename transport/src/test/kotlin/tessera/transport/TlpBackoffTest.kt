package tessera.transport

import tessera.core.PathId
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The PTO backoff's reset rule (PathState.armTlpProbe / onTlpProgress), driven as pure policy: given a sequence of
 * probes and the highest pn each ack newly acked, does the backoff clear? Wall-clock probe behaviour lives in the
 * timing suite; what is pinned here is that only forward progress past an outstanding probe resets the counter.
 */
class TlpBackoffTest {
    private fun path() = PathState(PathId(0), InetSocketAddress("127.0.0.1", 1))

    @Test
    fun ackBelowTheOutstandingProbeLeavesTheBackoffStanding() {
        val p = path()
        p.armTlpProbe(100)   // probe train occupies pn >= 100
        assertEquals(1, p.tlpBackoff)
        p.onTlpProgress(97)  // a late repair / stale re-send sent before the probe
        assertEquals(1, p.tlpBackoff, "an ack below the probe must not reset the backoff")
        p.onTlpProgress(99)
        assertEquals(1, p.tlpBackoff, "an ack below the probe must not reset the backoff")
    }

    @Test
    fun strayAcksUnderCongestionLetTheBackoffGrow() {
        val p = path()
        var pn = 100L
        // three PTOs in a row, each answered only by an ack for something older than the probe
        repeat(3) {
            p.armTlpProbe(pn)
            p.onTlpProgress(pn - 5)
            pn += 10
        }
        assertEquals(3, p.tlpBackoff, "exponential backoff must engage while nothing current is being delivered")
    }

    @Test
    fun anAckReachingTheProbeResets() {
        val p = path()
        p.armTlpProbe(100)
        p.armTlpProbe(110)
        assertEquals(2, p.tlpBackoff)
        p.onTlpProgress(110)   // the probe itself got through
        assertEquals(0, p.tlpBackoff)
        assertEquals(-1L, p.tlpProbePn)
    }

    @Test
    fun ackForDataSentAfterTheProbeAlsoResets() {
        val p = path()
        p.armTlpProbe(100)
        p.onTlpProgress(140)   // fresh data behind the probe is being delivered: the path has recovered
        assertEquals(0, p.tlpBackoff)
    }

    @Test
    fun withNoProbeOutstandingAnyAckResets() {
        val p = path()
        p.tlpBackoff = 4       // e.g. left over after the outstanding probe was answered
        p.onTlpProgress(0)
        assertEquals(0, p.tlpBackoff)
    }
}
