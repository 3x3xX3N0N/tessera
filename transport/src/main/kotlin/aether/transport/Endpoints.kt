package aether.transport

import aether.core.ConnId
import aether.core.ConnParams
import aether.core.Handshake
import aether.core.HandshakeKind
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

/** Largest retransmit train of the initial (copies per retransmission grow 2, 3, 3, ...). */
internal const val INITIAL_TRAIN_MAX = 3

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
    /** Flush whatever the calling thread has queued (deferred datapaths); a no-op where every send goes out at once. */
    fun flush() {}
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
 *
 * Address validation: the client's address is unvalidated until it answers the PathChallenge sent right after the
 * reply; until then the server sends at most 3x the bytes it received from it (PathValidation). The reply is re-sent
 * for every duplicate initial of a known ConnId and for unauthenticated short packets until the first authenticated
 * short packet (the implicit ack of the reply) arrives.
 */
class AetherServer(bind: InetSocketAddress, val staticKeys: Handshake.StaticKeys, ticketKey: ByteArray, val cfg: ConnConfig = ConnConfig()) : AutoCloseable {
    private val io = UdpIo.open(bind, cfg, "aether-server")
    private val zeroRtt = ZeroRtt.Server(staticKeys)
    private val resumption = Resumption.Server(ticketKey)
    private val accepted = LinkedBlockingQueue<AetherConnection>()
    val localAddress: InetSocketAddress get() = io.localAddress
    val connections: Collection<AetherConnection> get() = io.byShort.values
    /** The socket layer's own counters (datapath, batches, drops; the netem sim when attached). Diagnostics. */
    val ioStats: String get() = io.toString()
    /** Test hook: number of handshake replies (first or re-sent) to drop. */
    @Volatile var dropReplies = 0

    init { io.onLongHeader = ::onInitial; io.start() }

    fun accept(timeoutMs: Long): AetherConnection? = accepted.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun onInitial(buf: ByteBuffer, from: InetSocketAddress) {
        val datagramLen = buf.limit()
        val hdr = PacketHeader.read(buf)
        if (hdr.flags and Wire.F_HANDSHAKE != 0) return // a reply, not for us
        io.byConnId[hdr.conn.raw]?.let { existing ->     // client retransmitted its initial: our reply was lost (the connection may be lingering after close)
            existing.onDuplicateInitial(from, datagramLen, ::dropOneReply); return
        }
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        val nowMs = System.currentTimeMillis()
        val resumed = hdr.flags and F_RESUME != 0
        val key: ByteArray; val data: ByteArray
        if (resumed) { val a = resumption.accept(body, nowMs) ?: return; key = a.key; data = a.data }
        else { val a = zeroRtt.accept(body, nowMs) ?: return; key = a.key; data = a.data }
        if (AetherConnection.deriveConnId(key) != hdr.conn.raw) return // header/key binding
        val db = ByteBuffer.wrap(data)
        val offer = ConnParams.read(db)
        val payload = ByteArray(db.remaining()).also { db.get(it) }
        val shortId = UdpIo.newShortId(io.byShort)
        val conn = AetherConnection(io, from, key, isClient = false, localShortId = shortId, cfg = cfg)
        conn.handshakeKind = if (resumed) HandshakeKind.RESUME else HandshakeKind.PQ; conn.zeroRttBytes = payload.size
        val params = conn.negotiateAsServer(offer, datagramLen)
        val ticket = if (resumed) null else resumption.issueTicket(key, nowMs)
        val reply = conn.buildHandshakeReply(params, ticket)
        io.byConnId[hdr.conn.raw] = conn; io.register(conn)
        conn.established.countDown()
        conn.onHandshakeSent(reply.remaining())
        if (!dropOneReply()) io.send(reply.duplicate(), from)
        conn.deliverRaw(payload)
        accepted.put(conn)
        conn.afterAccept()
    }

    private fun dropOneReply(): Boolean { if (dropReplies > 0) { dropReplies--; return true }; return false }

    override fun close() { io.byShort.values.forEach { it.close() }; io.close() }
}

/** Client endpoint: one socket, any number of connections. */
class AetherClient(bind: InetSocketAddress = InetSocketAddress("127.0.0.1", 0), val cfg: ConnConfig = ConnConfig()) : AutoCloseable {
    private val io = UdpIo.open(bind, cfg, "aether-client")
    private val rng = SecureRandom()
    val localAddress: InetSocketAddress get() = io.localAddress
    /** The socket layer's own counters (datapath, batches, drops; the netem sim when attached). Diagnostics. */
    val ioStats: String get() = io.toString()
    /** Test hook: number of initial transmissions (the first send, or a whole retransmit train) to drop. */
    @Volatile var dropInitials = 0

    init { io.onLongHeader = ::onReply; io.start() }

