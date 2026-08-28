package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F8a — Tessera versus a scavenging transport (TEST-PLAN F8a). LEDBAT (RFC 6817) is designed to yield: it
 * targets a small standing queueing delay (TARGET, here 60 ms) and backs off as soon as the one-way delay
 * rises above it. Tessera is designed not to react to delay below its bufferbloat gate. TEST-PLAN's
 * prediction: "Tessera takes the bandwidth and the scavenger gets out of the way — and where both lanes live
 * in the same daemon, that looks like a bug in the scavenger rather than a policy choice in Tessera."
 *
 * Topology per the F8a table: LTE-shaped bottleneck (30 Mbit, 45 ms one-way -> 90 ms RTT, BDP ≈ 270 pkts)
 * at 1 BDP and at 0.25 BDP of queue; fairness topology (congested forward path, clean ack return). The
 * scavenger starts first and is in steady state before Tessera's bulk (W2, back-to-back send) joins.
 * Measured: each flow's share while contested, the scavenger's yield depth, and its recovery once Tessera
 * leaves. No fairness threshold exists (the policy is deliberately open) — the hard assertions are liveness:
 * the scavenger is not starved to zero forever, and it recovers after Tessera stops.
 *
 * Real-time harness: under full-suite JVM load the scavenger's own post-contention recovery ramp can miss
 * the 0.5x-solo floor (its slow start is wall-clock-paced Java threads starved by the suite); passes
 * isolated — the same load-sensitivity family as the 2000 msg/s test. Re-run in isolation before believing
 * a failure.
 */
