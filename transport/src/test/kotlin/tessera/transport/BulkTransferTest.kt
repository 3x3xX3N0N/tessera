package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W2 bulk transfer (TEST-PLAN W2 — the workload that had no data behind it until 2026-08-25): send()
 * back-to-back with no pacing gap, so credit slow-start, cwnd and the flow window are the only clock.
 * Two arms:
 *  - loopback: the ceiling. Measured 16-19 MB/s cold (bench `bulk`); asserted at a deep margin because
 *    timing tests share the machine with the rest of the suite.
 *  - a saturated 20 Mbit tail-drop bottleneck (the F8 topology): bulk must fill the pipe without
 *    collapsing — the v0.9 governor's contract extended from paced to self-clocked load.
 * Every arm asserts complete delivery: bulk moves data the app already committed, loss is not an option.
 */
@Tag("timing")
class BulkTransferTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 5).toByte() }

    private class Arm(val delivered: Int, val count: Int, val bytes: Long, val elapsedNs: Long, val stats: ConnStats,
                      val serverStats: ConnStats? = null) {
        val goodputMBs get() = bytes * 1e9 / elapsedNs / 1e6
    }

    private fun run(cfg: ConnConfig, totalBytes: Long, size: Int, serverCfg: ConnConfig = cfg): Arm {
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, serverCfg)
        val client = TesseraClient(cfg = cfg)
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 15_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)
            val count = (totalBytes / size).toInt()
            var got = 0; var gotBytes = 0L; var lastNs = 0L
            val t0 = System.nanoTime()
            val rx = Thread {
                while (got < count) {
                    val m = sc.receive(2_000) ?: if (got > 0 && System.nanoTime() - lastNs > 15_000_000_000L) break else continue
                    got++; gotBytes += m.size; lastNs = System.nanoTime()
                }
            }.apply { isDaemon = true; start() }
            val msg = ByteArray(size)
            val sender = Thread { try { repeat(count) { conn.send(msg) } } catch (_: Exception) { } }.apply { isDaemon = true; start() }
            sender.join(120_000); rx.join(120_000)
            val arm = Arm(got, count, gotBytes, lastNs - t0, conn.stats, sc.stats)
            conn.close(); sc.close()
            return arm
        } finally { client.close(); server.close(); cfg.netem?.close(); if (serverCfg !== cfg) serverCfg.netem?.close() }
    }

    @Test fun loopbackBulkDeliversEverythingAtRate() {
        val a = run(ConnConfig(), totalBytes = 20_000_000, size = 1100)
        println("BULK[loopback] ${a.delivered}/${a.count} ${"%.2f".format(a.goodputMBs)}MB/s | ${a.stats}")
        assertEquals(a.count, a.delivered, "bulk loses nothing on loopback: ${a.stats}")
        assertTrue(a.goodputMBs > 4.0, "loopback bulk must beat 4 MB/s (measured 16+ cold): ${"%.2f".format(a.goodputMBs)} ${a.stats}")
    }

    @Test fun highBdpLossyBulkNeverOutrunsTheReliabilityHorizon() {
        // The arm that wedged pre-fix (BENCH "W2 bulk local"): transcont-shaped, split topology. 1 Gbit x 180 ms
        // RTT = a BDP (~16k pkts) far past BODY_RING(4096), so without the horizon wait the sender evicts
        // retained symbols of undelivered sources and the connection deadlocks (5.9k/18.2k delivered, then a
        // silent-peer throw). With the wait, throughput is capped (<= BODY_RING undelivered in flight) but
        // delivery must be COMPLETE and the eviction/assumed-delivered tells must stay zero.
        val data = NetemSim("bulk-transcont-data", delayUs = 90_000, jitterUs = 2_000, lossP = 0.001,
            rateBps = 1_000_000_000L, seed = 5)
        val ackPath = NetemSim("bulk-transcont-ack", delayUs = 90_000, jitterUs = 2_000, lossP = 0.001, seed = 6)
        val a = run(ConnConfig(netem = data, pmtud = false), totalBytes = 20_000_000, size = 1100,
            serverCfg = ConnConfig(netem = ackPath, pmtud = false))
        println("BULK[transcont] ${a.delivered}/${a.count} ${"%.2f".format(a.goodputMBs)}MB/s hznStalls=${a.stats.horizonStalls}/${a.stats.horizonStallUs / 1000}ms | ${a.stats} | $data")
        println("BULK[transcont] server: evicted-tell skipDelivered=${a.serverStats?.skipDelivered} assumed=${a.serverStats?.horizonAssumedDelivered}")
        assertEquals(a.count, a.delivered, "high-BDP lossy bulk must deliver everything (pre-fix: wedged at ~1/3): ${a.stats}")
        assertEquals(0L, a.stats.resendEvicted, "the horizon makes eviction structurally impossible: ${a.stats}")
        // skipDelivered stays recorded, not asserted: it also counts benign residual-ARQ duplication (a verbatim
        // re-send landing after RLNC already recovered the source). The horizon-break misread is isolated by
        // horizonAssumedDelivered, and THAT must be zero.
        assertEquals(0L, a.serverStats?.horizonAssumedDelivered ?: -1L, "the DELIVERED_BITS assumption must never be exercised: ${a.serverStats}")
    }

    @Test fun saturatedBottleneckBulkFillsThePipeWithoutCollapsing() {
        // The F8 topology, split like CoexistenceTest (the physical full-duplex shape): the client's data rides
        // a 20 Mbit tail-drop bottleneck (ceiling 2.5 MB/s), the server's acks/grants ride a clean delay-only
        // return path. A first, single-sim version shared one queue for both directions: grants queued behind
        // the data flood, 11.5 % of everything (grants included) dropped, and goodput collapsed to 0.11 MB/s —
        // a half-duplex-radio-like regime worth remembering, but not this arm's contract.
        val sim = NetemSim("bulk-bottleneck", delayUs = 20_000, rateBps = 20_000_000, limit = 1_000, seed = 3)
        val ackPath = NetemSim("bulk-ack", delayUs = 20_000, seed = 4)
        val a = run(ConnConfig(netem = sim, pmtud = false), totalBytes = 20_000_000, size = 1100,
            serverCfg = ConnConfig(netem = ackPath, pmtud = false))
        println("BULK[20Mbit] ${a.delivered}/${a.count} ${"%.2f".format(a.goodputMBs)}MB/s | ${a.stats} | $sim")
        assertEquals(a.count, a.delivered, "bulk loses nothing on a saturated bottleneck: ${a.stats}")
        assertTrue(a.goodputMBs > 1.0, "self-clocked bulk must hold >1 MB/s of the 2.5 MB/s link (paced solo: 2.01): ${"%.2f".format(a.goodputMBs)} ${a.stats}")
    }
}
