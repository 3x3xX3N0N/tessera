package tessera.transport

import tessera.core.KeyPhaseState
import tessera.core.PacketKeys
import tessera.core.PacketProtection
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.Arrays
import org.bouncycastle.util.Pack
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-connection packet protection for the established phase, on core's key schedule:
 *
 *   secretC2S = HKDF(sessionKey, "tessera-v0.3 c2s")      secretS2C = HKDF(sessionKey, "tessera-v0.3 s2c")
 *   per direction: [KeyPhaseState](secret) -> [PacketKeys](key, iv, hp) per generation; hp pinned at generation 0.
 *
 * Two directions, two secrets, so the client->server and server->client packet-number spaces can never collide on a
 * nonce. Nonce = iv xor pn (core's [PacketKeys.nonce] layout); the caller folds the path id into bits 56-63 of the pn
 * for paths > 0 ([[MULTIPATH]], identity for path 0). AAD = the packet header (long or short).
 *
 * Same bytes as core's [PacketProtection.seal]/[PacketProtection.protectHeader] (PacketCryptoWrapperTest checks both
 * tag lengths against core), but in place on the transport's buffers with reusable cipher objects:
 *  - tag 16: BouncyCastle's ChaCha20Poly1305 AEAD, re-initialised per packet (allocates one AEADParameters, as before).
 *  - tag 8 (negotiated in ConnParams): sealed as the 16-byte tag truncated; opened by recomputing the full Poly1305 tag
 *    (RFC 8439 framing, BC's AEAD mode refuses 64-bit macs) with a reusable engine + mac (allocates one
 *    ParametersWithIV and one 32-byte KeyParameter per packet) and comparing the prefix in constant time.
 *  - header protection: the RFC 9001 §5.4.4 ChaCha20 mask is computed by a local block function ([ChaChaMask]) with one
 *    reused state array — no allocation — from the 16-byte sample at packet offset pnOffset + 4 (max pnLen; the
 *    receiver does not know pnLen before unmasking, so the offset is pn-length agnostic by construction).
 * KeyParameters are cached per [PacketKeys] instance (current/previous/next), so a key update costs one allocation.
 *
 * Key derivation is kept off the connect critical path: generation-0 packets are sealed/opened under plain
 * [PacketKeys] (3 HKDF-SHA256 per direction, [txKeys]/[rxKeys]); the full [KeyPhaseState]s (which also pre-derive the
 * next generation, 7 HKDF each) are built by [warm] on the timer thread — or by the client while it waits for the
 * handshake reply — and only on demand when a key phase actually changes ([tx]/[rx]). Generation 0 of a state is the
 * same derivation, so the switch from the plain keys to the state's `current` is byte-identical. [tagLen] is the
 * negotiated value for short packets; handshake packets always use 16 (the tag length is inside the reply).
 */
internal class PacketCrypto(sessionKey: ByteArray, val isClient: Boolean, useNativeAead: Boolean = tessera.native.NativeAead.enabledByDefault) {
    private val txSecret = hkdf(sessionKey, if (isClient) LABEL_C2S else LABEL_S2C)
    private val rxSecret = hkdf(sessionKey, if (isClient) LABEL_S2C else LABEL_C2S)
    private var txState: KeyPhaseState? = null
    private var rxState: KeyPhaseState? = null
    private var txMask: ChaChaMask? = null
    private var rxMask: ChaChaMask? = null

    /** Negotiated tag length for short-header packets (8 or 16). */
    var tagLen: Int = 16
        set(v) { require(v == 8 || v == 16) { "tagLen must be 8 or 16, got $v" }; field = v }

    private var txGen0: PacketKeys? = null
    private var rxGen0: PacketKeys? = null

    /** Our sending direction's key schedule; [KeyPhaseState.initiateUpdate] on it is a key update. Builds it if needed. */
    val tx: KeyPhaseState get() = txState ?: KeyPhaseState(txSecret, 16).also { txState = it; if (txMask == null) txMask = ChaChaMask(it.current.hp) }
    /** The peer's sending direction; follows the peer's key phase (see TesseraConnection.openShort). Builds it if needed. */
    val rx: KeyPhaseState get() = rxState ?: KeyPhaseState(rxSecret, 16).also { rxState = it; if (rxMask == null) rxMask = ChaChaMask(it.current.hp) }
    val txStateOrNull: KeyPhaseState? get() = txState
    val rxStateOrNull: KeyPhaseState? get() = rxState

