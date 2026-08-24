package tessera.transport

import tessera.core.Admission
import tessera.core.AddressValidator
import tessera.core.ConnId
import tessera.core.ConnParams
import tessera.core.Handshake
import tessera.core.HandshakeKind
import tessera.core.PacketHeader
import tessera.core.PathId
import tessera.core.Resumption
import tessera.core.RetryToken
import tessera.core.Wire
import tessera.core.ZeroRtt
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
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

/**
 * Address-family rules for the endpoints, in one place.
 *
 * A UDP socket can only send to a destination its own address family can express. [TesseraClient] used to default
 * to `127.0.0.1:0` - an IPv4-only socket - so every `connect` to an IPv6 peer failed, and failed the worst possible
 * way: the initial went nowhere, the retransmit train went nowhere, and the caller got a bare [TimeoutException]
 * seconds later with nothing pointing at the address family. That is what [defaultBind] and [requireReachable] fix.
 */
object AddressFamily {
    /**
     * The default bind for an endpoint that was not given one: the IPv6 unspecified address `::` on a host with a
     * working IPv6 stack, `0.0.0.0` otherwise.
     *
     * Why the v6 wildcard rather than a per-destination bind or a fail-fast IPv4 default: a JVM `DatagramChannel`
     * bound to `::` is *dual-stack* (the JDK opens an `AF_INET6` socket and clears `IPV6_V6ONLY`, on Windows
     * explicitly), so one socket reaches IPv6 peers natively and IPv4 peers through the v4-mapped path, and an
     * IPv4 client arriving at a `::` listener is reported back as a plain `Inet4Address`. That makes "a client with
     * no explicit bind can connect to anything the host can route to" true without deferring the bind until the
     * first `connect` - which would have to invent a local address for `localAddress` before one exists, and would
     * break [TesseraClient.adopt] and multi-destination clients, where one socket must serve peers of both families.
     *
     * The one place the assumption does not hold by itself is the native datapath, whose socket comes from Rust's
     * `UdpSocket::bind` and therefore inherits the OS default for `IPV6_V6ONLY`. [UdpIo.dualStack] reports what the
     * socket actually is, and a connect it cannot express is refused with a named diagnostic instead of timing out.
     */
    fun defaultBind(): InetSocketAddress =
        if (ipv6Available && wildcardIsDualStack()) InetSocketAddress("::", 0) else InetSocketAddress("0.0.0.0", 0)

    /**
     * Whether a `::` socket on the datapath that [UdpIo.open] would pick right now really reaches both families.
     * Always true on the JDK channel path; measured once on the native path, where the socket inherits the OS
     * default for `IPV6_V6ONLY`. Where it is false the default falls back to `0.0.0.0` (so nothing that worked
     * over IPv4 regresses) and an IPv6 destination is refused with [mismatch] telling the caller to bind `::`.
     */
    fun wildcardIsDualStack(): Boolean = !nativeSelected() || NativeUdpIo.dualStackCapable

    private fun nativeSelected(): Boolean = try { Datapath.nativeSelected() } catch (e: Throwable) { false }

    /** Whether this host has a usable IPv6 stack (an `AF_INET6` wildcard socket binds). Probed once. */
    val ipv6Available: Boolean by lazy { canBind("::") }

    /** Whether IPv6 loopback (`::1`) can actually be bound - false where IPv6 is compiled out. Probed once. */
    val ipv6LoopbackAvailable: Boolean by lazy { canBind("::1") }

    private fun canBind(literal: String): Boolean = try {
        DatagramChannel.open(StandardProtocolFamily.INET6).use { it.bind(InetSocketAddress(literal, 0)) }
        true
    } catch (e: Throwable) { false }

    /** Whether a socket bound to [local] (with [dualStack] as its datapath reports it) can send to [dst]. */
    fun canReach(local: InetAddress, dualStack: Boolean, dst: InetAddress): Boolean = when {
        local is Inet4Address -> dst is Inet4Address
        dst is Inet6Address -> true
        else -> dualStack   // IPv6 socket, IPv4 destination: only over the v4-mapped path
    }

