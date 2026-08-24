package tessera.core

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.fail

/**
 * Parser fuzzing for every wire-facing parse entry point.
 *
 * Contract under test — for arbitrary attacker-chosen bytes a parser must either
 *   (a) succeed, or
 *   (b) return null / false, or
 *   (c) throw one of the exception types **declared for that entry point** below,
 * and must never hang, allocate unboundedly, or fail in an undeclared way (OutOfMemoryError,
 * StackOverflowError, NegativeArraySizeException, ArrayIndexOutOfBoundsException,
 * NullPointerException, ...). An undeclared failure is a parser bug: fix the parser, do not widen
 * the accepted list here.
 *
 * Inputs per entry point: pure random, valid-prefix-then-garbage, every truncation of a valid
 * encoding, every single-bit flip of a valid encoding, and adversarial length fields (varints and
 * fixed-width lengths set to the largest encodable values).
 *
 * Reproducibility: everything derives from SEEDS. Failures print the seed and the exact input in
 * hex. Set `-Dtessera.fuzz.iterations=N` (or env `TESSERA_FUZZ_ITERATIONS`) for a much larger sweep;
 * the committed default is ~10k cases per entry point and runs in a few seconds.
 */
class FuzzTest {

    private companion object {
        val SEEDS = longArrayOf(1L, 0xC0FFEEL, 0x5EEDL, 20260823L)
        val ITERATIONS: Int =
            (System.getProperty("tessera.fuzz.iterations") ?: System.getenv("TESSERA_FUZZ_ITERATIONS"))
                ?.toIntOrNull() ?: 10_000
        /** Per-entry-point wall-clock budget. A parser that hangs or allocates unboundedly trips this. */
        const val BUDGET_MS = 60_000L
        const val MAX_LEN = 512

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- harness

    /** The input currently under test, so a hang can still name the bytes that caused it. */
    private val current = AtomicReference<Pair<Long, ByteArray>>()

    private class Case(val seed: Long, val bytes: ByteArray)

    /**
     * Runs [body] over the whole corpus on a worker thread with a wall-clock budget; any undeclared
     * Throwable, or blowing the budget, fails the test naming seed + input hex.
     */
    private fun sweep(name: String, corpus: List<Case>, declared: Set<KClass<out Throwable>>, body: (ByteArray) -> Unit) {
        var failure: String? = null
        val t = Thread({
            for (c in corpus) {
                current.set(c.seed to c.bytes)
                try {
                    body(c.bytes)
                } catch (e: Throwable) {
                    if (declared.none { it.java.isInstance(e) }) {
                        failure = name + ": undeclared " + e::class.qualifiedName + ": " + e.message + "\n" +
                            "  seed=" + c.seed + " len=" + c.bytes.size + " input=" + hex(c.bytes) + "\n" +
                            "  declared: " + declared.joinToString { d -> d.simpleName ?: "?" }
                        return@Thread
                    }
                }
            }
        }, "fuzz-" + name)
        t.isDaemon = true
        t.start()
        t.join(BUDGET_MS)
        if (t.isAlive) {
            val cur = current.get()
            val seed = cur?.first ?: 0L
            val bytes = cur?.second ?: ByteArray(0)
            val frame = t.stackTrace.take(6).joinToString("\n    ")
            fail(name + ": no progress within " + BUDGET_MS + "ms - hang or unbounded allocation.\n" +
                "  seed=" + seed + " len=" + bytes.size + " input=" + hex(bytes) + "\n    " + frame)
        }
        failure?.let { fail(it) }
    }

    // ---------------------------------------------------------------- corpus

    /** Pure random + semi-structured mutations of [valid], plus adversarial length fields. */
    private fun corpus(valid: List<ByteArray>, maxLen: Int = MAX_LEN): List<Case> {
        val out = ArrayList<Case>(ITERATIONS * 2)
        // 1. every truncation of every valid encoding (length checks at every boundary)
        for (v in valid) for (n in 0..v.size) out += Case(0, v.copyOf(n))
        // 2. every single-bit flip of every valid encoding
        for (v in valid) for (i in v.indices) for (b in 0..7)
            out += Case(0, v.copyOf().also { it[i] = (it[i].toInt() xor (1 shl b)).toByte() })
        // 3. adversarial length fields: valid prefix, then bytes that decode as huge lengths
        val huge = listOf(
            byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1),                 // 8-byte varint, 2^62-1
            byteArrayOf(-1, -1, -1, -1),                                  // 4-byte varint, 2^30-1
            byteArrayOf(0xBF.toByte(), -1, -1, -1),
            byteArrayOf(0xC0.toByte(), 0, 0, 0, -1, -1, -1, -1),          // low 32 bits all ones -> toInt() == -1
            byteArrayOf(0xC0.toByte(), 0, 0, 0, 0x7F, -1, -1, -1),
            byteArrayOf(0x7F, -1), byteArrayOf(-1), byteArrayOf(0x80.toByte(), 0, 0, 0)
        )
        for (v in valid) for (h in huge) for (cut in 0..minOf(v.size, 12)) out += Case(0, v.copyOf(cut) + h)
        // 4. pure random and valid-prefix-then-random, seeded
        val per = maxOf(1, ITERATIONS / (SEEDS.size * 2))
        for (s in SEEDS) {
            val rnd = Random(s)
            repeat(per) { out += Case(s, ByteArray(rnd.nextInt(0, maxLen)) { rnd.nextInt(256).toByte() }) }
            repeat(per) {
                val v = valid[rnd.nextInt(valid.size)]
                val keep = rnd.nextInt(0, v.size + 1)
                out += Case(s, v.copyOf(keep) + ByteArray(rnd.nextInt(0, 32)) { rnd.nextInt(256).toByte() })
            }
        }
        return out
    }

