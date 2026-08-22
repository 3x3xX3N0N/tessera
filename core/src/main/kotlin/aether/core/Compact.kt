package aether.core

import java.nio.ByteBuffer

/** QUIC-style variable-length integers (1/2/4/8 bytes, 2 prefix bits). */
object VarInt {
    fun write(buf: ByteBuffer, v: Long) {
        require(v >= 0)
        when {
            v < 0x40 -> buf.put(v.toByte())
            v < 0x4000 -> buf.putShort((v or 0x4000).toShort())
            v < 0x4000_0000 -> buf.putInt((v or 0x8000_0000L).toInt())
            else -> buf.putLong(v or (0xC0L shl 56))
        }
    }
    fun read(buf: ByteBuffer): Long {
        val first = buf.get(buf.position()).toInt() and 0xFF
        return when (first shr 6) {
            0 -> (buf.get().toLong() and 0x3F)
            1 -> (buf.getShort().toLong() and 0x3FFF)
            2 -> (buf.getInt().toLong() and 0x3FFF_FFFF)
            else -> buf.getLong() and 0x3FFF_FFFF_FFFF_FFFFL
        }
    }
    fun size(v: Long): Int = when { v < 0x40 -> 1; v < 0x4000 -> 2; v < 0x4000_0000 -> 4; else -> 8 }
}

/**
 * Compact short header for established connections: 1 flag byte + short connId + truncated packet number.
 *   flags: 1 bit form(0=short) | 2 bits pnLen-1 | 3 bits pathId | 2 bits key-phase/grease
 *   connId: 4 bytes (server-assigned at handshake; the 64-bit one is only in the initial packet)
 *   pn: 1..4 bytes, truncated; receiver reconstructs from largest seen (window = half the pn range).
 * 6 bytes typical vs 14 in v0.
 */
object ShortHeader {
    data class Parsed(val path: PathId, val shortConn: Int, val pn: Long, val keyPhase: Int)

    fun pnLenFor(pn: Long, largestAcked: Long): Int {
        val range = 2 * (pn - largestAcked).coerceAtLeast(1) + 1
        return when { range < 0x80 -> 1; range < 0x8000 -> 2; range < 0x80_0000 -> 3; else -> 4 }
    }

    fun write(buf: ByteBuffer, path: PathId, shortConn: Int, pn: Long, largestAcked: Long, keyPhase: Int = 0) {
        val len = pnLenFor(pn, largestAcked)
        val flags = ((len - 1) shl 5) or ((path.raw and 0x7) shl 2) or (keyPhase and 0x3)
        buf.put(flags.toByte()).putInt(shortConn)
        for (i in len - 1 downTo 0) buf.put((pn shr (8 * i)).toByte())
    }

    fun read(buf: ByteBuffer, largestSeen: Long): Parsed {
        val flags = buf.get().toInt() and 0xFF
        require(flags and 0x80 == 0) { "long header" }
        val len = ((flags shr 5) and 0x3) + 1
        val path = PathId((flags shr 2) and 0x7)
        val conn = buf.getInt()
        var trunc = 0L
        repeat(len) { trunc = (trunc shl 8) or (buf.get().toLong() and 0xFF) }
        return Parsed(path, conn, decodePn(trunc, len * 8, largestSeen), flags and 0x3)
    }

    /** RFC 9000 appendix A.3 reconstruction; not Google's — it's standard sliding-window decode. */
    fun decodePn(truncated: Long, bits: Int, largest: Long): Long {
        val expected = largest + 1
        val win = 1L shl bits; val half = win / 2; val mask = win - 1
        val candidate = (expected and mask.inv()) or truncated
        return when {
            candidate <= expected - half && candidate < (1L shl 62) - win -> candidate + win
            candidate > expected + half && candidate >= win -> candidate - win
            else -> candidate
        }
    }
}

/**
 * Compact Msg frame: type byte carries flags; msgId is a delta from the previous msgId in this packet;
 * offset omitted when zero; length omitted for the last frame in the packet.
 *   type: 0b0001_0OFL   O=offset present, F=fin, L=length present
 */
object CompactMsg {
    fun write(buf: ByteBuffer, msgId: Long, prevMsgId: Long, offset: Long, fin: Boolean, data: ByteBuffer, last: Boolean) {
        val t = 0x10 or (if (offset != 0L) 4 else 0) or (if (fin) 2 else 0) or (if (last) 0 else 1)
        buf.put(t.toByte())
        VarInt.write(buf, msgId - prevMsgId)
        if (offset != 0L) VarInt.write(buf, offset)
        if (!last) VarInt.write(buf, data.remaining().toLong())
        buf.put(data.duplicate())
    }
    fun read(buf: ByteBuffer, prevMsgId: Long): Frame.Msg {
        val t = buf.get().toInt() and 0xFF
        require(t and 0xF8 == 0x10)
        val id = prevMsgId + VarInt.read(buf)
        val off = if (t and 4 != 0) VarInt.read(buf) else 0L
        val len = if (t and 1 != 0) VarInt.read(buf).toInt() else buf.remaining()
        val d = buf.slice().limit(len); buf.position(buf.position() + len)
        return Frame.Msg(id, off.toInt(), t and 2 != 0, d)
    }
}
