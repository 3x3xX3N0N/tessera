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
        // Three PTOs in a row while the tail stays genuinely stuck: every ack is for traffic older than the FIRST
        // probe, so nothing at or past it has ever been delivered.
        repeat(3) {
            p.armTlpProbe(pn)
            p.onTlpProgress(95)
            pn += 10
        }
        assertEquals(3, p.tlpBackoff, "exponential backoff must engage while nothing current is being delivered")
    }

    /**
     * The mark belongs to the FIRST probe of a series, not the latest. Re-raising it per probe ratchets it ahead of
     * acks that lag by an RTT on a path that is still sending, so the backoff climbs to MAX_PTO_US on a link that
     * is lossy rather than dead — measured on wifi-busy as p99 820 ms -> 3597 ms, caught by `bench gate`. Here the
     * acks trail the probes by a fixed offset but do advance: that is a delivering path, and it must reset.
     */
    @Test
    fun aTrailingButAdvancingAckStreamResets() {
        val p = path()
        p.armTlpProbe(100)
        p.onTlpProgress(95)                       // before the first probe: no progress yet
        assertEquals(1, p.tlpBackoff)
        p.armTlpProbe(110)                        // the mark stays at 100
        assertEquals(100L, p.tlpProbePn, "the mark must not ratchet forward with each probe")
        p.onTlpProgress(105)                      // past the first probe: the series was answered
        assertEquals(0, p.tlpBackoff, "a path whose acks advance past the outstanding probe is delivering")
        assertEquals(-1L, p.tlpProbePn)
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
