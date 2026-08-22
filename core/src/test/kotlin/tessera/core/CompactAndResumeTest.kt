package tessera.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactAndResumeTest {
    @Test fun varintRoundTrip() {
        val buf = ByteBuffer.allocate(64)
        val vals = listOf(0L, 63, 64, 16383, 16384, 1_073_741_823, 1_073_741_824, 1L shl 40)
        vals.forEach { VarInt.write(buf, it) }; buf.flip()
        vals.forEach { assertEquals(it, VarInt.read(buf)) }
    }

    @Test fun shortHeaderIsSevenBytesAndPnReconstructs() {
        val buf = ByteBuffer.allocate(32)
        ShortHeader.write(buf, PathId(2), 0xCAFEBABE.toInt(), pn = 1000, largestAcked = 990)
        assertEquals(7, buf.position()) // 1 flags + 4 connId + 2-byte PN floor
        buf.flip()
        val p = ShortHeader.read(buf, largestSeen = 999)
        assertEquals(1000, p.pn); assertEquals(PathId(2), p.path); assertEquals(0xCAFEBABE.toInt(), p.shortConn)
        // wrap: pn 0x1_0005 sent with 1 byte, receiver saw 0xFFFF
        assertEquals(0x1_0005L, ShortHeader.decodePn(0x05, 8, 0xFFFF))
        // reorder robustness: a packet 300 behind largestSeen must still decode with the 2-byte floor
        assertEquals(2, ShortHeader.pnLenFor(pn = 1000, largestAcked = 995))
        val late = ByteBuffer.allocate(16); ShortHeader.write(late, PathId(0), 1, pn = 700, largestAcked = 695); late.flip()
        assertEquals(700, ShortHeader.read(late, largestSeen = 1000).pn)
    }

    @Test fun compactMsgSavesBytes() {
        val data = ByteBuffer.wrap(ByteArray(100))
        val compact = ByteBuffer.allocate(256); CompactMsg.write(compact, msgId = 5001, prevMsgId = 5000, offset = 0, fin = true, data = data, last = true)
        val legacy = ByteBuffer.allocate(256); Frame.Msg(5001, 0, true, data).write(legacy)
        assertEquals(102, compact.position()); assertEquals(116, legacy.position())
        compact.flip(); val m = CompactMsg.read(compact, 5000)
        assertEquals(5001, m.msgId); assertTrue(m.fin); assertEquals(100, m.data.remaining())
    }

    @Test fun paramsNegotiateTagAndDict() {
        val buf = ByteBuffer.allocate(64)
        ConnParams(tagLen = 8, dictId = 77, shortConnId = 123).write(buf); buf.flip()
        val p = ConnParams.read(buf)
        assertEquals(8, p.tagLen); assertEquals(77, p.dictId); assertEquals(123, p.shortConnId); assertEquals(2, p.ackFreq)
    }

    @Test fun resumptionGivesBigFirstFlightAndRejectsReplay() {
        val ticketKey = ByteArray(32) { it.toByte() }
        val srv = Resumption.Server(ticketKey)
        val sessionKey = ByteArray(32) { (it * 3).toByte() }
        val ticket = srv.issueTicket(sessionKey, nowMs = 1000)
        assertEquals(Resumption.TICKET_LEN, ticket.size)
        val client = Resumption.Client(ticket, Resumption.resumptionSecret(sessionKey))
        val big = ByteArray(Resumption.MAX_FIRST_DATA) { 0x42 }
        val (ck, body) = client.initial(big, nowMs = 5000, nonce = 9)
        assertTrue(Wire.HEADER_LEN + body.size <= Wire.MAX_DATAGRAM)
        assertTrue(Resumption.MAX_FIRST_DATA > 1200, "resumed first flight should carry >1.2KB, got ${Resumption.MAX_FIRST_DATA}")
        val acc = srv.accept(body, nowMs = 5002)
        assertNotNull(acc); assertContentEquals(big, acc.data); assertContentEquals(ck, acc.key)
        assertNull(srv.accept(body, 5002), "replay")
        assertNull(srv.accept(body, 5000 + 8 * 24 * 3600_000L), "expired ticket/stale")
    }
}
