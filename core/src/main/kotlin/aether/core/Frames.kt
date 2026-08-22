package aether.core

import java.nio.ByteBuffer

/** Frame types. 0x00-0x7F reserved; 0x80+ are length-prefixed extension/grease frames, skipped if unknown. */
sealed interface Frame {
    fun write(buf: ByteBuffer)

    /** Message-oriented data. Streams are a library concern, not transport. */
    data class Msg(val msgId: Long, val offset: Int, val fin: Boolean, val data: ByteBuffer) : Frame {
        override fun write(buf: ByteBuffer) {
            buf.put(0x01).putLong(msgId).putInt(offset).put(if (fin) 1 else 0)
                .putShort(data.remaining().toShort()).put(data.duplicate())
        }
    }

    /** Per-path ACK with ECN-CE count and receive timestamp for one-way-delay estimation. */
    data class Ack(val path: PathId, val largest: Long, val ranges: List<LongRange>, val ecnCe: Long, val rxTimeUs: Long) : Frame {
        override fun write(buf: ByteBuffer) {
            buf.put(0x02).put(path.raw.toByte()).putInt(largest.toInt()).putLong(ecnCe).putLong(rxTimeUs).put(ranges.size.toByte())
            ranges.forEach { buf.putInt(it.first.toInt()).putInt(it.last.toInt()) }
        }
    }

    /** Receiver-driven credit: bytes the sender may inject on this path. Core of Homa-style CC. */
    data class Grant(val path: PathId, val creditBytes: Int, val priority: Int) : Frame {
        override fun write(buf: ByteBuffer) {
            buf.put(0x03).put(path.raw.toByte()).putInt(creditBytes).put(priority.toByte())
        }
    }

    /** RLNC repair symbol over [windowBase, windowBase+windowLen). Coefficients regenerated from seed. */
    data class Repair(val windowBase: Long, val windowLen: Int, val seed: Int, val symbol: ByteBuffer) : Frame {
        override fun write(buf: ByteBuffer) {
            buf.put(0x04).putLong(windowBase).putShort(windowLen.toShort()).putInt(seed)
                .putShort(symbol.remaining().toShort()).put(symbol.duplicate())
        }
    }

    data class PathChallenge(val path: PathId, val nonce: Long) : Frame {
        override fun write(buf: ByteBuffer) { buf.put(0x05).put(path.raw.toByte()).putLong(nonce) }
    }

    data object Ping : Frame {
        override fun write(buf: ByteBuffer) { buf.put(0x06) }
    }
}

object FrameCodec {
    fun read(buf: ByteBuffer): Frame? {
        if (!buf.hasRemaining()) return null
        val t = buf.get().toInt() and 0xFF
        return when (t) {
            0x01 -> {
                val id = buf.getLong(); val off = buf.getInt(); val fin = buf.get().toInt() != 0
                val len = buf.getShort().toInt() and 0xFFFF
                val d = buf.slice().limit(len); buf.position(buf.position() + len)
                Frame.Msg(id, off, fin, d)
            }
            0x02 -> {
                val p = PathId(buf.get().toInt() and 0xFF)
                val l = buf.getInt().toLong() and 0xFFFFFFFFL
                val ce = buf.getLong(); val ts = buf.getLong()
                val n = buf.get().toInt() and 0xFF
                val r = List(n) {
                    val a = buf.getInt().toLong() and 0xFFFFFFFFL
                    val b = buf.getInt().toLong() and 0xFFFFFFFFL
                    a..b
                }
                Frame.Ack(p, l, r, ce, ts)
            }
            0x03 -> Frame.Grant(PathId(buf.get().toInt() and 0xFF), buf.getInt(), buf.get().toInt() and 0xFF)
            0x04 -> {
                val wb = buf.getLong(); val wl = buf.getShort().toInt() and 0xFFFF; val s = buf.getInt()
                val len = buf.getShort().toInt() and 0xFFFF
                val d = buf.slice().limit(len); buf.position(buf.position() + len)
                Frame.Repair(wb, wl, s, d)
            }
            0x05 -> Frame.PathChallenge(PathId(buf.get().toInt() and 0xFF), buf.getLong())
            0x06 -> Frame.Ping
            else -> if (t >= 0x80) {
                val len = buf.get().toInt() and 0xFF
                buf.position(buf.position() + len)
                read(buf)
            } else throw IllegalArgumentException("unknown frame type $t")
        }
    }
}