    /** The diagnostic thrown instead of letting the handshake time out: it names both ends and the way out. */
    fun mismatch(local: InetSocketAddress, dst: InetSocketAddress): IllegalArgumentException {
        val l = local.address; val d = dst.address
        val why = if (l is Inet4Address) "an IPv4-only socket cannot reach an IPv6 destination"
                  else "this IPv6 socket is v6-only (IPV6_V6ONLY), so it cannot reach an IPv4 destination"
        val fix = if (d is Inet6Address) "\"::\" (or a specific IPv6 address)" else "\"0.0.0.0\" (or a specific IPv4 address)"
        return IllegalArgumentException(
            "address family mismatch: bound to $local (${fam(l)}) but connecting to $dst (${fam(d)}) - $why. " +
            "Bind this endpoint to $fix, or leave the bind at its default (AddressFamily.defaultBind()).")
    }

    private fun fam(a: InetAddress?): String = when (a) { null -> "unresolved"; is Inet6Address -> "IPv6"; else -> "IPv4" }
}

/** Flag bit (outside Wire's grease mask 0x0F) marking a resumed initial. Now [Wire.F_RESUME]; kept as an alias. */
const val F_RESUME: Int = Wire.F_RESUME

/**
 * Wire bytes a retried initial adds ahead of the handshake prefix (`tokenLen(1) | token`). The client's
 * first-flight budget is reduced by this much so that an initial re-sent with a token still fits
 * [Wire.MAX_DATAGRAM] byte-for-byte: 17 B off the ~184 B fresh-PQ budget, and off the ~1.29 KB resumed one.
 */
const val RETRY_TOKEN_OVERHEAD: Int = 1 + RetryToken.LEN

/** Largest retransmit train of the initial (copies per retransmission grow 2, 3, 3, ...). */
internal const val INITIAL_TRAIN_MAX = 3

/**
 * One UDP socket + rx thread + 1ms timer thread, shared by every connection on it. Demux: long header (0x80) ->
 * [onLongHeader] (handshake), short header -> connection by the 4-byte short id at offset 1.
 * Implementations: [ChannelUdpIo] (DatagramChannel) and [NativeUdpIo] (batched native datapath, NativeIo.kt);
 * [open] / `UdpIo(bind, name)` pick one according to `-Dtessera.native=on|off|auto`.
 */
internal interface UdpIo : AutoCloseable {
    val localAddress: InetSocketAddress
    /**
     * Whether this socket can also reach the *other* address family: true for a dual-stack IPv6 socket (IPv4
     * destinations work through the v4-mapped path), false for an IPv4 socket or a v6-only IPv6 socket. See
     * [AddressFamily].
     */
    val dualStack: Boolean
    val pool: BufferPool
    val byShort: ConcurrentHashMap<Int, TesseraConnection>
    val byConnId: ConcurrentHashMap<Long, TesseraConnection>
    var onLongHeader: (ByteBuffer, InetSocketAddress) -> Unit
    fun start()
    fun send(buf: ByteBuffer, to: InetSocketAddress)
    /** Flush whatever the calling thread has queued (deferred datapaths); a no-op where every send goes out at once. */
    fun flush() {}
    fun register(c: TesseraConnection)
    fun unregister(c: TesseraConnection)

