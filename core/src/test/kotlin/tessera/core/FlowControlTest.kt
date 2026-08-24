package tessera.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sender side of connection-level flow control ([FlowSender], fed by [Frame.MaxData]). */
class FlowControlTest {
    @Test fun advertsAreMonotonicAndIdempotent() {
        val f = FlowSender()
        assertEquals(FlowSender.INITIAL_WINDOW, f.limit)
        f.onMaxData(1_000_000); assertEquals(1_000_000, f.limit)
        f.onMaxData(1_000_000); assertEquals(1_000_000, f.limit, "a re-sent advert is a no-op")
        f.onMaxData(500_000); assertEquals(1_000_000, f.limit, "a stale (reordered) advert never lowers the limit")
        f.onMaxData(2_000_000); assertEquals(2_000_000, f.limit)
    }

    @Test fun chargingStopsExactlyAtTheLimit() {
        val f = FlowSender()
        f.onMaxData(FlowSender.INITIAL_WINDOW + 1000)   // below INITIAL_WINDOW an advert is a no-op by design
        f.charge(FlowSender.INITIAL_WINDOW.toInt() + 999)
        assertTrue(f.canCharge(1), "the last byte of the window is spendable")
        assertFalse(f.canCharge(2), "one byte past the limit is not")
    }

    @Test fun refundReopensTheWindow() {
        val f = FlowSender()
        f.charge(FlowSender.INITIAL_WINDOW.toInt())     // the whole initial window
        assertFalse(f.canCharge(1))
        f.refund(400)   // an aborted send whose fin never went out gives its charge back
        assertTrue(f.canCharge(400)); assertFalse(f.canCharge(401))
        assertEquals(FlowSender.INITIAL_WINDOW - 400, f.charged)
    }

    @Test fun aNegativeWireLimitIsRejectedByTheCodec() {
        val buf = ByteBuffer.allocate(9).put(0x09).putLong(-1).flip()
        assertFailsWith<IllegalArgumentException> { FrameCodec.read(buf) }
    }
}
