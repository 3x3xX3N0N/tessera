package aether.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.Arrays
import org.bouncycastle.util.Pack
import java.nio.ByteBuffer

/**
 * Per-generation packet-protection keys for an established connection (HKDF-SHA256, no salt, label as info — the
 * same shape as [Resumption]):
 *   key = HKDF(secret, "aether pkt key", 32)   iv = HKDF(secret, "aether pkt iv", 12)   hp = HKDF(secret, "aether hp", 32)
 * [tagLen] (16 or 8, negotiated in [ConnParams]) does not enter the derivation — it is a wire-format choice, not key material.
 * [hpKey] lets a later key-update generation keep the connection's original header-protection key (see [KeyPhaseState]).
 */
class PacketKeys private constructor(val key: ByteArray, val iv: ByteArray, val hp: ByteArray, val tagLen: Int) {
    constructor(secret: ByteArray, tagLen: Int, hpKey: ByteArray? = null) :
        this(hkdf(secret, "aether pkt key", 32), hkdf(secret, "aether pkt iv", 12), hpKey ?: hkdf(secret, "aether hp", 32), tagLen)

    init { require(tagLen == 16 || tagLen == 8) { "tagLen must be 16 or 8, got $tagLen" } }

    /** Nonce = IV xor pn, pn in the low 8 bytes big-endian (TLS 1.3 / RFC 9001 §5.3 construction). */
    fun nonce(pn: Long): ByteArray = iv.copyOf().also { ByteBuffer.wrap(it).putLong(4, ByteBuffer.wrap(iv).getLong(4) xor pn) }

    companion object {
        internal fun hkdf(ikm: ByteArray, label: String, len: Int): ByteArray {
            val out = ByteArray(len)
            HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, null, label.toByteArray())) }.generateBytes(out, 0, len)
            return out
        }
        /** Raw key material (test vectors). */
        internal fun raw(key: ByteArray, iv: ByteArray, hp: ByteArray, tagLen: Int) = PacketKeys(key, iv, hp, tagLen)
    }
}

/**
 * ChaCha20-Poly1305 packet protection + QUIC-style header protection (RFC 9001 §5.4) for [ShortHeader] packets.
 *
 * AEAD: AAD = the header bytes, nonce = [PacketKeys.nonce]. Tag 16 uses BouncyCastle's AEAD directly. BouncyCastle 1.80
 * only accepts a 128-bit mac for ChaCha20Poly1305 (`Invalid value for MAC size: 64`), so tag 8 is the full tag truncated
 * on seal, and on open the full Poly1305 tag is recomputed (RFC 8439 §2.8 framing) and its prefix compared in constant
 * time before decrypting. Same cost as the 16-byte path; forgery bound 2^-64 per packet as documented in [ConnParams].
 *
 * Header protection: a 16-byte sample is taken 4 bytes past the start of the packet-number field (= ciphertext offset
 * 4 - pnLen: the receiver must find the sample before it knows pnLen, so the offset assumes the maximum pnLen of 4).
 * Mask = first 5 keystream bytes of ChaCha20(hp, counter = LE32(sample[0..4)), nonce = sample[4..16)) — RFC 9001 §5.4.4.
 * The flag byte is masked with [SHORT_FLAGS_MASK] = 0x63: only the pnLen bits (5-6) and key-phase bits (0-1) are hidden;
 * the form bit and the pathId bits (2-4) stay in clear on purpose so load balancers / path-aware middleboxes can read the
 * path without keys. The sender must pad so the sample exists: see [minPayloadLen].
 */
object PacketProtection {
    const val SAMPLE_LEN = 16
    const val SAMPLE_OFFSET = 4            // from the start of the pn field (max pnLen), RFC 9001 §5.4.2
    const val SHORT_FLAGS_MASK = 0x63      // pnLen-1 (bits 5-6) | key phase (bits 0-1); pathId bits 2-4 and form bit 7 untouched
    const val SHORT_PN_OFFSET = 1 + 4      // flags + 4-byte short connId

    /** Smallest ciphertext (payload + tag) that leaves a full sample after a pn of [pnLen] bytes. */
    fun minCiphertextLen(pnLen: Int): Int = SAMPLE_OFFSET - pnLen + SAMPLE_LEN
    /** Padding the sender must guarantee: payload bytes needed so the header-protection sample exists. */
    fun minPayloadLen(pnLen: Int, tagLen: Int): Int = (minCiphertextLen(pnLen) - tagLen).coerceAtLeast(0)
    /** Smallest packet the receiver can unprotect. */
    fun minPacketLen(pnOffset: Int): Int = pnOffset + SAMPLE_OFFSET + SAMPLE_LEN

