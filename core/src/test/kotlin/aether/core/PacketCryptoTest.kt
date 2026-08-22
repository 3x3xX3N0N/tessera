package aether.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.util.encoders.Hex
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PacketCryptoTest {
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val pnOff = PacketProtection.SHORT_PN_OFFSET

    /** Header written the way a sender does it; position is left at the end of the header (the AAD convention). */
    private fun header(pn: Long, largestAcked: Long, path: Int = 3, phase: Int = 0, conn: Int = 0x1234_5678): ByteBuffer =
        ByteBuffer.allocate(64).also { ShortHeader.write(it, PathId(path), conn, pn, largestAcked, phase) }

    @Test fun sealOpenRoundTripTag16And8() {
        val payload = "hello aether".toByteArray()
        val hdr = header(pn = 1000, largestAcked = 990)
        val cts = listOf(16, 8).map { tagLen ->
            val keys = PacketKeys(secret, tagLen)
            val ct = PacketProtection.seal(keys, 1000, hdr, payload)
            assertEquals(payload.size + tagLen, ct.size)
            assertEquals(7, hdr.position(), "seal must not move the header buffer")
            assertContentEquals(payload, PacketProtection.open(keys, 1000, hdr, ct), "tag $tagLen")
            ct
        }
        // tag 8 is the 16-byte tag truncated: same key/iv, same ciphertext prefix
        assertContentEquals(cts[0].copyOf(cts[0].size - 8), cts[1])
        // a wrapped/flipped header buffer yields the same AAD as the written one
        val wrapped = ByteBuffer.wrap(PacketProtection.aadOf(hdr))
        assertContentEquals(payload, PacketProtection.open(PacketKeys(secret, 16), 1000, wrapped, cts[0]))
        val k16 = PacketKeys(secret, 16)
        assertContentEquals(ByteArray(0), PacketProtection.open(k16, 5, hdr, PacketProtection.seal(k16, 5, hdr, ByteArray(0))))
    }

    @Test fun tamperRejected() {
        val payload = ByteArray(40) { it.toByte() }
        for (tagLen in listOf(16, 8)) {
            val keys = PacketKeys(secret, tagLen)
            val hdr = header(pn = 77, largestAcked = 70)
            val ct = PacketProtection.seal(keys, 77, hdr, payload)
            fun flipped(i: Int) = ct.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(PacketProtection.open(keys, 77, hdr, flipped(0)), "ciphertext bit, tag $tagLen")
            assertNull(PacketProtection.open(keys, 77, hdr, flipped(ct.size - 1)), "tag bit, tag $tagLen")
            assertNull(PacketProtection.open(keys, 78, hdr, ct), "wrong pn, tag $tagLen")
            assertNull(PacketProtection.open(keys, 77, header(pn = 77, largestAcked = 70, path = 4), ct), "AAD mismatch, tag $tagLen")
            assertNull(PacketProtection.open(PacketKeys(secret.copyOf().also { it[0] = 9 }, tagLen), 77, hdr, ct), "wrong key, tag $tagLen")
            assertNull(PacketProtection.open(keys, 77, hdr, ct.copyOf(tagLen - 1)), "too short, tag $tagLen")
            assertContentEquals(payload, PacketProtection.open(keys, 77, hdr, ct), "untouched still opens, tag $tagLen")
        }
    }

    @Test fun headerProtectionRoundTripKeepsPathIdInClear() {
        val keys = PacketKeys(secret, 16)
        val pnFor = mapOf(2 to 10L, 3 to 20_000L, 4 to 5_000_000L) // pnLenFor(pn, 0) picks exactly that length (2-byte floor)
        var masked = 0
        for ((pnLen, pn) in pnFor) for (path in 0..7) for (phase in 0..1) {
            val hdr = header(pn, largestAcked = 0, path = path, phase = phase)
            assertEquals(pnOff + pnLen, hdr.position())
            val payload = ByteArray(PacketProtection.minPayloadLen(pnLen, 16) + 3) { (it + path).toByte() }
            val clear = PacketProtection.aadOf(hdr) + PacketProtection.seal(keys, pn, hdr, payload)
            val packet = clear.copyOf()
            PacketProtection.protectHeader(keys, packet, pnOff, pnLen)
            assertEquals(clear[0].toInt() and 0x9C, packet[0].toInt() and 0x9C, "form bit + pathId bits in clear")
            assertEquals(path, (packet[0].toInt() shr 2) and 0x7, "pathId readable on the protected packet")
            assertContentEquals(clear.copyOfRange(1, pnOff), packet.copyOfRange(1, pnOff), "connId untouched")
            assertContentEquals(clear.copyOfRange(pnOff + pnLen, clear.size), packet.copyOfRange(pnOff + pnLen, clear.size), "ciphertext untouched")
            if (!packet.copyOfRange(pnOff, pnOff + pnLen).contentEquals(clear.copyOfRange(pnOff, pnOff + pnLen))) masked++
            assertEquals(pnLen, PacketProtection.unprotectHeader(keys, packet, pnOff))
            assertContentEquals(clear, packet, "unprotect restores pn bytes and flag bits")
            // receiver path end to end: parse the unprotected header, then open with it as AAD
            val bb = ByteBuffer.wrap(packet)
            val parsed = ShortHeader.read(bb, largestSeen = pn - 1)
            assertEquals(pn, parsed.pn); assertEquals(PathId(path), parsed.path); assertEquals(phase, parsed.keyPhase)
            assertContentEquals(payload, PacketProtection.open(keys, parsed.pn, bb, packet.copyOfRange(bb.position(), packet.size)))
        }
        assertTrue(masked > pnFor.size * 16 * 3 / 4, "pn bytes should be masked in nearly all ${pnFor.size * 16} cases, got $masked")
    }

    @Test fun sampleNeedsPadding() {
        val keys = PacketKeys(secret, 8)
        val hdr = header(pn = 10, largestAcked = 0) // pnLen 2 (floor): 18 B of ciphertext needed -> 10 B payload with an 8 B tag
        assertEquals(11, PacketProtection.minPayloadLen(1, 8)); assertEquals(0, PacketProtection.minPayloadLen(4, 16))
        val short = PacketProtection.aadOf(hdr) + PacketProtection.seal(keys, 10, hdr, ByteArray(9))
        assertFailsWith<IllegalArgumentException> { PacketProtection.protectHeader(keys, short, pnOff, 2) }
        assertFailsWith<IllegalArgumentException> { PacketProtection.unprotectHeader(keys, short, pnOff) }
        val ok = PacketProtection.aadOf(hdr) + PacketProtection.seal(keys, 10, hdr, ByteArray(10))
        PacketProtection.protectHeader(keys, ok, pnOff, 2)
        assertEquals(2, PacketProtection.unprotectHeader(keys, ok, pnOff))
        assertFailsWith<IllegalArgumentException>("pnLen must match the flag bits") { PacketProtection.protectHeader(keys, ok, pnOff, 3) }
    }

    /** RFC 9001 appendix A.5 (ChaCha20-Poly1305 short header): same nonce construction and HP mask as QUIC, so the
     *  ciphertext and masked pn bytes must match byte for byte; only the flag byte differs (our bit layout, mask 0x63). */
    @Test fun matchesRfc9001ChaChaVector() {
        val s = Hex.decode("9ac312a7f877468ebe69422748ad00a15443f18203a07d6060f688f30f21632b")
        fun expandLabel(label: String, len: Int): ByteArray {
            val l = "tls13 $label".toByteArray()
            val info = ByteBuffer.allocate(4 + l.size).putShort(len.toShort()).put(l.size.toByte()).put(l).put(0).array()
            return ByteArray(len).also { HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters.skipExtractParameters(s, info)) }.generateBytes(it, 0, len) }
        }
        val hp = expandLabel("quic hp", 32)
        assertEquals("25a282b9e82f06f21f488917a4fc8f1b73573685608597d0efcb076b0ab7a7a4", Hex.toHexString(hp))
        val keys = PacketKeys.raw(expandLabel("quic key", 32), expandLabel("quic iv", 12), hp, 16)
        val hdr = Hex.decode("4200bff4") // flags | 3-byte truncated pn; the RFC example has an empty connection id
        val ct = PacketProtection.seal(keys, 654360564, ByteBuffer.wrap(hdr), byteArrayOf(1))
        assertEquals("655e5cd55c41f69080575d7999c25a5bfb", Hex.toHexString(ct))
        assertEquals("aefefe7d03", Hex.toHexString(PacketProtection.hpMask(hp, ct, 1)))
        val packet = hdr + ct
        PacketProtection.protectHeader(keys, packet, pnOffset = 1, pnLen = 3)
        assertEquals("fe4189", Hex.toHexString(packet.copyOfRange(1, 4)), "masked pn bytes as in the RFC")
        assertEquals(0x42 xor (0xae and 0x63), packet[0].toInt() and 0xFF)
        assertEquals(3, PacketProtection.unprotectHeader(keys, packet, pnOffset = 1))
        assertContentEquals(hdr + ct, packet)
    }

    @Test fun nonceXorsPnIntoLowBytesAndDerivationIgnoresTagLen() {
        val k = PacketKeys(secret, 16)
        assertEquals(32, k.key.size); assertEquals(12, k.iv.size); assertEquals(32, k.hp.size)
        assertFalse(k.key.contentEquals(k.hp))
        assertContentEquals(k.iv, k.nonce(0))
        val n = k.nonce(0x0102030405060708L)
        assertContentEquals(k.iv.copyOfRange(0, 4), n.copyOfRange(0, 4))
        for (i in 0 until 8) assertEquals((k.iv[4 + i].toInt() xor (i + 1)).toByte(), n[4 + i])
        val k8 = PacketKeys(secret, 8)
        assertContentEquals(k.key, k8.key); assertContentEquals(k.iv, k8.iv); assertContentEquals(k.hp, k8.hp)
        assertFailsWith<IllegalArgumentException> { PacketKeys(secret, 12) }
    }

    @Test fun keyUpdateConvergesAndKeepsOldPhaseForReorder() {
        for (tagLen in listOf(16, 8)) {
            val a = KeyPhaseState(secret, tagLen); val b = KeyPhaseState(secret, tagLen)
            assertContentEquals(a.current.key, b.current.key); assertContentEquals(a.next.key, b.next.key)
            val hp = a.current.hp
            val h1 = header(pn = 1, largestAcked = 0, phase = a.currentPhase)
            val p1 = a.seal(1, h1, "old".toByteArray()) // gen 0, delayed in the network

            a.initiateUpdate()
            assertEquals(1, a.currentPhase); assertTrue(a.updatePending)
            assertFailsWith<IllegalStateException> { a.initiateUpdate() }
            assertContentEquals(hp, a.current.hp, "header-protection key never rotates")
            val h2 = header(pn = 2, largestAcked = 0, phase = a.currentPhase)
            val p2 = a.seal(2, h2, "first with phase 1".toByteArray())

            // B: other phase, nothing pending, nothing older retained -> the pre-derived next keys; then B follows
            assertSame(b.next, b.keysFor(1))
            assertContentEquals("first with phase 1".toByteArray(), PacketProtection.open(b.keysFor(1), 2, h2, p2))
            assertTrue(b.onPeerPhase(1)); assertEquals(1, b.currentPhase); assertEquals(1, b.generation)
            assertContentEquals(a.current.key, b.current.key); assertContentEquals(a.current.iv, b.current.iv)
            assertContentEquals(hp, b.current.hp)

            // B -> A with phase 1: A's current keys open it and A's pending update is confirmed
            val h3 = header(pn = 3, largestAcked = 0, phase = b.currentPhase)
            val p3 = b.seal(3, h3, "reply".toByteArray())
            assertContentEquals("reply".toByteArray(), PacketProtection.open(a.current, 3, h3, p3))
            assertContentEquals("reply".toByteArray(), a.open(1, 3, h3, p3)); assertFalse(a.updatePending)
            // A -> B with phase 1 opens with B's current keys
            val h4 = header(pn = 4, largestAcked = 0, phase = a.currentPhase)
            val p4 = a.seal(4, h4, "a->b".toByteArray())
            assertContentEquals("a->b".toByteArray(), PacketProtection.open(b.current, 4, h4, p4))

            // the delayed gen-0 packet reaches B after the update: old-phase keys retained for one update
            assertSame(b.previous, b.keysFor(0))
            assertContentEquals("old".toByteArray(), PacketProtection.open(b.keysFor(0), 1, h1, p1))
            assertContentEquals("old".toByteArray(), b.open(0, 1, h1, p1))
            assertEquals(1, b.generation, "a reordered old packet must not trigger another rotation")
            // a forged packet with the other phase never rotates B
            assertNull(b.open(0, 99, header(pn = 99, largestAcked = 0, phase = 0), ByteArray(40)))
            assertEquals(1, b.generation); assertEquals(1, b.currentPhase)

            // second update, this time from B: A follows only after authenticating under its next keys
            b.initiateUpdate(); assertEquals(0, b.currentPhase); assertEquals(2, b.generation)
            val h5 = header(pn = 5, largestAcked = 0, phase = b.currentPhase)
            val p5 = b.seal(5, h5, "gen2".toByteArray())
            assertContentEquals("gen2".toByteArray(), a.open(0, 5, h5, p5))
            assertEquals(2, a.generation); assertEquals(0, a.currentPhase); assertFalse(a.updatePending)
            assertContentEquals(a.current.key, b.current.key)
            // gen 0 is now two updates back on both sides: dropped
            assertNull(a.open(0, 1, h1, p1)); assertNull(b.open(0, 1, h1, p1))
            // A -> B in gen 2 confirms B's update
            val h6 = header(pn = 6, largestAcked = 0, phase = a.currentPhase)
            val p6 = a.seal(6, h6, "ack gen2".toByteArray())
            assertContentEquals("ack gen2".toByteArray(), b.open(0, 6, h6, p6)); assertFalse(b.updatePending)
        }
    }

    @Test fun pendingUpdateTreatsOldPhaseAsPeerNotCaughtUp() {
        val a = KeyPhaseState(secret, 16); val b = KeyPhaseState(secret, 16)
        a.initiateUpdate()
        val h = header(pn = 1, largestAcked = 0, phase = b.currentPhase)
        val p = b.seal(1, h, "still old".toByteArray())
        assertSame(a.previous, a.keysFor(0))
        assertContentEquals("still old".toByteArray(), a.open(0, 1, h, p))
        assertFalse(a.onPeerPhase(0)); assertEquals(1, a.generation); assertTrue(a.updatePending)
    }

    @Test fun simultaneousUpdatesConverge() {
        val a = KeyPhaseState(secret, 16); val b = KeyPhaseState(secret, 16)
        a.initiateUpdate(); b.initiateUpdate()
        assertEquals(a.currentPhase, b.currentPhase); assertContentEquals(a.current.key, b.current.key)
        val h = header(pn = 1, largestAcked = 0, phase = a.currentPhase)
        val p = a.seal(1, h, "x".toByteArray())
        assertContentEquals("x".toByteArray(), b.open(1, 1, h, p)); assertFalse(b.updatePending)
        assertFalse(a.onPeerPhase(1)); assertFalse(a.updatePending)
        assertEquals(1, a.generation); assertEquals(1, b.generation)
    }

    /** Full sender -> wire -> receiver path across a key update, the way a connection would drive it. */
    @Test fun fullPacketPathAcrossKeyUpdate() {
        val tx = KeyPhaseState(secret, 8); val rx = KeyPhaseState(secret, 8)
        var largestSeen = -1L
        fun send(pn: Long, msg: String): ByteArray {
            val buf = ByteBuffer.allocate(Wire.MAX_DATAGRAM)
            ShortHeader.write(buf, PathId(5), 0x0BADF00D, pn, largestAcked = 0, keyPhase = tx.currentPhase)
            val pnLen = buf.position() - pnOff
            val payload = msg.toByteArray().let { if (it.size < PacketProtection.minPayloadLen(pnLen, 8)) it.copyOf(PacketProtection.minPayloadLen(pnLen, 8)) else it }
            buf.put(tx.seal(pn, buf, payload))
            return buf.array().copyOf(buf.position()).also { PacketProtection.protectHeader(tx.current, it, pnOff, pnLen) }
        }
        fun receive(packet: ByteArray): String? {
            PacketProtection.unprotectHeader(rx.current, packet, pnOff)
            val bb = ByteBuffer.wrap(packet)
            val h = ShortHeader.read(bb, largestSeen)
            assertEquals(PathId(5), h.path)
            val pt = rx.open(h.keyPhase, h.pn, bb, packet.copyOfRange(bb.position(), packet.size)) ?: return null
            largestSeen = maxOf(largestSeen, h.pn)
            return String(pt).trimEnd(' ')
        }
        assertEquals("one", receive(send(0, "one")))
        val delayed = send(1, "two")
        tx.initiateUpdate()
        assertEquals("three", receive(send(2, "three"))); assertEquals(1, rx.currentPhase)
        assertEquals("two", receive(delayed), "reordered old-phase packet after the update")
        assertEquals("four", receive(send(300, "four")))
        val tampered = send(301, "five").also { it[it.size - 1] = (it.last().toInt() xor 0x80).toByte() }
        assertNull(receive(tampered))
    }
}
