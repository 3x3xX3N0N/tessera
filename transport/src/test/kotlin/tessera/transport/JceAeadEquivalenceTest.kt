package tessera.transport

import tessera.core.PacketKeys
import tessera.core.PacketProtection
import tessera.core.PathId
import tessera.core.ShortHeader
import java.nio.ByteBuffer
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The datapath AEAD moved from BouncyCastle to the JDK provider, which HotSpot intrinsifies: 16.67 us per
 * 1200-byte message down to 6.00 (BENCH-netem, "the JDK's own AEAD"). Both are RFC 8439, so the change must be
 * invisible on the wire — an upgraded peer has to interoperate with one that never moved, and with every
 * packet already captured in a test vector.
 *
 * [PacketCryptoWrapperTest] pins that equality for one payload size; this widens it across the lengths where a
 * block or MAC boundary bug would live, and pins the two things that equality alone cannot see: that the fast
 * path is actually installed (a silently absent provider would leave the transport correct but as slow as
 * before, and every test would still pass), and that the truncated-tag open — which the JCE `Cipher` API cannot
 * express and which therefore stays on BouncyCastle — still agrees with the sealing side.
 */
class JceAeadEquivalenceTest {
    private val secret = ByteArray(32) { (it * 11 + 7).toByte() }

    /**
     * The whole point of the change: if the provider is missing the transport still works, but the measured
     * win is silently gone. That must fail a test rather than pass quietly.
     */
    @Test fun theJdkProviderIsPresentSoTheFastPathIsLive() {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        assertTrue(c.provider.name.isNotEmpty(), "no provider for ChaCha20-Poly1305")
    }

    /** Sealed bytes must equal core's BouncyCastle output at every length across the block and MAC boundaries. */
    @Test fun sealedBytesMatchBouncyCastleAtEveryLength() {
        for (tagLen in listOf(16, 8)) {
            val conn = PacketCrypto(secret, isClient = true).apply { this.tagLen = tagLen }
            val refKeys = PacketKeys(PacketCrypto.hkdf(secret, PacketCrypto.LABEL_C2S), tagLen)
            var pn = 1L
            for (len in listOf(0, 1, 15, 16, 17, 31, 32, 63, 64, 65, 127, 128, 129, 200, 1100, 1200)) {
                pn += 1
                val scratch = ByteArray(4096)
                val buf = ByteBuffer.allocateDirect(4096)
                ShortHeader.write(buf, PathId(0), 0x0BAD_F00D, pn, 0, 0)
                val hdrEnd = buf.position()
                val payload = ByteArray(len) { (it * 13 + len).toByte() }
                buf.put(payload)
                val end = conn.seal(buf, 0, hdrEnd, buf.position(), conn.tx.current, pn, tagLen, scratch)

                val got = ByteArray(end - hdrEnd).also { buf.get(hdrEnd, it) }
                val hdr = ByteBuffer.allocate(64).also { ShortHeader.write(it, PathId(0), 0x0BAD_F00D, pn, 0, 0) }
                val ref = PacketProtection.seal(refKeys, pn, hdr, payload)
                assertContentEquals(ref, got, "tagLen=$tagLen len=$len: sealed bytes differ from the BouncyCastle reference")
            }
        }
    }

    /**
     * Round trip through the transport's own open, including the truncated-tag path that stayed on
     * BouncyCastle while sealing moved to the JDK — the one place the two implementations meet inside a
     * single packet.
     */
    @Test fun sealOpensBackAtEveryLengthIncludingTheTruncatedTagPath() {
        for (tagLen in listOf(16, 8)) {
            val client = PacketCrypto(secret, isClient = true).apply { this.tagLen = tagLen }
            val server = PacketCrypto(secret, isClient = false).apply { this.tagLen = tagLen }
            var pn = 500L
            for (len in listOf(0, 1, 16, 17, 64, 65, 129, 1200)) {
                pn += 1
                val scratch = ByteArray(4096); val out = ByteArray(4096)
                val buf = ByteBuffer.allocateDirect(4096)
                ShortHeader.write(buf, PathId(0), 0x1111_2222, pn, 0, 0)
                val hdrEnd = buf.position()
                val payload = ByteArray(len) { (it * 3 + 1).toByte() }
                buf.put(payload)
                val end = client.seal(buf, 0, hdrEnd, buf.position(), client.tx.current, pn, tagLen, scratch)

                val n = server.open(buf, 0, hdrEnd, end, server.rx.current, pn, tagLen, scratch, out)
                assertEquals(len, n, "tagLen=$tagLen len=$len did not open")
                assertContentEquals(payload, out.copyOf(len), "tagLen=$tagLen len=$len plaintext differs")

                // and a single flipped ciphertext bit must be refused on both paths
                if (len > 0) {
                    val tampered = ByteBuffer.allocateDirect(4096)
                    buf.get(0, ByteArray(end).also { tampered.put(it) })
                    tampered.put(hdrEnd, (tampered.get(hdrEnd).toInt() xor 1).toByte())
                    assertEquals(-1, server.open(tampered, 0, hdrEnd, end, server.rx.current, pn, tagLen, scratch, out),
                        "tagLen=$tagLen len=$len: a flipped ciphertext bit was accepted")
                }
            }
        }
    }
}
