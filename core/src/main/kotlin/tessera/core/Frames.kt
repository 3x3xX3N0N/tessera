package tessera.core

import java.nio.ByteBuffer

/** Frame types. 0x00-0x7F reserved; 0x80+ are length-prefixed extension/grease frames, skipped if unknown. */
sealed interface Frame {
    fun write(buf: ByteBuffer)

    /** Message-oriented data. Streams are a library concern, not transport. */
    data class Msg(val msgId: Long, val offset: Int, val fin: Boolean, val data: ByteBuffer) : Frame {
        override fun write(buf: ByteBuffer) {
            require(data.remaining() <= 0xFFFF) { "Msg payload ${data.remaining()} B exceeds the 16-bit wire length" }
            buf.put(0x01).putLong(msgId).putInt(offset).put(if (fin) 1 else 0)
                .putShort(data.remaining().toShort()).put(data.duplicate())
        }
    }

    /** Per-path ACK with ECN-CE count and receive timestamp for one-way-delay estimation. */
    data class Ack(val path: PathId, val largest: Long, val ranges: List<LongRange>, val ecnCe: Long, val rxTimeUs: Long) : Frame {
        override fun write(buf: ByteBuffer) {
            require(largest <= 0xFFFF_FFFFL) { "ack largest $largest exceeds the 32-bit wire field" }
            buf.put(0x02).put(path.raw.toByte()).putInt(largest.toInt()).putLong(ecnCe).putLong(rxTimeUs).put(ranges.size.toByte())
            ranges.forEach { buf.putInt(it.first.toInt()).putInt(it.last.toInt()) }
        }
    }

    /**
     * Receiver-driven credit (Homa lineage), **cumulative**: [creditBytes] is the absolute credit limit — the total
     * number of credit-charged bytes (source, repair, re-send and probe datagrams, see `SenderCredit`) the sender may
     * have sent on this path since it was set up. It is not a delta: a sender keeps `max(limit, creditBytes)`, so a
     * later grant supersedes a lost one, re-sent and piggybacked grants are idempotent, and reordered grants are
     * harmless (v0.5 grants were additive deltas and a lost one stalled the sender until the re-send timer).
     * Wire: `0x03 path(1) creditBytes(8) priority(1)`.
     */
    data class Grant(val path: PathId, val creditBytes: Long, val priority: Int) : Frame {
        override fun write(buf: ByteBuffer) {
            buf.put(0x03).put(path.raw.toByte()).putLong(creditBytes).put(priority.toByte())
        }
    }