    /** Keys to seal with now: the state's current generation, or generation 0 while no state exists. */
    fun txKeys(): PacketKeys = txState?.current ?: (txGen0 ?: PacketKeys(txSecret, 16).also { txGen0 = it; txMask = ChaChaMask(it.hp) })
    /** Keys that open the peer's current-generation packets (generation 0 while no state exists). */
    fun rxKeys(): PacketKeys = rxState?.current ?: (rxGen0 ?: PacketKeys(rxSecret, 16).also { rxGen0 = it; rxMask = ChaChaMask(it.hp) })
    /** Key-phase bit to put on our packets. */
    val txPhase: Int get() = txState?.currentPhase ?: 0
    val txGeneration: Int get() = txState?.generation ?: 0

    /** Derives everything (both full states) — call off the critical path; idempotent. */
    fun warm() { tx; rx }

    private val txCipher = ChaCha20Poly1305()
    private val rxCipher = ChaCha20Poly1305()
    /**
     * The JDK's own ChaCha20-Poly1305, which HotSpot intrinsifies. Measured on a 1200-byte packet: seal
     * 3.03 us and open 2.97 us against BouncyCastle's 7.00 and 9.67 — 16.67 us per message down to 6.00
     * (BENCH-netem, "the JDK's own AEAD"). BouncyCastle is kept for the truncated-tag open, which the JCE
     * `Cipher` API cannot express, and as the fallback when the provider is missing.
     */
    private val jce: JceAead? = JceAead.create()
    /**
     * RustCrypto's ChaCha20-Poly1305 in the native library, in place on one off-heap segment: no per-packet
     * cipher init and no decrypt-side buffering (the JCE path's top allocation sites in the bulk profile). Tried
     * first; null falls through to [jce], then BouncyCastle. `-Dtessera.native.aead=off` (or `-Dtessera.native=off`)
     * keeps it out for an A/B. Same RFC 8439 bytes on the wire: JceAeadEquivalenceTest pins all three.
     */
    private val nat: tessera.native.NativeAead? = if (useNativeAead) tessera.native.NativeAead.createOrNull() else null
    /** Which AEAD this connection seals with: `native`, `SunJCE` or `BouncyCastle` (datapath report, tests). */
    val aeadName: String get() = when { nat != null -> "native"; jce != null -> "SunJCE"; else -> "BouncyCastle" }
    private var jceOut = ByteArray(0)
    private val rxEngine = ChaCha7539Engine()
    private val rxMac = Poly1305()
    private val keyParams = KeyParamCache()
    private val nonce = ByteArray(12)
    private val block0 = ByteArray(64)
    private val zeros64 = ByteArray(64)
    private val tagBuf = ByteArray(16)
    private val lengths = ByteArray(16)
    private val sample = ByteArray(PacketProtection.SAMPLE_LEN)
    private val mask = ByteArray(5)