    companion object {
        /** Keeps the `UdpIo(bind, name)` call sites unchanged: same selection as [open]. */
        operator fun invoke(bind: InetSocketAddress, name: String): UdpIo = open(bind, ConnConfig(), name)
        /** [NativeUdpIo] when `-Dtessera.native` allows it and `tessera_native` loaded, else [ChannelUdpIo] (see NativeIo.kt). */
        fun open(bind: InetSocketAddress, cfg: ConnConfig = ConnConfig(), name: String = "tessera"): UdpIo = openUdpIo(bind, cfg, name)

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
    /** `DatagramChannel.open()` is AF_INET6 with IPV6_V6ONLY cleared on every JDK platform, so an IPv6 bind here is dual-stack. */
    override val dualStack: Boolean get() = localAddress.address is Inet6Address
    override val pool = BufferPool(64, 2048)
    override val byShort = ConcurrentHashMap<Int, TesseraConnection>()
    override val byConnId = ConcurrentHashMap<Long, TesseraConnection>()
    @Volatile override var onLongHeader: (ByteBuffer, InetSocketAddress) -> Unit = { _, _ -> }
    @Volatile private var running = true
    private val rxThread = Thread(::rxLoop, "$name-rx").apply { isDaemon = true }
    private val timerThread = Thread(::timerLoop, "$name-timer").apply { isDaemon = true }
    /** Datagrams the socket refused (counted, never thrown: the transport's loss recovery covers them) and timer callbacks that threw. */
    @Volatile var sendErrors = 0L; private set
    @Volatile var tickErrors = 0L; private set
    @Volatile var firstError: String? = null; private set

    override fun start() { rxThread.start(); timerThread.start() }
    override fun send(buf: ByteBuffer, to: InetSocketAddress) {
        try { ch.send(buf, to) } catch (e: Exception) { if (running) { sendErrors++; if (firstError == null) firstError = "send: $e" } }
    }

    override fun register(c: TesseraConnection) { byShort[c.localShortId] = c }
    override fun unregister(c: TesseraConnection) { byShort.remove(c.localShortId, c); byConnId.remove(c.connId.raw, c) }

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
            val now = TesseraConnection.nowUs()
            // one connection's failure must not silence every other connection's timers (nor kill this thread)
            for (c in byShort.values) try { c.onTick(now) } catch (e: Exception) { tickErrors++; if (firstError == null) firstError = "tick: $e" }
        }
    }

    override fun close() { running = false; ch.close(); timerThread.interrupt() }

    override fun toString(): String = "ChannelUdpIo($localAddress, sendErrors=$sendErrors tickErrors=$tickErrors${firstError?.let { " first=$it" } ?: ""})"
}

/**
 * Server: accepts fresh PQ (ZeroRtt) and resumed (Resumption) connects. The 0-RTT payload of an accepted connection
 * is its first [TesseraConnection.receive] result. Each established connection also gets a fresh ticket on the
 * fresh path. Stateless w.r.t. tickets; per-connection state lives in the connection itself.
 *
 * Address validation: the client's address is unvalidated until it answers the PathChallenge sent right after the
 * reply; until then the server sends at most 3x the bytes it received from it (PathValidation). The reply is re-sent
 * for every duplicate initial of a known ConnId and for unauthenticated short packets until the first authenticated
 * short packet (the implicit ack of the reply) arrives.
 */
