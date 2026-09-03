package tessera.transport

import tessera.core.CompactMsg
import tessera.core.Frame
import tessera.core.Handshake
import tessera.core.PacketProtection
import tessera.core.PathId
import tessera.core.PathResponse
import tessera.core.ShortHeader
import tessera.core.VarInt
import tessera.core.Wire
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F7 at the endpoint, where `core/FuzzTest` stops.
 *
 * `FuzzTest` holds each *parser* to a declared-exception contract on attacker bytes. That is necessary and not
 * sufficient: the parsers sit behind a demux, a rate limiter and a crypto layer, and the properties that matter to an
 * off-path attacker are properties of the whole endpoint, not of any one `read()`:
 *
 *  - **No amplification.** A malformed datagram must never make the server emit more than it received. The design
 *    bound is 3x until the path is validated (PathValidation); the *measured* bound reported here is what these
 *    sweeps actually saw, which for garbage is 0 or one ~31 B Retry against a ~1.2 KB initial.
 *  - **No crash escapes.** The rx loop catches per-datagram, so "it did not throw" proves nothing on its own — a
 *    dead rx thread looks exactly like a quiet one. Every sweep therefore ends by driving an *honest* connect and
 *    echo through the same endpoint: the endpoint has to still work, not merely still be running.
 *  - **A counted rejection is a pass.** `rxErrors`, `authFail`, `decodeErrors`, `oversizeDropped` exist precisely
 *    because these paths reject input by counting it. Nothing here asserts a zero count; what is asserted is that
 *    rejection is all that happened.
 *  - **Anti-replay holds.** A verbatim replay of a captured initial buys no second connection; a verbatim replay of
 *    an authenticated short packet is counted as a duplicate, not delivered twice.
 *
 * Reproducibility: every case derives from [SEEDS] and the case index. A failure prints the seed, the case index and
 * the datagram in hex; `-Dtessera.fuzz.seed=N` replaces the seed list with a single seed so a discovered case can be
 * replayed, and `-Dtessera.fuzz.endpoint.iterations=N` scales the sweep (the committed default is small enough to sit
 * in the ordinary suite; the high-count run is recorded in `docs/BENCH-netem.md`).
 */
class EndpointFuzzTest {

