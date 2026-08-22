package aether.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * PSK resumption: returning clients skip the KEM entirely. Packet one = ticket(16) | ts(8) | nonce(8) | AEAD(data).
 * First-flight data budget ≈ 1350 - 14 - 32 - tag ≈ 1.29 KB (vs 184 B for a fresh PQ connect).
 *
 * Ticket = server-encrypted blob of (resumption secret, dictId, issue time) so the server stays stateless.
 * Resumption secret derives from the previous session key; forward secrecy of 0-RTT data is bounded by the ticket
 * lifetime — same trade-off as TLS 1.3 PSK. A fresh ephemeral exchange can be piggybacked later to re-key.
 */
object Resumption {
    const val TICKET_LEN = 16 + 32 + 8 + 16 // ticketNonce | enc(secret) | issueMs | tag  -- wire form
    const val PREFIX_LEN = TICKET_LEN + 8 + 8
    const val MAX_FIRST_DATA = Wire.MAX_DATAGRAM - Wire.HEADER_LEN - PREFIX_LEN - 16
    private val rng = SecureRandom()

    fun resumptionSecret(sessionKey: ByteArray): ByteArray = hkdf(sessionKey, "aether-resume".toByteArray())
    private fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, null, info)) }.generateBytes(out, 0, 32); return out
    }

    class Server(private val ticketKey: ByteArray, private val lifetimeMs: Long = 7 * 24 * 3600_000L, private val replayWindowMs: Long = 10_000) {
        private val seen = HashMap<Long, Long>()

        /** Issue after a successful full handshake. Client stores it opaque. */
        fun issueTicket(sessionKey: ByteArray, nowMs: Long): ByteArray {
            val nonce = ByteArray(16).also { rng.nextBytes(it) }
            val body = ByteBuffer.allocate(40).put(resumptionSecret(sessionKey)).putLong(nowMs).array()
            return nonce + Aead.seal(ticketKey, nonce.copyOf(12), nonce, body)
        }

        class Accepted(val key: ByteArray, val data: ByteArray)
        fun accept(body: ByteArray, nowMs: Long): Accepted? {
            if (body.size < PREFIX_LEN + 16) return null
            val bb = ByteBuffer.wrap(body)
            val ticket = ByteArray(TICKET_LEN).also { bb.get(it) }
            val ts = bb.getLong(); val nonce = bb.getLong()
            if (kotlin.math.abs(nowMs - ts) > replayWindowMs) return null
            val tn = ticket.copyOfRange(0, 16)
            val open = Aead.open(ticketKey, tn.copyOf(12), tn, ticket.copyOfRange(16, TICKET_LEN)) ?: return null
            val secret = open.copyOfRange(0, 32); val issued = ByteBuffer.wrap(open, 32, 8).getLong()
            if (nowMs - issued > lifetimeMs) return null
            val key = sessionKey(secret, ts, nonce)
            val fp = ByteBuffer.wrap(tn).getLong() xor nonce
            if (seen.containsKey(fp)) return null
            val data = Aead.open(key, ZeroRtt.nonceFrom(nonce, 0), body.copyOfRange(0, PREFIX_LEN), body.copyOfRange(PREFIX_LEN, body.size)) ?: return null
            seen[fp] = ts
            if (seen.size > 100_000) seen.entries.removeIf { nowMs - it.value > replayWindowMs }
            return Accepted(key, data)
        }
    }

    class Client(private val ticket: ByteArray, private val secret: ByteArray) {
        fun initial(data: ByteArray, nowMs: Long, nonce: Long): Pair<ByteArray, ByteArray> {
            require(data.size <= MAX_FIRST_DATA)
            val key = sessionKey(secret, nowMs, nonce)
            val prefix = ByteBuffer.allocate(PREFIX_LEN).put(ticket).putLong(nowMs).putLong(nonce).array()
            return key to (prefix + Aead.seal(key, ZeroRtt.nonceFrom(nonce, 0), prefix, data))
        }
    }

    /** Per-connection key binds ts+nonce so a replayed ticket with a fresh nonce still yields a distinct key. */
    fun sessionKey(secret: ByteArray, ts: Long, nonce: Long): ByteArray =
        hkdf(secret, ByteBuffer.allocate(16 + 6).put("resume".toByteArray()).putLong(ts).putLong(nonce).array())
}