class TesseraServer(bind: InetSocketAddress, val staticKeys: Handshake.StaticKeys, ticketKey: ByteArray, val cfg: ConnConfig = ConnConfig(),
                    validator: AddressValidator? = null) : AutoCloseable {
    private val io = UdpIo.open(bind, cfg, "tessera-server")
    private val zeroRtt = ZeroRtt.Server(staticKeys)
    private val resumption = Resumption.Server(ticketKey)
    /**
     * Address validation in front of the KEM (see `core/AddressValidation.kt`). Its secret derives from the ticket
     * key, so tokens survive a restart that keeps the same ticket key; the policy knobs are [ConnConfig].
     */
    val validator: AddressValidator = validator ?: AddressValidator(secret = RetryToken.deriveSecret(ticketKey))
    /** Retries sent (diagnostics; the validator carries the rest of the counters). */
    @Volatile var retriesSent = 0L; private set
    private val accepted = LinkedBlockingQueue<TesseraConnection>()
    val localAddress: InetSocketAddress get() = io.localAddress
    val connections: Collection<TesseraConnection> get() = io.byShort.values
    /** The socket layer's own counters (datapath, batches, drops; the netem sim when attached). Diagnostics. */
    val ioStats: String get() = io.toString()
    /** Test hook: number of handshake replies (first or re-sent) to drop. */
    @Volatile var dropReplies = 0

    init { io.onLongHeader = ::onInitial; io.start() }

    fun accept(timeoutMs: Long): TesseraConnection? = accepted.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun onInitial(buf: ByteBuffer, from: InetSocketAddress) {
        val datagramLen = buf.limit()
        val hdr = PacketHeader.read(buf)
        if (hdr.flags and Wire.F_HANDSHAKE != 0) return // a reply, not for us
        io.byConnId[hdr.conn.raw]?.let { existing ->     // client retransmitted its initial: our reply was lost (the connection may be lingering after close)
            existing.onDuplicateInitial(from, datagramLen, ::dropOneReply); return
        }
        var body = ByteArray(buf.remaining()).also { buf.get(it) }
        val nowMs = System.currentTimeMillis()
        val resumed = hdr.flags and F_RESUME != 0

        // ---- address validation, ahead of any asymmetric crypto (core/AddressValidation.kt) ----
        var validated = false
        if (hdr.flags and Wire.F_TOKEN != 0) {
            if (body.isEmpty()) return
            val tl = body[0].toInt() and 0xFF
            if (body.size < 1 + tl) return
            validated = validator.verifyToken(from, body.copyOfRange(1, 1 + tl), nowMs)
            body = body.copyOfRange(1 + tl, body.size)
        }
        if (resumed) {
            // A resumed initial does no KEM: one AEAD open of the ticket. Only the per-source bucket applies.
            if (!validator.onCheapInitial(from, nowMs)) return
        } else when (validator.onExpensiveInitial(from, validated, nowMs)) {
            Admission.DROP -> return
            Admission.RETRY -> { sendRetry(hdr.conn, from, nowMs); return }
            Admission.ADMIT -> {}
        }

        val key: ByteArray; val data: ByteArray
        if (resumed) { val a = resumption.accept(body, nowMs) ?: run { validator.onFailure(nowMs); return }; key = a.key; data = a.data }
        else { val a = zeroRtt.accept(body, nowMs) ?: run { validator.onFailure(nowMs); return }; key = a.key; data = a.data }
        if (TesseraConnection.deriveConnId(key) != hdr.conn.raw) { validator.onFailure(nowMs); return } // header/key binding
        val db = ByteBuffer.wrap(data)
        val offer = ConnParams.read(db)
        val payload = ByteArray(db.remaining()).also { db.get(it) }
        val shortId = UdpIo.newShortId(io.byShort)
        val conn = TesseraConnection(io, from, key, isClient = false, localShortId = shortId, cfg = cfg)
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

    /**
     * Stateless Retry: header | tokenLen(1) | token. ~31 B against a >= 1.2 KB initial, so it is not an
     * amplification vector, and the server keeps no state at all for it.
     */
    private fun sendRetry(conn: ConnId, to: InetSocketAddress, nowMs: Long) {
        val token = validator.mintToken(to, nowMs)
        val pkt = ByteBuffer.allocate(Wire.HEADER_LEN + 1 + token.size)
        PacketHeader(Wire.F_INITIAL or Wire.F_HANDSHAKE or Wire.F_TOKEN, conn, PathId(0), 0).write(pkt)
        pkt.put(token.size.toByte()).put(token).flip()
        retriesSent++
        io.send(pkt, to)
    }

    private fun dropOneReply(): Boolean { if (dropReplies > 0) { dropReplies--; return true }; return false }

    override fun close() { io.byShort.values.forEach { it.close() }; io.close() }
}

/**
 * Client endpoint: one socket, any number of connections.
 *
 * The default `bind` is [AddressFamily.defaultBind] - the dual-stack IPv6 wildcard `::` where the host has IPv6,
 * `0.0.0.0` where it does not - so a client given no bind reaches peers of either family. It used to be
 * `127.0.0.1:0`, which could not reach an IPv6 peer at all and said so only as a connect timeout. An explicit
 * `bind` is still honoured verbatim, and a destination it cannot express is refused immediately by
 * [AddressFamily.mismatch]'s diagnostic rather than after the whole retransmit train.
 */
class TesseraClient(bind: InetSocketAddress = AddressFamily.defaultBind(), val cfg: ConnConfig = ConnConfig()) : AutoCloseable {
    private val io = UdpIo.open(bind, cfg, "tessera-client")
    private val rng = SecureRandom()
    /** Initials still waiting for a reply, so a Retry can be answered on the rx thread without a polling delay. */
    private val pending = ConcurrentHashMap<Long, PendingInitial>()
    /** Retries answered (diagnostics / tests). */
    @Volatile var retriesAnswered = 0L; private set

    private class PendingInitial(val addr: InetSocketAddress, val flags: Int, val body: ByteArray) {
        val used = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    val localAddress: InetSocketAddress get() = io.localAddress
    /**
     * Whether this endpoint's socket also reaches the other address family (a dual-stack IPv6 socket). False for an
     * IPv4 bind, and for an IPv6 bind on a datapath whose sockets are v6-only. See [AddressFamily].
     */
    val isDualStack: Boolean get() = io.dualStack
    /** The socket layer's own counters (datapath, batches, drops; the netem sim when attached). Diagnostics. */
    val ioStats: String get() = io.toString()
    /** Test hook: number of initial transmissions (the first send, or a whole retransmit train) to drop. */
    @Volatile var dropInitials = 0

    init { io.onLongHeader = ::onReply; io.start() }

    /** Fresh PQ-hybrid connect; `firstFlight` (<= [maxFreshFirstFlight] bytes) is delivered to the server app with 0 RTT. */
    fun connect(addr: InetSocketAddress, serverX25519Pub: X25519PublicKeyParameters, serverKemPub: MLKEMPublicKeyParameters,
                firstFlight: ByteArray, timeoutMs: Long = 3_000): TesseraConnection {
        val init = Handshake.initiate(serverX25519Pub, serverKemPub)
        val nonce = rng.nextLong(); val shortId = UdpIo.newShortId(io.byShort)
        val data = offer(shortId) + firstFlight
        require(data.size <= ZeroRtt.MAX_FIRST_DATA - RETRY_TOKEN_OVERHEAD) { "first flight ${firstFlight.size} B > ${maxFreshFirstFlight} B" }
        val body = ZeroRtt.Client(init).initial(data, System.currentTimeMillis(), nonce)
        return doConnect(addr, init.key, Wire.F_INITIAL, body, shortId, timeoutMs, HandshakeKind.PQ, firstFlight.size)
    }

    /** PSK resumption with a ticket from an earlier connection; `firstFlight` budget is ~1.2 KB. */
    fun resume(addr: InetSocketAddress, ticket: ByteArray, secret: ByteArray, firstFlight: ByteArray, timeoutMs: Long = 3_000): TesseraConnection {
        val nonce = rng.nextLong(); val shortId = UdpIo.newShortId(io.byShort)
        val data = offer(shortId) + firstFlight
        require(data.size <= Resumption.MAX_FIRST_DATA - RETRY_TOKEN_OVERHEAD) { "first flight ${firstFlight.size} B > ${maxResumedFirstFlight} B" }
        val (key, body) = Resumption.Client(ticket, secret).initial(data, System.currentTimeMillis(), nonce)
        return doConnect(addr, key, Wire.F_INITIAL or F_RESUME, body, shortId, timeoutMs, HandshakeKind.RESUME, firstFlight.size)
    }

    val maxFreshFirstFlight: Int get() = ZeroRtt.MAX_FIRST_DATA - RETRY_TOKEN_OVERHEAD - offer(0x7FFF_FFFF).size
    val maxResumedFirstFlight: Int get() = Resumption.MAX_FIRST_DATA - RETRY_TOKEN_OVERHEAD - offer(0x7FFF_FFFF).size

    /**
     * Moves an established connection onto this endpoint's socket: its packets now leave from (and arrive at) this
     * endpoint's port, which the server sees as an address change (path challenge + migration). The old endpoint
     * stops seeing the connection; packets the server still sends to the old address are dropped there.
     */
    fun adopt(conn: TesseraConnection) = conn.rebind(io)

    private fun offer(shortId: Int): ByteArray {
        val b = ByteBuffer.allocate(64)
        ConnParams(shortConnId = shortId, ackFreq = cfg.ackFreq, tagLen = cfg.tagLen, maxDatagram = cfg.maxDatagram, dictId = cfg.dictId).write(b)
        return b.array().copyOf(b.position())
    }

    /** Refuses a destination this endpoint's socket cannot express, naming the mismatch (see [AddressFamily]). */
    private fun requireReachable(addr: InetSocketAddress) {
        val dst = addr.address ?: throw IllegalArgumentException("unresolved destination address: $addr")
        val local = io.localAddress
        val l = local.address ?: return
        if (!AddressFamily.canReach(l, io.dualStack, dst)) throw AddressFamily.mismatch(local, addr)
    }

    private fun doConnect(addr: InetSocketAddress, key: ByteArray, flags: Int, body: ByteArray, shortId: Int, timeoutMs: Long,
                          kind: HandshakeKind, zeroRttBytes: Int): TesseraConnection {
        requireReachable(addr)
        val conn = TesseraConnection(io, addr, key, isClient = true, localShortId = shortId, cfg = cfg)
        conn.offeredDictId = cfg.dictId; conn.handshakeKind = kind; conn.zeroRttBytes = zeroRttBytes
        val pkt = ByteBuffer.allocate(Wire.HEADER_LEN + body.size)
        PacketHeader(flags, conn.connId, PathId(0), 0).write(pkt); pkt.put(body); pkt.flip()
        conn.handshakePacket = pkt
        pending[conn.connId.raw] = PendingInitial(addr, flags, body)
        io.byConnId[conn.connId.raw] = conn; io.register(conn)
        sendInitial(pkt, addr, copies = 1)
        conn.prepare()   // key schedule + parameter-sized state, derived while the reply is in flight (off the critical path)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var wait = 100L   // retransmit the initial with backoff 100, 200, 400, ... ms (capped) until the reply arrives
        var attempt = 0
        while (!conn.established.await(wait, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() > deadline) { conn.close(); pending.remove(conn.connId.raw); throw TimeoutException("tessera connect to $addr timed out after ${timeoutMs}ms") }
            // Retransmits are byte-identical (same ephemeral, same ConnId: any reply matches) and go out as trains of 2,
            // then 3 copies: under bursty (Gilbert-Elliott) loss a single retransmit is lost with the burst's per-packet
            // persistence (80 % on the lte profile) and the handshake dies with it; a train survives unless the burst
            // outlasts it. The first flight stays one packet (the 0-RTT cost); the server rate-limits its reply re-send
            // so the copies cost nothing there. Same idea as QUIC's two probes per PTO.
            // conn.handshakePacket, not `pkt`: a Retry answered on the rx thread replaces it with the token-carrying
            // initial, and every retransmit from here on must be that one.
            sendInitial(conn.handshakePacket ?: pkt, addr, copies = min(2 + attempt, INITIAL_TRAIN_MAX)); attempt++
            wait = min(wait * 2, 1_000)
        }
        io.byConnId.remove(conn.connId.raw, conn)
        pending.remove(conn.connId.raw)
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
        if (hdr.flags and Wire.F_TOKEN != 0) { onRetry(hdr.conn, conn, buf, from); return }
        conn.onHandshakeReply(buf)
    }

    /**
     * Server asked us to prove our address. Re-send the *same* initial (same ephemeral, same KEM ciphertext, same
     * ConnId, so the reply still matches) with the token prepended, right here on the rx thread - waiting for the
     * connect loop's next retransmit tick would add up to 100 ms to a 1-RTT cost.
     *
     * A Retry is unauthenticated by construction, so it is accepted at most once per connect and only from the
     * address we sent the initial to. Worst case an off-path forger who can guess our ConnId costs us one extra
     * initial; it cannot stop the connect, because the original retransmit train continues either way.
     */
    private fun onRetry(id: ConnId, conn: TesseraConnection, buf: ByteBuffer, from: InetSocketAddress) {
        val p = pending[id.raw] ?: return
        if (from != p.addr) return
        if (!buf.hasRemaining()) return
        val tl = buf.get().toInt() and 0xFF
        if (tl == 0 || buf.remaining() < tl) return
        val token = ByteArray(tl).also { buf.get(it) }
        if (!p.used.compareAndSet(false, true)) return
        val pkt = ByteBuffer.allocate(Wire.HEADER_LEN + 1 + tl + p.body.size)
        PacketHeader(p.flags or Wire.F_TOKEN, id, PathId(0), 0).write(pkt)
        pkt.put(tl.toByte()).put(token).put(p.body).flip()
        conn.handshakePacket = pkt
        retriesAnswered++
        sendInitial(pkt, p.addr, copies = 1)
    }

    override fun close() { io.byShort.values.forEach { it.close() }; io.close() }
}