    private fun enc(cap: Int = 1024, f: (ByteBuffer) -> Unit): ByteArray {
        val b = ByteBuffer.allocate(cap); f(b); b.flip(); return ByteArray(b.remaining()).also { b.get(it) }
    }

    private fun payload() = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    // ---------------------------------------------------------------- entry points

    /**
     * `FrameCodec.read`: returns null on an empty buffer; declared IllegalArgumentException (unknown
     * frame type, or a length field pointing past the end of the buffer) and BufferUnderflowException.
     */
    @Test fun frameCodecRead() {
        val valid = listOf(
            enc { Frame.Msg(7, 3, true, payload()).write(it) },
            enc { Frame.Ack(PathId(1), 9, listOf(1L..4L, 6L..6L), 2, 33).write(it) },
            enc { Frame.Grant(PathId(2), (1 shl 20).toLong(), 7).write(it) },
            enc { Frame.Repair(5, 32, 0x1234, payload()).write(it) },
            enc { Frame.PathChallenge(PathId(3), -1L).write(it) },
            enc { Frame.Ping.write(it) },
            enc { PathResponse(PathId(4), 12345).write(it) },
            enc { Frame.Padding(64).write(it) },
            byteArrayOf(0x82.toByte(), 4, 1, 2, 3, 4, 0x06)   // unknown extension frame then Ping
        )
        sweep("FrameCodec.read", corpus(valid),
            setOf(IllegalArgumentException::class, BufferUnderflowException::class)) { b ->
            val buf = ByteBuffer.wrap(b)
            // a frame parser is used in a loop over a datagram, so fuzz the loop too
            var guard = 0
            while (buf.hasRemaining() && guard++ < 4096) {
                val before = buf.position()
                FrameCodec.read(buf) ?: break
                if (buf.position() == before) break   // no progress: would spin in the transport
            }
        }
    }

    /**
     * Deep extension-frame nesting: `FrameCodec.read` self-calls on `0x80+` frames. A long run of
     * minimal extension frames must not blow the stack.
     */
    @Test fun frameCodecDoesNotStackOverflowOnExtensionFrames() {
        for (n in listOf(1_000, 100_000, 1_000_000)) {
            val b = ByteArray(n * 2) { if (it % 2 == 0) 0x80.toByte() else 0 }
            try {
                FrameCodec.read(ByteBuffer.wrap(b))
            } catch (e: Throwable) {
                if (e !is IllegalArgumentException && e !is BufferUnderflowException)
                    fail("FrameCodec.read: " + e::class.qualifiedName + " on " + n +
                        " nested extension frames (input = " + n + " x 8000); the skip must not recurse")
            }
        }
    }

