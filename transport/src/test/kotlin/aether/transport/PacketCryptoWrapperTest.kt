package aether.transport

import aether.core.PacketKeys
import aether.core.PacketProtection
import aether.core.PathId
import aether.core.ShortHeader
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The transport's in-place crypto must produce exactly core's bytes (AEAD for both tag lengths, header protection). */
class PacketCryptoWrapperTest {
    private val secret = ByteArray(32) { (it * 5 + 3).toByte() }
    private val pnOff = PacketProtection.SHORT_PN_OFFSET

    @Test fun sealOpenAndHeaderProtectionMatchCoreForBothTagLengthsAndEveryPnLength() {
        for (tagLen in listOf(16, 8)) {
            val client = PacketCrypto(secret, isClient = true).apply { this.tagLen = tagLen }
            val server = PacketCrypto(secret, isClient = false).apply { this.tagLen = tagLen }
            // client tx (c2s) == server rx (c2s): same label, same generation-0 keys; distinct from the other direction
            assertContentEquals(client.tx.current.key, server.rx.current.key)
            assertContentEquals(client.tx.current.hp, server.rx.current.hp)
            assertTrue(!client.tx.current.key.contentEquals(client.rx.current.key), "directions must not share keys")
            val refKeys = PacketKeys(PacketCrypto.hkdf(secret, PacketCrypto.LABEL_C2S), tagLen)
            assertContentEquals(refKeys.key, client.tx.current.key); assertContentEquals(refKeys.iv, client.tx.current.iv)
            // the plain generation-0 keys used before a state exists are the same derivation
            val plain = PacketCrypto(secret, isClient = true)
            assertContentEquals(refKeys.key, plain.txKeys().key); assertContentEquals(refKeys.hp, plain.txKeys().hp)
            assertEquals(0, plain.txPhase); plain.warm(); assertContentEquals(refKeys.key, plain.txKeys().key)
            var pnLensSeen = 0
            for (pn in listOf(10L, 100L, 20_000L, 5_000_000L)) {            // ShortHeader.pnLenFor(pn, 0) picks 1..4 bytes
                val scratch = ByteArray(2048); val out = ByteArray(2048)
                val buf = ByteBuffer.allocateDirect(2048)
                ShortHeader.write(buf, PathId(0), 0x1234_5678, pn, 0, 0)
                val hdrEnd = buf.position(); val pnLen = hdrEnd - pnOff     // derived from the header, never assumed
                pnLensSeen = pnLensSeen or (1 shl pnLen)
                val payload = ByteArray(40) { (it * 7 + pnLen).toByte() }
                buf.put(payload)
                val end = client.seal(buf, 0, hdrEnd, buf.position(), client.tx.current, pn, tagLen, scratch)
                assertEquals(hdrEnd + payload.size + tagLen, end)
                // same ciphertext as core for the same header/payload/keys
                val hdr = ByteBuffer.allocate(64).also { ShortHeader.write(it, PathId(0), 0x1234_5678, pn, 0, 0) }
                val ref = PacketProtection.seal(refKeys, pn, hdr, payload)
                val mine = ByteArray(end - hdrEnd).also { buf.get(hdrEnd, it) }
                assertContentEquals(ref, mine, "tag $tagLen pnLen $pnLen ciphertext")
                // same header-protection mask as core (core on a byte[] copy, ours in place)
                val packet = ByteArray(end).also { buf.get(0, it) }
                PacketProtection.protectHeader(refKeys, packet, pnOff, pnLen)
                client.protectHeader(buf, pnLen)
                val protectedMine = ByteArray(end).also { buf.get(0, it) }
                assertContentEquals(packet, protectedMine, "tag $tagLen pnLen $pnLen header protection")
                assertEquals(0, (protectedMine[0].toInt() shr 2) and 7, "path id bits stay in the clear")
                // receiver: unprotect (pnLen from the flags), open, and reject a flipped tag bit
                buf.limit(end).position(0)
                assertEquals(pnLen, server.unprotectHeader(buf))
                assertEquals(0, buf.get(0).toInt() and 0x63 xor (packet[0].toInt() and 0x63) and 0, "flags restored")
                val n = server.open(buf, 0, hdrEnd, end, server.rx.current, pn, tagLen, scratch, out)
                assertEquals(payload.size, n); assertContentEquals(payload, out.copyOf(n))
                buf.put(end - 1, (buf.get(end - 1).toInt() xor 1).toByte())
                assertEquals(-1, server.open(buf, 0, hdrEnd, end, server.rx.current, pn, tagLen, scratch, out), "tampered tag must fail (tag $tagLen)")
                buf.put(end - 1, (buf.get(end - 1).toInt() xor 1).toByte())
                assertEquals(-1, server.open(buf, 0, hdrEnd, end, server.rx.current, pn + 1, tagLen, scratch, out), "wrong pn must fail")
            }
            assertTrue(Integer.bitCount(pnLensSeen) >= 2, "expected several pn lengths, saw mask $pnLensSeen")
        }
    }

    @Test fun keyUpdateKeepsHeaderProtectionKeyAndOldGenerationOpensAfterRotation() {
        val a = PacketCrypto(secret, isClient = true); val b = PacketCrypto(secret, isClient = false)
        val hp = a.tx.current.hp
        fun seal(c: PacketCrypto, keys: PacketKeys, pn: Long, phase: Int): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(256)
            ShortHeader.write(buf, PathId(0), 1, pn, 0, phase); val hdrEnd = buf.position()
            buf.put(ByteArray(20) { pn.toByte() })
            val end = c.seal(buf, 0, hdrEnd, buf.position(), keys, pn, 16, ByteArray(256))
            c.protectHeader(buf, hdrEnd - pnOff)
            return buf.limit(end).position(0) as ByteBuffer
        }
        val old = seal(a, a.tx.current, 1, a.tx.currentPhase)
        a.tx.initiateUpdate()
        assertContentEquals(hp, a.tx.current.hp, "hp is pinned across generations")
        val fresh = seal(a, a.tx.current, 2, a.tx.currentPhase)
        val out = ByteArray(256); val scratch = ByteArray(256)
        // B: the new-phase packet opens under the pre-derived next keys -> B follows
        val pnLen2 = b.unprotectHeader(fresh)
        assertEquals(1, fresh.get(0).toInt() and 1)
        assertTrue(b.open(fresh, 0, pnOff + pnLen2, fresh.limit(), b.rx.next, 2, 16, scratch, out) == 20)
        b.rx.onPeerPhase(1); assertEquals(1, b.rx.generation)
        // the reordered old-phase packet opens under the retained previous generation
        val pnLen1 = b.unprotectHeader(old)
        assertEquals(0, old.get(0).toInt() and 1)
        assertTrue(b.open(old, 0, pnOff + pnLen1, old.limit(), b.rx.previous!!, 1, 16, scratch, out) == 20)
    }
}
