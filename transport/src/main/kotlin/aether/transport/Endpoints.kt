package aether.transport

import aether.core.ConnId
import aether.core.ConnParams
import aether.core.Handshake
import aether.core.PacketHeader
import aether.core.PathId
import aether.core.Resumption
import aether.core.Wire
import aether.core.ZeroRtt
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Flag bit (outside Wire's grease mask 0x0F) marking a resumed initial. Local to transport until Wire.kt grows it. */
const val F_RESUME: Int = 0x10

/**
 * One UDP socket + rx thread + 1ms timer thread, shared by every connection on it. Demux: long header (0x80) ->
 * [onLongHeader] (handshake), short header -> connection by the 4-byte short id at offset 1.
 * Implementations: [ChannelUdpIo] (DatagramChannel) and [NativeUdpIo] (batched native datapath, NativeIo.kt);
 * [open] / `UdpIo(bind, name)` pick one according to `-Daether.native=on|off|auto`.
 */
internal interface UdpIo : AutoCloseable {
    val localAddress: InetSocketAddress
    val pool: BufferPool
    val byShort: ConcurrentHashMap<Int, AetherConnection>
    val byConnId: ConcurrentHashMap<Long, AetherConnection>
    var onLongHeader: (ByteBuffer, InetSocketAddress) -> Unit
    fun start()
    fun send(buf: ByteBuffer, to: InetSocketAddress)
    fun register(c: AetherConnection)
    fun unregister(c: AetherConnection)

    companion object {
        /** Keeps the `UdpIo(bind, name)` call sites unchanged: same selection as [open]. */
        operator fun invoke(bind: InetSocketAddress, name: String): UdpIo = open(bind, ConnConfig(), name)
        /** [NativeUdpIo] when `-Daether.native` allows it and `aether_native` loaded, else [ChannelUdpIo] (see NativeIo.kt). */
        fun open(bind: InetSocketAddress, cfg: ConnConfig = ConnConfig(), name: String = "aether"): UdpIo = openUdpIo(bind, cfg, name)

        private val ids = AtomicInteger(SecureRandom().nextInt())
        fun newShortId(taken: Map<Int, *>): Int { while (true) { val id = ids.incrementAndGet(); if (id != 0 && !taken.containsKey(id)) return id } }
    }
}

/**
 * [UdpIo] on a blocking [DatagramChannel]: one `receive` per datagram on the rx thread, one `send` per packet.
 * The rx loop owns one direct buffer; what still allocates per datagram is the InetSocketAddress
 * DatagramChannel.receive returns (JDK; a connected client socket + read() would avoid it).
 */
internal class ChannelUdpIo(bind: InetSocketAddress, name: String) : UdpIo {
    private val ch: DatagramChannel = DatagramChannel.open().apply {
        setOption(StandardSocketOptions.SO_RCVBUF, 4 shl 20)
        setOption(StandardSocketOptions.SO_SNDBUF, 4 shl 20)
        bind(bind)
    }
    override val localAddress: InetSocketAddress get() = ch.localAddress as InetSocketAddress
    override val pool = BufferPool(64, 2048)
    override val byShort = ConcurrentHashMap<Int, AetherConnection>()
    override val byConnId = ConcurrentHashMap<Long, AetherConnection>()
    @Volatile override var onLongHeader: (ByteBuffer, InetSocketAddress) -> Unit = { _, _ -> }
    @Volatile private var running = true
    private val rxThread = Thread(::rxLoop, "$name-rx").apply { isDaemon = true }
    private val timerThread = Thread(::timerLoop, "$name-timer").apply { isDaemon = true }

    override fun start() { rxThread.start(); timerThread.start() }
    override fun send(buf: ByteBuffer, to: InetSocketAddress) { try { ch.send(buf, to) } catch (e: Exception) { if (running) throw e } }

    override fun register(c: AetherConnection) { byShort[c.localShortId] = c }
    override fun unregister(c: AetherConnection) { byShort.remove(c.localShortId, c); byConnId.remove(c.connId.raw, c) }

    private fun rxLoop() {
        val buf = ByteBuffer.allocateDirect(2048)
        while (running) {
            buf.clear()
            val from = try { ch.receive(buf) ?: continue } catch (e: Exception) { if (running) continue else return }
            buf.flip()
            if (buf.remaining() < 5) continue
            try {
                if (buf.get(0).toInt() and Wire.F_INITIAL != 0) onLongHeader(buf, from as InetSocketAddress)
                else byShort[buf.getInt(1)]?.onShortPacket(buf, from as InetSocketAddress)
            } catch (e: Exception) { /* malformed packet: drop */ }
        }
    }

    private fun timerLoop() {
        while (running) {
            try { Thread.sleep(1) } catch (e: InterruptedException) { return }
            val now = AetherConnection.nowUs()
            for (c in byShort.values) c.onTick(now)
        }
    }

    override fun close() { running = false; ch.close(); timerThread.interrupt() }
}

/**
 * Server: accepts fresh PQ (ZeroRtt) and resumed (Resumption) connects. The 0-RTT payload of an accepted connection
 * is its first [AetherConnection.receive] result. Each established connection also gets a fresh ticket on the
 * fresh path. Stateless w.r.t. tickets; per-connection state lives in the connection itself.
 */
class AetherServer(bind: InetSocketAddress, val staticKeys: Handshake.StaticKeys, ticketKey: ByteArray, val cfg: ConnConfig = ConnConfig()) : AutoCloseable {
    private val io = UdpIo(bind, "aether-server")
    private val zeroRtt = ZeroRtt.Server(staticKeys)
    private val resumption = Resumption.Server(ticketKey)
    private val accepted = LinkedBlockingQueue<AetherConnection>()
    val localAddress: InetSocketAddress get() = io.localAddress
    val connections: Collection<AetherConnection> get() = io.byShort.values

    init { io.onLongHeader = ::onInitial; io.start() }

    fun accept(timeoutMs: Long): AetherConnection? = accepted.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun onInitial(buf: ByteBuffer, from: InetSocketAddress) {
        val hdr = PacketHeader.read(buf)
        if (hdr.flags and Wire.F_HANDSHAKE != 0) return // a reply, not for us
        io.byConnId[hdr.conn.raw]?.let { existing ->     // client retransmitted its initial: our reply was lost
            existing.handshakePacket?.let { io.send(it.duplicate(), from) }; return
        }
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        val nowMs = System.currentTimeMillis()
        val resumed = hdr.flags and F_RESUME != 0
        val key: ByteArray; val data: ByteArray; val nonceOff: Int
        if (resumed) { val a = resumption.accept(body, nowMs) ?: return; key = a.key; data = a.data; nonceOff = Resumption.PREFIX_LEN - 8 }
        else { val a = zeroRtt.accept(body, nowMs) ?: return; key = a.key; data = a.data; nonceOff = ZeroRtt.PREFIX_LEN - 8 }
        val connNonce = ByteBuffer.wrap(body).getLong(nonceOff)
        if (AetherConnection.deriveConnId(key) != hdr.conn.raw) return // header/key binding
        val db = ByteBuffer.wrap(data)
        val offer = ConnParams.read(db)
        val payload = ByteArray(db.remaining()).also { db.get(it) }
        val shortId = UdpIo.newShortId(io.byShort)
        val conn = AetherConnection(io, from, key, connNonce, isClient = false, localShortId = shortId,
            peerShortId = offer.shortConnId, peerAckFreq = offer.ackFreq, cfg = cfg)
        val ticket = if (resumed) null else resumption.issueTicket(key, nowMs)
        val reply = conn.buildHandshakeReply(ConnParams(shortConnId = shortId, ackFreq = cfg.ackFreq, tagLen = cfg.tagLen, maxDatagram = cfg.maxDatagram), ticket)
        io.byConnId[hdr.conn.raw] = conn; io.register(conn)
        conn.established.countDown()
        io.send(reply.duplicate(), from)
        conn.deliver(payload)
        accepted.put(conn)
    }

    override fun close() { io.byShort.values.forEach { it.close() }; io.close() }
}

/** Client endpoint: one socket, any number of connections. */
class AetherClient(bind: InetSocketAddress = InetSocketAddress("127.0.0.1", 0), val cfg: ConnConfig = ConnConfig()) : AutoCloseable {
    private val io = UdpIo(bind, "aether-client")
    private val rng = SecureRandom()
    val localAddress: InetSocketAddress get() = io.localAddress

    init { io.onLongHeader = ::onReply; io.start() }

    /** Fresh PQ-hybrid connect; `firstFlight` (<= [maxFreshFirstFlight] bytes) is delivered to the server app with 0 RTT. */
    fun connect(addr: InetSocketAddress, serverX25519Pub: X25519PublicKeyParameters, serverKemPub: MLKEMPublicKeyParameters,
                firstFlight: ByteArray, timeoutMs: Long = 3_000): AetherConnection {
        val init = Handshake.initiate(serverX25519Pub, serverKemPub)
        val nonce = rng.nextLong(); val shortId = UdpIo.newShortId(io.byShort)
        val data = offer(shortId) + firstFlight
        require(data.size <= ZeroRtt.MAX_FIRST_DATA) { "first flight ${firstFlight.size} B > ${maxFreshFirstFlight} B" }
        val body = ZeroRtt.Client(init).initial(data, System.currentTimeMillis(), nonce)
        return doConnect(addr, init.key, nonce, Wire.F_INITIAL, body, shortId, timeoutMs)
    }

    /** PSK resumption with a ticket from an earlier connection; `firstFlight` budget is ~1.2 KB. */
    fun resume(addr: InetSocketAddress, ticket: ByteArray, secret: ByteArray, firstFlight: ByteArray, timeoutMs: Long = 3_000): AetherConnection {
        val nonce = rng.nextLong(); val shortId = UdpIo.newShortId(io.byShort)
        val data = offer(shortId) + firstFlight
        require(data.size <= Resumption.MAX_FIRST_DATA) { "first flight ${firstFlight.size} B > ${maxResumedFirstFlight} B" }
        val (key, body) = Resumption.Client(ticket, secret).initial(data, System.currentTimeMillis(), nonce)
        return doConnect(addr, key, nonce, Wire.F_INITIAL or F_RESUME, body, shortId, timeoutMs)
    }

    val maxFreshFirstFlight: Int get() = ZeroRtt.MAX_FIRST_DATA - OFFER_LEN
    val maxResumedFirstFlight: Int get() = Resumption.MAX_FIRST_DATA - OFFER_LEN

    private fun offer(shortId: Int): ByteArray {
        val b = ByteBuffer.allocate(32); ConnParams(shortConnId = shortId, ackFreq = cfg.ackFreq, tagLen = cfg.tagLen, maxDatagram = cfg.maxDatagram).write(b)
        return b.array().copyOf(b.position())
    }

    private fun doConnect(addr: InetSocketAddress, key: ByteArray, nonce: Long, flags: Int, body: ByteArray, shortId: Int, timeoutMs: Long): AetherConnection {
        val conn = AetherConnection(io, addr, key, nonce, isClient = true, localShortId = shortId, peerShortId = 0, peerAckFreq = cfg.ackFreq, cfg = cfg)
        val pkt = ByteBuffer.allocate(Wire.HEADER_LEN + body.size)
        PacketHeader(flags, conn.connId, PathId(0), 0).write(pkt); pkt.put(body); pkt.flip()
        conn.handshakePacket = pkt
        io.byConnId[conn.connId.raw] = conn; io.register(conn)
        io.send(pkt.duplicate(), addr)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var wait = 200L
        while (!conn.established.await(wait, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() > deadline) { conn.close(); throw TimeoutException("aether connect to $addr timed out after ${timeoutMs}ms") }
            io.send(pkt.duplicate(), addr); wait = min(wait * 2, 1_000)
        }
        io.byConnId.remove(conn.connId.raw, conn)
        return conn
    }

    private fun onReply(buf: ByteBuffer, from: InetSocketAddress) {
        val hdr = PacketHeader.read(buf)
        if (hdr.flags and Wire.F_HANDSHAKE == 0) return
        val conn = io.byConnId[hdr.conn.raw] ?: return
        conn.onHandshakeReply(buf)
    }

    override fun close() { io.byShort.values.forEach { it.close() }; io.close() }

    private companion object { const val OFFER_LEN = 16 }
}