    /** `PacketHeader.read`: fixed 14-byte header, no length fields; declared BufferUnderflowException. */
    @Test fun packetHeaderRead() {
        val valid = listOf(
            enc { PacketHeader(Wire.F_INITIAL, ConnId(1), PathId(0), 1).write(it) },
            enc { PacketHeader(0xFF, ConnId(-1), PathId(255), 0xFFFFFFFFL).write(it) }
        )
        sweep("PacketHeader.read", corpus(valid, 64), setOf(BufferUnderflowException::class)) {
            PacketHeader.read(ByteBuffer.wrap(it))
        }
    }

    /** `ShortHeader.read`: declared IllegalArgumentException (long-header form bit) and BufferUnderflowException. */
    @Test fun shortHeaderRead() {
        val valid = buildList {
            for (pn in listOf(1L, 0x7FFFL, 0x40000L, 0x4000000L))
                add(enc { ShortHeader.write(it, PathId(3), 0x11223344, pn, 0, 1) })
        }
        sweep("ShortHeader.read", corpus(valid, 64),
            setOf(IllegalArgumentException::class, BufferUnderflowException::class)) {
            for (largest in longArrayOf(0, 1L shl 40, Long.MAX_VALUE / 2))
                ShortHeader.read(ByteBuffer.wrap(it), largest)
        }
    }

    /** `VarInt.read`: declared BufferUnderflowException. Must never read past the limit, and never
     *  return a negative value (the encoder requires non-negative input). */
    @Test fun varIntRead() {
        val valid = listOf(0L, 1L, 0x3FL, 0x40L, 0x3FFFL, 0x4000L, 0x3FFF_FFFFL, 0x4000_0000L, 0x3FFF_FFFF_FFFF_FFFFL)
            .map { v -> enc(16) { VarInt.write(it, v) } }
        sweep("VarInt.read", corpus(valid, 32), setOf(BufferUnderflowException::class)) { b ->
            val buf = ByteBuffer.wrap(b)
            var guard = 0
            while (buf.hasRemaining() && guard++ < 64) {
                val v = VarInt.read(buf)
                check(v >= 0) { "VarInt.read returned a negative value " + v }
            }
        }
    }

    /**
     * `CompactMsg.read`: declared IllegalArgumentException (bad type bits, or a length field past the
     * end of the buffer) and BufferUnderflowException.
     */
    @Test fun compactMsgRead() {
        val valid = listOf(
            enc { CompactMsg.write(it, 10, 7, 0, false, payload(), true) },
            enc { CompactMsg.write(it, 10, 7, 0, false, payload(), false) },
            enc { CompactMsg.write(it, 0x4000, 0, 0x1234, true, payload(), false) },
            enc { CompactMsg.write(it, 1, 0, 63, true, payload(), true) }
        )
        sweep("CompactMsg.read", corpus(valid),
            setOf(IllegalArgumentException::class, BufferUnderflowException::class)) { b ->
            val buf = ByteBuffer.wrap(b)
            var guard = 0
            var prev = 0L
            while (buf.hasRemaining() && guard++ < 4096) {
                val before = buf.position()
                val m = CompactMsg.read(buf, prev)
                prev = m.msgId
                if (buf.position() == before) break
            }
        }
    }

    /**
     * `ConnParams.read`: a TLV loop over attacker-chosen tags and lengths; declared
     * IllegalArgumentException (bad tagLen, or a TLV length that walks outside the buffer) and
     * BufferUnderflowException. Must terminate: a length that moves the position backwards would
     * loop forever.
     */
    @Test fun connParamsRead() {
        val valid = listOf(
            enc { ConnParams().write(it) },
            enc { ConnParams(tagLen = 8, dictId = 0x1234, maxDatagram = 1200, ackFreq = 4,
                             shortConnId = 0x11223344, zeroRttReplayWindowMs = 5000).write(it) },
            enc { ConnParams(shortConnId = -559038737).write(it) }
        )
        sweep("ConnParams.read", corpus(valid, 128),
            setOf(IllegalArgumentException::class, BufferUnderflowException::class)) {
            ConnParams.read(ByteBuffer.wrap(it))
        }
    }

