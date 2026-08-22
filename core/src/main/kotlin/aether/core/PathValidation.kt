package aether.core

import java.nio.ByteBuffer
import java.util.Random

/**
 * Echo of a [Frame.PathChallenge] nonce. Frame type 0x07.
 *
 * Defined here (not in Frames.kt, which this module must not touch) as a member of the same sealed [Frame]
 * hierarchy — same package and module, so it writes polymorphically like every other frame.
 * Integration pending: [FrameCodec.read] does not know 0x07 yet and rejects it as unknown; decode with
 * [PathResponse.read] after peeking the type byte, and add `0x07 -> PathResponse.read(buf)` to FrameCodec
 * (plus the SPEC.md frame table) when Frames.kt is next edited.
 */
data class PathResponse(val path: PathId, val nonce: Long) : Frame {
    override fun write(buf: ByteBuffer) { buf.put(TYPE.toByte()).put(path.raw.toByte()).putLong(nonce) }

    companion object {
        const val TYPE = 0x07
        /** Reads a whole frame, type byte included. */
        fun read(buf: ByteBuffer): PathResponse {
            val t = buf.get().toInt() and 0xFF
            require(t == TYPE) { "not a PathResponse: frame type $t" }
            return PathResponse(PathId(buf.get().toInt() and 0xFF), buf.getLong())
        }
    }
}

/**
 * Per-path address validation and anti-amplification. Same logic as RFC 9000 §8/§9 (the standardized mechanism),
 * written fresh for Aether.
 *
 * Until the peer proves it can receive at this path's address we may send at most [AMPLIFICATION_FACTOR] × the bytes
 * received from it: [canSend] is a pure check, so pair it with [onSent] (as with [SenderCredit]) and call
 * [onReceived] for every datagram accepted from the address — including the one that triggered a migration.
 * Validation is a nonce echo: [challenge] issues a random 64-bit nonce (up to [MAX_OUTSTANDING] kept, oldest evicted
 * so a retransmitted challenge and its predecessor both still count); [onResponse] with a matching nonce validates.
 * [onMigration] (peer seen from a new address) drops back to unvalidated with a fresh budget and forgets outstanding
 * nonces, so an off-path attacker spoofing the peer's source address cannot redirect more than 3× its own bytes.
 */
class PathValidation(val path: PathId, private val random: Random, address: Any? = null) {
    companion object {
        const val AMPLIFICATION_FACTOR = 3
        const val MAX_OUTSTANDING = 3
    }

    /** Peer address this path currently maps to (transport passes its socket address; opaque to core). */
    var address: Any? = address; private set
    var validated = false; private set
    var bytesReceived = 0L; private set
    var bytesSent = 0L; private set
    private val outstanding = ArrayDeque<Long>()
    val outstandingChallenges: Int get() = outstanding.size

    fun onReceived(bytes: Int) { bytesReceived += bytes }

    /** True if `bytes` more may be sent: always once validated, else while within the amplification budget. */
    fun canSend(bytes: Int): Boolean = validated || bytesSent + bytes <= AMPLIFICATION_FACTOR * bytesReceived

    fun onSent(bytes: Int) { bytesSent += bytes }

    /** New challenge to send on this path; its nonce stays valid until answered, evicted or the path migrates. */
    fun challenge(): Frame.PathChallenge {
        val nonce = random.nextLong()
        if (outstanding.size >= MAX_OUTSTANDING) outstanding.removeFirst()
        outstanding.addLast(nonce)
        return Frame.PathChallenge(path, nonce)
    }

    /** True (and the path becomes validated) iff the nonce matches an outstanding challenge. */
    fun onResponse(nonce: Long): Boolean {
        if (!outstanding.remove(nonce)) return false
        validated = true
        outstanding.clear()
        return true
    }

    /** Frame form of [onResponse]; a response carrying another path id is ignored. */
    fun onResponse(r: PathResponse): Boolean = r.path == path && onResponse(r.nonce)

    /** Validation implied by other means (e.g. the handshake completed on this path). */
    fun markValidated() { validated = true; outstanding.clear() }

    /** Peer now appears at `newAddress`: back to unvalidated with a fresh amplification budget. Same address: no-op. */
    fun onMigration(newAddress: Any) {
        if (newAddress == address) return
        address = newAddress
        validated = false
        bytesReceived = 0; bytesSent = 0
        outstanding.clear()
    }
}
