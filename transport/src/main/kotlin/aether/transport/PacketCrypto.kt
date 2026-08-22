package aether.transport

import aether.core.ZeroRtt
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer

/**
 * Per-connection packet protection for the established (short-header) phase.
 *
 * LOCAL TO transport/ (belongs in core, not there yet): directional key schedule. The handshake yields one
 * session key; packet one (0-RTT) is sealed under it with pn 0 by ZeroRtt/Resumption. Everything after runs under
 * two HKDF-derived sub-keys so client->server and server->client packet-number spaces can never collide on a nonce:
 *   keyC2S = HKDF(sessionKey, info="aether-v0.2 c2s"), keyS2C = HKDF(sessionKey, info="aether-v0.2 s2c").
 * Nonce = ZeroRtt.nonceFrom(connNonce, pn); AAD = the exact header bytes (long or short) of the packet.
 *
 * === HEADER PROTECTION HOOK ===
 * [[HP-HOOK]] Header protection (PN/flags masking, QUIC-style) is being built by another agent. Plug-in points:
 *   - PacketCrypto.protectHeader(buf, hdrLen, ...)   called by Packetizer after seal, before send
 *   - PacketCrypto.unprotectHeader(buf, ...)         called by the rx loop before ShortHeader.read
 * Both are identity today. The HP key should be derived alongside the AEAD keys in [deriveKeys].
 */
class PacketCrypto(sessionKey: ByteArray, isClient: Boolean, val tagLen: Int = 16) {
    private val c2s = hkdf(sessionKey, "aether-v0.2 c2s")
    private val s2c = hkdf(sessionKey, "aether-v0.2 s2c")
    private val txKey = KeyParameter(if (isClient) c2s else s2c)
    private val rxKey = KeyParameter(if (isClient) s2c else c2s)
    // one cipher instance per direction, re-initialised per packet (BC has no cheaper reset with a new nonce)
    private val txCipher = ChaCha20Poly1305()
    private val rxCipher = ChaCha20Poly1305()
    private val nonce = ByteArray(12)
    private val macBits = tagLen * 8

    /**
     * Seal `buf[hdrStart, bodyEnd)` in place: header bytes [hdrStart, hdrEnd) are AAD; plaintext [hdrEnd, bodyEnd)
     * is replaced by ciphertext + tag. `scratch` must be a heap array of at least (bodyEnd-hdrStart)+tagLen bytes.
     * Returns the new end position. Caller guarantees buf has tagLen bytes of spare capacity.
     */
    fun seal(buf: ByteBuffer, hdrStart: Int, hdrEnd: Int, bodyEnd: Int, connNonce: Long, pn: Long, scratch: ByteArray): Int {
        val hdrLen = hdrEnd - hdrStart; val ptLen = bodyEnd - hdrEnd
        buf.get(hdrStart, scratch, 0, hdrLen + ptLen)
        ZeroRtt.nonceInto(nonce, connNonce, pn)
        txCipher.init(true, AEADParameters(txKey, macBits, nonce, null))
        txCipher.processAADBytes(scratch, 0, hdrLen)
        val n = txCipher.processBytes(scratch, hdrLen, ptLen, scratch, hdrLen)
        val total = n + txCipher.doFinal(scratch, hdrLen + n)
        buf.put(hdrEnd, scratch, hdrLen, total)
        return hdrEnd + total
    }

    /**
     * Open `buf[hdrStart, end)` whose header is [hdrStart, hdrEnd). Plaintext is written into `out` starting at 0;
     * returns plaintext length or -1 on auth failure. `scratch` as in [seal].
     */
    fun open(buf: ByteBuffer, hdrStart: Int, hdrEnd: Int, end: Int, connNonce: Long, pn: Long, scratch: ByteArray, out: ByteArray): Int {
        val hdrLen = hdrEnd - hdrStart; val ctLen = end - hdrEnd
        if (ctLen < tagLen) return -1
        buf.get(hdrStart, scratch, 0, hdrLen + ctLen)
        ZeroRtt.nonceInto(nonce, connNonce, pn)
        return try {
            rxCipher.init(false, AEADParameters(rxKey, macBits, nonce, null))
            rxCipher.processAADBytes(scratch, 0, hdrLen)
            val n = rxCipher.processBytes(scratch, hdrLen, ctLen, out, 0)
            n + rxCipher.doFinal(out, n)
        } catch (e: Exception) { -1 }
    }

    /** [[HP-HOOK]] identity today; mask flags low bits + PN bytes using a sample of the ciphertext. */
    fun protectHeader(buf: ByteBuffer, hdrStart: Int, hdrEnd: Int) {}
    /** [[HP-HOOK]] identity today; must run before ShortHeader.read so the PN length bits are readable. */
    fun unprotectHeader(buf: ByteBuffer, hdrStart: Int) {}

    companion object {
        fun hkdf(ikm: ByteArray, info: String): ByteArray {
            val out = ByteArray(32)
            HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, null, info.toByteArray())) }.generateBytes(out, 0, 32)
            return out
        }
    }
}

/** Allocation-free variant of ZeroRtt.nonceFrom (same layout: (connNonce xor pn) | pn.toInt()). */
private fun ZeroRtt.nonceInto(dst: ByteArray, connNonce: Long, pn: Long) {
    val hi = connNonce xor pn
    for (i in 0 until 8) dst[i] = (hi shr (56 - 8 * i)).toByte()
    val lo = pn.toInt()
    for (i in 0 until 4) dst[8 + i] = (lo shr (24 - 8 * i)).toByte()
}
