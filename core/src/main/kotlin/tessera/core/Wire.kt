package tessera.core

import java.nio.ByteBuffer

/** Tessera wire format v0. Fixed header, then frames. */
object Wire {
    /**
     * "TS" (0x5453) + 16-bit version. Since 2026-08-29 every long-header packet carries this word on the wire,
     * directly after the flags byte — readable before any key exists, which is the entire point: a version skew
     * used to surface as an AEAD decrypt failure blaming the crypto for a framing problem (TODO §11). The top
     * 16 bits are a magic tag: a long header whose version word does not start 0x5453 is not Tessera talking a
     * different version, it is not Tessera, and is dropped rather than answered.
     */
    const val VERSION: Int = 0x54530001 // "TS" + v1 of the long header (v0 carried no version word)
    const val VERSION_TAG_MASK: Int = -0x10000    // 0xFFFF0000: the "TS" magic
    /**
     * Grease versions: TS-tagged, low word matching 0xXAYA (QUIC's 0x?a?a?a?a idea scaled to our 16-bit space).
     * A version-mismatch notice always carries one alongside the real version, so every client parses
     * multi-entry version lists and skips unknown entries from day one — the property that keeps the version
     * field usable later is that nothing on the path can assume today's list shape. A receiver treats a greased
     * INITIAL exactly like any other unknown version (the ordinary mismatch path); there is deliberately no
     * special case anywhere for grease, because a special case is the thing middleboxes fossilize on.
     */
    fun isGreaseVersion(v: Int) = (v and VERSION_TAG_MASK) == (VERSION and VERSION_TAG_MASK) && (v and 0x0F0F) == 0x0A0A
    fun greaseVersion(rnd: java.util.Random): Int =
        (VERSION and VERSION_TAG_MASK) or 0x0A0A or (rnd.nextInt(16) shl 12) or (rnd.nextInt(16) shl 4)
    const val HEADER_LEN = 1 + 8 + 1 + 4  // flags, connId, pathId, pathPacketNumber (short header; long adds version(4))
    const val LONG_HEADER_LEN = 1 + 4 + 8 + 1 + 4 // flags, version, connId, pathId, pn
    const val MAX_DATAGRAM = 1350          // conservative MTU; DPLPMTUD later

    const val F_INITIAL: Int = 0x80
    const val F_HANDSHAKE: Int = 0x40
    const val F_REPAIR: Int = 0x20       // short header only: payload is an RLNC repair symbol
    const val F_RESUME: Int = 0x10       // long header only: the initial is a PSK resumption, not a fresh PQ connect
    /**
     * Long header only (0x20 means [F_REPAIR] on short headers, which never carry [F_INITIAL]):
     *  - on an initial (no [F_HANDSHAKE]): the body starts with `tokenLen(1) | token(tokenLen)`, an address
     *    validation token the server minted for this source (see `AddressValidation.kt`), before the usual
     *    [ZeroRtt]/[Resumption] prefix.
     *  - together with [F_HANDSHAKE] (server -> client): the packet *is* a Retry — header, then
     *    `tokenLen(1) | token(tokenLen)` and nothing else. The client re-sends its initial carrying the token.
     * A peer that predates this flag never sends a token and never receives a Retry (the server only mints one
     * under pressure), so the addition is skippable in exactly the way the rest of the wire format is.
     */
    const val F_TOKEN: Int = 0x20
    const val F_GREASE_MASK: Int = 0x0F  // randomized by sender, ignored by receiver
}

/** Connection identity is a key, not a 4-tuple: migration is free. */
@JvmInline value class ConnId(val raw: Long)
@JvmInline value class PathId(val raw: Int)

data class PacketHeader(val flags: Int, val conn: ConnId, val path: PathId, val pn: Long,
                        /** Long headers only; [Wire.VERSION] on everything this build writes. Short headers carry none. */
                        val version: Int = Wire.VERSION) {
    /** Short-header form (no version word). Long-header packets must use [writeLong]. */
    fun write(buf: ByteBuffer) {
        buf.put(flags.toByte()).putLong(conn.raw).put(path.raw.toByte()).putInt(pn.toInt())
    }
    /** Long-header form: flags, version(4), connId, pathId, pn — the version readable before any key exists. */
    fun writeLong(buf: ByteBuffer) {
        buf.put(flags.toByte()).putInt(version).putLong(conn.raw).put(path.raw.toByte()).putInt(pn.toInt())
    }
    companion object {
        fun read(buf: ByteBuffer): PacketHeader {
            val f = buf.get().toInt() and 0xFF
            val c = buf.getLong()
            val p = buf.get().toInt() and 0xFF
            val pn = buf.getInt().toLong() and 0xFFFFFFFFL
            return PacketHeader(f, ConnId(c), PathId(p), pn)
        }
        /** Long-header read; the caller checks [PacketHeader.version] before trusting anything after it. */
        fun readLong(buf: ByteBuffer): PacketHeader {
            val f = buf.get().toInt() and 0xFF
            val v = buf.getInt()
            val c = buf.getLong()
            val p = buf.get().toInt() and 0xFF
            val pn = buf.getInt().toLong() and 0xFFFFFFFFL
            return PacketHeader(f, ConnId(c), PathId(p), pn, v)
        }
    }
}
