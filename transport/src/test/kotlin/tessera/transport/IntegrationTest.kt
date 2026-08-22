package tessera.transport

import tessera.core.DictTrainer
import tessera.core.Handshake
import tessera.core.RingTracer
import tessera.core.TraceEvents
import tessera.core.VantagePoint
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Random
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Connection-level tests for the v0.3 integration: crypto/key update, acks + path validation, PMTUD, CC + codec, tracing, robustness. */
class IntegrationTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 3).toByte() }
    private fun server(cfg: ConnConfig = ConnConfig()) = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)

    private class Pair(val s: TesseraServer, val c: TesseraClient, val conn: TesseraConnection, val sc: TesseraConnection) : AutoCloseable {
        override fun close() { c.close(); s.close() }
    }

    private fun pair(serverCfg: ConnConfig = ConnConfig(), clientCfg: ConnConfig = ConnConfig()): Pair {
        val s = server(serverCfg); val c = TesseraClient(cfg = clientCfg)
        val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray())
        val sc = assertNotNull(s.accept(2_000)); assertContentEquals("hi".toByteArray(), sc.receive(1_000))
        return Pair(s, c, conn, sc)
    }

    private fun busySpin(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
    private fun awaitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) { if (cond()) return true; LockSupport.parkNanos(200_000) }
        return cond()
    }
    private fun str(b: ByteArray?) = String(assertNotNull(b))

    // ---------------------------------------------------------------- step 1: header protection, key update, tagLen

    @Test fun keyUpdateMidConnectionAndReorderedOldPhasePacketStillDecrypts() {
        pair().use { p ->
            p.conn.send("before".toByteArray()); assertEquals("before", str(p.sc.receive(1_000)))
            // hold a generation-0 packet, update keys, send a generation-1 packet, then release the old one:
            // the server sees the new phase first (follows), then the reordered old-phase packet (previous keys)
            p.conn.holdNextPacket = true
            p.conn.send("old-phase".toByteArray())
            assertTrue(p.conn.updateKeys())
            assertEquals(1, p.conn.keyGeneration)   // (a second update is refused only until the peer confirms — see the wrapper test)
            p.conn.send("new-phase".toByteArray())
            p.conn.releaseHeld()
            val got = setOf(str(p.sc.receive(1_000)), str(p.sc.receive(1_000)))
            assertEquals(setOf("old-phase", "new-phase"), got)
            assertTrue(awaitUntil(1_000) { p.sc.keyGeneration == 1 }, "server must follow to generation 1: ${p.sc.stats}")
            p.sc.send("reply".toByteArray()); assertEquals("reply", str(p.conn.receive(1_000)))
            assertEquals(1, p.conn.keyGeneration); assertEquals(1, p.sc.stats.keyUpdatesFollowed)
            assertEquals(0, p.sc.stats.authFail); assertEquals(0, p.conn.stats.authFail)
            // the server's new-phase packet confirmed our update: a second update is possible, from the other side too
            assertTrue(awaitUntil(1_000) { p.conn.updateKeys() })
            p.conn.send("gen2".toByteArray()); assertEquals("gen2", str(p.sc.receive(1_000)))
            p.sc.send("gen2-back".toByteArray()); assertEquals("gen2-back", str(p.conn.receive(1_000)))
            assertTrue(awaitUntil(1_000) { p.sc.updateKeys() }, "server-initiated update")
            p.sc.send("gen3".toByteArray()); assertEquals("gen3", str(p.conn.receive(1_000)))
            p.conn.send("gen3-back".toByteArray()); assertEquals("gen3-back", str(p.sc.receive(1_000)))
            assertEquals(3, p.conn.keyGeneration); assertEquals(3, p.sc.keyGeneration)
            assertEquals(0, p.sc.stats.authFail + p.conn.stats.authFail)
        }
    }

    @Test fun tagLen8IsNegotiatedAndHonouredEndToEnd() {
        pair(ConnConfig(tagLen = 8), ConnConfig(tagLen = 8)).use { p ->
            assertEquals(8, p.conn.stats.tagLen); assertEquals(8, p.sc.stats.tagLen); assertEquals(8, p.conn.tagLen)
            val msgs = List(60) { ByteArray(it * 25) { b -> (b + it).toByte() } }   // includes empty and tiny (padded) bodies
            msgs.forEach { p.conn.send(it) }
            val got = HashMap<Int, ByteArray>()
            repeat(msgs.size) { val m = assertNotNull(p.sc.receive(1_000)); got[m.size] = m }
            msgs.forEach { assertContentEquals(it, got[it.size]) }
            val big = ByteArray(5_000) { it.toByte() }
            p.sc.send(big); assertContentEquals(big, p.conn.receive(1_000))
            assertEquals(0, p.conn.stats.authFail + p.sc.stats.authFail)
            assertTrue(p.sc.stats.packetsReceived >= 60)
        }
        // 8 only when both sides offer it
        pair(ConnConfig(tagLen = 16), ConnConfig(tagLen = 8)).use { p ->
            assertEquals(16, p.conn.stats.tagLen); assertEquals(16, p.sc.stats.tagLen)
            p.conn.send("x".toByteArray()); assertEquals("x", str(p.sc.receive(1_000)))
        }
    }

    // ---------------------------------------------------------------- step 2: path validation + migration

    @Test fun clientRebindTriggersChallengeAndMigration() {
        pair().use { p ->
            assertTrue(awaitUntil(1_000) { p.sc.pathValidated }, "initial validation after the handshake: ${p.sc.stats}")
            assertTrue(p.sc.stats.challengesSent >= 1); assertTrue(p.conn.stats.responsesSent >= 1)
            val oldPeer = p.sc.peer
            TesseraClient().use { c2 ->
                c2.adopt(p.conn)   // the client's socket changes: the server sees a new source port
                for (i in 0 until 30) { p.conn.send(byteArrayOf(i.toByte())); busySpin(200) }
                val got = HashSet<Int>()
                while (got.size < 30) { val m = p.sc.receive(1_000) ?: break; got += m[0].toInt() }
                assertEquals(30, got.size, "client->server must not be interrupted by the rebind: ${p.sc.stats}")
                assertTrue(awaitUntil(2_000) { p.sc.pathValidated && p.sc.peer != oldPeer }, "migration + validation: ${p.sc.stats}")
                assertEquals(c2.localAddress.port, p.sc.peer.port)
                assertEquals(1, p.sc.stats.migrations); assertTrue(p.sc.stats.challengesSent >= 2)
                p.sc.send("after-migration".toByteArray()); assertEquals("after-migration", str(p.conn.receive(1_000)))
                p.conn.send("still-up".toByteArray()); assertEquals("still-up", str(p.sc.receive(1_000)))
            }
        }
    }

    @Test fun amplificationLimitHoldsAfterMigrationUntilValidated() {
        pair(ConnConfig(creditWaitMs = 400)).use { p ->
            assertTrue(awaitUntil(1_000) { p.sc.pathValidated })
            p.conn.txFilter = { kind, _, _ -> kind == TesseraConnection.KIND_PATH }   // the client stops answering challenges
            TesseraClient().use { c2 ->
                c2.adopt(p.conn)
                repeat(3) { p.conn.send(byteArrayOf(it.toByte())); busySpin(300) }
                repeat(3) { assertNotNull(p.sc.receive(1_000)) }
                assertTrue(awaitUntil(1_000) { p.sc.stats.migrations == 1L && !p.sc.pathValidated })
                val before = p.sc.stats
                // unvalidated new address: the server may send at most 3x what it received from it
                assertFailsWith<IllegalStateException> { repeat(8) { p.sc.send(ByteArray(1200)) } }
                val st = p.sc.stats
                assertTrue(st.ampStalls >= 1, "send must stall on the amplification limit: $st")
                assertTrue(st.bytesSent - before.bytesSent <= 3 * (st.bytesReceived - before.bytesReceived) + 3 * 1500, "3x budget: $st vs $before")
                // answer the challenges again. With its budget spent the server cannot even re-challenge until the client
                // sends again (RFC 9000 §8.1); a live client keeps sending, so: the re-challenge (backoff) validates the
                // path and server sends resume.
                p.conn.txFilter = null
                var k = 10
                assertTrue(awaitUntil(4_000) { p.conn.send(byteArrayOf(k++.toByte())); Thread.sleep(50); p.sc.pathValidated }, "re-challenge must validate: ${p.sc.stats}")
                p.sc.send("validated".toByteArray())
                assertTrue(awaitUntil(3_000) { p.conn.stats.messagesDelivered >= 2 }, "server data after validation: ${p.conn.stats}")
                assertEquals(0, p.sc.stats.authFail + p.conn.stats.authFail)
            }
        }
    }

    // ---------------------------------------------------------------- step 3: DPLPMTUD

    @Test fun pmtudRisesToNegotiatedMaxOnLoopbackAndNeverOversizes() {
        for (md in listOf(1350, 1500)) pair(ConnConfig(maxDatagram = md), ConnConfig(maxDatagram = md)).use { p ->
            assertEquals(1200, p.conn.plpmtu)
            repeat(300) { p.conn.send(ByteArray(100)); busySpin(200) }
            repeat(300) { assertNotNull(p.sc.receive(1_000)) }
            assertTrue(awaitUntil(2_000) { p.conn.plpmtu == md && p.sc.plpmtu == md }, "plpmtu client=${p.conn.plpmtu} server=${p.sc.plpmtu} ${p.conn.stats}")
            val st = p.conn.stats
            assertEquals(0, st.oversized, "non-probe datagrams above plpmtu: $st")
            assertTrue(st.probesSent in 2..6, "two probes confirm base and max on loopback: $st")
            assertEquals(0, st.probesLost)
            // a big message now travels in plpmtu-sized datagrams
            val big = ByteArray(20_000) { it.toByte() }
            p.conn.send(big); assertContentEquals(big, p.sc.receive(2_000))
            val st2 = p.conn.stats
            assertTrue(st2.maxDatagramSent in (md - 60)..md, "datagrams should fill the PLPMTU: max=${st2.maxDatagramSent} plpmtu=${st2.plpmtu}")
            assertEquals(0, st2.oversized)
        }
    }

    // ---------------------------------------------------------------- step 4: HybridCc + shared-dictionary codec

    @Test fun hybridCcGatesSendsAndReportsMode() {
        pair().use { p ->
            repeat(50) { p.conn.send(ByteArray(500)) }
            repeat(50) { assertNotNull(p.sc.receive(1_000)) }
            val st = p.conn.stats
            assertTrue(st.ccMode in setOf("UNLIMITED", "GRANT_LIMITED", "CWND_LIMITED"), st.ccMode)
            assertTrue(st.cwnd >= 2 * 1350, "cubic window reported: $st")
            assertEquals(0, st.ccLossEvents, "no loss on loopback -> no CUBIC reduction: $st")
        }
    }

    @Test fun sharedDictionaryCutsWireBytesAndMismatchFallsBackToIdentity() {
        val dict = DictTrainer.train(telemetry(600, seed = 7))
        val msgs = telemetry(500, seed = 99)
        fun run(serverDict: ByteArray?, clientDict: ByteArray?): kotlin.Pair<Long, Long> =
            pair(ConnConfig(dictionary = serverDict), ConnConfig(dictionary = clientDict)).use { p ->
                for (m in msgs) { p.conn.send(m); busySpin(100) }
                val got = HashSet<String>()
                repeat(msgs.size) { got += str(p.sc.receive(1_000)) }
                assertEquals(msgs.map { String(it) }.toSet(), got)
                assertTrue(awaitUntil(1_000) { p.conn.stats.sourcesSent.toInt() == msgs.size })
                val st = p.conn.stats
                assertEquals(0, st.codecErrors)
                (st.bytesSent - st.probeBytesSent) to st.dictId
            }
        val (withDict, idShared) = run(dict, dict)
        val (plain, idNone) = run(null, null)
        val (mismatch, idMismatch) = run(null, dict)
        assertNotEquals(0L, idShared); assertEquals(0L, idNone); assertEquals(0L, idMismatch, "server without the dict -> dictId 0 on the wire")
        assertTrue(withDict <= 0.7 * plain, "bytes on wire with dict=$withDict without=$plain (need >= 30% less)")
        assertTrue(mismatch in (plain * 9 / 10)..(plain * 11 / 10), "identity codec on mismatch: $mismatch vs $plain")
    }

    // ---------------------------------------------------------------- step 5: tracing

    @Test fun ringTracerCapturesHandshakeAndPacketEvents() {
        val tracer = RingTracer(4_000, vantagePoint = VantagePoint.CLIENT)
        pair(ConnConfig(), ConnConfig(tracer = tracer)).use { p ->
            repeat(5) { p.conn.send("x".toByteArray()); assertEquals("x", str(p.sc.receive(1_000))) }
            p.sc.send("y".toByteArray()); assertEquals("y", str(p.conn.receive(1_000)))
            val lines = tracer.snapshot()
            assertTrue(lines.any { it.contains("\"name\":\"${TraceEvents.HANDSHAKE}\"") && it.contains("\"kind\":\"pq\"") }, lines.take(5).toString())
            assertTrue(lines.count { it.contains("\"name\":\"${TraceEvents.PACKET_SENT}\"") } > 0)
            assertTrue(lines.count { it.contains("\"name\":\"${TraceEvents.PACKET_RECEIVED}\"") } > 0)
            assertTrue(lines.count { it.contains("\"name\":\"${TraceEvents.METRICS_UPDATED}\"") } > 0, "metrics on RTT samples")
            assertTrue(lines.all { it.startsWith("{\"time\":") && it.endsWith("}\n") }, "one JSON record per line")
        }
    }

    // ---------------------------------------------------------------- robustness (netem findings)

    /**
     * Item 1, v0.6 semantics: every standalone grant *packet* lost for 2 s must not stall the sender at all — the credit
     * limit is cumulative and rides on every ACK, so a lost grant is superseded by the next ACK. (v0.5: additive deltas;
     * the sender stalled until credit probes and grant re-sends brought the lost credit back, 1.5-7 s for 60 messages.)
     * A blackout of *every* Grant frame, ACK-borne ones included, is RecoveryTest's grant test.
     */
    @Test fun lostStandaloneGrantsNeverStallTheSender() {
        pair().use { p ->
            val until = System.nanoTime() + 2_000_000_000L
            p.sc.txFilter = { kind, _, _ -> kind == TesseraConnection.KIND_GRANT && System.nanoTime() < until }
            val n = 60
            val t0 = System.nanoTime()
            val sender = Thread { repeat(n) { i -> p.conn.send(ByteArray(1200).also { it[0] = i.toByte() }) } }.apply { start() }
            val got = HashSet<Int>()
            val deadline = System.nanoTime() + 8_000_000_000L
            while (got.size < n && System.nanoTime() < deadline) { val m = p.sc.receive(200) ?: continue; got += m[0].toInt() }
            sender.join(8_000)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val cs = p.conn.stats; val ss = p.sc.stats
            assertEquals(n, got.size, "all messages despite the lost grant packets: client=$cs server=$ss")
            assertTrue(elapsedMs < 1_000, "no stall on lost grant packets (the ACKs carry the limit): ${elapsedMs}ms client=$cs server=$ss")
            assertTrue(ss.simDropped >= 1, "the filter must have dropped standalone grants: $ss")
            assertTrue(ss.grantsPiggybacked >= 1, "ACKs carry the cumulative limit: $ss")
            assertEquals(0L, cs.creditStallUs / 100_000, "no credit stall worth mentioning: $cs")
        }
    }

    /** Item 2: the handshake survives lost replies and lost initials, fresh and resumed. */
    @Test fun connectSurvivesLostRepliesAndLostInitials() {
        server().use { s -> TesseraClient().use { c ->
            fun check(conn: TesseraConnection, minMs: Long, t0: Long) {
                val sc = assertNotNull(s.accept(3_000)); assertContentEquals("ff".toByteArray(), sc.receive(1_000))
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue(ms >= minMs, "two drops need two retransmit intervals (100+200 ms), took ${ms}ms")
                sc.send("ok".toByteArray()); assertEquals("ok", str(conn.receive(1_000)))
                assertEquals(0, s.dropReplies); assertEquals(0, c.dropInitials)
            }
            s.dropReplies = 2
            var t0 = System.nanoTime(); val c1 = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, "ff".toByteArray()); check(c1, 300, t0)
            assertTrue(s.connections.first().stats.replyResends >= 1)
            val ticket = assertNotNull(c1.ticket); val secret = c1.resumptionSecret
            c.dropInitials = 2
            t0 = System.nanoTime(); check(c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, "ff".toByteArray()), 300, t0)
            s.dropReplies = 2
            t0 = System.nanoTime(); check(c.resume(s.localAddress, ticket, secret, "ff".toByteArray()), 300, t0)
            c.dropInitials = 2
            t0 = System.nanoTime(); check(c.resume(s.localAddress, ticket, secret, "ff".toByteArray()), 300, t0)
        } }
    }

    /** 0.5-RTT data: the server app sends right after accept; if that overtakes the (here: lost and re-sent) handshake
     *  reply, the client must buffer it until the reply arrives rather than drop it. */
    @Test fun serverDataThatOvertakesTheHandshakeReplyIsBufferedAndDelivered() {
        server().use { s -> TesseraClient().use { c ->
            s.dropReplies = 1
            val st = Thread { val sc = s.accept(3_000)!!; sc.receive(1_000); sc.send("early".toByteArray()) }.apply { start() }
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray())
            st.join(3_000)
            assertEquals("early", str(conn.receive(1_000)), "client=${conn.stats}")
            val sc = s.connections.first()
            assertTrue(awaitUntil(1_000) { sc.pathValidated }, "buffered challenge answered after establishment: ${sc.stats}")
            assertEquals(0, conn.stats.authFail)
        } }
    }

    /** Item 4: first data packets and every probe lost for 300 ms -> PTO backoff keeps probing with data; connection stays alive. */
    @Test fun ptoBackoffRecoversLostFirstDataPackets() {
        pair().use { p ->
            assertTrue(awaitUntil(1_000) { p.sc.pathValidated })
            var sources = 0
            val start = System.nanoTime()
            p.sc.txFilter = { kind, _, _ ->
                when (kind) {
                    TesseraConnection.KIND_SOURCE -> sources++ < 4
                    TesseraConnection.KIND_REPAIR, TesseraConnection.KIND_RESEND -> System.nanoTime() - start < 300_000_000L
                    else -> false
                }
            }
            repeat(4) { p.sc.send(byteArrayOf(it.toByte())) }
            val got = HashSet<Int>()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (got.size < 4 && System.nanoTime() < deadline) { val m = p.conn.receive(200) ?: continue; got += m[0].toInt() }
            val st = p.sc.stats
            assertEquals(setOf(0, 1, 2, 3), got, "all four recovered: $st")
            assertTrue(st.repairsTlp + st.sourceResends >= 3, "several backed-off probes while they were dropped: $st")
            assertTrue(st.repairsTlp + st.sourceResends <= 40, "backoff must bound the probe count: $st")
            p.sc.send(byteArrayOf(9)); assertEquals(9, assertNotNull(p.conn.receive(1_000))[0].toInt())
            p.conn.send(byteArrayOf(8)); assertEquals(8, assertNotNull(p.sc.receive(1_000))[0].toInt())
            assertTrue(!p.sc.isClosed && !p.conn.isClosed)
        }
    }

    /** Item 5: at 50 msg/s with 5% loss a lost packet waits ~T (tail repair), not a repair interval: p99 < 10 ms, 100% delivered. */
    @Test fun lowRateLossTailRepairKeepsTailLatencyUnder10ms() {
        pair().use { p ->
            p.conn.lossSim = 0.05
            val n = 400; val gapNs = 20_000_000L
            val sent = LongArray(n); val lat = LongArray(n) { -1L }
            val rx = Thread {
                var got = 0; val deadline = System.nanoTime() + n * gapNs + 3_000_000_000L
                while (got < n && System.nanoTime() < deadline) {
                    val m = p.sc.receive(100) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < n && lat[i] < 0) { lat[i] = System.nanoTime() - sent[i]; got++ }
                }
            }.apply { start() }
            repeat(n) { i ->
                val m = ByteArray(64); m[0] = (i shr 8).toByte(); m[1] = i.toByte()
                sent[i] = System.nanoTime(); p.conn.send(m)
                LockSupport.parkNanos(gapNs)
            }
            rx.join()
            val delivered = lat.filter { it >= 0 }.sorted()
            val st = p.conn.stats
            assertEquals(n, delivered.size, "100% delivery: $st")
            val p99 = delivered[((n - 1) * 0.99).toInt()] / 1e6; val p50 = delivered[n / 2] / 1e6
            println(String.format(Locale.ROOT, "lowrate  n=%d 50 msg/s 5%% loss: p50=%.2fms p99=%.2fms max=%.2fms | %s", n, p50, p99, delivered.last() / 1e6, st))
            assertTrue(p99 < 10.0, "p99=${p99}ms: $st")
            assertTrue(st.repairsTail > n / 2, "nearly every message gets a trailing repair at low rate: $st")
        }
    }

    companion object {
        private val STATES = arrayOf("idle", "run", "jump", "shoot", "dead", "crouch")
        /** Small game-telemetry JSON messages (same shape as the core codec test / bench). */
        fun telemetry(n: Int, seed: Long): List<ByteArray> {
            val rnd = Random(seed)
            fun f(x: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", x)
            return List(n) { i ->
                buildString {
                    append("{\"t\":").append(1_000_000 + i * 50 + rnd.nextInt(50))
                    append(",\"id\":\"player-").append(rnd.nextInt(400)).append('"')
                    append(",\"pos\":[").append(f(rnd.nextDouble() * 2000, 1)).append(',').append(f(rnd.nextDouble() * 2000, 1)).append(',').append(f(rnd.nextDouble() * 120, 1)).append(']')
                    if (rnd.nextInt(4) != 0) append(",\"vel\":[").append(f(rnd.nextGaussian() * 6, 2)).append(',').append(f(rnd.nextGaussian() * 6, 2)).append(',').append(f(rnd.nextGaussian() * 2, 2)).append(']')
                    append(",\"hp\":").append(rnd.nextInt(101))
                    append(",\"state\":\"").append(STATES[rnd.nextInt(STATES.size)]).append('"')
                    if (rnd.nextInt(3) == 0) append(",\"zone\":\"sector-").append(rnd.nextInt(40)).append('"')
                    if (rnd.nextInt(3) == 0) append(",\"ammo\":{\"rifle\":").append(rnd.nextInt(120)).append(",\"pistol\":").append(rnd.nextInt(40)).append('}')
                    append(",\"seq\":").append(i).append('}')
                }.toByteArray()
            }
        }
    }
}