    /** Fresh PQ-hybrid connect; `firstFlight` (<= [maxFreshFirstFlight] bytes) is delivered to the server app with 0 RTT. */
    fun connect(addr: InetSocketAddress, serverX25519Pub: X25519PublicKeyParameters, serverKemPub: MLKEMPublicKeyParameters,
                firstFlight: ByteArray, timeoutMs: Long = 3_000): AetherConnection {
        val init = Handshake.initiate(serverX25519Pub, serverKemPub)
        val nonce = rng.nextLong(); val shortId = UdpIo.newShortId(io.byShort)
        val data = offer(shortId) + firstFlight
        require(data.size <= ZeroRtt.MAX_FIRST_DATA) { "first flight ${firstFlight.size} B > ${maxFreshFirstFlight} B" }
        val body = ZeroRtt.Client(init).initial(data, System.currentTimeMillis(), nonce)
        return doConnect(addr, init.key, Wire.F_INITIAL, body, shortId, timeoutMs, HandshakeKind.PQ, firstFlight.size)
    }

    /** PSK resumption with a ticket from an earlier connection; `firstFlight` budget is ~1.2 KB. */
    fun resume(addr: InetSocketAddress, ticket: ByteArray, secret: ByteArray, firstFlight: ByteArray, timeoutMs: Long = 3_000): AetherConnection {
        val nonce = rng.nextLong(); val shortId = UdpIo.newShortId(io.byShort)
        val data = offer(shortId) + firstFlight
        require(data.size <= Resumption.MAX_FIRST_DATA) { "first flight ${firstFlight.size} B > ${maxResumedFirstFlight} B" }
        val (key, body) = Resumption.Client(ticket, secret).initial(data, System.currentTimeMillis(), nonce)
        return doConnect(addr, key, Wire.F_INITIAL or F_RESUME, body, shortId, timeoutMs, HandshakeKind.RESUME, firstFlight.size)
    }

    val maxFreshFirstFlight: Int get() = ZeroRtt.MAX_FIRST_DATA - offer(0x7FFF_FFFF).size
    val maxResumedFirstFlight: Int get() = Resumption.MAX_FIRST_DATA - offer(0x7FFF_FFFF).size

    /**
     * Moves an established connection onto this endpoint's socket: its packets now leave from (and arrive at) this
     * endpoint's port, which the server sees as an address change (path challenge + migration). The old endpoint
     * stops seeing the connection; packets the server still sends to the old address are dropped there.
     */
    fun adopt(conn: AetherConnection) = conn.rebind(io)

    private fun offer(shortId: Int): ByteArray {
        val b = ByteBuffer.allocate(64)
        ConnParams(shortConnId = shortId, ackFreq = cfg.ackFreq, tagLen = cfg.tagLen, maxDatagram = cfg.maxDatagram, dictId = cfg.dictId).write(b)
        return b.array().copyOf(b.position())
    }

    private fun doConnect(addr: InetSocketAddress, key: ByteArray, flags: Int, body: ByteArray, shortId: Int, timeoutMs: Long,
                          kind: HandshakeKind, zeroRttBytes: Int): AetherConnection {
        val conn = AetherConnection(io, addr, key, isClient = true, localShortId = shortId, cfg = cfg)
        conn.offeredDictId = cfg.dictId; conn.handshakeKind = kind; conn.zeroRttBytes = zeroRttBytes
        val pkt = ByteBuffer.allocate(Wire.HEADER_LEN + body.size)
        PacketHeader(flags, conn.connId, PathId(0), 0).write(pkt); pkt.put(body); pkt.flip()
        conn.handshakePacket = pkt
        io.byConnId[conn.connId.raw] = conn; io.register(conn)
        sendInitial(pkt, addr, copies = 1)
        conn.prepare()   // key schedule + parameter-sized state, derived while the reply is in flight (off the critical path)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var wait = 100L   // retransmit the initial with backoff 100, 200, 400, ... ms (capped) until the reply arrives
        var attempt = 0
        while (!conn.established.await(wait, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() > deadline) { conn.close(); throw TimeoutException("aether connect to $addr timed out after ${timeoutMs}ms") }
            // Retransmits are byte-identical (same ephemeral, same ConnId: any reply matches) and go out as trains of 2,
            // then 3 copies: under bursty (Gilbert-Elliott) loss a single retransmit is lost with the burst's per-packet
            // persistence (80 % on the lte profile) and the handshake dies with it; a train survives unless the burst
            // outlasts it. The first flight stays one packet (the 0-RTT cost); the server rate-limits its reply re-send
            // so the copies cost nothing there. Same idea as QUIC's two probes per PTO.
            sendInitial(pkt, addr, copies = min(2 + attempt, INITIAL_TRAIN_MAX)); attempt++
            wait = min(wait * 2, 1_000)
        }
        io.byConnId.remove(conn.connId.raw, conn)
        return conn
    }

    private fun sendInitial(pkt: ByteBuffer, addr: InetSocketAddress, copies: Int) {
        if (dropInitials > 0) { dropInitials--; return }   // drops the whole train
        repeat(copies) { io.send(pkt.duplicate(), addr) }
    }

    private fun onReply(buf: ByteBuffer, from: InetSocketAddress) {
        val hdr = PacketHeader.read(buf)
        if (hdr.flags and Wire.F_HANDSHAKE == 0) return
        val conn = io.byConnId[hdr.conn.raw] ?: return
        conn.onHandshakeReply(buf)
    }

    override fun close() { io.byShort.values.forEach { it.close() }; io.close() }
}
