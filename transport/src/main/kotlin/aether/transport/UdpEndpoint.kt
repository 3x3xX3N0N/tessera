package aether.transport

import aether.core.ConnId
import aether.core.Frame
import aether.core.FrameCodec
import aether.core.PacketHeader
import aether.core.PathEstimator
import aether.core.PathId
import aether.core.ReceiverCredit
import aether.core.RlncDecoder
import aether.core.RlncEncoder
import aether.core.SenderCredit
import aether.core.Wire
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * v0 single-path datapath: unreliable-message send with systematic RLNC repair and receiver grants.
 * Plaintext (handshake keys not yet wired to AEAD). Off-heap buffers; one RX thread. Good enough to measure against.
 */
class UdpEndpoint(bind: InetSocketAddress, private val conn: ConnId, private val symbolSize: Int = 1200) : AutoCloseable {
    private val ch: DatagramChannel = DatagramChannel.open().apply {
        setOption(StandardSocketOptions.SO_RCVBUF, 4 shl 20)
        setOption(StandardSocketOptions.SO_SNDBUF, 4 shl 20)
        bind(bind)
    }
    val localAddress: InetSocketAddress get() = ch.localAddress as InetSocketAddress
    private val path = PathId(0)
    val estimator = PathEstimator(path)
    private val senderCredit = SenderCredit()
    private val receiverCredit = ReceiverCredit(estimator)
    private val enc = RlncEncoder(symbolSize)
    private val dec = RlncDecoder(symbolSize)
    private var pn = 0L
    private var msgSeq = 0L
    private var sinceRepair = 0
    private val received = LinkedBlockingQueue<Pair<Long, ByteArray>>()
    private val deliveredSeqs = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var running = true
    private val rxThread = Thread(::rxLoop, "aether-rx").apply { isDaemon = true; start() }

    /** Send one fixed-size message; emits a repair symbol every 1/redundancy source symbols. */
    fun send(to: InetSocketAddress, payload: ByteArray) {
        require(payload.size == symbolSize)
        val seq = msgSeq++
        enc.push(seq, payload)
        val buf = ByteBuffer.allocateDirect(Wire.MAX_DATAGRAM)
        PacketHeader(0, conn, path, pn++).write(buf)
        Frame.Msg(seq, 0, true, ByteBuffer.wrap(payload)).write(buf)
        buf.flip(); ch.send(buf, to); senderCredit.onSent(buf.limit())
        val red = estimator.fecRedundancy().coerceAtLeast(0.02)
        if (++sinceRepair >= (1 / red).toInt()) {
            sinceRepair = 0
            val rb = ByteBuffer.allocateDirect(Wire.MAX_DATAGRAM)
            PacketHeader(Wire.F_REPAIR, conn, path, pn++).write(rb)
            enc.repair(seed = seq.toInt() xor 0x5A5A).write(rb)
            rb.flip(); ch.send(rb, to)
        }
    }

    fun receive(timeoutMs: Long): Pair<Long, ByteArray>? = received.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun rxLoop() {
        val buf = ByteBuffer.allocateDirect(Wire.MAX_DATAGRAM)
        while (running) {
            buf.clear()
            val from = try { ch.receive(buf) ?: continue } catch (e: Exception) { if (running) continue else return }
            buf.flip()
            val hdr = PacketHeader.read(buf)
            if (hdr.conn != conn) continue
            while (true) {
                val f = FrameCodec.read(buf) ?: break
                when (f) {
                    is Frame.Msg -> {
                        val b = ByteArray(f.data.remaining()).also { f.data.get(it) }
                        dec.onSource(f.msgId, b); deliver(f.msgId, b)
                        receiverCredit.onReceived(b.size)
                    }
                    is Frame.Repair -> {
                        dec.onRepair(f)
                        // anything newly decodable inside the window is delivered
                        for (i in 0 until f.windowLen) {
                            val s = f.windowBase + i
                            if (s !in deliveredSeqs) dec.get(s)?.let { deliver(s, it) }
                        }
                    }
                    is Frame.Grant -> senderCredit.onGrant(f)
                    is Frame.Ack -> senderCredit.onAck(f)
                    else -> {}
                }
            }
            receiverCredit.tick()?.let { g ->
                val gb = ByteBuffer.allocateDirect(64)
                PacketHeader(0, conn, path, pn++).write(gb); g.write(gb); gb.flip(); ch.send(gb, from as InetSocketAddress)
            }
        }
    }

    private fun deliver(seq: Long, b: ByteArray) { if (deliveredSeqs.add(seq)) received.put(seq to b) }

    override fun close() { running = false; ch.close() }
}
