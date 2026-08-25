package tessera.transport

import org.junit.jupiter.api.Tag
import tessera.core.Handshake
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F8 AQM/ECN regime (TEST-PLAN F8b "shallow buffer or AQM"): congestion signalled *without* queueing delay
 * or loss — precisely the signal the delay gate is built to ignore, and before this landed the transport was
 * blind to it end to end (the rx path hardcoded ecnCe=false and a rising CE count in ACKs never reached
 * HybridCc). The sim's [NetemSim.ecnThreshold] step-marks at a shallow depth; the mark rides the in-process
 * side channel (NetemSim.EcnCe), is consumed on rx, shrinks the receiver's credit target, echoes to the
 * sender in the ACK CE count, and engages the CUBIC fallback.
 *
 * A/B on an identical saturated bottleneck: the marking arm must see CE flow end to end and lean on the
 * signal instead of the queue — fewer forced drops than the drop-only arm. Both arms must deliver
 * everything (this is bulk; loss is not an option).
 */
@Tag("timing")
class AqmEcnTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 3).toByte() }

    private class Arm(val delivered: Int, val count: Int, val elapsedNs: Long,
                      val client: ConnStats, val server: ConnStats, val queueDrops: Long, val ceMarked: Long)

    private fun run(ecnThreshold: Int): Arm {
        // The F8 topology, split: data over a 20 Mbit / 20 ms / shallow(100) bottleneck, acks on a clean return.
        val data = NetemSim("aqm-data", delayUs = 20_000, rateBps = 20_000_000, limit = 100, seed = 9,
            ecnThreshold = ecnThreshold)
        val ackPath = NetemSim("aqm-ack", delayUs = 20_000, seed = 10)
        val server = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, ConnConfig(netem = ackPath, pmtud = false))
        val client = TesseraClient(cfg = ConnConfig(netem = data, pmtud = false))
        try {
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "hi".toByteArray(), timeoutMs = 15_000)
            val sc = assertNotNull(server.accept(5_000)); sc.receive(2_000)
            val count = 9090   // ~10 MB of 1100 B messages, ~4 s at the 2.5 MB/s ceiling
            var got = 0; var lastNs = 0L
            val t0 = System.nanoTime()
            val rx = Thread {
                while (got < count) {
                    if (sc.receive(2_000) == null && got > 0 && System.nanoTime() - lastNs > 15_000_000_000L) break
                    else { got++; lastNs = System.nanoTime() }
                }
            }.apply { isDaemon = true; start() }
            val msg = ByteArray(1100)
            val sender = Thread { try { repeat(count) { conn.send(msg) } } catch (_: Exception) { } }.apply { isDaemon = true; start() }
            sender.join(90_000); rx.join(90_000)
            val arm = Arm(got, count, lastNs - t0, conn.stats, sc.stats, data.queueDrops, data.ceMarked)
            conn.close(); sc.close()
            return arm
        } finally { client.close(); server.close(); data.close(); ackPath.close() }
    }

    @Test fun aqmMarksReplaceDropsAndTheSenderYieldsToThem() {
        val marked = run(ecnThreshold = 20)
        println("AQM[mark] ${marked.delivered}/${marked.count} in ${marked.elapsedNs / 1_000_000}ms " +
            "ceMarked=${marked.ceMarked} ceSeen=${marked.server.ecnCeReceived} queueDrops=${marked.queueDrops} | ${marked.client}")
        val dropOnly = run(ecnThreshold = 0)
        println("AQM[drop] ${dropOnly.delivered}/${dropOnly.count} in ${dropOnly.elapsedNs / 1_000_000}ms " +
            "queueDrops=${dropOnly.queueDrops} | ${dropOnly.client}")

        assertEquals(marked.count, marked.delivered, "bulk over a marking AQM loses nothing: ${marked.client}")
        assertEquals(dropOnly.count, dropOnly.delivered, "bulk over a drop-only queue loses nothing: ${dropOnly.client}")
        assertTrue(marked.ceMarked > 0, "the AQM never marked — the arm did not saturate: ${marked.ceMarked}")
        assertTrue(marked.server.ecnCeReceived > 0, "marks were made but never consumed on rx — the side channel is broken: ${marked.server}")
        assertTrue(marked.client.ackCeSeen > 0, "the receiver saw CE but the sender's ACKs never carried it: ${marked.client}")
        assertTrue(marked.queueDrops < dropOnly.queueDrops,
            "with a working CE reaction the marking arm must force fewer tail drops (${marked.queueDrops} vs ${dropOnly.queueDrops})")
    }
}
