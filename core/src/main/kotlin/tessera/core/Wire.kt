package tessera.core

import java.nio.ByteBuffer

/** Tessera wire format v0. Fixed header, then frames. */
object Wire {
    const val VERSION: Int = 0x54530000 // "TS" (Tessera) + v0
    const val HEADER_LEN = 1 + 8 + 1 + 4  // flags, connId, pathId, pathPacketNumber
    const val MAX_DATAGRAM = 1350          // conservative MTU; DPLPMTUD later

    const val F_INITIAL: Int = 0x80
    const val F_HANDSHAKE: Int = 0x40
    const val F_REPAIR: Int = 0x20       // payload is RLNC repair symbol
    const val F_GREASE_MASK: Int = 0x0F  // randomized by sender, ignored by receiver
}

/** Connection identity is a key, not a 4-tuple: migration is free. */
@JvmInline value class ConnId(val raw: Long)
@JvmInline value class PathId(val raw: Int)

data class PacketHeader(val flags: Int, val conn: ConnId, val path: PathId, val pn: Long) {
    fun write(buf: ByteBuffer) {
        buf.put(flags.toByte()).putLong(conn.raw).put(path.raw.toByte()).putInt(pn.toInt())
    }
    companion object {
        fun read(buf: ByteBuffer): PacketHeader {
            val f = buf.get().toInt() and 0xFF
            val c = buf.getLong()
            val p = buf.get().toInt() and 0xFF
            val pn = buf.getInt().toLong() and 0xFFFFFFFFL
            return PacketHeader(f, ConnId(c), PathId(p), pn)
        }
    }
}