    /**
     * Seal `buf[hdrStart, bodyEnd)` in place under [keys]: header bytes [hdrStart, hdrEnd) are AAD; plaintext
     * [hdrEnd, bodyEnd) is replaced by ciphertext + tag of [tagLen] bytes. `scratch` must be a heap array of at least
     * (bodyEnd - hdrStart) + 16 bytes. Returns the new end position. Caller guarantees 16 bytes of spare capacity.
     */
    fun seal(buf: ByteBuffer, hdrStart: Int, hdrEnd: Int, bodyEnd: Int, keys: PacketKeys, noncePn: Long, tagLen: Int, scratch: ByteArray): Int {
        val hdrLen = hdrEnd - hdrStart; val ptLen = bodyEnd - hdrEnd
        buf.get(hdrStart, scratch, 0, hdrLen + ptLen)
        nonceInto(keys.iv, noncePn)
        // Seal always computes the full 16-byte tag and transmits the first [tagLen] bytes of it, so the JCE
        // path serves both negotiated tag lengths; only the truncated OPEN needs primitives BouncyCastle has
        // and the Cipher API does not.
        val viaOut = nat != null || jce != null
        val total = if (viaOut) {
            if (jceOut.size < hdrLen + ptLen + 16) jceOut = ByteArray(hdrLen + ptLen + 16)
            if (nat != null) nat.seal(keys.key, nonce, scratch, hdrLen, ptLen, jceOut) - (16 - tagLen)
            else jce!!.seal(keys, nonce, scratch, hdrLen, ptLen, jceOut) - (16 - tagLen)
        } else {
            txCipher.init(true, AEADParameters(keyParams.of(keys), 128, nonce, null))
            txCipher.processAADBytes(scratch, 0, hdrLen)
            val n = txCipher.processBytes(scratch, hdrLen, ptLen, scratch, hdrLen)
            n + txCipher.doFinal(scratch, hdrLen + n) - (16 - tagLen)
        }
        buf.put(hdrEnd, if (viaOut) jceOut else scratch, if (viaOut) 0 else hdrLen, total)
        return hdrEnd + total
    }

    /**
     * Open `buf[hdrStart, end)` whose header is [hdrStart, hdrEnd) under [keys]. Plaintext goes to `out` from 0;
     * returns its length or -1 on authentication failure. `scratch` as in [seal].
     */
    fun open(buf: ByteBuffer, hdrStart: Int, hdrEnd: Int, end: Int, keys: PacketKeys, noncePn: Long, tagLen: Int, scratch: ByteArray, out: ByteArray): Int {
        val hdrLen = hdrEnd - hdrStart; val ctLen = end - hdrEnd
        if (ctLen < tagLen) return -1
        buf.get(hdrStart, scratch, 0, hdrLen + ctLen)
        nonceInto(keys.iv, noncePn)
        if (tagLen != 16) return openTruncated(keys, scratch, hdrLen, ctLen, tagLen, out)
        if (nat != null) return nat.open(keys.key, nonce, scratch, hdrLen, ctLen, out)
        if (jce != null) return jce.open(keys, nonce, scratch, hdrLen, ctLen, out)
        return try {
            rxCipher.init(false, AEADParameters(keyParams.of(keys), 128, nonce, null))
            rxCipher.processAADBytes(scratch, 0, hdrLen)
            val n = rxCipher.processBytes(scratch, hdrLen, ctLen, out, 0)
            n + rxCipher.doFinal(out, n)
        } catch (e: Exception) { -1 }
    }

    /** Verify a truncated tag by recomputing the full one (RFC 8439 §2.8 framing), then decrypt. */
    private fun openTruncated(keys: PacketKeys, scratch: ByteArray, hdrLen: Int, ctLen: Int, tagLen: Int, out: ByteArray): Int {
        val n = ctLen - tagLen
        rxEngine.init(true, ParametersWithIV(keyParams.of(keys), nonce))
        rxEngine.processBytes(zeros64, 0, 64, block0, 0)            // keystream block 0 -> one-time Poly1305 key
        rxMac.init(KeyParameter(block0, 0, 32))
        rxMac.update(scratch, 0, hdrLen); pad16(hdrLen)
        rxMac.update(scratch, hdrLen, n); pad16(n)
        Pack.longToLittleEndian(hdrLen.toLong(), lengths, 0); Pack.longToLittleEndian(n.toLong(), lengths, 8)
        rxMac.update(lengths, 0, 16)
        rxMac.doFinal(tagBuf, 0)
        if (!Arrays.constantTimeAreEqual(tagLen, tagBuf, 0, scratch, hdrLen + n)) return -1
        rxEngine.processBytes(scratch, hdrLen, n, out, 0)           // engine now sits at block 1
        return n
    }

    private fun pad16(len: Int) { val r = len % 16; if (r != 0) rxMac.update(zeros64, 0, 16 - r) }

    /**
     * Header protection (tx), in place on the sealed packet at `buf[0, ...)`: masks the pnLen/key-phase flag bits and
     * the `pnLen` pn bytes with [ChaChaMask] over the sample at [PacketProtection.SHORT_PN_OFFSET] + 4. The caller pads
     * so the sample exists ([PacketProtection.minPayloadLen]).
     */
    fun protectHeader(buf: ByteBuffer, pnLen: Int) {
        if (txMask == null) txKeys()
        buf.get(SAMPLE_AT, sample, 0, PacketProtection.SAMPLE_LEN)
        txMask!!.compute(sample, mask)
        applyMask(buf, pnLen)
    }

