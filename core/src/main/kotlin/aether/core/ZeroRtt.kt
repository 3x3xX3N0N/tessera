package aether.core

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer

/**
 * Instant connect: the very first datagram carries encrypted application data. The responder decrypts and
 * acts on it with no round trip. Layout of the initial packet payload:
 *   ePub(32) | kemCt(1088) | tsMs(8) | nonce(8) | AEAD(data)  — AEAD key = handshake key, AAD = everything before it.
 * 1350 - 14 header - 1136 handshake - 16 tag = ~184 B of 0-RTT data in packet one; more in immediately-following
 * packets under the same key (they don't wait for a reply either).
 *
 * Replay: responder rejects ts outside ±window and any (ePub) seen inside it. ePub is random per connect, so the
 * seen-set is bounded by connects-per-window. 0-RTT data must still be idempotent at the application layer.
 */
object ZeroRtt {
    const val HS_LEN = 32 + 1088
    const val PREFIX_LEN = HS_LEN + 8 + 8
    const val TAG_LEN = 16
    const val MAX_FIRST_DATA = Wire.MAX_DATAGRAM - Wire.HEADER_LEN - PREFIX_LEN - TAG_LEN

    class Client(private val init: Handshake.InitiatorResult) {
        val key get() = init.key
        /** Build the initial packet body. */
        fun initial(data: ByteArray, nowMs: Long, nonce: Long): ByteArray {
            require(data.size <= MAX_FIRST_DATA) { "first-flight data ${data.size} > $MAX_FIRST_DATA" }
            val prefix = ByteBuffer.allocate(PREFIX_LEN).put(init.ePub).put(init.kemCt).putLong(nowMs).putLong(nonce).array()
            return prefix + Aead.seal(init.key, nonceFrom(nonce, 0), prefix, data)
        }
    }

    class Server(private val keys: Handshake.StaticKeys, private val replayWindowMs: Long = 10_000) {
        private val seen = HashMap<Long, Long>() // ePub fingerprint -> ts
        class Accepted(val key: ByteArray, val data: ByteArray)

        /** Returns decrypted 0-RTT data + session key, or null on replay/bad auth. One call, no round trip. */
        fun accept(body: ByteArray, nowMs: Long): Accepted? {
            if (body.size < PREFIX_LEN + TAG_LEN) return null
            val bb = ByteBuffer.wrap(body)
            val ePub = ByteArray(32).also { bb.get(it) }
            val ct = ByteArray(1088).also { bb.get(it) }
            val ts = bb.getLong(); val nonce = bb.getLong()
            if (kotlin.math.abs(nowMs - ts) > replayWindowMs) return null
            val fp = ByteBuffer.wrap(ePub).getLong()
            if (seen.containsKey(fp)) return null
            val key = Handshake.respond(keys, ePub, ct)
            val aad = body.copyOfRange(0, PREFIX_LEN)
            val data = Aead.open(key, nonceFrom(nonce, 0), aad, body.copyOfRange(PREFIX_LEN, body.size)) ?: return null
            seen[fp] = ts
            if (seen.size > 100_000) seen.entries.removeIf { nowMs - it.value > replayWindowMs }
            return Accepted(key, data)
        }
    }

    fun nonceFrom(connNonce: Long, pn: Long): ByteArray =
        ByteBuffer.allocate(12).putLong(connNonce xor pn).putInt(pn.toInt()).array()
}

object Aead {
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        val c = ChaCha20Poly1305(); c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(pt.size)); val n = c.processBytes(pt, 0, pt.size, out, 0); c.doFinal(out, n); return out
    }
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray? = try {
        val c = ChaCha20Poly1305(); c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(ct.size)); val n = c.processBytes(ct, 0, ct.size, out, 0); c.doFinal(out, n); out
    } catch (e: Exception) { null }
}
