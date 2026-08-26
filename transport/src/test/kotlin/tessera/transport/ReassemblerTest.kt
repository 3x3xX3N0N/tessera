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

    @Test fun aFragmentPastTheFinEstablishedLengthIsDroppedAndTheMessageStillCompletes() {
        val re = r()
        // fin at [100, 500) fixes the length at 500 and clamps the buffer to it; the old code then wrote a later
        // [600, 800) fragment past that allocation -> IndexOutOfBoundsException -> the slot leaked forever.
        assertNull(re.onFragment(9, offset = 100, data = buf(400), fin = true))
        assertNull(re.onFragment(9, offset = 600, data = buf(200), fin = false), "a fragment past the known total is dropped")
        assertEquals(1L, re.oversizeDropped)
        assertEquals(1, re.pending, "the drop must not disturb the entry")
        // the missing prefix still completes the message
        val out = re.onFragment(9, offset = 0, data = buf(100, fill = 2), fin = false)
        assertEquals(500, out!!.size)
        assertEquals(0, re.pending); assertEquals(0L, re.bytes)
    }

    @Test fun aFinBelowTheBufferedExtentIsDropped() {
        val re = r()
        // [0, 800) already buffered; a fin claiming the message ends at 500 would pass the completion check and
        // truncate what arrived. Drop it; the honest fin (end >= extent) still completes.
        assertNull(re.onFragment(11, offset = 0, data = buf(800), fin = false))
        assertNull(re.onFragment(11, offset = 100, data = buf(400), fin = true), "a fin below the buffered extent is dropped")
        assertEquals(1L, re.oversizeDropped)
        val out = re.onFragment(11, offset = 800, data = buf(200), fin = true)
        assertEquals(1000, out!!.size)
        assertEquals(0, re.pending); assertEquals(0L, re.bytes)
    }

    @Test fun aFragmentEndingExactlyAtTheTotalIsAccepted() {
        val re = r()
        assertNull(re.onFragment(13, offset = 500, data = buf(500), fin = true))            // total = 1000
        val dup = re.onFragment(13, offset = 500, data = buf(500), fin = true)              // the fin re-received
        assertNull(dup, "a duplicate fin (end == total) is legal, not a drop")
        assertEquals(0L, re.oversizeDropped)
        val out = re.onFragment(13, offset = 0, data = buf(500, fill = 3), fin = false)     // ends exactly at 500 <= total
        assertEquals(1000, out!!.size)
    }

    // --- flow-window leak credit (v0.9). A dropped message's charge is never retired by `consumed`, so the
    // reassembler accumulates what the receiver may hand back instead. The rule: the largest offset+len seen for
    // an abandoned id, clamped to maxMessageBytes; never more, because over-crediting breaks the receive bound.

    @Test fun aRefusedMessageCreditsWhatWasSeenOfIt() {
        val re = r()
        for (id in 0 until maxConc) re.onFragment(msgId = id.toLong(), offset = 0, data = buf(256), fin = false)
        assertEquals(0L, re.abandonedBytes, "nothing has been given up on yet")
        // No slot left: msgId 99 is abandoned on its first fragment and credits that fragment's extent...
        assertNull(re.onFragment(99, offset = 0, data = buf(300), fin = false))
        assertEquals(300L, re.abandonedBytes)
        // ...and its later fragments keep raising the credit to the message's true size, because the sender has no
        // idea it was dropped and keeps sending. The fin at 1000 makes the credit exact.
        assertNull(re.onFragment(99, offset = 300, data = buf(400), fin = false))
        assertEquals(700L, re.abandonedBytes)
        assertNull(re.onFragment(99, offset = 700, data = buf(300), fin = true))
        assertEquals(1000L, re.abandonedBytes, "the whole message must be credited once its fin arrives")
        // A re-sent fragment credits nothing further: the running maximum, not a sum.
        assertNull(re.onFragment(99, offset = 0, data = buf(300), fin = false))
        assertEquals(1000L, re.abandonedBytes)
        assertEquals(maxConc, re.pending, "an abandoned message never takes a slot back")
    }

    @Test fun anAbandonedMessageIsNeverDeliveredEvenIfItCouldComplete() {
        val re = TesseraConnection.Reassembler(maxMsg, 1, maxBytes)
        assertNull(re.onFragment(1, offset = 0, data = buf(100), fin = false))   // takes the only slot
        assertNull(re.onFragment(2, offset = 0, data = buf(500), fin = false), "no slot: message 2 is abandoned")
        // Every later fragment of 2 stays dropped. If it were allowed back in it would complete, be delivered AND
        // keep its credit — the one direction that breaks `limit <= consumed + window`.
        assertNull(re.onFragment(2, offset = 500, data = buf(500), fin = true), "an abandoned message must not revive")
        assertEquals(1000L, re.abandonedBytes)
        assertEquals(1, re.pending)
    }

    @Test fun abandonedCreditNeverExceedsOneMaxSizeMessage() {
        val re = r()
        // A crafted fragment far past the per-message cap: dropped, and it may credit at most maxMessageBytes —
        // send() refuses anything larger, so no honest charge can exceed that and the advert cannot be inflated.
        assertNull(re.onFragment(5, offset = maxMsg + (1 shl 24), data = buf(100), fin = true))
        assertEquals(1L, re.oversizeDropped)
        assertEquals(maxMsg.toLong(), re.abandonedBytes)
        assertNull(re.onFragment(5, offset = maxMsg + 1, data = buf(100), fin = true))
        assertEquals(maxMsg.toLong(), re.abandonedBytes, "credit is clamped per message, not per fragment")
    }

    @Test fun theAbandonedLedgerIsBoundedAndStopsCreditingRatherThanOverCrediting() {
        val re = TesseraConnection.Reassembler(maxMsg, 1, maxBytes)
        re.onFragment(0, offset = 0, data = buf(100), fin = false)                       // the only slot
        val n = TesseraConnection.Reassembler.ABANDONED_MEMORY + 500
        for (id in 1..n) assertNull(re.onFragment(id.toLong(), offset = 0, data = buf(100), fin = false))
        assertEquals(TesseraConnection.Reassembler.ABANDONED_MEMORY, re.abandonedPending, "the ledger must stay bounded")
        assertEquals(n * 100L, re.abandonedBytes)
        // A forgotten id stays dropped (it can never be delivered) but credits nothing more: under-crediting only
        // slows the sender, whereas letting it back in would double-count.
        assertNull(re.onFragment(1, offset = 100, data = buf(900), fin = true))
        assertEquals(n * 100L, re.abandonedBytes)
        // A still-remembered id keeps crediting normally.
        assertNull(re.onFragment(n.toLong(), offset = 100, data = buf(900), fin = true))
        assertEquals(n * 100L + 900, re.abandonedBytes)
    }

    @Test fun aContradictingFragmentCreditsNothingBecauseTheMessageStillArrives() {
        val re = r()
        assertNull(re.onFragment(9, offset = 100, data = buf(400), fin = true))          // total = 500
        assertNull(re.onFragment(9, offset = 600, data = buf(200), fin = false))         // past the total: dropped
        assertEquals(1L, re.oversizeDropped)
        assertEquals(500, re.onFragment(9, offset = 0, data = buf(100, fill = 2), fin = false)!!.size)
        assertEquals(0L, re.abandonedBytes, "a message that still completes is retired by consumed(), not by credit")
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
