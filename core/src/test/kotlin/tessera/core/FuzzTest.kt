package tessera.core

import java.net.InetSocketAddress
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
    private fun sweep(name: String, corpus: Sequence<Case>, declared: Set<KClass<out Throwable>>, body: (ByteArray) -> Unit) {
        var failure: String? = null
        var executed = 0
        val t0 = System.nanoTime()
        val t = Thread({
            for (c in corpus) {
                executed++
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
        // "the fuzzer passes" says nothing without the count: report what was actually executed.
        println("[fuzz] " + name + ": " + executed + " cases in " + ((System.nanoTime() - t0) / 1_000_000) + " ms")
    }

    // ---------------------------------------------------------------- corpus

    /**
     * Pure random + semi-structured mutations of [valid], plus adversarial length fields.
     *
     * Lazy, and restartable (`Sequence { ... }` around a generator, so the same corpus may be swept twice). It has to
     * be lazy: materialising it as a list made `-Dtessera.fuzz.iterations=1000000` die of OutOfMemoryError inside the
     * harness — a few million live ByteArrays — which would have made the large-run switch a promise the harness could
     * not keep. Generation is deterministic in the seed, so laziness costs nothing in reproducibility.
     */
    private fun corpus(valid: List<ByteArray>, maxLen: Int = MAX_LEN): Sequence<Case> = Sequence { cases(valid, maxLen).iterator() }

    private fun cases(valid: List<ByteArray>, maxLen: Int) = sequence {
        // 1. every truncation of every valid encoding (length checks at every boundary)
        for (v in valid) for (n in 0..v.size) yield(Case(0, v.copyOf(n)))
        // 2. every single-bit flip of every valid encoding
        for (v in valid) for (i in v.indices) for (b in 0..7)
            yield(Case(0, v.copyOf().also { it[i] = (it[i].toInt() xor (1 shl b)).toByte() }))
        // 3. adversarial length fields: valid prefix, then bytes that decode as huge lengths
        val huge = listOf(
            byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1),                 // 8-byte varint, 2^62-1
            byteArrayOf(-1, -1, -1, -1),                                  // 4-byte varint, 2^30-1
            byteArrayOf(0xBF.toByte(), -1, -1, -1),
            byteArrayOf(0xC0.toByte(), 0, 0, 0, -1, -1, -1, -1),          // low 32 bits all ones -> toInt() == -1
            byteArrayOf(0xC0.toByte(), 0, 0, 0, 0x7F, -1, -1, -1),
            byteArrayOf(0x7F, -1), byteArrayOf(-1), byteArrayOf(0x80.toByte(), 0, 0, 0)
        )
        for (v in valid) for (h in huge) for (cut in 0..minOf(v.size, 12)) yield(Case(0, v.copyOf(cut) + h))
        // 4. pure random and valid-prefix-then-random, seeded
        val per = maxOf(1, ITERATIONS / (SEEDS.size * 2))
        for (s in SEEDS) {
            val rnd = Random(s)
            repeat(per) { yield(Case(s, ByteArray(rnd.nextInt(0, maxLen)) { rnd.nextInt(256).toByte() })) }
            repeat(per) {
                val v = valid[rnd.nextInt(valid.size)]
                val keep = rnd.nextInt(0, v.size + 1)
                yield(Case(s, v.copyOf(keep) + ByteArray(rnd.nextInt(0, 32)) { rnd.nextInt(256).toByte() }))
            }
        }
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
            enc { Frame.Close(3, "err").write(it) },
            enc { Frame.MaxData((8L shl 20)).write(it) },
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
        // (the cap scales with ITERATIONS, so a large run really does sweep more of this one too)
        sweep("RlncDecoder.onRepair", corpus(valid, 128).take(maxOf(3_000, ITERATIONS / 4)), emptySet()) { b ->
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
        // KEM decapsulation dominates the cost (~0.5 ms each); keep this sweep small, scaled with ITERATIONS
        val cases = corpus(valid, ZeroRtt.PREFIX_LEN + 64).filter { it.bytes.size >= ZeroRtt.PREFIX_LEN - 8 }.take(maxOf(300, ITERATIONS / 2_000))
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

    /**
     * `RetryToken.verify` on attacker-chosen tokens: the un-authenticated path's only parser besides the header, and
     * the one an off-path attacker can call for free. Nothing is declared - a token is a fixed 16 bytes and the length
     * check is the whole parser, so any throw here is a bug. The property is that no crafted token verifies: only the
     * server's own mint does, and only inside its two-bucket window.
     */
    @Test fun retryTokenVerify() {
        val secret = RetryToken.deriveSecret(ByteArray(32) { it.toByte() })
        val addr = InetSocketAddress("192.0.2.7", 4433)
        val other = InetSocketAddress("192.0.2.8", 4433)
        val now = 1_700_000_000_000L
        val valid = listOf(RetryToken.mint(secret, addr, now), RetryToken.mint(secret, addr, now - RetryToken.BUCKET_MS))
        var forged = 0
        sweep("RetryToken.verify", corpus(valid, 64), emptySet()) { b ->
            // a forged token must not verify for this address, and a genuine one must not verify for another
            if (RetryToken.verify(secret, addr, b, now) && valid.none { it.contentEquals(b) }) forged++
            RetryToken.verify(secret, other, b, now)
            RetryToken.verify(secret, addr, b, now + 10 * RetryToken.BUCKET_MS)   // long expired
        }
        check(forged == 0) { "a crafted token verified " + forged + " times" }
        // and the genuine ones still do, so the sweep above was not vacuous
        check(RetryToken.verify(secret, addr, valid[0], now))
        check(!RetryToken.verify(secret, other, valid[0], now)) { "a token minted for one address verified for another" }
    }

    /**
     * `StatelessReset.matches` on a crafted candidate: a reset packet's trailing bytes are wholly attacker-chosen, so
     * the constant-time compare runs on them. Nothing is declared, including for a candidate of the wrong length.
     */
    @Test fun statelessResetMatches() {
        val secret = StatelessReset.deriveSecret(ByteArray(32) { (it * 5).toByte() })
        val expected = StatelessReset.token(secret, 0x11223344)
        var forged = 0
        sweep("StatelessReset.matches", corpus(listOf(expected), 64), emptySet()) { b ->
            if (StatelessReset.matches(expected, b) && !b.contentEquals(expected)) forged++
        }
        check(forged == 0) { "a crafted reset trailer matched " + forged + " times" }
        check(StatelessReset.matches(expected, expected.copyOf()))
    }

    /**
     * The transport's FEC [RlncDecoder.SymbolValidator] (`TesseraConnection.fecValidator`, reproduced here) on solved
     * symbols the decoder hands it. It indexes the first six bytes, and the symbol it is given comes from the decoder,
     * not the wire - but the *contents* are attacker-influenced through the repair payload, so run it over arbitrary
     * ones. Nothing is declared.
     */
    @Test fun fecSymbolValidator() {
        for (symbolSize in intArrayOf(8, 64, 1200)) {
            val validator = RlncDecoder.SymbolValidator { seq, sym ->
                val len = ((sym[0].toInt() and 0xFF) shl 8) or (sym[1].toInt() and 0xFF)
                len in 5..(symbolSize - 2) && (sym[2].toInt() and 0xFF) == 0x80 && sym[3].toInt() == 2 &&
                    (((sym[4].toInt() and 0xFF) shl 8) or (sym[5].toInt() and 0xFF)) == (seq and 0xFFFF).toInt()
            }
            val rnd = Random(0x5A11DL + symbolSize)
            val cases = generateSequence { Case(0x5A11DL, ByteArray(symbolSize) { rnd.nextInt(256).toByte() }) }
                .take(maxOf(1, ITERATIONS / 4))
            sweep("fecValidator(symbolSize=" + symbolSize + ")", cases, emptySet()) { sym ->
                for (seq in longArrayOf(0, 1, 0xFFFF, Long.MAX_VALUE)) validator.isValid(seq, sym)
            }
        }
    }

    /**
     * Frame *streams* under duplication and reordering, not just corruption: the transport reads frames in a loop over
     * one datagram body, so a repeated or transposed frame is an input the loop must survive as much as a truncated
     * one. Same declared set as [frameCodecRead]; the extra property is that the loop always makes progress.
     */
    @Test fun frameStreamsUnderDuplicationAndReordering() {
        val parts = listOf(
            enc { Frame.Ping.write(it) },
            enc { Frame.Ack(PathId(0), 9, listOf(1L..4L, 6L..6L), 2, 33).write(it) },
            enc { Frame.Grant(PathId(0), 1L shl 20, 7).write(it) },
            enc { Frame.Msg(7, 3, true, payload()).write(it) },
            enc { Frame.Repair(5, 32, 0x1234, payload()).write(it) },
            enc { Frame.MaxData(8L shl 20).write(it) },
            enc { Frame.Padding(8).write(it) },
        )
        // Restartable and lazy for the same reason [corpus] is: a large -Dtessera.fuzz.iterations must not OOM.
        val cases = Sequence { streamCases(parts).iterator() }
        sweep("FrameCodec.read(stream)", cases,
            setOf(IllegalArgumentException::class, BufferUnderflowException::class)) { b ->
            val buf = ByteBuffer.wrap(b)
            var guard = 0
            while (buf.hasRemaining() && guard++ < 4096) {
                val before = buf.position()
                FrameCodec.read(buf) ?: break
                check(buf.position() > before) { "FrameCodec.read consumed nothing: the transport loop would spin" }
            }
        }
    }

    /** The frame-stream corpus of [frameStreamsUnderDuplicationAndReordering], generated lazily. */
    private fun streamCases(parts: List<ByteArray>) = sequence {
        for (s in SEEDS) {
            val rnd = Random(s)
            repeat(maxOf(1, ITERATIONS / SEEDS.size)) {
                // build a stream, then duplicate and transpose frames within it
                val stream = ArrayList<ByteArray>()
                repeat(rnd.nextInt(1, 12)) { stream += parts[rnd.nextInt(parts.size)] }
                repeat(rnd.nextInt(0, 4)) { if (stream.isNotEmpty()) stream += stream[rnd.nextInt(stream.size)] }
                repeat(rnd.nextInt(0, 4)) {
                    if (stream.size > 1) {
                        val i = rnd.nextInt(stream.size); val j = rnd.nextInt(stream.size)
                        val t = stream[i]; stream[i] = stream[j]; stream[j] = t
                    }
                }
                var bytes = stream.fold(ByteArray(0)) { a, b -> a + b }
                if (rnd.nextInt(4) == 0) bytes = bytes.copyOf(rnd.nextInt(bytes.size + 1))       // truncate the stream
                if (rnd.nextInt(4) == 0 && bytes.isNotEmpty())                                    // corrupt one byte
                    bytes = bytes.copyOf().also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
                yield(Case(s, bytes))
            }
        }
    }
}