    /**
     * `RlncDecoder.onRepair` fed from `FrameCodec.read`: window length and symbol length come off the
     * wire. Nothing is declared - a repair frame that parsed must be safe to hand to the decoder.
     */
    @Test fun rlncDecoderOnRepair() {
        val sym = ByteBuffer.wrap(ByteArray(64) { it.toByte() })
        val valid = listOf(
            enc { Frame.Repair(0, 8, 1, sym.duplicate()).write(it) },
            enc { Frame.Repair(Long.MAX_VALUE - 4, 64, -1, sym.duplicate()).write(it) },
            enc { Frame.Repair(-1, 0, 0, sym.duplicate()).write(it) }
        )
        // windowLen is a 16-bit wire field, so a single case can cost 64K rows: cap the corpus size
        sweep("RlncDecoder.onRepair", corpus(valid, 128).take(3000), emptySet()) { b ->
            val buf = ByteBuffer.wrap(b)
            val f = try { FrameCodec.read(buf) } catch (e: RuntimeException) { null }
            if (f is Frame.Repair) {
                val d = RlncDecoder(64)
                d.onSource(0, ByteArray(64))
                d.onRepair(f)
            }
        }
    }

    /**
     * `ZeroRtt.Server.accept`: returns null on short, replayed or unauthenticated bodies. The hybrid
     * handshake runs on attacker bytes, so BouncyCastle's own input validation is declared:
     * IllegalArgumentException and IllegalStateException (e.g. an all-zero X25519 agreement).
     */
    @Test fun zeroRttAccept() {
        val keys = Handshake.generate()
        val client = ZeroRtt.Client(Handshake.initiate(keys.x25519Pub, keys.kemPub))
        val valid = listOf(
            client.initial(ByteArray(32) { it.toByte() }, 1000, 77),
            client.initial(ByteArray(0), 1000, 78)
        )
        // KEM decapsulation dominates the cost; keep this sweep small but structured
        val cases = corpus(valid, ZeroRtt.PREFIX_LEN + 64).filter { it.bytes.size >= ZeroRtt.PREFIX_LEN - 8 }.take(300)
        sweep("ZeroRtt.Server.accept", cases,
            setOf(IllegalArgumentException::class, IllegalStateException::class)) {
            ZeroRtt.Server(keys).accept(it, 1000)
        }
    }

    /**
     * `Resumption.Server.accept`: returns null on short, expired, replayed or unauthenticated bodies.
     * Declared: IllegalArgumentException, BufferUnderflowException.
     */
    @Test fun resumptionAccept() {
        val ticketKey = ByteArray(32) { it.toByte() }
        val sessionKey = ByteArray(32) { (it * 3).toByte() }
        val ticket = Resumption.Server(ticketKey).issueTicket(sessionKey, 1000)
        val secret = Resumption.resumptionSecret(sessionKey)
        val valid = listOf(
            Resumption.Client(ticket, secret).initial(ByteArray(16), 1000, 5).second,
            Resumption.Client(ticket, secret).initial(ByteArray(0), 1000, 6).second
        )
        sweep("Resumption.Server.accept", corpus(valid, Resumption.PREFIX_LEN + 64),
            setOf(IllegalArgumentException::class, BufferUnderflowException::class)) {
            Resumption.Server(ticketKey).accept(it, 1000)
        }
    }

    /**
     * `PacketProtection.open` (returns null on any authentication failure, nothing declared) and
     * `PacketProtection.unprotectHeader` (declared IllegalArgumentException: long-header form bit, or
     * a packet too short to hold the header-protection sample).
     */
    @Test fun packetProtection() {
        val keys = PacketKeys(ByteArray(32) { it.toByte() }, 16)
        val keys8 = PacketKeys(ByteArray(32) { it.toByte() }, 8)
        val hdr = ByteBuffer.allocate(16).also { ShortHeader.write(it, PathId(2), 0x11223344, 100, 99) }
        val sealed = PacketProtection.seal(keys, 100, hdr, ByteArray(32) { it.toByte() })
        val valid = listOf(sealed, PacketProtection.aadOf(hdr) + sealed)
        val cases = corpus(valid, 96)
        sweep("PacketProtection.open", cases, emptySet()) {
            PacketProtection.open(keys, 100, hdr, it)
            PacketProtection.open(keys8, 100, hdr, it)
        }
        sweep("PacketProtection.unprotectHeader", cases, setOf(IllegalArgumentException::class)) {
            PacketProtection.unprotectHeader(keys, it.copyOf(), PacketProtection.SHORT_PN_OFFSET)
        }
    }
}
