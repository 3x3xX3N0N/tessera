package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import tessera.core.Wire
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W4 idle-then-burst (TEST-PLAN W4, measured 2026-08-26 with bench `idle`): what a connection that has been
 * QUIET for several seconds does to its first burst. The radio half of W4 — doze, carrier NAT expiry, RRC
 * promotion — needs a handset; this pins the local half, which measurement showed has exactly two contracts
 * worth pinning and one loudly falsified hypothesis.
 *
 * Falsified: "the first burst after idle is stalled behind slow start again". It is not. The receiver's credit
 * target moves only on congestion evidence (shrink) or on a drained/blocked sender (grow), and silence is
 * neither — the decaying arrival-rate EWMA only lowers the BDP *floor* under the target, which cannot cut it.
 * Across 11 measured arms the target was byte-identical before and after gaps of 1, 5 and 30 s, and the first
 * post-idle message cost 158-626 us against a 93-257 us paced steady-state p50 — the same spread a gap=0
 * back-to-back burst costs, i.e. the burst shape, not the idleness.
 *
 * What is pinned:
 *  - the grown credit target survives idle byte-for-byte, and the burst that follows neither stalls on credit
 *    nor loses a message;
 *  - idle beyond `idleTimeoutMs` TEARS THE CONNECTION DOWN, because the protocol has no keepalive frame. That
 *    is a real constraint on applications with quiet periods (the default timeout is 10 s), and it is the
 *    dominant local cost of this workload — not anything in the congestion control.
 */
@Tag("timing")
class IdleBurstTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 7).toByte() }
    private val size = 1200

    private class Burst(val delivered: Int, val firstUs: Long, val maxUs: Long,
                        val targetBefore: Long, val targetAtBurst: Long, val creditStalls: Long, val creditStallUs: Long)

    /**
     * Warms with `warm` back-to-back messages — paced traffic never drains 75 % of the credit target, so slow
     * start never fires and the target sits at its floor, which could not tell "survived the gap" from "had
     * nothing to lose" — then goes silent for `gapMs`, then sends `burst` messages back-to-back.
     */
    private fun run(cfg: ConnConfig, gapMs: Long, warm: Int = 3000, burst: Int = 50): Burst {
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "w4".toByteArray(), timeoutMs = 15_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)
            val total = warm + burst
            val sent = LongArray(total); val lat = LongArray(total) { -1L }
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val got = java.util.concurrent.atomic.AtomicInteger()
            val rx = Thread {
                while (!stop.get()) {
                    val m = sc.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < total && lat[i] < 0) { lat[i] = System.nanoTime() - sent[i]; got.incrementAndGet() }
                }
            }.apply { isDaemon = true; start() }
            fun put(i: Int) {
                val p = ByteArray(size); p[0] = (i shr 8).toByte(); p[1] = i.toByte()
                sent[i] = System.nanoTime(); conn.send(p)
            }
            repeat(warm) { put(it) }
            Thread.sleep(500)   // let the warm-up's own acks and grants land, or "before" is a mid-flight snapshot
            val targetBefore = sc.stats.creditTargetBytes
            val before = conn.stats
            Thread.sleep(gapMs)
            val targetAtBurst = sc.stats.creditTargetBytes
            repeat(burst) { put(warm + it) }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (got.get() < total && System.nanoTime() < deadline) Thread.sleep(5)
            stop.set(true); rx.join(2_000)
            val after = conn.stats
            val b = (warm until total).map { lat[it] }
            val r = Burst(b.count { it >= 0 }, if (lat[warm] >= 0) lat[warm] / 1000 else -1, (b.max()) / 1000,
                targetBefore, targetAtBurst, after.creditStalls - before.creditStalls, after.creditStallUs - before.creditStallUs)
            conn.close(); sc.close()
            return r
        } finally { client.close(); server.close(); cfg.netem?.close() }
    }

    @Test fun aGrownCreditTargetSurvivesIdleAndTheBurstAfterItDoesNotStall() {
        val r = run(ConnConfig(idleTimeoutMs = 60_000), gapMs = 5_000)
        // Teeth for the assertion below: without a target that actually grew past its floor, "unchanged across
        // the gap" would be satisfied by a connection that never had anything to lose.
        val floor = 10L * Wire.MAX_DATAGRAM
        assertTrue(r.targetBefore > 2 * floor, "warm-up did not grow the credit target past its floor: ${r.targetBefore} vs floor $floor")
        assertEquals(r.targetBefore, r.targetAtBurst, "the credit target moved across 5 s of idle")
        assertEquals(50, r.delivered, "post-idle burst lost messages")
        // Measured 0-5 stalls and 0-2 ms across every arm; 200 ms is the "slow start restarted" signal, not noise.
        assertTrue(r.creditStallUs < 200_000, "post-idle burst stalled on credit for ${r.creditStallUs / 1000} ms")
        // First-message bound at ~40x the measured 158-626 us: loose enough for a loaded suite, tight enough that
        // a grant round trip per burst — what a collapsed target would cost — cannot hide under it.
        assertTrue(r.firstUs in 0..25_000, "first message after idle took ${r.firstUs} us")
        println("W4 idle 5 s: first=${r.firstUs}us max=${r.maxUs}us target ${r.targetBefore} -> ${r.targetAtBurst} B stalls=${r.creditStalls}/${r.creditStallUs / 1000}ms")
    }

    @Test fun idleBeyondTheIdleTimeoutTearsTheConnectionDownBecauseThereIsNoKeepalive() {
        // Same gap, two timeouts. The protocol has no keepalive frame and the idle timeout keys on
        // max(lastRx, lastTx), so a quiet application is indistinguishable from a dead one: the only thing
        // standing between it and a teardown is how the timeout was configured.
        val survived = run(ConnConfig(idleTimeoutMs = 30_000), gapMs = 3_000, warm = 200, burst = 10)
        assertEquals(10, survived.delivered, "a 3 s gap under a 30 s idle timeout should be uneventful")

        val e = assertFailsWith<IllegalStateException> { run(ConnConfig(idleTimeoutMs = 1_000), gapMs = 3_000, warm = 200, burst = 10) }
        println("W4 idle timeout: 3 s gap under a 1 s idle timeout -> ${e.message}")
    }
}