    private companion object {
        val SEEDS: LongArray =
            System.getProperty("tessera.fuzz.seed")?.toLongOrNull()?.let { longArrayOf(it) }
                ?: longArrayOf(1L, 0xF0FFEEL, 20260826L)
        val ITERATIONS: Int =
            (System.getProperty("tessera.fuzz.endpoint.iterations") ?: System.getenv("TESSERA_FUZZ_ENDPOINT_ITERATIONS"))
                ?.toIntOrNull() ?: 600
        val LOOP: InetAddress = InetAddress.getLoopbackAddress()
        fun hex(b: ByteArray, n: Int = b.size) = b.take(n).joinToString("") { "%02x".format(it) }
    }

    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 13 + 5).toByte() }

    /**
     * Re-measures one datagram's amplification with the socket quiescent: drain anything outstanding, send it alone,
     * then wait 50 ms of silence so a slow reply cannot be missed. It confirms a suspicion, so the cost is paid once
     * per suspicion rather than on all 1800 cases.
     */
    /**
     * Re-measures one case with the socket quiesced first. [quiesceMs] must be generous: the point is to drain
     * every straggler from earlier cases before sending, because a straggler counted here is exactly the
     * misattribution this re-check exists to rule out.
     *
     * Strengthened 2026-08-28. A full-suite run reproduced the historical false alarm *through* this check —
     * 42 B sent, 262 B back, 6.24x, byte-identical to the reading the comment below already documents as
     * fabricated — while the same run's aggregate was 0.0176 with a worst single case of 1.11, and no dedicated
     * run reproduced it. One re-check inside a loaded JVM is not isolation; a 50 ms drain can end with a reply
     * still in flight. The caller now requires two consecutive reproductions, and the drain is four times longer.
     */
    /**
     * Reproduces a suspected single-packet amplifier on a FRESH server. The sweep's server accumulates lingering
     * half-open connections (a mutation that only touches non-AEAD bytes of a real initial still decodes, draws a
     * ~700 B handshake reply, and then retransmits it on a PTO timer for hundreds of ms); a small later packet's
     * measurement window catches those retransmits and reads a false high ratio, and because the interferer REPEATS
     * two consecutive windows both catch one — which is why measuring twice on the sweep's socket was not enough
     * (case 298 of seed 1, 2026-09-02: 12.86x on the sweep, 1.67x — a 35 B Retry — replayed alone; BENCH/TODO).
     *
     * Single-packet amplification is a property of how one server answers one datagram, so it reproduces on a
     * server with no other connections; a misattributed straggler cannot follow the packet to a new server.
     * Pressure is forced on so a reply that only fires under pressure (the address-validation Retry) is exercised.
     */
    private fun amplificationOf(d: ByteArray): Double = server().use { srv ->
        srv.validator.forcePressure(true)
        DatagramSocket(0, LOOP).use { sock ->
            sock.soTimeout = 400
            sock.send(DatagramPacket(d, d.size, LOOP, srv.localAddress.port))
            var got = 0L; val rx = ByteArray(4096)
            while (true) { val p = DatagramPacket(rx, rx.size); try { sock.receive(p) } catch (e: SocketTimeoutException) { break }; got += p.length }
            got.toDouble() / d.size
        }
    }

    private fun server(cfg: ConnConfig = ConnConfig()) = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)

    // ------------------------------------------------------------------ mutation operators

    /**
     * The mutation set, applied to a *valid* encoding. Pure random bytes almost never get past a length check, so the
     * cases that reach a parser's interesting states are mutations of something that already parses.
     *
     * 0 bit flip, 1 byte splat, 2 truncation, 3 extension with garbage, 4 length-field corruption (a varint anywhere
     * in the body replaced by the largest encodable value), 5 duplication of a byte run (a duplicated frame, when the
     * run happens to be one), 6 reordering of two byte runs, 7 pure random of the same length.
     */
    private fun mutate(rnd: Random, valid: ByteArray): ByteArray {
        if (valid.isEmpty()) return valid
        return when (rnd.nextInt(8)) {
            0 -> valid.copyOf().also { val i = rnd.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            1 -> valid.copyOf().also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
            2 -> valid.copyOf(rnd.nextInt(valid.size + 1))
            3 -> valid + ByteArray(rnd.nextInt(1, 64)) { rnd.nextInt(256).toByte() }
            4 -> valid.copyOf().also {
                // 8-byte varint form, all ones: 2^62-1 wherever the parser expects a length
                val i = rnd.nextInt(maxOf(1, it.size - 8))
                for (k in 0 until minOf(8, it.size - i)) it[i + k] = -1
            }
            5 -> {                                            // duplicate a run: frames repeated verbatim
                val at = rnd.nextInt(valid.size); val len = rnd.nextInt(1, minOf(64, valid.size - at) + 1)
                valid.copyOf(at + len) + valid.copyOfRange(at, at + len) + valid.copyOfRange(at + len, valid.size)
            }
            6 -> {                                            // swap two runs: frames out of order
                val n = valid.size; val cut = 1 + rnd.nextInt(maxOf(1, n - 1))
                valid.copyOfRange(cut, n) + valid.copyOf(cut)
            }
            else -> ByteArray(valid.size) { rnd.nextInt(256).toByte() }
        }
    }

    // ------------------------------------------------------------------ un-authenticated path

    /** A real client initial, captured off the wire: a sink socket answers nothing, so the client just sends it. */
    private fun captureInitial(): ByteArray = DatagramSocket(0, LOOP).use { sink ->
        sink.soTimeout = 10_000
        val sinkAddr = InetSocketAddress(LOOP, sink.localPort)
        val t = Thread {
            try {
                TesseraClient(InetSocketAddress("127.0.0.1", 0)).use {
                    it.connect(sinkAddr, keys.x25519Pub, keys.kemPub, "payload".toByteArray(), timeoutMs = 300)
                }
            } catch (e: Exception) { /* nothing answers the sink: the timeout is the point */ }
        }
        t.isDaemon = true; t.start()
        val p = DatagramPacket(ByteArray(2048), 2048)
        sink.receive(p)
        t.join(2_000)
        p.data.copyOf(p.length)
    }

    /**
     * The headline property on the un-authenticated path: mutated and generated initials, straight at a live server's
     * socket, must never buy more bytes back than were sent, and must leave the server able to serve an honest client.
     */
    @Test fun malformedInitialsNeverAmplifyAndLeaveTheServerServing() {
        val initial = captureInitial()
        assertTrue(initial.size > 200, "captured initial looks wrong: ${initial.size} B")
        server().use { srv ->
            var sent = 0L; var back = 0L; var cases = 0; var worst = 0.0
            DatagramSocket(0, LOOP).use { sock ->
                sock.soTimeout = 2
                val rx = ByteArray(2048)
                for (seed in SEEDS) {
                    val rnd = Random(seed)
                    repeat(ITERATIONS) { i ->
                        // half mutations of a real initial, half generated long-header-shaped garbage
                        val d = if (rnd.nextBoolean()) mutate(rnd, initial)
                                else ByteArray(rnd.nextInt(5, 1400)) { rnd.nextInt(256).toByte() }
                                    .also { it[0] = (it[0].toInt() or Wire.F_INITIAL).toByte() }
                        if (d.isEmpty()) return@repeat
                        cases++
                        try { sock.send(DatagramPacket(d, d.size, LOOP, srv.localAddress.port)) } catch (e: Exception) { return@repeat }
                        sent += d.size
                        var thisBack = 0L
                        while (true) {
                            val p = DatagramPacket(rx, rx.size)
                            try { sock.receive(p) } catch (e: SocketTimeoutException) { break }
                            thisBack += p.length
                        }
                        back += thisBack
                        val ratio = thisBack.toDouble() / d.size
                        if (ratio > worst) worst = ratio
                        // A per-case ratio is only sound if every byte in the drain window answers THIS datagram,
                        // and with a 2 ms window under full-suite load it is not: the server's reply to an earlier
                        // case can miss its own window and be billed to a later, smaller one. That produced a 6.24x
                        // reading (42 B sent, 262 B "back") on a run whose aggregate ratio was 0.0173 and whose worst
                        // case in isolation was 2.07 - a fabricated security alarm from correct behaviour. A
                        // violation is therefore re-tried alone and quiescent, and only a reproduction fails. The
                        // aggregate assertion below needs none of this: misattribution cannot inflate it.
                        if (ratio > 3.0) {
                            // Confirm on a FRESH server: the >3.0 reading under sweep load is almost always a lingering
                            // connection's retransmitted handshake reply misattributed to this packet's window (see amplificationOf).
                            val fresh = amplificationOf(d)   // fresh server: no lingering retransmitter to misattribute
                            if (fresh > 3.0) fail(
                                "amplification " + "%.2f".format(fresh) + " x on a malformed initial on a FRESH server " +
                                "(first seen " + "%.2f".format(ratio) + " x under sweep load): sent ${d.size} B, " +
                                "seed=$seed case=$i input=${hex(d, 64)}...")
                        }
                    }
                }
                // Drain whatever arrived after the last send window before judging the totals.
                while (true) {
                    val p = DatagramPacket(rx, rx.size)
                    try { sock.receive(p) } catch (e: SocketTimeoutException) { break }
                    back += p.length
                }
            }
            println("[fuzz] malformed initials: cases=$cases sent=$sent B back=$back B " +
                "ratio=${"%.4f".format(back.toDouble() / sent)} worst-single=${"%.2f".format(worst)} " +
                "admitted=${srv.validator.admitted} retried=${srv.validator.retried} dropped=${srv.validator.dropped}")
            assertTrue(back <= sent, "aggregate amplification: sent $sent B, server emitted $back B")
            // The endpoint must still *work*, not merely still be running: a dead rx thread is silent, not loud.
            honestExchange(srv, "after the malformed-initial sweep")
        }
    }

    /** Generated short-header garbage at the demux miss ([TesseraServer.onUnmatchedShort]) — the stateless-reset hook. */
    @Test fun unmatchedShortPacketsNeverAmplifyAndAreRateLimited() {
        server().use { srv ->
            var sent = 0L; var back = 0L; var cases = 0
            DatagramSocket(0, LOOP).use { sock ->
                sock.soTimeout = 2
                val rx = ByteArray(2048)
                for (seed in SEEDS) {
                    val rnd = Random(seed)
                    repeat(ITERATIONS) {
                        val d = ByteArray(rnd.nextInt(5, 200)) { rnd.nextInt(256).toByte() }
                        d[0] = (d[0].toInt() and Wire.F_INITIAL.inv()).toByte()   // short header: forces the demux miss
                        cases++
                        sock.send(DatagramPacket(d, d.size, LOOP, srv.localAddress.port)); sent += d.size
                        var thisBack = 0L
                        while (true) {
                            val p = DatagramPacket(rx, rx.size)
                            try { sock.receive(p) } catch (e: SocketTimeoutException) { break }
                            thisBack += p.length
                        }
                        // Same attribution caveat as above: a reset provoked by an earlier datagram lands in this
                        // case's window, so two resets (80 B) can follow one 53 B send. The per-case bound that
                        // actually holds is TesseraServer.RESET_PACKET_LEN <= the datagram that provoked it, which
                        // `onUnmatchedShort` enforces by refusing to answer anything shorter; the aggregate is the
                        // measurement.
                        if (thisBack > 4L * d.size) fail(
                            "a reset for an unknown id amplified: sent ${d.size} B, got $thisBack B back (seed=$seed)")
                        back += thisBack
                    }
                }
            }
            println("[fuzz] unmatched short packets: cases=$cases sent=$sent B back=$back B resetsSent=${srv.resetsSent}")
            assertTrue(back <= sent, "aggregate amplification on the demux miss: sent $sent B, emitted $back B")
            honestExchange(srv, "after the unmatched-short sweep")
        }
    }

    /**
     * Anti-replay on the un-authenticated path: the *same* valid initial, replayed verbatim, buys one connection and
     * no more. (The replay window lives in `ZeroRtt.Server`; what is checked here is that the endpoint honours it —
     * a replayed initial for a ConnId the server already holds is answered with a re-send of the existing reply, not
     * with a fresh KEM and a second connection.)
     */
    @Test fun aReplayedInitialBuysNoSecondConnection() {
        val initial = captureInitial()
        server().use { srv ->
            DatagramSocket(0, LOOP).use { sock ->
                sock.soTimeout = 50
                val rx = ByteArray(2048)
                repeat(50) {
                    sock.send(DatagramPacket(initial, initial.size, LOOP, srv.localAddress.port))
                    while (true) { try { sock.receive(DatagramPacket(rx, rx.size)) } catch (e: SocketTimeoutException) { break } }
                }
            }
            Thread.sleep(200)
            val n = generateSequence { srv.accept(50) }.count()
            assertEquals(1, n, "50 verbatim replays of one initial produced $n connections")
            assertEquals(1L, srv.validator.admitted, "a replay must not buy a second KEM")
        }
    }

    // ------------------------------------------------------------------ post-authentication frames

    /**
     * `parseFrames` on fuzzed frame bodies that are genuinely *authenticated*: the client's own [PacketCrypto] seals
     * them, so the server opens them and runs the real frame loop, reassembly and FEC paths on attacker-chosen frame
     * bytes. This is the peer-holds-keys threat model — a bug here is still a bug, and the counters exist because
     * the transport already knows these paths can throw.
     *
     * Two things make this non-vacuous, and both had to be built rather than assumed:
     *
     *  - **The packets must actually authenticate.** They carry packet numbers far above the honest connection's and
     *    never reuse one, so none is discarded as a duplicate or outside the decode window before it reaches the
     *    parser. The sweep counts the packets the server really parsed and fails if that count is not most of them.
     *  - **A fuzzed `Close` is a legitimate teardown, not a defect.** `Frame.Close` is in the corpus (its reason-string
     *    length field is worth fuzzing) and mutations of other frames land on the Close type byte too, so the peer
     *    connection dies during the sweep by design. The sweep therefore rebuilds the pair whenever the server side
     *    stops receiving, and keeps going; what is asserted is that every rebuild succeeds and the totals add up.
     */
    @Test fun authenticatedGarbageFramesAreCountedNotFatal() {
        server().use { srv ->
            var cases = 0; var parsed = 0L; var pairs = 0
            var rxErrors = 0L; var decodeErrors = 0L; var oversize = 0L; var refused = 0L; var authFail = 0L
            var firstError: String? = null
            val valid = validFrameBodies()
            DatagramSocket(0, LOOP).use { sock ->
                for (seed in SEEDS) {
                    val rnd = Random(seed)
                    var i = 0
                    while (i < ITERATIONS) {
                        pairs++
                        TesseraClient(InetSocketAddress("127.0.0.1", 0)).use { c ->
                            val conn = c.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
                            val sc = assertNotNull(srv.accept(5_000), "server stopped accepting mid-sweep (pair $pairs)")
                            assertNotNull(sc.receive(2_000))
                            val crypto = peerCrypto(conn)
                            val base = sc.stats.packetsReceived
                            var pn = 8_000L
                            var lastSeen = base
                            var sinceCheck = 0
                            // Send until the peer connection stops receiving (a fuzzed Close tore it down) or the
                            // seed's budget runs out; then rebuild and continue where this pair left off.
                            while (i < ITERATIONS) {
                                val body = mutate(rnd, valid[rnd.nextInt(valid.size)])
                                i++
                                if (body.isEmpty() || body.size > 1100) continue
                                val d = try { seal(crypto, sc.localShortId, pn++, body) } catch (e: Exception) {
                                    fail("sealing a fuzz body threw ${e::class.qualifiedName}: seed=$seed case=$i " +
                                        "body=${hex(body, 48)}")
                                }
                                cases++
                                sock.send(DatagramPacket(d, d.size, LOOP, srv.localAddress.port))
                                // Checked in small batches: everything sent after a fuzzed Close and before the
                                // check is simply dropped at the demux, so a large batch quietly wastes cases.
                                if (++sinceCheck >= 8) {
                                    sinceCheck = 0
                                    Thread.sleep(3)                     // one rx thread: let it drain
                                    val now = sc.stats.packetsReceived
                                    if (now == lastSeen) { lastSeen = now; break }   // torn down: rebuild
                                    lastSeen = now
                                }
                            }
                            Thread.sleep(50)
                            val s = sc.stats
                            parsed += s.packetsReceived - base
                            rxErrors += s.rxErrors; decodeErrors += s.decodeErrors; authFail += s.authFail
                            oversize += s.oversizeDropped; refused += s.reassemblyRefused
                            if (firstError == null) firstError = s.firstRxError
                            try { conn.close() } catch (e: Exception) { /* already torn down by a fuzzed Close */ }
                            try { sc.close() } catch (e: Exception) { }
                        }
                    }
                }
            }
            println("[fuzz] authenticated garbage frames: cases=$cases parsed=$parsed pairs=$pairs " +
                "authFail=$authFail rxErrors=$rxErrors decodeErrors=$decodeErrors " +
                "oversize=$oversize refused=$refused first=$firstError")
            // Most of what was sent must have reached parseFrames. The shortfall is structural, not a defect: the
            // batch in flight when a fuzzed Close lands is dropped at the demux, and a loopback socket drops some of
            // a burst outright. Half is a floor on "this sweep actually exercised the frame parser", not a target.
            assertTrue(parsed > cases / 2,
                "only $parsed of $cases fuzz packets authenticated - the sweep proved little " +
                "(check the pn window, the short id and the teardown detection)")
            honestExchange(srv, "after the authenticated-frame sweep")
        }
    }

    /**
     * Anti-replay after authentication: a verbatim re-send of a sealed packet is counted a duplicate, never delivered
     * twice. The packet carries a `CompactMsg` rather than a Ping, because delivery is the property with teeth — the
     * connection is receiving honest ACKs from its own peer throughout, so a packet *count* would move for reasons
     * that have nothing to do with the replay, while a second delivery of the same message could only be the replay.
     */
    @Test fun aReplayedAuthenticatedPacketIsCountedNotRedelivered() {
        server().use { srv ->
            TesseraClient(InetSocketAddress("127.0.0.1", 0)).use { c ->
                val conn = c.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 10_000)
                val sc = assertNotNull(srv.accept(5_000)); assertNotNull(sc.receive(2_000))
                val marker = "replay-me".toByteArray()
                val body = enc { CompactMsg.write(it, 4_242L, 0L, 0L, true, ByteBuffer.wrap(marker), last = true) }
                val d = seal(peerCrypto(conn), sc.localShortId, 9_000L, body)
                val before = sc.stats
                DatagramSocket(0, LOOP).use { sock ->
                    repeat(32) { sock.send(DatagramPacket(d, d.size, LOOP, srv.localAddress.port)); Thread.sleep(1) }
                }
                Thread.sleep(300)
                assertEquals("replay-me", String(assertNotNull(sc.receive(2_000), "the message never arrived at all")))
                assertEquals(null, sc.receive(300), "a replayed packet was delivered twice")
                val s = sc.stats
                assertTrue(s.dups - before.dups >= 30, "replays were not counted as duplicates (dups=${s.dups - before.dups})")
                println("[fuzz] authenticated replay: dups=${s.dups - before.dups} of 31 re-sends")
                conn.close(); sc.close()
            }
        }
    }

    // ------------------------------------------------------------------ reassembly

    /**
     * [TesseraConnection.Reassembler] on arbitrary (msgId, offset, len, fin) tuples, including the contradictions an
     * honest sender cannot produce: a fragment past a fin-established length, a fin below what already arrived, a
     * re-sent fragment of an abandoned id. The caps are small so a broken guard fails by allocating a few MB rather
     * than by OOM-ing the JVM (same rationale as `ReassemblerTest`).
     *
     * Properties: never throws; `pending <= maxConcurrent`; `bytes <= maxBytes`; the flow-credit accounting is
     * monotone and each id is credited at most `maxMessageBytes` (over-crediting would advertise a limit above
     * `consumed + window` and let the peer overrun the receive buffer the window exists to bound).
     */
    @Test fun reassemblerHoldsItsCapsOnArbitraryFragments() {
        val maxMsg = 1 shl 16; val maxConc = 8; val maxBytes = 4L shl 16
        for (seed in SEEDS) {
            val rnd = Random(seed)
            val re = TesseraConnection.Reassembler(maxMsg, maxConc, maxBytes)
            var credited = 0L
            repeat(ITERATIONS * 20) { i ->
                val msgId = when (rnd.nextInt(4)) {
                    0 -> rnd.nextLong(0, 8)                     // a small hot set: contradictions collide
                    1 -> rnd.nextLong(0, Long.MAX_VALUE)
                    2 -> -rnd.nextLong(0, 1 shl 20)             // negative ids: the wire varint is unsigned, but be sure
                    else -> i.toLong()
                }
                val offset = when (rnd.nextInt(4)) {
                    0 -> rnd.nextInt(0, 256)
                    1 -> rnd.nextInt(0, maxMsg + 64)
                    2 -> Int.MAX_VALUE - rnd.nextInt(0, 64)
                    else -> -rnd.nextInt(0, 1 shl 20)           // parser-checked non-negative; check the guard anyway
                }
                val len = rnd.nextInt(0, 300)
                try {
                    re.onFragment(msgId, offset, ByteBuffer.wrap(ByteArray(len)), fin = rnd.nextBoolean())
                } catch (e: Throwable) {
                    fail("Reassembler.onFragment threw ${e::class.qualifiedName}: ${e.message}\n" +
                        "  seed=$seed case=$i msgId=$msgId offset=$offset len=$len")
                }
                assertTrue(re.pending <= maxConc, "pending ${re.pending} > $maxConc (seed=$seed case=$i)")
                assertTrue(re.bytes in 0..maxBytes, "buffered ${re.bytes} outside 0..$maxBytes (seed=$seed case=$i)")
                assertTrue(re.abandonedBytes >= credited, "abandoned credit went backwards (seed=$seed case=$i)")
                credited = re.abandonedBytes
            }
            println("[fuzz] reassembler seed=$seed: pending=${re.pending} bytes=${re.bytes} " +
                "oversize=${re.oversizeDropped} refused=${re.refused} abandonedBytes=${re.abandonedBytes}")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun enc(cap: Int = 1200, f: (ByteBuffer) -> Unit): ByteArray {
        val b = ByteBuffer.allocate(cap); f(b); b.flip(); return ByteArray(b.remaining()).also { b.get(it) }
    }

    /** Valid frame *bodies* (what `parseFrames` sees after the AEAD), the seeds the mutation operators work on. */
    private fun validFrameBodies(): List<ByteArray> {
        val p = ByteBuffer.wrap(ByteArray(64) { it.toByte() })
        return listOf(
            enc { Frame.Ping.write(it) },
            enc { Frame.Ack(PathId(0), 4, listOf(1L..3L), 0, 100).write(it) },
            enc { Frame.Grant(PathId(0), 1L shl 20, 3).write(it) },
            enc { Frame.MaxData(8L shl 20).write(it) },
            enc { Frame.PathChallenge(PathId(0), 0x1122334455667788L).write(it) },
            enc { PathResponse(PathId(0), 42L).write(it) },
            enc { Frame.Msg(1, 0, true, p.duplicate()).write(it) },
            enc { Frame.Repair(0, 8, 7, p.duplicate()).write(it) },
            enc { Frame.Close(0, "bye").write(it) },
            enc { Frame.Padding(32).write(it) },
            // the shapes the sender actually emits: FEC extension frame, then a CompactMsg
            enc { it.put(0x80.toByte()).put(2).putShort(0); CompactMsg.write(it, 1, 0, 0, true, p.duplicate(), true) },
            enc { it.put(0x80.toByte()).put(2).putShort(1); CompactMsg.write(it, 2, 1, 64, false, p.duplicate(), false)
                  Frame.Padding.writeTo(it, 16) },
            // a stream of several frames, so duplication/reordering mutations have joins to land on
            enc { Frame.Ping.write(it); Frame.Ack(PathId(0), 9, listOf(1L..9L), 0, 5).write(it)
                  Frame.Grant(PathId(0), 4096, 1).write(it); VarInt.write(it, 0) },
        )
    }

    /**
     * A [PacketCrypto] of our own on the client's session key, in the client's sending direction.
     *
     * Not the connection's own instance: that one is a single-threaded object (one AEAD engine, one nonce buffer, one
     * header-protection mask) which the connection's timer thread is concurrently sealing ACKs with, and BouncyCastle's
     * ChaCha20Poly1305 also refuses to re-encrypt under a nonce it has already used. Sharing it made the sweep fail on
     * its own harness rather than on the transport. A separate instance derives the same keys and interleaves safely.
     */
    private fun peerCrypto(conn: TesseraConnection): PacketCrypto =
        PacketCrypto(conn.sessionKey, isClient = true).also { it.tagLen = conn.tagLen }

    /**
     * Builds an authenticated short packet carrying [body] verbatim: header, body, padding to the header-protection
     * sample, AEAD, header protection — the same order `TesseraConnection.packet` uses. The pn is ours to choose,
     * which is what lets the sweep step outside the honest sender's sequence.
     */
    private fun seal(crypto: PacketCrypto, peerShortId: Int, pn: Long, body: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(2048)
        ShortHeader.write(buf, PathId(0), peerShortId, pn, 0L, crypto.txPhase)
        val hdrEnd = buf.position()
        buf.put(body)
        val need = PacketProtection.minPayloadLen(hdrEnd - PacketProtection.SHORT_PN_OFFSET, crypto.tagLen) -
            (buf.position() - hdrEnd)
        if (need > 0) Frame.Padding.writeTo(buf, maxOf(need, 2))
        val end = crypto.seal(buf, 0, hdrEnd, buf.position(), crypto.txKeys(), pn, crypto.tagLen, ByteArray(4096))
        crypto.protectHeader(buf, hdrEnd - PacketProtection.SHORT_PN_OFFSET)
        buf.limit(end).position(0)
        return ByteArray(end).also { buf.get(it) }
    }

    /** Drives a real connect + echo through [srv]. The proof that the endpoint survived, as opposed to went quiet. */
    private fun honestExchange(srv: TesseraServer, what: String) {
        TesseraClient(InetSocketAddress("127.0.0.1", 0)).use { c ->
            val conn = c.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "hello".toByteArray(), timeoutMs = 10_000)
            val sc = assertNotNull(srv.accept(5_000), "server stopped accepting $what")
            assertEquals("hello", String(assertNotNull(sc.receive(3_000), "server stopped delivering $what")))
            conn.send("ping".toByteArray())
            assertEquals("ping", String(assertNotNull(sc.receive(3_000), "server stopped receiving $what")))
            conn.close(); sc.close()
        }
    }
}
