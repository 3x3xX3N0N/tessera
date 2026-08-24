package tessera.transport

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Receive-side flow control. A `Msg` frame's `offset` is wire-controlled and reassembly buffers grow to
 * `offset + len`, so without bounds an authenticated peer forces an arbitrary allocation from one crafted fragment,
 * or pins memory with never-completed messages. These tests hold [TesseraConnection.Reassembler] to its three caps.
 *
 * The caps here are deliberately small so that a *broken* guard fails by allocating a few MB (a harmless test
 * failure) rather than by OOM-ing the JVM.
 */
class ReassemblerTest {
    private val maxMsg = 1 shl 20          // 1 MiB per message
    private val maxConc = 8
    private val maxBytes = 4L shl 20       // 4 MiB total

    private fun r() = TesseraConnection.Reassembler(maxMsg, maxConc, maxBytes)
    private fun buf(n: Int, fill: Byte = 1) = ByteBuffer.wrap(ByteArray(n) { fill })

    @Test fun oneCraftedFragmentCannotForceAHugeAllocation() {
        val re = r()
        // offset just past the per-message cap, fin set: the old code did buf = ByteArray(offset+len) here.
        val out = re.onFragment(msgId = 1, offset = maxMsg + 4096, data = buf(100), fin = true)
        assertNull(out, "an over-cap fragment must be dropped, not assembled")
        assertEquals(1L, re.oversizeDropped)
        assertEquals(0L, re.bytes, "nothing may be buffered for a rejected fragment")
        assertEquals(0, re.pending)
    }

    @Test fun overCapIsRejectedAtTheBoundary() {
        val re = r()
        assertNull(re.onFragment(1, offset = maxMsg, data = buf(1), fin = true))   // offset+len = maxMsg+1
        assertEquals(1L, re.oversizeDropped)
        // exactly maxMsg is allowed
        val whole = ByteArray(maxMsg) { (it and 0x7F).toByte() }
        val out = re.onFragment(2, offset = 0, data = ByteBuffer.wrap(whole), fin = true)
        assertContentEquals(whole, out, "a message of exactly maxMessageBytes must complete")
        assertEquals(0, re.pending); assertEquals(0L, re.bytes)
    }

    @Test fun concurrentPartialMessagesAreCapped() {
        val re = r()
        // Open maxConc+5 distinct messages, each one incomplete fragment (fin = false).
        repeat(maxConc + 5) { re.onFragment(msgId = 100L + it, offset = 0, data = buf(256), fin = false) }
        assertEquals(maxConc, re.pending, "in-progress messages must not exceed the concurrent cap")
        assertEquals(5L, re.refused, "fragments for new messages past the cap are refused")
        assertTrue(re.bytes <= maxBytes)
    }

    @Test fun totalBufferedBytesAreCapped() {
        val re = r()
        // Max-size non-final fragments: 4 fill the 4 MiB budget, the 5th must be refused by the byte cap — and the
        // concurrent cap (8) is not reached, so this isolates the byte budget.
        for (id in 0 until 4) assertNull(re.onFragment(msgId = id.toLong(), offset = 0, data = buf(maxMsg), fin = false))
        assertEquals(maxBytes, re.bytes)
        assertNull(re.onFragment(msgId = 99, offset = 0, data = buf(maxMsg), fin = false))
        assertEquals(1L, re.refused, "the fragment that would breach the byte budget is refused")
        assertEquals(4, re.pending, "the byte-refused message left no slot behind")
        assertTrue(re.bytes <= maxBytes, "buffered bytes ${re.bytes} exceeded the cap $maxBytes")
    }

    @Test fun aLegitimateFragmentedMessageReassemblesAndFreesItsBytes() {
        val re = r()
        val whole = ByteArray(3000) { (it % 251).toByte() }
        // three out-of-order fragments
        assertNull(re.onFragment(7, offset = 1000, data = ByteBuffer.wrap(whole, 1000, 1000).slice(), fin = false))
        assertTrue(re.bytes > 0 && re.pending == 1)
        assertNull(re.onFragment(7, offset = 2000, data = ByteBuffer.wrap(whole, 2000, 1000).slice(), fin = true))
        val out = re.onFragment(7, offset = 0, data = ByteBuffer.wrap(whole, 0, 1000).slice(), fin = false)
        assertContentEquals(whole, out)
        assertEquals(0, re.pending, "a completed message frees its slot")
        assertEquals(0L, re.bytes, "a completed message frees its bytes")
    }

    @Test fun aFloodOfNeverCompletingMessagesStaysBounded() {
        val re = r()
        // 100k distinct one-fragment messages that never finish: memory must stay within the caps regardless.
        repeat(100_000) { re.onFragment(msgId = it.toLong(), offset = 0, data = buf(512), fin = false) }
        assertEquals(maxConc, re.pending)
        assertTrue(re.bytes <= maxBytes)
        assertEquals((100_000 - maxConc).toLong(), re.refused)
    }
}