    /** ciphertext || tag(tagLen). [header] is the already-written header (the AAD), see [aadOf]; its position is not moved. */
    fun seal(keys: PacketKeys, pn: Long, header: ByteBuffer, payload: ByteArray): ByteArray {
        val full = Aead.seal(keys.key, keys.nonce(pn), aadOf(header), payload)
        return if (keys.tagLen == 16) full else full.copyOf(full.size - (16 - keys.tagLen))
    }

    /** Plaintext, or null when authentication fails (wrong keys, pn, header or tampered bytes). */
    fun open(keys: PacketKeys, pn: Long, header: ByteBuffer, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size < keys.tagLen) return null
        val aad = aadOf(header); val nonce = keys.nonce(pn)
        return if (keys.tagLen == 16) Aead.open(keys.key, nonce, aad, ciphertext) else openTruncated(keys.key, nonce, aad, ciphertext, keys.tagLen)
    }

    /** In place: mask pn bytes and the pnLen/key-phase flag bits. [pnLen] must match the flag bits already written. */
    fun protectHeader(keys: PacketKeys, packet: ByteArray, pnOffset: Int, pnLen: Int) {
        require(pnLen in 1..4) { "pnLen $pnLen" }
        checkShort(packet, pnOffset)
        require(pnLenOf(packet[0]) == pnLen) { "flag pnLen bits say ${pnLenOf(packet[0])}, caller says $pnLen" }
        val mask = hpMask(keys.hp, packet, pnOffset + SAMPLE_OFFSET)
        applyMask(packet, pnOffset, pnLen, mask)
    }

    /** In place: unmask the flag bits, read pnLen from them, unmask the pn bytes. Returns pnLen (1..4). */
    fun unprotectHeader(keys: PacketKeys, packet: ByteArray, pnOffset: Int): Int {
        checkShort(packet, pnOffset)
        val mask = hpMask(keys.hp, packet, pnOffset + SAMPLE_OFFSET)
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and SHORT_FLAGS_MASK)).toByte()
        val pnLen = pnLenOf(packet[0])
        for (i in 0 until pnLen) packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        return pnLen
    }

    /**
     * Header bytes used as AAD: `[0, position)` when the header has been written into the buffer (position > 0, the
     * state right after [ShortHeader.write] / [ShortHeader.read]); `[0, limit)` for a wrapped or flipped buffer.
     */
    fun aadOf(header: ByteBuffer): ByteArray {
        val end = if (header.position() > 0) header.position() else header.limit()
        return ByteArray(end).also { header.duplicate().limit(end).position(0).get(it) }
    }

    /** RFC 9001 §5.4.4 mask: 5 bytes of ChaCha20 keystream, block counter/nonce taken from the sample. */
    internal fun hpMask(hp: ByteArray, sample: ByteArray, off: Int): ByteArray {
        val counter = Pack.littleEndianToInt(sample, off).toLong() and 0xFFFF_FFFFL
        val e = ChaCha7539Engine().apply { init(true, ParametersWithIV(KeyParameter(hp), sample, off + 4, 12)) }
        e.skip(counter * 64)  // BC exposes no counter setter; skipping whole blocks sets the 32-bit block counter
        return ByteArray(5).also { e.processBytes(it, 0, 5, it, 0) }
    }

    private fun pnLenOf(flags: Byte): Int = (((flags.toInt() and 0xFF) shr 5) and 0x3) + 1

    private fun checkShort(packet: ByteArray, pnOffset: Int) {
        require(packet[0].toInt() and 0x80 == 0) { "long header: only short headers are header-protected" }
        require(packet.size >= minPacketLen(pnOffset)) { "packet ${packet.size} B too short for the sample (need ${minPacketLen(pnOffset)}); pad the payload" }
    }

    private fun applyMask(packet: ByteArray, pnOffset: Int, pnLen: Int, mask: ByteArray) {
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and SHORT_FLAGS_MASK)).toByte()
        for (i in 0 until pnLen) packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
    }

    /** Verify a truncated tag by recomputing the full one (BC's AEAD mode insists on 128-bit tags), then decrypt. */
    private fun openTruncated(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray, tagLen: Int): ByteArray? {
        val n = ct.size - tagLen
        val cipher = ChaCha7539Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
        val block0 = ByteArray(64).also { cipher.processBytes(it, 0, 64, it, 0) }   // block 0 -> one-time Poly1305 key
        val mac = Poly1305().apply { init(KeyParameter(block0, 0, 32)) }
        fun pad16(len: Int) { val r = len % 16; if (r != 0) mac.update(ByteArray(16 - r), 0, 16 - r) }
        mac.update(aad, 0, aad.size); pad16(aad.size)
        mac.update(ct, 0, n); pad16(n)
        val lengths = ByteArray(16); Pack.longToLittleEndian(aad.size.toLong(), lengths, 0); Pack.longToLittleEndian(n.toLong(), lengths, 8)
        mac.update(lengths, 0, 16)
        val tag = ByteArray(16).also { mac.doFinal(it, 0) }
        if (!Arrays.constantTimeAreEqual(tagLen, tag, 0, ct, n)) return null
        return ByteArray(n).also { cipher.processBytes(ct, 0, n, it, 0) }       // cipher now sits at block 1
    }
}

