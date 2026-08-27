package tessera.native

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The native ChaCha20-Poly1305 (RFC 8439, hand-written in the crate) against BouncyCastle.
 *
 * A hand-written AEAD is the one kind of code that fails silently: wrong ciphertext is still ciphertext, and a
 * round-trip test passes happily against its own mistake. Every test here therefore compares against an
 * independent implementation rather than against itself. BouncyCastle remains the transport's reference and
 * fallback; this suite is the evidence for ever preferring the native one.
 *
 * The crate carries the RFC 8439 vectors in its own unit tests (`cargo test`): the §2.3.2 block function, the
 * §2.4.2 keystream, Poly1305 §2.5.2 and §A.3, and the §2.8.2 AEAD. What the vectors cannot cover is the space
 * of lengths, AAD sizes and key material a real link produces — that is what the fuzz below is for.
 */
class AeadNativeTest {

    private fun bcSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(pt.size))
        val n = c.processBytes(pt, 0, pt.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    private fun bcOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray? = try {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(ct.size))
        val n = c.processBytes(ct, 0, ct.size, out, 0)
        c.doFinal(out, n)
        out
    } catch (e: Exception) { null }

    /**
     * The headline: 4000 random cases, each checked three ways — the native ciphertext and tag must equal
     * BouncyCastle's byte for byte, the native side must open BouncyCastle's output, and BouncyCastle must open
     * the native side's. Agreeing on the ciphertext but not the tag, or vice versa, is a distinct bug class, so
     * the whole sealed blob is compared rather than a boolean.
     */
    @Test fun matchesBouncyCastleOverRandomInputs() {
        assumeTrue(AeadNative.available, "tessera_native not available")
        val rnd = Random(20260827)
        repeat(4000) { i ->
            val key = ByteArray(32).also { rnd.nextBytes(it) }
            val nonce = ByteArray(12).also { rnd.nextBytes(it) }
            // lengths biased at the block/MAC boundaries, where a padding or chunking bug lives
            val len = when (i % 4) {
                0 -> rnd.nextInt(80)
                1 -> listOf(0, 1, 15, 16, 17, 63, 64, 65, 127, 128, 129).random(kotlin.random.Random(i))
                2 -> rnd.nextInt(1500)
                else -> 1200
            }
            val aadLen = if (i % 3 == 0) 0 else rnd.nextInt(40)
            val aad = ByteArray(aadLen).also { rnd.nextBytes(it) }
            val pt = ByteArray(len).also { rnd.nextBytes(it) }

            val expect = bcSeal(key, nonce, aad, pt)
            val got = AeadNative.sealBytes(key, nonce, aad, pt)
            assertContentEquals(expect, got, "case $i: len=$len aadLen=$aadLen sealed blob differs from BouncyCastle")

            assertContentEquals(pt, AeadNative.openBytes(key, nonce, aad, expect), "case $i: native could not open BouncyCastle's output")
            assertContentEquals(pt, bcOpen(key, nonce, aad, got), "case $i: BouncyCastle could not open the native output")
        }
    }

    /**
     * Forgeries must be refused. Flipping one bit anywhere in the sealed blob — ciphertext or tag — or in the
     * AAD or nonce must fail the open. This is the property an implementation bug in the MAC would break while
     * every round-trip test still passed.
     */
    @Test fun refusesEverySingleBitForgery() {
        assumeTrue(AeadNative.available, "tessera_native not available")
        val rnd = Random(7)
        val key = ByteArray(32).also { rnd.nextBytes(it) }
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val aad = ByteArray(12).also { rnd.nextBytes(it) }
        val pt = ByteArray(64).also { rnd.nextBytes(it) }
        val sealed = AeadNative.sealBytes(key, nonce, aad, pt)

        for (byte in sealed.indices) for (bit in 0 until 8) {
            val t = sealed.copyOf(); t[byte] = (t[byte].toInt() xor (1 shl bit)).toByte()
            assertNull(AeadNative.openBytes(key, nonce, aad, t), "forged sealed byte $byte bit $bit was accepted")
        }
        for (byte in aad.indices) {
            val a = aad.copyOf(); a[byte] = (a[byte].toInt() xor 1).toByte()
            assertNull(AeadNative.openBytes(key, nonce, a, sealed), "forged aad byte $byte was accepted")
        }
        for (byte in nonce.indices) {
            val n = nonce.copyOf(); n[byte] = (n[byte].toInt() xor 1).toByte()
            assertNull(AeadNative.openBytes(key, n, aad, sealed), "wrong nonce byte $byte was accepted")
        }
    }

    /**
     * The transport negotiates an 8-byte tag. A truncated open must accept exactly the transmitted prefix and
     * reject a difference inside it; a difference beyond the prefix was never transmitted, so accepting it is
     * the documented meaning of truncation rather than a defect.
     */
    @Test fun truncatedTagCoversExactlyTheTransmittedPrefix() {
        assumeTrue(AeadNative.available, "tessera_native not available")
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it * 3).toByte() }
        val aad = ByteArray(9) { it.toByte() }
        val pt = ByteArray(200) { (it * 7).toByte() }
        val sealed = AeadNative.sealBytes(key, nonce, aad, pt)
        val truncated = sealed.copyOf(pt.size + 8)

        assertContentEquals(pt, AeadNative.openBytes(key, nonce, aad, truncated, tagLen = 8))
        val inside = truncated.copyOf(); inside[pt.size + 7] = (inside[pt.size + 7].toInt() xor 0x80).toByte()
        assertNull(AeadNative.openBytes(key, nonce, aad, inside, tagLen = 8), "a difference inside the prefix was accepted")
    }

    /** Empty plaintext and empty AAD are legal and must still agree with BouncyCastle (the tag is all there is). */
    @Test fun emptyPlaintextAndAadAgree() {
        assumeTrue(AeadNative.available, "tessera_native not available")
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 5).toByte() }
        for (aad in listOf(ByteArray(0), ByteArray(16) { it.toByte() })) {
            val expect = bcSeal(key, nonce, aad, ByteArray(0))
            val got = AeadNative.sealBytes(key, nonce, aad, ByteArray(0))
            assertContentEquals(expect, got, "empty plaintext, aad=${aad.size}")
            assertEquals(0, AeadNative.openBytes(key, nonce, aad, got)!!.size)
        }
    }

    /** The binding rejects malformed arguments rather than reading past a buffer. */
    @Test fun rejectsMalformedArguments() {
        assumeTrue(AeadNative.available, "tessera_native not available")
        val key = ByteArray(32); val nonce = ByteArray(12)
        assertNull(AeadNative.openBytes(key, nonce, ByteArray(0), ByteArray(4), tagLen = 8), "a blob shorter than the tag was accepted")
        assertTrue(runCatching { AeadNative.openBytes(key, nonce, ByteArray(0), ByteArray(32), tagLen = 17) }.isFailure, "tagLen 17 was accepted")
    }
}
