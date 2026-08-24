package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.CubicCc
import tessera.core.Handshake
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F8 — coexistence with a loss-reactive transport on one bottleneck. Tessera's credit CC is deliberately not
 * loss-reactive (random loss is FEC's job), which is correct on a lossy radio link and potentially antisocial on a
 * shared queue: this measures it instead of guessing. One [NetemSim] is the bottleneck for the DATA direction of
 * both flows (one departure cursor, one tail-drop limit); a second, delay-only sim carries both flows' acks back —
 * the standard fairness topology (congested forward path, clean return), and the only shape in which the shallow
 * regime is even expressible here (acks contending for the same tail-drop queue would make every loss come with
 * queueing delay). The competitor is a real [CubicCc]-driven UDP flow (window + pacing from core's CUBIC/HyStart++,
 * receiver-side gap detection, RTO), not a constant-rate blaster.
 *
 * Two regimes, per TEST-PLAN F8b:
 * - deep buffer (limit 1000 pkts ≈ 12 BDP): drops arrive only after the queue — and the queueing delay — has grown
 *   far past HybridCc's gate, so Tessera's CUBIC fallback should engage and share.
 * - shallow buffer (limit 56 pkts: ~40 sit in the 20 ms propagation stage, so ~16 of real backlog ≈ 8 ms < the
 *   ~10 ms gate): drops arrive *without* sustained queueing delay — precisely the signal Tessera is built to
 *   ignore (`ccLossIgnored`), so this is the regime where it may crowd the neighbour out.
 *
 * There is deliberately no fairness pass/fail threshold (TEST-PLAN: "no threshold until someone sets a policy");
 * the hard assertions are liveness — the neighbour keeps making progress, and recovers once Tessera leaves. The
 * shares and the mechanism counters are printed and recorded in TEST-PLAN F8.
 *
 * What the first runs measured (2026-08-24) inverted the question: the neighbour was never in danger — TESSERA
 * collapses on a saturated tail-drop bottleneck, with or without a competitor (the solo control arm delivers
 * ~0 MB/s at 56 % self-inflicted drops). See TEST-PLAN F8 for the numbers and the mechanism; these tests record
 * that state, they do not certify it as acceptable.
 */
@Tag("timing")
class CoexistenceTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 5).toByte() }

    companion object {
        const val RATE_BPS = 20_000_000L          // 2.5 MB/s bottleneck
        const val DELAY_US = 20_000L              // 40 ms RTT; BDP = 100 KB = 80 pkts of 1250 B
        const val PKT = 1222                      // + 28 B sim header = 1250 B on the link, ~a Tessera datagram
    }

    /** A loss-reactive competitor: CUBIC window + pacing over plain UDP through the shared sims. */
    private class CubicFlow(private val dataSim: NetemSim, private val ackSim: NetemSim) : AutoCloseable {
        private val lo: InetAddress = InetAddress.getLoopbackAddress()
        private val rx = DatagramSocket(0, lo).apply { soTimeout = 20 }          // data lands here
        private val ackRx = DatagramSocket(0, lo).apply { soTimeout = 1 }        // acks land here
        private val txData = DatagramSocket()                                    // sim sink -> rx
        private val txAck = DatagramSocket()                                     // sim sink -> ackRx
        private val rxAddr = rx.localSocketAddress as InetSocketAddress
        private val ackAddr = ackRx.localSocketAddress as InetSocketAddress
        val receivedBytes = AtomicLong()
        @Volatile private var running = true
        @Volatile var sendErrors: Exception? = null
        val cc = CubicCc(mss = PKT, hystart = true)
        var rtoEvents = 0; private set

        // receiver state, mirrored back in every ack: [receivedCount(8) highestSeq(8) lossEvents(8) echoTsUs(8)]
        private val receiver = Thread {
            var receivedCount = 0L; var highestSeq = -1L; var lossEvents = 0L
            val buf = DatagramPacket(ByteArray(2048), 2048)
            while (running) {
                try { rx.receive(buf) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                val b = ByteBuffer.wrap(buf.data, 0, buf.length)
                val seq = b.getLong(); val ts = b.getLong()
                receivedCount++; receivedBytes.addAndGet(buf.length.toLong())
                if (seq > highestSeq + 1) lossEvents++                            // reorder=0 on the sim: a gap is loss
                if (seq > highestSeq) highestSeq = seq
                val ack = ByteBuffer.allocate(32).putLong(receivedCount).putLong(highestSeq).putLong(lossEvents).putLong(ts).flip()
                ackSim.submit(ack, ackAddr) { bb, a -> txAck.send(DatagramPacket(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining(), a)) }
            }
        }.apply { isDaemon = true }

        private val sender = Thread {
            var sentSeq = 0L; var ackedCount = 0L; var seenLossEvents = 0L; var highestAcked = -1L
            var inFlight = 0L; var lastAckNs = System.nanoTime(); var nextSendNs = System.nanoTime()
            val ackBuf = DatagramPacket(ByteArray(64), 64)
            val payload = ByteArray(PKT)
            try {
                while (running) {
                    // drain acks
                    while (true) {
                        try { ackRx.receive(ackBuf) } catch (_: SocketTimeoutException) { break } catch (_: Exception) { return@Thread }
                        val b = ByteBuffer.wrap(ackBuf.data, 0, ackBuf.length)
                        val count = b.getLong(); val highest = b.getLong(); val losses = b.getLong(); val echoTs = b.getLong()
                        val now = System.nanoTime(); lastAckNs = now
                        val rttUs = max(1L, now / 1000 - echoTs)
                        if (count > ackedCount) { cc.onAcked(((count - ackedCount) * PKT).toInt(), rttUs, now / 1000); ackedCount = count }
                        repeat((losses - seenLossEvents).toInt()) { cc.onLoss(PKT, now / 1000) }   // CubicCc folds same-RTT losses
                        seenLossEvents = losses
                        if (highest > highestAcked) highestAcked = highest
                        inFlight = (sentSeq - 1 - highestAcked).coerceAtLeast(0) * PKT
                    }
                    // RTO: everything outstanding is stale — one congestion event, window restart
                    if (inFlight > 0 && System.nanoTime() - lastAckNs > 250_000_000L) {
                        cc.onLoss(PKT, System.nanoTime() / 1000); inFlight = 0; lastAckNs = System.nanoTime(); rtoEvents++
                    }
                    // paced, window-limited sends
                    while (running && cc.canSend(inFlight, PKT) && System.nanoTime() >= nextSendNs) {
                        val b = ByteBuffer.allocate(PKT).putLong(sentSeq).putLong(System.nanoTime() / 1000).put(payload, 0, PKT - 16).flip()
                        dataSim.submit(b, rxAddr) { bb, a -> txData.send(DatagramPacket(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining(), a)) }
                        cc.onSent(PKT, System.nanoTime() / 1000)
                        sentSeq++; inFlight += PKT
                        nextSendNs += (PKT * 1e9 / cc.pacingRateBytesPerSec).toLong()
                        if (nextSendNs < System.nanoTime() - 10_000_000L) nextSendNs = System.nanoTime()  // don't bank idle time into a burst
                    }
                    LockSupport.parkNanos(200_000)
                }
            } catch (e: Exception) { sendErrors = e }
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

    private fun runRegime(label: String, queueLimit: Int, withCubic: Boolean = true) {
        // Forward (data) bottleneck shared by both flows; delay-only return path shared by both flows' acks.
        val bottleneck = NetemSim("f8-$label-data", delayUs = DELAY_US, rateBps = RATE_BPS, limit = queueLimit, seed = 42)
        val ackPath = NetemSim("f8-$label-ack", delayUs = DELAY_US, seed = 43)
        val serverCfg = ConnConfig(netem = ackPath, pmtud = false, idleTimeoutMs = 30_000)     // server sends acks/grants
        val clientCfg = ConnConfig(netem = bottleneck, pmtud = false, idleTimeoutMs = 30_000)  // client sends the data
        val cubic = if (withCubic) CubicFlow(bottleneck, ackPath) else null
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, serverCfg)
        val client = TesseraClient(cfg = clientCfg)
        val tessError = AtomicReference<Exception>()
        var tessSender: Thread? = null
        var drain: Thread? = null
        try {
            cubic?.start()
            var cubicSolo = 0.0
            if (cubic != null) {
                Thread.sleep(1_000)                                               // past slow start
                cubicSolo = bytesOver(cubic.receivedBytes::get, 2_000)
            }

            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 15_000)
            val sc = assertNotNull(server.accept(5_000))
            drain = Thread { try { while (true) sc.receive(100) } catch (_: InterruptedException) { } }.apply { isDaemon = true; start() }
            val msg = ByteArray(1200)
            val sendFailures = AtomicLong()
            tessSender = Thread {
                // An app that retries: under collapse send() throws "send blocked for 5000ms" — count it and go on,
                // so the run measures the transport's steady state rather than dying at the first stall.
                while (!Thread.interrupted() && !conn.isClosed) {
                    try { conn.send(msg) }
                    catch (e: InterruptedException) { break }
                    catch (e: Exception) {
                        if (conn.isClosed) break
                        sendFailures.incrementAndGet(); tessError.compareAndSet(null, e)
                        try { Thread.sleep(50) } catch (e: InterruptedException) { break }
                    }
                }
            }.apply { isDaemon = true; start() }

            Thread.sleep(2_000)                                                   // transition out of the join transient
            val t0 = sc.stats.payloadBytesOut
            val cubicConc = if (cubic != null) bytesOver(cubic.receivedBytes::get, 6_000) else { Thread.sleep(6_000); 0.0 }
            val tessConc = (sc.stats.payloadBytesOut - t0) * 1000.0 / 6_000
            val s = conn.stats
            val est = conn.estimator

            tessSender.interrupt(); conn.close()
            var cubicPost = 0.0
            if (cubic != null) {
                Thread.sleep(1_000)                                               // let the queue drain
                cubicPost = bytesOver(cubic.receivedBytes::get, 2_000)
            }

            val mb = 1e6
            val cubicLine = if (cubic != null)
                "cubic solo=${"%.2f".format(cubicSolo / mb)} conc=${"%.2f".format(cubicConc / mb)} post=${"%.2f".format(cubicPost / mb)} MB/s (share=${"%.0f".format(100 * cubicConc / (cubicConc + tessConc).coerceAtLeast(1.0))}%, lossReductions=${cubic.cc.lossReductions}, rto=${cubic.rtoEvents}) | " else "solo | "
            println("F8[$label] limit=$queueLimit link=${RATE_BPS / 8 / mb}MB/s | " + cubicLine +
                "tessera conc=${"%.2f".format(tessConc / mb)} MB/s ccLoss=${s.ccLossEvents}/${s.ccLossEvents + s.ccLossIgnored} mode=${s.ccMode} fec=${"%.3f".format(s.fecRedundancy)} resends=${s.sourceResends} " +
                "creditTarget=${s.creditTargetBytes} stalls(credit=${s.creditStalls} cwnd=${s.cwndStalls}) sendFailures=${sendFailures.get()}${tessError.get()?.let { " first=$it" } ?: ""} " +
                "srtt=${"%.1f".format(est.srttUs / 1000)}ms minRtt=${"%.1f".format(est.minRttUs / 1000)}ms | sim: $bottleneck")

            assertNull(cubic?.sendErrors, "[$label] competitor harness failed: ${cubic?.sendErrors}")
            if (cubic != null) {
                // Liveness of the NEIGHBOUR only — TEST-PLAN F8 sets no fairness threshold, and Tessera's own
                // outcome is recorded, not asserted: the first runs measured it collapsing on any saturated
                // tail-drop bottleneck, competitor or not (the recorded defect lives in TEST-PLAN F8), and a
                // measurement test must not fail for reporting the truth it was built to expose.
                assertTrue(cubicSolo > 1.0 * mb, "[$label] competitor never got going alone (${cubicSolo / mb} MB/s of 2.5): harness, not fairness")
                assertTrue(cubicConc > 0.05 * mb, "[$label] competitor starved to ~zero while sharing with tessera (${cubicConc / mb} MB/s)")
                assertTrue(cubicPost > cubicSolo * 0.5, "[$label] competitor did not recover after tessera left (${cubicPost / mb} vs solo ${cubicSolo / mb} MB/s)")
            }
        } finally {
            tessSender?.interrupt(); drain?.interrupt()
            client.close(); server.close(); cubic?.close()
            bottleneck.close(); ackPath.close()
        }
    }

    @Test fun deepBufferSharesWithACubicNeighbour() = runRegime("deep", queueLimit = 1_000)

    @Test fun shallowBufferRegimeIsMeasuredAgainstACubicNeighbour() = runRegime("shallow", queueLimit = 56)

    /** Control arm: no competitor. Separates "starved by the neighbour" from "cannot saturate a tail-drop bottleneck at all". */
    @Test fun tesseraAloneOnTheSameBottleneck() = runRegime("solo", queueLimit = 1_000, withCubic = false)
}