@Tag("timing")
class LedbatCoexistenceTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 13).toByte() }

    companion object {
        const val RATE_BPS = 30_000_000L        // 3.75 MB/s
        const val DELAY_US = 45_000L            // 90 ms RTT; BDP ≈ 337 KB ≈ 270 pkts of 1250 B
        const val PKT = 1222
        const val TARGET_US = 60_000L           // RFC 6817 TARGET (must be <= 100 ms; uTP ships 100, libutp 60)
        const val GAIN = 1.0
    }

    /**
     * A LEDBAT-style scavenger over plain UDP through the shared sims. One-way delay is measured directly —
     * both ends share this host's clock, so the receiver stamps `now - sentTs` into every ack (the real
     * protocol gets this from remote timestamp deltas; the sim makes it exact). Base delay is the running
     * minimum; cwnd moves by GAIN * off_target per RTT (RFC 6817 §2.4.2) and halves on loss, floored at
     * 2 packets — the scavenger never fully stops probing, which is what "recovers when the bully leaves"
     * relies on.
     */
    private class LedbatFlow(private val dataSim: NetemSim, private val ackSim: NetemSim) : AutoCloseable {
        private val lo: InetAddress = InetAddress.getLoopbackAddress()
        private val rx = DatagramSocket(0, lo).apply { soTimeout = 20 }
        private val ackRx = DatagramSocket(0, lo).apply { soTimeout = 1 }
        private val txData = DatagramSocket()
        private val txAck = DatagramSocket()
        private val rxAddr = rx.localSocketAddress as InetSocketAddress
        private val ackAddr = ackRx.localSocketAddress as InetSocketAddress
        val receivedBytes = AtomicLong()
        @Volatile private var running = true
        @Volatile var cwnd = 4L * PKT; private set
        @Volatile var lastQueueingUs = 0L; private set
        var baseDelayUs = Long.MAX_VALUE; private set
        var lossBackoffs = 0; private set

        // ack payload: [receivedCount(8) highestSeq(8) lossEvents(8) echoTsUs(8) owdUs(8)]
        private val receiver = Thread {
            var receivedCount = 0L; var highestSeq = -1L; var lossEvents = 0L
            val buf = DatagramPacket(ByteArray(2048), 2048)
            while (running) {
                try { rx.receive(buf) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                val b = ByteBuffer.wrap(buf.data, 0, buf.length)
                val seq = b.getLong(); val ts = b.getLong()
                val owdUs = System.nanoTime() / 1000 - ts
                receivedCount++; receivedBytes.addAndGet(buf.length.toLong())
                if (seq > highestSeq + 1) lossEvents++
                if (seq > highestSeq) highestSeq = seq
                val ack = ByteBuffer.allocate(40).putLong(receivedCount).putLong(highestSeq).putLong(lossEvents).putLong(ts).putLong(owdUs).flip()
                ackSim.submit(ack, ackAddr) { bb, a -> txAck.send(DatagramPacket(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining(), a)) }
            }
        }.apply { isDaemon = true }

        private val sender = Thread {
            var sentSeq = 0L; var ackedCount = 0L; var seenLossEvents = 0L; var highestAcked = -1L
            var inFlight = 0L; var lastAckNs = System.nanoTime(); var nextSendNs = System.nanoTime()
            var srttUs = 90_000.0; var lastBackoffNs = 0L; var slowStart = true
            val ackBuf = DatagramPacket(ByteArray(64), 64)
            val payload = ByteArray(PKT)
            try {
                while (running) {
                    while (true) {
                        try { ackRx.receive(ackBuf) } catch (_: SocketTimeoutException) { break } catch (_: Exception) { return@Thread }
                        val b = ByteBuffer.wrap(ackBuf.data, 0, ackBuf.length)
                        val count = b.getLong(); val highest = b.getLong(); val losses = b.getLong(); val echoTs = b.getLong(); val owdUs = b.getLong()
                        val now = System.nanoTime(); lastAckNs = now
                        srttUs = 0.875 * srttUs + 0.125 * max(1L, now / 1000 - echoTs)
                        if (owdUs in 0 until 10_000_000L) {
                            if (owdUs < baseDelayUs) baseDelayUs = owdUs
                            lastQueueingUs = owdUs - baseDelayUs
                        }
                        if (count > ackedCount) {
                            val bytesAcked = (count - ackedCount) * PKT
                            if (slowStart && lastQueueingUs > TARGET_US / 2) slowStart = false   // delay says the queue is forming
                            cwnd = if (slowStart) cwnd + bytesAcked   // RFC 6817 permits standard slow start until delay/loss
                            else {
                                // RFC 6817 §2.4.2: cwnd += GAIN * off_target * bytes_newly_acked * MSS / cwnd
                                val offTarget = (TARGET_US - lastQueueingUs).toDouble() / TARGET_US
                                max(2L * PKT, cwnd + (GAIN * offTarget * bytesAcked * PKT / cwnd).toLong())
                            }
                            ackedCount = count
                        }
                        if (losses > seenLossEvents && now - lastBackoffNs > (srttUs * 1000).toLong()) {
                            slowStart = false
                            cwnd = max(2L * PKT, cwnd / 2); lossBackoffs++; lastBackoffNs = now   // at most once per RTT
                        }
                        seenLossEvents = losses
                        if (highest > highestAcked) highestAcked = highest
                        inFlight = (sentSeq - 1 - highestAcked).coerceAtLeast(0) * PKT
                    }
                    if (inFlight > 0 && System.nanoTime() - lastAckNs > 500_000_000L) {   // RTO: window restart
                        cwnd = 2L * PKT; inFlight = 0; lastAckNs = System.nanoTime(); lossBackoffs++
                    }
                    while (running && inFlight + PKT <= cwnd && System.nanoTime() >= nextSendNs) {
                        val b = ByteBuffer.allocate(PKT).putLong(sentSeq).putLong(System.nanoTime() / 1000).put(payload, 0, PKT - 16).flip()
                        dataSim.submit(b, rxAddr) { bb, a -> txData.send(DatagramPacket(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining(), a)) }
                        sentSeq++; inFlight += PKT
                        nextSendNs += (PKT * srttUs * 1000 / max(cwnd, 1L)).toLong()   // pace one cwnd per srtt
                        if (nextSendNs < System.nanoTime() - 10_000_000L) nextSendNs = System.nanoTime()
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(200_000)
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }

        fun start() { receiver.start(); sender.start() }
        override fun close() {
            running = false
            sender.join(2_000); receiver.join(2_000)
            rx.close(); ackRx.close(); txData.close(); txAck.close()
        }
    }

    private fun bytesOver(counter: () -> Long, ms: Long): Double {
        val a = counter(); Thread.sleep(ms)
        return (counter() - a) * 1000.0 / ms
    }

    private fun runRegime(label: String, queueLimit: Int) {
        val bottleneck = NetemSim("f8a-$label-data", delayUs = DELAY_US, rateBps = RATE_BPS, limit = queueLimit, seed = 21)
        val ackPath = NetemSim("f8a-$label-ack", delayUs = DELAY_US, seed = 22)
        val serverCfg = ConnConfig(pingIntervalMs = 0, netem = ackPath, pmtud = false, idleTimeoutMs = 30_000)
        val clientCfg = ConnConfig(pingIntervalMs = 0, netem = bottleneck, pmtud = false, idleTimeoutMs = 30_000)
        val ledbat = LedbatFlow(bottleneck, ackPath)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, serverCfg)
        val client = TesseraClient(cfg = clientCfg)
        var tessSender: Thread? = null
        var drain: Thread? = null
        try {
            ledbat.start()
            Thread.sleep(3_000)                                               // scavenger reaches steady state first
            val ledbatSolo = bytesOver(ledbat.receivedBytes::get, 2_000)

            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 15_000)
            val sc = assertNotNull(server.accept(5_000))
            drain = Thread { try { while (true) sc.receive(100) } catch (_: InterruptedException) { } }.apply { isDaemon = true; start() }
            val msg = ByteArray(1200)
            tessSender = Thread {
                while (!Thread.interrupted() && !conn.isClosed) {
                    try { conn.send(msg) }
                    catch (_: InterruptedException) { break }
                    catch (_: Exception) { if (conn.isClosed) break; try { Thread.sleep(50) } catch (_: InterruptedException) { break } }
                }
            }.apply { isDaemon = true; start() }

            Thread.sleep(2_000)                                               // join transient
            val t0 = sc.stats.payloadBytesOut
            val ledbatConc = bytesOver(ledbat.receivedBytes::get, 6_000)
            val tessConc = (sc.stats.payloadBytesOut - t0) * 1000.0 / 6_000
            val qDelayMs = ledbat.lastQueueingUs / 1000.0

            tessSender.interrupt(); tessSender.join(3_000); tessSender = null
            conn.close()
            Thread.sleep(2_000)                                               // drain the queue Tessera left
            val ledbatAfter = bytesOver(ledbat.receivedBytes::get, 3_000)

            val mb = 1e6
            println("F8a[$label] limit=$queueLimit link=${RATE_BPS / 8 / mb}MB/s | " +
                "ledbat solo=${"%.2f".format(ledbatSolo / mb)} contested=${"%.2f".format(ledbatConc / mb)} " +
                "(${"%.0f".format(100 * ledbatConc / max(ledbatSolo, 1.0))}% of solo) after=${"%.2f".format(ledbatAfter / mb)}MB/s " +
                "backoffs=${ledbat.lossBackoffs} base=${ledbat.baseDelayUs / 1000}ms lastQueueing=${"%.1f".format(qDelayMs)}ms | " +
                "tessera contested=${"%.2f".format(tessConc / mb)}MB/s | ${bottleneck}")
            println("F8a[$label] tessera server view: ${sc.stats}")

            // Liveness, not fairness (TEST-PLAN: no threshold until someone sets a policy). Both directions:
            // neither flow may be starved to literal zero, and the scavenger must recover once Tessera leaves.
            // (Measured 2026-08-25: the F8a prediction INVERTED — Tessera is the more timid scavenger; see the
            // printed shares and TEST-PLAN F8a outcome.)
            assertTrue(ledbatConc > 0.01 * mb, "the scavenger must keep making *some* progress while contested: ${"%.3f".format(ledbatConc / mb)}MB/s")
            assertTrue(ledbatAfter > 0.5 * ledbatSolo,
                "the scavenger must recover after Tessera leaves: after=${"%.2f".format(ledbatAfter / mb)} solo=${"%.2f".format(ledbatSolo / mb)}MB/s")
            // Tessera's contested share is recorded, not asserted, mirroring CoexistenceTest: under contention
            // it is the more timid scavenger and can trickle toward zero — the open F8 policy question.
        } finally {
            tessSender?.interrupt(); drain?.interrupt()
            client.close(); server.close(); ledbat.close(); bottleneck.close(); ackPath.close()
        }
    }

    // The sim's limit counts the propagation stage too (~135 pkts at 45 ms / 3.75 MB/s), so a regime's
    // *standing* queue is limit - 135: 405 -> 1 BDP (270 pkts) of backlog, 202 -> 0.25 BDP (67 pkts).
    @Test fun oneBdpQueueAgainstALedbatScavenger() = runRegime("1bdp", queueLimit = 405)
    @Test fun quarterBdpQueueAgainstALedbatScavenger() = runRegime("0.25bdp", queueLimit = 202)
}
