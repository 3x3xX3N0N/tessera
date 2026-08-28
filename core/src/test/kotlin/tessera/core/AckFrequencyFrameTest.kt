package tessera.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire and the state machine behind [Frame.AckFrequency]: codec round-trip, the clamps that keep a peer from
 * asking us to stop acking, and the [AckTracker] property that makes a raised cadence safe — an out-of-order
 * arrival still acks at once, so loss detection never waits on the counter.
 */
class AckFrequencyFrameTest {
    private fun roundTrip(f: Frame.AckFrequency): Frame.AckFrequency {
        val b = ByteBuffer.allocate(64)
        f.write(b); b.flip()
        return FrameCodec.read(b) as Frame.AckFrequency
    }

    @Test fun theFrameRoundTrips() {
        val f = Frame.AckFrequency(32, 20_000)
        assertEquals(f, roundTrip(f))
        assertEquals(6, ByteBuffer.allocate(64).also { f.write(it) }.position(), "0x0A + freq(1) + delay(4)")
    }

    @Test fun bothFieldsAreClampedOnTheWireInBothDirections() {
        assertEquals(Frame.AckFrequency(Frame.AckFrequency.MAX_FREQ, Frame.AckFrequency.MAX_DELAY_US),
            roundTrip(Frame.AckFrequency(100_000, 60_000_000L)))
        assertEquals(Frame.AckFrequency(1, 0), roundTrip(Frame.AckFrequency(0, -5)))
        // and a hand-built frame with the raw maxima decodes to the clamps rather than to what it says
        val hostile = ByteBuffer.allocate(8).put(Frame.AckFrequency.TYPE.toByte()).put(255.toByte()).putInt(-1)
        hostile.flip()
        val got = FrameCodec.read(hostile) as Frame.AckFrequency
        assertEquals(Frame.AckFrequency.MAX_DELAY_US, got.maxAckDelayUs, "an unsigned 0xFFFFFFFF delay was honoured")
    }

    /** A raised cadence delays the steady in-order case and nothing else. */
    @Test fun aRaisedCadenceDelaysAcksButNotTheGapSignal() {
        val est = PathEstimator(PathId(0))
        val t = AckTracker(est, ackFreq = 2, maxAckDelayUs = 1_000_000)
        t.setAckPolicy(64, 1_000_000)

        var now = 0L
        for (pn in 0 until 32L) { t.onPacketReceived(pn, 1200, false, now); now += 100 }
        assertTrue(t.ackFrameIfDue(now) == null, "32 in-order packets acked before the requested cadence of 64")

        // a gap: pn 33 arrives, 32 does not
        t.onPacketReceived(33, 1200, false, now)
        assertTrue(t.ackFrameIfDue(now) != null, "a gap did not draw an immediate ACK; a raised cadence would blind RACK")
    }

    /** The delay bound is the other trigger, and it survives a policy change too. */
    @Test fun theDelayBoundStillFiresUnderARaisedCadence() {
        val est = PathEstimator(PathId(0))
        val t = AckTracker(est, ackFreq = 2, maxAckDelayUs = 1_000)
        t.setAckPolicy(64, 25_000)
        t.onPacketReceived(0, 1200, false, 0)
        assertTrue(t.ackFrameIfDue(10_000) == null, "acked 10 ms into a 25 ms delay budget")
        assertTrue(t.ackFrameIfDue(25_000) != null, "the 25 ms delay bound never fired")
    }
}
