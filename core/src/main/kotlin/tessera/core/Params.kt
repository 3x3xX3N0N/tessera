package tessera.core

import java.nio.ByteBuffer

/**
 * Connection parameters negotiated in the first flight (client offer) and confirmed in the server's reply.
 * Encoded as TLV with varint tags so new params are skippable by old peers.
 */
data class ConnParams(
    /** AEAD tag bytes: 16 (default) or 8 (forgery 2^-64/packet; OK for media/game state, not transactions). */
    val tagLen: Int = 16,
    /** Shared-dictionary id for payload compression; 0 = none. Both sides must hold the same trained dict. */
    val dictId: Long = 0,
    val maxDatagram: Int = Wire.MAX_DATAGRAM,
    val ackFreq: Int = 2,            // ack every N packets (grants are never delayed)
    val shortConnId: Int = 0,        // server-assigned 32-bit id for short headers
    val zeroRttReplayWindowMs: Int = 10_000,
) {
    fun write(buf: ByteBuffer) {
        fun tlv(tag: Long, v: Long) { VarInt.write(buf, tag); VarInt.write(buf, VarInt.size(v).toLong()); VarInt.write(buf, v) }
        if (tagLen != 16) tlv(1, tagLen.toLong())
        if (dictId != 0L) tlv(2, dictId)
        if (maxDatagram != Wire.MAX_DATAGRAM) tlv(3, maxDatagram.toLong())
        if (ackFreq != 2) tlv(4, ackFreq.toLong())
        if (shortConnId != 0) tlv(5, shortConnId.toLong() and 0xFFFF_FFFFL)
        if (zeroRttReplayWindowMs != 10_000) tlv(6, zeroRttReplayWindowMs.toLong())
        buf.put(0) // end
    }
    companion object {
        fun read(buf: ByteBuffer): ConnParams {
            var p = ConnParams()
            while (true) {
                val tag = VarInt.read(buf); if (tag == 0L) return p
                val len = VarInt.read(buf).toInt()
                val end = buf.position() + len
                val v = VarInt.read(buf)
                when (tag) {
                    1L -> p = p.copy(tagLen = v.toInt().also { require(it == 8 || it == 16) })
                    2L -> p = p.copy(dictId = v)
                    3L -> p = p.copy(maxDatagram = v.toInt())
                    4L -> p = p.copy(ackFreq = v.toInt())
                    5L -> p = p.copy(shortConnId = v.toInt())
                    6L -> p = p.copy(zeroRttReplayWindowMs = v.toInt())
                    else -> {} // unknown: skip
                }
                buf.position(end)
            }
        }
    }
}

/** Pluggable payload codec; default is identity. A zstd shared-dictionary codec plugs in here (bench first). */
interface PayloadCodec {
    val dictId: Long
    fun encode(src: ByteArray): ByteArray
    fun decode(src: ByteArray): ByteArray
    object Identity : PayloadCodec { override val dictId = 0L; override fun encode(src: ByteArray) = src; override fun decode(src: ByteArray) = src }
}