    /** Header unprotection (rx) in place; returns pnLen read from the unmasked flags. `buf.limit()` must be >= [MIN_PACKET]. */
    fun unprotectHeader(buf: ByteBuffer): Int {
        if (rxMask == null) rxKeys()
        buf.get(SAMPLE_AT, sample, 0, PacketProtection.SAMPLE_LEN)
        rxMask!!.compute(sample, mask)
        buf.put(0, (buf.get(0).toInt() xor (mask[0].toInt() and PacketProtection.SHORT_FLAGS_MASK)).toByte())
        val pnLen = (((buf.get(0).toInt() and 0xFF) shr 5) and 0x3) + 1
        for (i in 0 until pnLen) {
            val at = PacketProtection.SHORT_PN_OFFSET + i
            buf.put(at, (buf.get(at).toInt() xor mask[1 + i].toInt()).toByte())
        }
        return pnLen
    }

    private fun applyMask(buf: ByteBuffer, pnLen: Int) {
        buf.put(0, (buf.get(0).toInt() xor (mask[0].toInt() and PacketProtection.SHORT_FLAGS_MASK)).toByte())
        for (i in 0 until pnLen) {
            val at = PacketProtection.SHORT_PN_OFFSET + i
            buf.put(at, (buf.get(at).toInt() xor mask[1 + i].toInt()).toByte())
        }
    }

    /** Allocation-free [PacketKeys.nonce]: iv with `pn` xor-ed into the low 8 bytes, big-endian. */
    private fun nonceInto(iv: ByteArray, pn: Long) {
        for (i in 0 until 4) nonce[i] = iv[i]
        for (i in 0 until 8) nonce[4 + i] = (iv[4 + i].toInt() xor (pn shr (56 - 8 * i)).toInt()).toByte()
    }

    /** Identity-keyed cache of BC KeyParameters (which copy the key on construction) for the few live generations. */
    /**
     * The JDK provider's ChaCha20-Poly1305, held per connection with its own `Cipher` pair and a small cache of
     * `SecretKeySpec` per [PacketKeys] generation (mirroring [KeyParamCache]: a key update is then one allocation,
     * not one per packet).
     *
     * [create] returns null when the provider is absent, so the connection silently keeps the BouncyCastle path
     * rather than failing — the two are the same RFC 8439 construction and interoperate on the wire.
     *
     * SunJCE refuses to re-initialise for ENCRYPTION under a key and nonce it has just used. That guard is
     * welcome: nonce reuse under one key is catastrophic for this AEAD. The transport satisfies it because every
     * packet gets a fresh packet number and the nonce is `iv xor pn` — a "verbatim" re-send re-seals the retained
     * *plaintext* under a new pn. If it ever fires it is a real defect, so [seal] lets it surface with the pn in
     * the message instead of quietly falling back and masking a key/nonce collision.
     */
    private class JceAead private constructor(private val enc: Cipher, private val dec: Cipher) {
        private val specs = arrayOfNulls<PacketKeys>(4)
        private val cached = arrayOfNulls<SecretKeySpec>(4)
        private var next = 0

        private fun keyOf(k: PacketKeys): SecretKeySpec {
            for (i in specs.indices) if (specs[i] === k) return cached[i]!!
            val s = SecretKeySpec(k.key, "ChaCha20")
            specs[next] = k; cached[next] = s; next = (next + 1) and 3
            return s
        }

        /** AAD is `src[0, hdrLen)`, plaintext `src[hdrLen, hdrLen + ptLen)`; writes `ct || tag16` to `out`. */
        fun seal(keys: PacketKeys, nonce: ByteArray, src: ByteArray, hdrLen: Int, ptLen: Int, out: ByteArray): Int {
            try {
                enc.init(Cipher.ENCRYPT_MODE, keyOf(keys), IvParameterSpec(nonce))
            } catch (e: java.security.InvalidKeyException) {
                throw IllegalStateException("the JDK provider refused this key+nonce as already used for encryption - a repeated packet number would mean nonce reuse", e)
            }
            enc.updateAAD(src, 0, hdrLen)
            return enc.doFinal(src, hdrLen, ptLen, out, 0)
        }

