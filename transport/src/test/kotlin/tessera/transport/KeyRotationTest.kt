package tessera.transport

import tessera.core.Handshake
import java.net.InetSocketAddress
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Automatic AEAD key rotation ([ConnConfig.keyUpdatePackets] / [ConnConfig.keyUpdateBytes], v0.9). The key-update
 * machinery itself is covered by IntegrationTest and PacketCryptoWrapperTest; what is tested here is the *policy*
 * that fires it — that it fires at all under a small configured threshold, that the peer follows, that the
 * application never notices, that the off switch really is off, and that a peer which never confirms gets exactly
 * one rotation rather than a storm.
 */
class KeyRotationTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 5).toByte() }

    private class Pair(val s: TesseraServer, val c: TesseraClient, val conn: TesseraConnection, val sc: TesseraConnection) : AutoCloseable {
        override fun close() { c.close(); s.close() }
    }

    private fun pair(clientCfg: ConnConfig, serverCfg: ConnConfig = ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 30_000)): Pair {
        val s = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, serverCfg)
        val c = TesseraClient(cfg = clientCfg)
        val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray())
        val sc = assertNotNull(s.accept(2_000)); assertNotNull(sc.receive(1_000))
        return Pair(s, c, conn, sc)
    }

    private fun awaitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) { if (cond()) return true; LockSupport.parkNanos(200_000) }
        return cond()
    }

    /** Sends [n] numbered messages and asserts every one arrives, in order and byte-identical. */
    private fun streamThrough(p: Pair, n: Int) {
        val payload = ByteArray(400) { it.toByte() }
        for (i in 0 until n) p.conn.send("$i:".toByteArray() + payload)
        for (i in 0 until n) {
            val got = assertNotNull(p.sc.receive(2_000), "message $i of $n was lost (${p.sc.stats})")
            assertEquals("$i:", String(got, 0, "$i:".length))
            assertEquals(400 + "$i:".length, got.size)
        }
    }

    @Test fun packetThresholdRotatesRepeatedlyThePeerFollowsAndNothingIsLost() {
        pair(ConnConfig(pingIntervalMs = 0, keyUpdatePackets = 32, keyUpdateBytes = 0, idleTimeoutMs = 30_000)).use { p ->
            streamThrough(p, 500)
            val gen = p.conn.keyGeneration
            assertTrue(gen >= 3, "500 packets at a 32-packet threshold must rotate several times, got generation $gen")
            assertEquals(gen.toLong(), p.conn.stats.keyUpdates)
            // the server followed every one of them: its tx side rotates on the peer's phase (openShort -> tx.onPeerPhase)
            assertTrue(awaitUntil(2_000) { p.sc.keyGeneration == gen }, "server must follow to generation $gen: ${p.sc.stats}")
            assertEquals(0, p.conn.stats.authFail + p.sc.stats.authFail, "a rotation must never cost an authentication")
            // and the connection still works, in both directions, on the generation it ended up on
            p.sc.send("back".toByteArray()); assertEquals("back", String(assertNotNull(p.conn.receive(2_000))))
            p.conn.send("forth".toByteArray()); assertEquals("forth", String(assertNotNull(p.sc.receive(2_000))))
        }
    }

    @Test fun byteThresholdAlsoRotates() {
        pair(ConnConfig(pingIntervalMs = 0, keyUpdatePackets = 0, keyUpdateBytes = 32_000, idleTimeoutMs = 30_000)).use { p ->
            streamThrough(p, 300)   // ~450 B/packet, so ~70 packets per generation
            assertTrue(p.conn.keyGeneration >= 2, "byte trigger must rotate: generation ${p.conn.keyGeneration}")
            assertEquals(0, p.conn.stats.authFail + p.sc.stats.authFail)
        }
    }

    /** Teeth: a new trigger has nothing to fail against, so prove the negative — off means the generation never moves. */
    @Test fun disabledMeansTheGenerationNeverAdvances() {
        pair(ConnConfig(pingIntervalMs = 0, keyUpdatePackets = 0, keyUpdateBytes = 0, idleTimeoutMs = 30_000)).use { p ->
            streamThrough(p, 500)
            assertEquals(0, p.conn.keyGeneration, "rotation is off; nothing may rotate")
            assertEquals(0L, p.conn.stats.keyUpdates)
            assertEquals(0, p.sc.keyGeneration)
            assertEquals(0L, p.sc.stats.keyUpdatesFollowed)
        }
    }

    /**
     * A peer that never confirms: everything the server sends is dropped on its own send side from the moment the
     * client's first automatic rotation lands, so the client's update stays pending forever. KeyPhaseState refuses a
     * second initiation while pending, and the counters freeze with it — exactly one rotation, no storm.
     */
    @Test fun aPeerThatNeverFollowsGetsExactlyOneRotation() {
        pair(ConnConfig(pingIntervalMs = 0, keyUpdatePackets = 8, keyUpdateBytes = 0, idleTimeoutMs = 30_000)).use { p ->
            p.sc.txFilter = { _, _, _ -> true }   // the server goes silent: no acks, no confirmation of the new phase
            try { repeat(400) { p.conn.send("x".repeat(200).toByteArray()) } } catch (e: Exception) {
                // expected once credit/cwnd runs out against a peer that acknowledges nothing
            }
            assertEquals(1, p.conn.keyGeneration, "a pending update must block every further rotation")
            assertEquals(1L, p.conn.stats.keyUpdates)
        }
    }
}