/**
 * Key update via the key-phase bit (RFC 9001 §6 shape). Generation n has secret_n, secret_{n+1} = HKDF(secret_n, "aether ku"),
 * phase = n mod 2. The header-protection key is derived once from secret_0 and shared by every generation: the key-phase
 * bit is under header protection, so the receiver must be able to unprotect before it knows the phase.
 *
 * Both sides run the same rules, so they converge on the same generation:
 *  - [initiateUpdate] moves to n+1 and marks the update pending until the peer sends with the new phase. A second
 *    initiation while pending is refused — it would put us two generations ahead of a peer that followed only once.
 *  - [onPeerPhase] with the other phase while nothing is pending means the peer initiated: we follow to n+1. While our own
 *    update is pending, the other phase only means the peer has not caught up yet. Simultaneous initiation by both sides
 *    lands both on n+1 with matching phases, so each just sees the other "catch up".
 *  - Keys of generation n-1 are retained for exactly one update so reordered old-phase packets still open; they are the
 *    [keysFor] answer for the other phase. Generation n+1 keys are pre-derived ([next]).
 *  - The phase bit is not authenticated until the AEAD succeeds, so an attacker could flip it to provoke a rotation:
 *    follow only after a packet authenticated under [next]. [open] implements exactly that trial order; callers using
 *    [keysFor]/[onPeerPhase] directly must call [onPeerPhase] only after a successful open.
 */
class KeyPhaseState(initialSecret: ByteArray, val tagLen: Int) {
    private val hp: ByteArray
    private var nextSecret: ByteArray
    var currentPhase: Int = 0; private set
    var generation: Int = 0; private set
    var current: PacketKeys; private set
    var next: PacketKeys; private set
    var previous: PacketKeys? = null; private set
    /** True between our [initiateUpdate] and the first packet from the peer carrying the new phase. */
    var updatePending: Boolean = false; private set

    init {
        current = PacketKeys(initialSecret, tagLen); hp = current.hp
        nextSecret = PacketKeys.hkdf(initialSecret, "aether ku", 32); next = PacketKeys(nextSecret, tagLen, hp)
    }

    fun initiateUpdate() {
        check(!updatePending) { "key update already pending; wait until the peer sends with phase $currentPhase" }
        rotate(); updatePending = true
    }

    /** Peer sent with [phase] (bit 0; bit 1 of the header field is reserved/grease). Returns true if we rotated to follow. */
    fun onPeerPhase(phase: Int): Boolean {
        if (phase and 1 == currentPhase) { updatePending = false; return false }
        if (updatePending) return false
        rotate(); return true
    }

    /** Keys for a packet carrying [phase]: current, or for the other phase the retained previous generation (reordered
     *  packet / peer not yet caught up), or — when nothing older is retained — the pre-derived next generation. */
    fun keysFor(phase: Int): PacketKeys = if (phase and 1 == currentPhase) current else previous ?: next

    fun seal(pn: Long, header: ByteBuffer, payload: ByteArray): ByteArray = PacketProtection.seal(current, pn, header, payload)

    /** Receive side: current keys; else previous (reordered); else, with no update of ours pending, next — and follow it
     *  once it authenticates. Null when nothing opens the packet (forgery, or older than one update). */
    fun open(phase: Int, pn: Long, header: ByteBuffer, ciphertext: ByteArray): ByteArray? {
        if (phase and 1 == currentPhase) return PacketProtection.open(current, pn, header, ciphertext)?.also { updatePending = false }
        previous?.let { old -> PacketProtection.open(old, pn, header, ciphertext)?.let { return it } }
        if (updatePending) return null
        return PacketProtection.open(next, pn, header, ciphertext)?.also { rotate() }
    }

    private fun rotate() {
        previous = current; current = next; currentPhase = currentPhase xor 1; generation++
        nextSecret = PacketKeys.hkdf(nextSecret, "aether ku", 32); next = PacketKeys(nextSecret, tagLen, hp)
    }
}