        /** Returns the plaintext length written to `out`, or -1 when the tag does not verify. */
        fun open(keys: PacketKeys, nonce: ByteArray, src: ByteArray, hdrLen: Int, ctLen: Int, out: ByteArray): Int = try {
            dec.init(Cipher.DECRYPT_MODE, keyOf(keys), IvParameterSpec(nonce))
            dec.updateAAD(src, 0, hdrLen)
            dec.doFinal(src, hdrLen, ctLen, out, 0)
        } catch (e: Exception) { -1 }

        companion object {
            /** Null when the provider cannot supply ChaCha20-Poly1305 (the connection then stays on BouncyCastle). */
            fun create(): JceAead? = try {
                JceAead(Cipher.getInstance("ChaCha20-Poly1305"), Cipher.getInstance("ChaCha20-Poly1305"))
            } catch (e: Exception) { null }

            /** Whether this JVM offers the provider; read by tests and by the datapath report. */
            val available: Boolean get() = create() != null
        }
    }

    private class KeyParamCache {
        private val keys = arrayOfNulls<PacketKeys>(4)
        private val params = arrayOfNulls<KeyParameter>(4)
        private var next = 0
        fun of(k: PacketKeys): KeyParameter {
            for (i in keys.indices) if (keys[i] === k) return params[i]!!
            val p = KeyParameter(k.key)
            keys[next] = k; params[next] = p; next = (next + 1) and 3
            return p
        }
    }

    /**
     * ChaCha20 block function (RFC 8439 §2.3) producing the 5 header-protection mask bytes of RFC 9001 §5.4.4:
     * counter = LE32(sample[0..4)), nonce = sample[4..16), mask = first 5 keystream bytes. One reused state array.
     */
    internal class ChaChaMask(hp: ByteArray) {
        private val key = IntArray(8) { Pack.littleEndianToInt(hp, it * 4) }
        private val x = IntArray(16)

        fun compute(sample: ByteArray, out: ByteArray) {
            val s = x
            s[0] = C0; s[1] = C1; s[2] = C2; s[3] = C3
            for (i in 0 until 8) s[4 + i] = key[i]
            s[12] = Pack.littleEndianToInt(sample, 0)
            s[13] = Pack.littleEndianToInt(sample, 4); s[14] = Pack.littleEndianToInt(sample, 8); s[15] = Pack.littleEndianToInt(sample, 12)
            for (r in 0 until 10) {
                qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
                qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
            }
            val w0 = s[0] + C0; val w1 = s[1] + C1
            out[0] = w0.toByte(); out[1] = (w0 ushr 8).toByte(); out[2] = (w0 ushr 16).toByte(); out[3] = (w0 ushr 24).toByte()
            out[4] = w1.toByte()
        }

        private fun qr(a: Int, b: Int, c: Int, d: Int) {
            val s = x
            s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
            s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
            s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
            s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
        }

        private companion object { const val C0 = 0x61707865; const val C1 = 0x3320646e; const val C2 = 0x79622d32; const val C3 = 0x6b206574 }
    }

    companion object {
        const val LABEL_C2S = "tessera-v0.3 c2s"
        const val LABEL_S2C = "tessera-v0.3 s2c"
        /** Sample offset in a short packet: pn field start + 4 (max pnLen), see [PacketProtection.SAMPLE_OFFSET]. */
        const val SAMPLE_AT = PacketProtection.SHORT_PN_OFFSET + PacketProtection.SAMPLE_OFFSET
        /** Smallest short packet that can be unprotected (25 bytes). */
        val MIN_PACKET: Int = PacketProtection.minPacketLen(PacketProtection.SHORT_PN_OFFSET)

        fun hkdf(ikm: ByteArray, info: String): ByteArray {
            val out = ByteArray(32)
            HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, null, info.toByteArray())) }.generateBytes(out, 0, 32)
            return out
        }
    }
}
