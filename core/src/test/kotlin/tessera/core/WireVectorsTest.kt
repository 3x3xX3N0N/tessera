package tessera.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GOLDEN WIRE VECTORS - the wire format's tripwire.
 *
 * Every constant below is the exact byte encoding produced by this codebase for a fixed input. If a diff
 * here shows up, **the wire format changed**. At v0 that is allowed, but it must be deliberate: a peer
 * built from an older commit will no longer interoperate. When you change one of these on purpose, update
 * the vector AND note the change in `docs/SPEC.md`. Never "fix" a vector to make the build green.
 *
 * Covered: every frame type via its `write`, `PacketHeader.write`, `ShortHeader.write` at each reachable
 * pnLen, `CompactMsg.write` with and without offset/fin/length, `ConnParams.write`, `VarInt.write` at each
 * size boundary, and `Wire.VERSION`. Each parseable vector is also round-tripped: bytes -> parse ->
 * re-encode -> identical bytes.
 *
 * Checked against `docs/SPEC.md` at the time of writing; the disagreements found are noted inline.
 */
class WireVectorsTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun enc(f: (ByteBuffer) -> Unit): String {
        val b = ByteBuffer.allocate(4096); f(b); b.flip()
        return hex(ByteArray(b.remaining()).also { b.get(it) })
    }
    private fun payload() = ByteBuffer.wrap(bytes("deadbeef"))

    // ------------------------------------------------------------------ version

    /** SPEC: no version field is carried on the wire in v0; this constant only tags the build. */
    @Test fun version() = assertEquals(0x54530000, Wire.VERSION)

    @Test fun headerConstants() {
        assertEquals(14, Wire.HEADER_LEN)        // SPEC "Packet": flags(1) connId(8) pathId(1) pn(4)
        assertEquals(1350, Wire.MAX_DATAGRAM)
        assertEquals(0x80, Wire.F_INITIAL)
        assertEquals(0x40, Wire.F_HANDSHAKE)
        assertEquals(0x20, Wire.F_REPAIR)
        assertEquals(0x0F, Wire.F_GREASE_MASK)
    }

    // ------------------------------------------------------------------ long header

    /** `flags(1) connId(8) pathId(1) pathPacketNumber(4)` - matches SPEC "Packet". */
    @Test fun packetHeader() {
        val h = PacketHeader(0x80, ConnId(0x0102030405060708L), PathId(3), 0xDEADBEEFL)
        val v = "80" + "0102030405060708" + "03" + "deadbeef"
        assertEquals(v, enc { h.write(it) })
        assertEquals(h, PacketHeader.read(ByteBuffer.wrap(bytes(v))))
        assertEquals(v, enc { PacketHeader.read(ByteBuffer.wrap(bytes(v))).write(it) })
    }

    // ------------------------------------------------------------------ frames

    /** `0x01 msgId(8) offset(4) fin(1) len(2) data` - SPEC `0x01 Msg(msgId, offset, fin, data)`. */
    @Test fun msgFrame() {
        roundTrip("01" + "0102030405060708" + "11223344" + "01" + "0004" + "deadbeef",
            Frame.Msg(0x0102030405060708L, 0x11223344, true, payload()))
        roundTrip("01" + "0000000000000001" + "00000000" + "00" + "0000",
            Frame.Msg(1, 0, false, ByteBuffer.allocate(0)))
    }

    /** `0x02 path(1) largest(4) ecnCe(8) rxTimeUs(8) nRanges(1) [first(4) last(4)]*` - SPEC `0x02 Ack(...)`.
     *  Note `largest` is 4 bytes on the wire though the field is a Long: values above 2^32 do not survive. */
    @Test fun ackFrame() {
        roundTrip("02" + "02" + "00001234" + "0000000000000007" + "0a0b0c0d0e0f1011" + "02" +
            "0000000100000005" + "0000000900000009",
            Frame.Ack(PathId(2), 0x1234, listOf(1L..5L, 9L..9L), 7, 0x0A0B0C0D0E0F1011L))
        roundTrip("02" + "00" + "00000000" + "0000000000000000" + "0000000000000000" + "00",
            Frame.Ack(PathId(0), 0, emptyList(), 0, 0))
    }

    /** `0x03 path(1) creditBytes(8) priority(1)` - SPEC `0x03 Grant(path, creditBytes, priority)`. */
    @Test fun grantFrame() =
        roundTrip("03" + "01" + "0011223344556677" + "c8", Frame.Grant(PathId(1), 0x0011223344556677L, 200))

    /** `0x04 windowBase(8) windowLen(2) seed(4) len(2) symbol` - SPEC `0x04 Repair(...)`. */
    @Test fun repairFrame() =
        roundTrip("04" + "0102030405060708" + "0040" + "11223344" + "0004" + "deadbeef",
            Frame.Repair(0x0102030405060708L, 64, 0x11223344, payload()))

    /** `0x05 path(1) nonce(8)` - SPEC `0x05 PathChallenge`. */
    @Test fun pathChallengeFrame() =
        roundTrip("05" + "04" + "7766554433221100", Frame.PathChallenge(PathId(4), 0x7766554433221100uL.toLong()))

    /** `0x06` - SPEC `0x06 Ping`. */
    @Test fun pingFrame() = roundTrip("06", Frame.Ping)

    /** `0x07 path(1) nonce(8)` - SPEC `0x07 PathResponse`. */
    @Test fun pathResponseFrame() =
        roundTrip("07" + "05" + "0123456789abcdef", PathResponse(PathId(5), 0x0123456789ABCDEFL))

    /**
     * `0x81 len(1) zero(len)`, chunked so no chunk ever leaves a 1-byte remainder.
     * SPEC gap: SPEC lists `0x80+` as "extension/grease (length-prefixed, skippable)" but does not name
     * `0x81 = Padding` or the 2..257 B chunk rule. Documented in `Frames.kt` only - flagged as a finding.
     */
    @Test fun paddingFrame() {
        assertEquals("", enc { Frame.Padding(0).write(it) })
        assertEquals("8100", enc { Frame.Padding(2).write(it) })
        assertEquals("81080000000000000000", enc { Frame.Padding(10).write(it) })
        assertEquals("81ff" + "00".repeat(255), enc { Frame.Padding(257).write(it) })
        // 258 would leave a 1-byte remainder after a 257 B chunk, so it goes 256 + 2
        assertEquals("81fe" + "00".repeat(254) + "8100", enc { Frame.Padding(258).write(it) })
        assertEquals(Frame.Padding(10), FrameCodec.read(ByteBuffer.wrap(bytes("81080000000000000000"))))
    }

    /** Unknown `0x80+` frames are length-prefixed and skipped; the next known frame is returned. */
    @Test fun extensionFrameIsSkipped() =
        assertEquals(Frame.Ping, FrameCodec.read(ByteBuffer.wrap(bytes("8204" + "01020304" + "06"))))

    // ------------------------------------------------------------------ varints

    /** QUIC-style varints: 2 prefix bits select 1/2/4/8 bytes, holding 6/14/30/62 value bits. */
    @Test fun varIntSizeBoundaries() {
        val vectors = listOf(
            0L to "00",
            0x3FL to "3f",                              // largest 1-byte
            0x40L to "4040",                            // smallest 2-byte
            0x3FFFL to "7fff",                          // largest 2-byte
            0x4000L to "80004000",                      // smallest 4-byte
            0x3FFF_FFFFL to "bfffffff",                 // largest 4-byte
            0x4000_0000L to "c000000040000000",         // smallest 8-byte
            0x3FFF_FFFF_FFFF_FFFFL to "ffffffffffffffff" // largest encodable
        )
        for ((v, h) in vectors) {
            assertEquals(h, enc { VarInt.write(it, v) }, "VarInt.write($v)")
            assertEquals(h.length / 2, VarInt.size(v), "VarInt.size($v)")
            assertEquals(v, VarInt.read(ByteBuffer.wrap(bytes(h))), "VarInt.read($h)")
        }
    }

    // ------------------------------------------------------------------ short header

    /**
     * `flags(1) shortConnId(4) pn(pnLen)`; flags = `0 | pnLen-1 (bits 6-5) | pathId (bits 4-2) | keyPhase (bits 1-0)`.
     * SPEC v0.2 says "1-4 byte truncated PN", but [ShortHeader.MIN_PN_LEN] is 2, so `write` never emits a
     * 1-byte PN - pnLen 1 is parseable but unreachable from this encoder. Flagged as a SPEC/code mismatch.
     */
    @Test fun shortHeaderAtEachPnLen() {
        assertEquals(2, ShortHeader.MIN_PN_LEN)
        // pnLen 2: flags 0x34 = pnLen-1 1 | path 5 | phase 0
        check2("34" + "11223344" + "03e8", PathId(5), 0x11223344, 1000, 999, 0)
        // pnLen 3: flags 0x43 = pnLen-1 2 | path 0 | phase 3
        check2("43" + "ffffffff" + "007fff", PathId(0), -1, 0x7FFF, 0, 3)
        // pnLen 3, path 7, phase 1
        check2("5d" + "0a0b0c0d" + "040000", PathId(7), 0x0A0B0C0D, 0x40000, 0, 1)
        // pnLen 4: flags 0x6e = pnLen-1 3 | path 3 | phase 2
        check2("6e" + "00000001" + "04000000", PathId(3), 1, 0x4000000, 0, 2)
    }

    private fun check2(h: String, path: PathId, conn: Int, pn: Long, largestAcked: Long, phase: Int) {
        assertEquals(h, enc { ShortHeader.write(it, path, conn, pn, largestAcked, phase) })
        val p = ShortHeader.read(ByteBuffer.wrap(bytes(h)), pn - 1)
        assertEquals(ShortHeader.Parsed(path, conn, pn, phase), p)
        assertEquals(h, enc { ShortHeader.write(it, p.path, p.shortConn, p.pn, largestAcked, p.keyPhase) })
    }

    // ------------------------------------------------------------------ compact msg

    /** type byte `0b0001_0OFL`: O = offset present, F = fin, L = length present. */
    @Test fun compactMsg() {
        // no offset, no fin, last frame in the packet (length implied)
        checkCompact("10" + "03" + "deadbeef", 10, 7, 0, false, true)
        // same but not last: explicit length varint
        checkCompact("11" + "03" + "04" + "deadbeef", 10, 7, 0, false, false)
        // offset + fin + length; msgId delta 0x4000 needs a 4-byte varint, offset 0x1234 a 2-byte one
        checkCompact("17" + "80004000" + "5234" + "04" + "deadbeef", 0x4000, 0, 0x1234, true, false)
        // offset + fin, last frame
        checkCompact("16" + "01" + "3f" + "deadbeef", 1, 0, 63, true, true)
    }

    private fun checkCompact(h: String, msgId: Long, prev: Long, offset: Long, fin: Boolean, last: Boolean) {
        assertEquals(h, enc { CompactMsg.write(it, msgId, prev, offset, fin, payload(), true.let { _ -> last }) })
        val m = CompactMsg.read(ByteBuffer.wrap(bytes(h)), prev)
        assertEquals(msgId, m.msgId); assertEquals(offset.toInt(), m.offset); assertEquals(fin, m.fin)
        assertEquals("deadbeef", hex(ByteArray(m.data.remaining()).also { m.data.duplicate().get(it) }))
        assertEquals(h, enc { CompactMsg.write(it, m.msgId, prev, m.offset.toLong(), m.fin, m.data.duplicate(), last) })
    }

    // ------------------------------------------------------------------ conn params

    /** TLV `tag(varint) len(varint) value(varint)` repeated, terminated by a single `0x00` tag.
     *  Only non-default values are emitted, so the all-defaults encoding is the terminator alone. */
    @Test fun connParams() {
        checkParams("00", ConnParams())
        checkParams("0504" + "91223344" + "00", ConnParams(shortConnId = 0x11223344))
        checkParams(
            "010108" +              // tag 1 tagLen = 8
                "0202" + "5234" +   // tag 2 dictId = 0x1234
                "0302" + "44b0" +   // tag 3 maxDatagram = 1200
                "040104" +          // tag 4 ackFreq = 4
                "0508" + "c0000000deadbeef" +   // tag 5 shortConnId = 0xdeadbeef (8-byte varint: > 2^30)
                "0602" + "5388" +   // tag 6 zeroRttReplayWindowMs = 5000
                "00",
            ConnParams(tagLen = 8, dictId = 0x1234, maxDatagram = 1200, ackFreq = 4,
                shortConnId = -559038737, zeroRttReplayWindowMs = 5000))
    }

    private fun checkParams(h: String, p: ConnParams) {
        assertEquals(h, enc { p.write(it) })
        assertEquals(p, ConnParams.read(ByteBuffer.wrap(bytes(h))))
        assertEquals(h, enc { ConnParams.read(ByteBuffer.wrap(bytes(h))).write(it) })
    }

    // ------------------------------------------------------------------ helper

    /** bytes == frame.write, and bytes -> FrameCodec.read -> write == the same bytes. */
    private fun roundTrip(h: String, f: Frame) {
        assertEquals(h, enc { f.write(it) }, "encoding of $f")
        val parsed = FrameCodec.read(ByteBuffer.wrap(bytes(h)))!!
        assertEquals(h, enc { parsed.write(it) }, "re-encoding of the parsed $f")
    }
}