    /** RLNC repair symbol over [windowBase, windowBase+windowLen). Coefficients regenerated from seed. */
    data class Repair(val windowBase: Long, val windowLen: Int, val seed: Int, val symbol: ByteBuffer) : Frame {
        override fun write(buf: ByteBuffer) {
            require(symbol.remaining() <= 0xFFFF) { "repair symbol ${symbol.remaining()} B exceeds the 16-bit wire length" }
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

    /**
     * Connection close, frame `0x08`: `0x08 code(1) reasonLen(1) reason(reasonLen)`. Tells the peer this connection
     * is being torn down so it can free state immediately instead of waiting out its idle timeout. Sent inside an
     * authenticated packet like any other frame; `code` 0 is a normal application close, non-zero is an error.
     * (Distinct from a stateless reset ([StatelessReset]), which covers the case where the sender has *lost* its keys
     * and cannot authenticate a frame at all — a restarted server.)
     */
    data class Close(val code: Int, val reason: String) : Frame {
        override fun write(buf: ByteBuffer) {
            val r = reason.toByteArray(Charsets.UTF_8)
            require(r.size <= 255) { "close reason ${r.size} B exceeds 255" }
            buf.put(0x08).put(code.toByte()).put(r.size.toByte()).put(r)
        }
        companion object {
            const val TYPE = 0x08
            /** Normal application close. */
            const val CODE_APP = 0
            /**
             * The sender's message can never be delivered and will never be retransmitted: the receiver's reassembler
             * hit a cap and destroyed it (`Connection.Reassembler.abandon`). Fatal by construction — a reliable
             * transport that loses a message has broken its contract, and the only honest report is a failed
             * connection. The reason names the msg id. (2026-08-30; before this the loss was silent and both ends
             * hung, BENCH "The discriminator ran, and refuted its own hypothesis".)
             */
            const val CODE_UNDELIVERABLE = 1
        }
    }

    /**
     * Connection-level flow control, frame `0x09`: `0x09 limitBytes(8)`. [limitBytes] is the absolute cumulative
     * limit in **app-payload bytes** (pre-encode, the unit of one `send()` call; the 0-RTT first flight counts too)
     * the peer may have committed to this connection since setup. The receiver computes it as consumed + window,
     * so it is monotone; the sender keeps `max(limit, limitBytes)` ([FlowSender]), making re-sent and piggybacked
     * adverts idempotent — a lost one is superseded by the copy on the next ACK. Deliberately independent of the
     * congestion credit ([Grant], charged wire bytes): credit paces the network, this bounds the receiver's
     * delivered-but-unread memory (RFC 9000 §4.1 shape, per-connection only — no per-stream windows).
     */
    data class MaxData(val limitBytes: Long) : Frame {
        override fun write(buf: ByteBuffer) { buf.put(TYPE.toByte()).putLong(limitBytes) }
        companion object { const val TYPE = 0x09 }
    }

    /**
     * ACK cadence request, frame `0x0A`: `0x0A ackFreq(1) maxAckDelayUs(4)`. The *sender* of this frame asks its peer
     * to change how often the peer acknowledges **our** packets: ack every [ackFreq] ack-eliciting packets, and never
     * sit on one longer than [maxAckDelayUs]. [ConnParams.ackFreq] does the same thing once, at setup; this frame is
     * the mid-connection form, because the right cadence is a property of the path (rate, RTT, uplink cost) and the
     * path changes — a rebind onto a metered radio, a rate that climbs two orders of magnitude during slow start.
     *
     * It is a *request*, and only about cadence: the peer stays free to ack sooner, and does. Reordering and gaps
     * still force an immediate ACK inside [AckTracker], so raising the frequency never blinds RACK — what it delays
     * is the steady in-order case, which is the only case that is cheap to delay.
     *
     * Idempotent and unreliable by design: it is not retransmitted, and a lost one is superseded by the next. Both
     * fields are clamped by the receiver ([MAX_FREQ], [MAX_DELAY_US]) so a hostile or buggy peer cannot ask us to
     * stop acking — the two live bounds on how long a sender can be left without feedback.
     */
    data class AckFrequency(val ackFreq: Int, val maxAckDelayUs: Long) : Frame {
        override fun write(buf: ByteBuffer) {
            buf.put(TYPE.toByte()).put(clampFreq(ackFreq).toByte()).putInt(clampDelay(maxAckDelayUs).toInt())
        }
        companion object {
            const val TYPE = 0x0A
            /** An ack every 255 packets is 128 ms at 2000 pkt/s; beyond that the sender's RTT sampling starves. */
            const val MAX_FREQ = 255
            /** A quarter second: longer than any measured srtt in the netem matrix, short enough that a PTO still bounds the tail. */
            const val MAX_DELAY_US = 250_000L
            fun clampFreq(v: Int) = v.coerceIn(1, MAX_FREQ)
            fun clampDelay(v: Long) = v.coerceIn(0L, MAX_DELAY_US)
        }
    }

    /**
     * Padding, extension frame 0x81: `0x81 len(1) zero(len)`, i.e. 2..257 wire bytes per chunk; [bytes] is the total
     * wire size and is written as as many chunks as needed (never leaving a 1-byte remainder). Used to reach the
     * header-protection sample size on tiny packets and to build DPLPMTUD probes. Skippable by peers that do not know it.
     */
    data class Padding(val bytes: Int) : Frame {
        override fun write(buf: ByteBuffer) = writeTo(buf, bytes)

        companion object {
            const val TYPE = 0x81
            const val MAX_CHUNK = 257
            /** Allocation-free form of [write]; `bytes` must be 0 or >= 2. */
            fun writeTo(buf: ByteBuffer, bytes: Int) {
                require(bytes == 0 || bytes >= 2) { "padding of $bytes bytes is not encodable" }
                var left = bytes
                while (left > 0) {
                    val chunk = if (left <= MAX_CHUNK) left else if (left - MAX_CHUNK == 1) MAX_CHUNK - 1 else MAX_CHUNK
                    buf.put(TYPE.toByte()).put((chunk - 2).toByte())
                    for (i in 0 until chunk - 2) buf.put(0)
                    left -= chunk
                }
            }
        }
    }
}

object FrameCodec {
    /**
     * Reads one frame, skipping any unknown `0x80+` extension frames ahead of it. The skip is a loop, not a
     * self-call: a datagram is attacker-chosen, and a long run of minimal extension frames (`0x80 0x00 ...`)
     * once recursed once per frame and overflowed the stack at ~50k of them. (fuzz finding)
     */
    fun read(buf: ByteBuffer): Frame? {
        while (true) {
            if (!buf.hasRemaining()) return null
            val t = buf.get().toInt() and 0xFF
            if (t >= 0x80 && t != Frame.Padding.TYPE) {
                val len = buf.get().toInt() and 0xFF
                require(len <= buf.remaining()) { "extension frame of $len B past the end of the packet" }
                buf.position(buf.position() + len)
                continue
            }
            return readKnown(buf, t)
        }
    }

    private fun readKnown(buf: ByteBuffer, t: Int): Frame {
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
            0x03 -> Frame.Grant(PathId(buf.get().toInt() and 0xFF), buf.getLong(), buf.get().toInt() and 0xFF)
            0x04 -> {
                val wb = buf.getLong(); val wl = buf.getShort().toInt() and 0xFFFF; val s = buf.getInt()
                val len = buf.getShort().toInt() and 0xFFFF
                val d = buf.slice().limit(len); buf.position(buf.position() + len)
                Frame.Repair(wb, wl, s, d)
            }
            0x05 -> Frame.PathChallenge(PathId(buf.get().toInt() and 0xFF), buf.getLong())
            0x06 -> Frame.Ping
            0x07 -> PathResponse(PathId(buf.get().toInt() and 0xFF), buf.getLong()) // type byte already consumed
            0x08 -> { // type byte already consumed
                val code = buf.get().toInt() and 0xFF
                val len = buf.get().toInt() and 0xFF
                require(len <= buf.remaining()) { "close reason len $len exceeds ${buf.remaining()}" }
                val r = ByteArray(len).also { buf.get(it) }
                Frame.Close(code, String(r, Charsets.UTF_8))
            }
            Frame.MaxData.TYPE -> {
                val limit = buf.getLong()
                require(limit >= 0) { "MaxData limit $limit is negative" }
                Frame.MaxData(limit)
            }
            Frame.AckFrequency.TYPE -> {
                val f = buf.get().toInt() and 0xFF
                val d = buf.getInt().toLong() and 0xFFFF_FFFFL
                Frame.AckFrequency(Frame.AckFrequency.clampFreq(f), Frame.AckFrequency.clampDelay(d))
            }
            Frame.Padding.TYPE -> {
                val len = buf.get().toInt() and 0xFF
                buf.position(buf.position() + len)
                Frame.Padding(2 + len)
            }
            else -> throw IllegalArgumentException("unknown frame type $t")
        }
    }
}
