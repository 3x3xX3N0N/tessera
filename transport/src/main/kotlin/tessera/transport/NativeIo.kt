package tessera.transport

import tessera.core.Wire
import tessera.native.Gf256Native
import tessera.native.NativeLib
import tessera.native.NativeUdp
import tessera.native.PacketBatch
import tessera.native.SockAddrCache
import tessera.native.TxBatch
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport

/**
 * Implementation selection behind [UdpIo.open]: `-Dtessera.native=on|off|auto` (default `auto`). [NativeUdpIo] when
 * allowed and `tessera_native` loaded — installing [Gf256Native] as the process-wide GF(256) kernel on the way, so the
 * property switches the whole native datapath — otherwise [ChannelUdpIo]. `on` throws when the library is missing
 * rather than silently falling back.
 */
internal fun openUdpIo(bind: InetSocketAddress, cfg: ConnConfig, name: String): UdpIo {
    val io: UdpIo = if (!Datapath.nativeSelected()) ChannelUdpIo(bind, name) else { Gf256Native.install(); NativeUdpIo(bind, name, cfg) }
    return cfg.netem?.let { NetemUdpIo(io, it) } ?: io   // link impairment on the whole send path (handshakes included)
}

/**
 * [UdpIo] on the native datapath ([NativeUdp] + [PacketBatch]/[TxBatch]).
 *
 * Receive: the rx thread takes up to [RX_BATCH] datagrams per call (`recvmmsg` on Linux, one FFI crossing either way;
 * the library blocks in a single receive while datagrams trickle in and drains non-blocking once a backlog builds, see
 * [NativeUdp.recvBatch]) and dispatches each exactly like [ChannelUdpIo] — same buffer contract (position 0,
 * limit = length, big-endian view of the slot) and the same demux — with the sender address resolved through a
 * [SockAddrCache] so a steady peer costs no allocation. The receive never times out (so the kernel never cancels a
 * receive that may already hold a datagram); [close] wakes it with a one-byte datagram to the socket itself, which the
 * loop ignores like any other runt.
 *
 * Send: every datagram the handlers emit during one rx iteration (acks, grants, reactive repairs, handshake replies)
 * is coalesced into the rx thread's [TxBatch], flushed at the end of the iteration or when the batch is full; the
 * timer thread batches one tick the same way. Any other thread (the application's `send`) goes out immediately
 * unless it opted into deferred mode ([deferSends]). On the wire every datagram is byte-identical to what
 * [ChannelUdpIo] would have sent, in the same order.
 *
 * GSO: while flushing, a run of equal-size datagrams to one destination ([TxBatch.runEnd]) goes out as one
 * super-datagram that the kernel segments — `UDP_SEGMENT` on Linux, USO (`UDP_SEND_MSG_SIZE`) on Windows 10 2004+.
 * `-Dtessera.native.gso=off` disables that; `on` forces the code path elsewhere, where the library segments in user
 * space (same bytes on the wire either way).
 */
internal class NativeUdpIo(bind: InetSocketAddress, name: String, cfg: ConnConfig = ConnConfig()) : UdpIo {
    private class TxState(val batch: TxBatch) { var deferred = false }

    private val sock: NativeUdp
    override val localAddress: InetSocketAddress
    /**
     * The native socket comes from Rust's `UdpSocket::bind`, which does not touch `IPV6_V6ONLY`, so an IPv6 bind is
     * dual-stack only where the OS says so (Linux with `net.ipv6.bindv6only=0`; **not** Windows, where the option
     * defaults to on). Rather than guess per OS, [dualStackCapable] measures it once with a pair of throwaway
     * native sockets - and [TesseraClient] turns a `false` here into a named error instead of a connect timeout.
     */
    override val dualStack: Boolean get() = localAddress.address is Inet6Address && dualStackCapable
    override val pool = BufferPool(64, SLOT)
    override val byShort = ConcurrentHashMap<Int, TesseraConnection>()
    override val byConnId = ConcurrentHashMap<Long, TesseraConnection>()
    @Volatile override var onLongHeader: (ByteBuffer, InetSocketAddress) -> Unit = { _, _ -> }
    @Volatile private var running = true
    private val rxThread = Thread(::rxLoop, "$name-native-rx").apply { isDaemon = true }
    private val timerThread = Thread(::timerLoop, "$name-native-timer").apply { isDaemon = true }
    private val gso: Boolean = gsoEnabled()
    private val txLocal: ThreadLocal<TxState> = ThreadLocal.withInitial { TxState(TxBatch(TX_BATCH, TX_BATCH * SLOT)) }

    // diagnostics (rx counters are written by the rx thread only)
    @Volatile var datagramsIn = 0L; private set
    @Volatile var rxBatches = 0L; private set
    @Volatile var addressMisses = 0L; private set
    private val flushes = LongAdder()
    private val datagramsOut = LongAdder()
    private val gsoRunsOut = LongAdder()
    private val droppedOut = LongAdder()
    private val sendErrorsOut = LongAdder()
    private val gsoFallbacksOut = LongAdder()
    @Volatile private var tickErrorsOut = 0L
    @Volatile private var firstError: String? = null
    /** GSO runs sent so far (each is one super-datagram of >= 2 segments). */
    val gsoRuns: Long get() = gsoRunsOut.sum()
    /** Datagrams not handed to the kernel: the socket kept refusing them (would-block backoff exhausted) or a send failed. */
    val dropped: Long get() = droppedOut.sum()
    /** Send calls that failed with an error (every one is counted and the affected datagrams are in [dropped]; nothing is thrown or silently lost). */
    val sendErrors: Long get() = sendErrorsOut.sum()
    /** GSO runs the kernel refused that went out per datagram instead. */
    val gsoFallbacks: Long get() = gsoFallbacksOut.sum()
    /** Timer callbacks that threw (the timer thread survives them). */
    val tickErrors: Long get() = tickErrorsOut

    init {
        require(cfg.maxDatagram <= SLOT) { "maxDatagram ${cfg.maxDatagram} exceeds the $SLOT-byte datagram slot" }
        val ip = bind.address ?: throw IllegalArgumentException("unresolved bind address: $bind")
        val literal = if (ip is Inet6Address) ip.hostAddress.substringBefore('%') else ip.hostAddress
        sock = NativeUdp(literal, bind.port)
        localAddress = InetSocketAddress(ip, sock.localPort)
        Datapath.openNative.incrementAndGet()
    }

    override fun start() { rxThread.start(); timerThread.start() }

    override fun send(buf: ByteBuffer, to: InetSocketAddress) {
        val t = txLocal.get()
        if (!t.batch.add(buf, to)) {
            flush(t)
            if (!t.batch.add(buf, to)) throw IllegalArgumentException("datagram of ${buf.remaining()} B does not fit a $SLOT-byte slot")
        }
        buf.position(buf.limit()) // DatagramChannel.send consumes the buffer; keep that contract
        if (!t.deferred || t.batch.isFull) flush(t)
    }

    /** Sends whatever the calling thread has queued (only deferred-mode threads ever have anything pending). */
    override fun flush() = flush(txLocal.get())

    /** Deferred mode for the calling thread: sends queue up and go out on [flush] or when the batch is full. */
    fun deferSends(defer: Boolean) { val t = txLocal.get(); if (!defer) flush(t); t.deferred = defer }

    override fun register(c: TesseraConnection) { byShort[c.localShortId] = c }
    override fun unregister(c: TesseraConnection) { byShort.remove(c.localShortId, c); byConnId.remove(c.connId.raw, c) }

    /**
     * Sends the thread's batch. A send failure is counted ([sendErrors], the datagrams not sent in [dropped]) and never
     * thrown: the rx thread's and the timer thread's flushes used to propagate an IOException out of their loops, which
     * silently ended all reception / all timers of the endpoint for good; the transport's FEC / ARQ covers a lost datagram.
     */
    private fun flush(t: TxState) {
        val b = t.batch
        if (b.isEmpty) return
        try { drain(b) } catch (e: Exception) { if (running) recordError(e, b.count) } finally { b.clear() }
    }

    private fun recordError(e: Exception, lostDatagrams: Int) {
        sendErrorsOut.increment(); droppedOut.add(lostDatagrams.toLong())
        if (firstError == null) firstError = "send: $e"
    }

    /** Sends the batch in order: plain ranges through sendBatch, equal-size runs (>= 2) as GSO super-datagrams. */
    private fun drain(b: TxBatch) {
        flushes.increment()
        var i = 0
        while (i < b.count) {
            var k = i; var runEnd = -1
            if (gso) {
                while (k < b.count) {
                    val e = b.runEnd(k, GSO_MAX_SEGMENTS, GSO_MAX_BYTES)
                    if (e - k >= 2) { runEnd = e; break }
                    k++
                }
            } else k = b.count
            if (k > i) sendPlain(b, i, k)
            if (runEnd > 0) { sendRun(b, k, runEnd); i = runEnd } else i = k
        }
    }

    private fun sendPlain(b: TxBatch, from: Int, end: Int) {
        var done = from
        var waits = 0
        while (done < end) {
            val n = try { sock.sendBatch(b, done, end - done) } catch (e: java.io.IOException) { recordError(e, end - done); break }
            done += n
            if (done < end && !backoff(waits++)) { droppedOut.add((end - done).toLong()); break }
        }
        datagramsOut.add((done - from).toLong())
    }

    /** A GSO run; whatever the kernel refuses (an error, or would-block) goes out per datagram through [sendPlain]. */
    private fun sendRun(b: TxBatch, from: Int, end: Int) {
        gsoRunsOut.increment()
        val n = try { sock.sendGso(b, from, end) } catch (e: java.io.IOException) {
            gsoFallbacksOut.increment(); if (firstError == null) firstError = "gso: $e"
            0
        }
        datagramsOut.add(n.toLong())
        if (n < end - from) sendPlain(b, from + n, end)
    }

    /** The socket would block (4 MB send buffer full): park briefly; after ~100 ms treat the rest as dropped. */
    private fun backoff(attempt: Int): Boolean {
        if (attempt >= MAX_BACKOFF || !running) return false
        LockSupport.parkNanos(100_000)
        return true
    }

    private fun rxLoop() {
        val batch = PacketBatch(RX_BATCH, SLOT)
        val views = Array(RX_BATCH) { batch.buffer(it).asByteBuffer() } // direct, big-endian views of the slots
        val addrs = SockAddrCache()
        val t = txLocal.get().also { it.deferred = true }
        while (running) {
            val n = try { sock.recvBatch(batch, timeoutMs = -1) } catch (e: Exception) {
                if (!running) break
                LockSupport.parkNanos(1_000_000); continue
            }
            for (i in 0 until n) {
                val len = batch.length(i)
                if (len < 5) continue
                val buf = views[i]
                buf.limit(len).position(0)
                val from = batch.address(i, addrs) ?: continue
                try {
                    if (buf.get(0).toInt() and Wire.F_INITIAL != 0) onLongHeader(buf, from)
                    else byShort[buf.getInt(1)]?.onShortPacket(buf, from)
                } catch (e: Exception) { /* malformed packet: drop */ }
            }
            if (n > 0) { datagramsIn += n; rxBatches++; addressMisses = addrs.misses }
            flush(t) // everything the handlers queued for this batch
        }
        flush(t)
    }

    private fun timerLoop() {
        val t = txLocal.get().also { it.deferred = true }
        try {
            while (running) {
                try { Thread.sleep(1) } catch (e: InterruptedException) { break }
                val now = TesseraConnection.nowUs()
                for (c in byShort.values) try { c.onTick(now) } catch (e: Exception) { tickErrorsOut++; if (firstError == null) firstError = "tick: $e" }
                flush(t)
            }
        } finally { flush(t) }   // anything queued by the last tick leaves before the thread dies
    }

    override fun close() {
        if (!running) return
        running = false
        timerThread.interrupt()
        val self = Thread.currentThread()
        if (self !== rxThread && rxThread.isAlive) {
            wakeRx()
            rxThread.join(CLOSE_JOIN_MS) // lets it flush and exit before the handle goes away
        }
        if (self !== timerThread) timerThread.join(100)
        sock.close()
        Datapath.openNative.decrementAndGet()
    }

    /** One-byte datagram to ourselves: ends the rx thread's blocking receive (it drops anything under 5 bytes). */
    private fun wakeRx() {
        val ip = localAddress.address
        val target = if (ip.isAnyLocalAddress) InetSocketAddress(if (ip is Inet6Address) "::1" else "127.0.0.1", localAddress.port) else localAddress
        try {
            val t = txLocal.get()
            flush(t)
            t.batch.add(WAKE, target)
            drain(t.batch)
            t.batch.clear()
        } catch (e: Exception) { /* best effort; close() falls back to the join timeout */ }
    }

    val stats: String get() = "in=$datagramsIn batches=$rxBatches addrMiss=$addressMisses | out=${datagramsOut.sum()} flushes=${flushes.sum()} gsoRuns=$gsoRuns gsoFallback=$gsoFallbacks dropped=$dropped sendErrors=$sendErrors tickErrors=$tickErrors gso=${if (gso) "on" else "off"}${firstError?.let { " first=$it" } ?: ""}"

    override fun toString(): String = "NativeUdpIo($localAddress, $stats)"

    companion object {
        /** Per-datagram slot, rx and tx (matches [ChannelUdpIo]'s 2048-byte buffers). */
        const val SLOT = 2048
        /** Datagrams per `recvmmsg` / per flush. */
        const val RX_BATCH = 64
        const val TX_BATCH = 64
        /** How long [close] waits for the rx thread after waking it. */
        const val CLOSE_JOIN_MS = 500L
        private val WAKE = ByteArray(1)

        /**
         * Whether a native socket bound to `::` also receives/sends IPv4 (v4-mapped): measured once, by sending one
         * datagram from a throwaway `::` socket to a throwaway `127.0.0.1` socket. False if the library is missing,
         * if either bind fails, or if the datagram does not arrive within [DUAL_PROBE_MS].
         */
        val dualStackCapable: Boolean by lazy { probeDualStack() }
        private const val DUAL_PROBE_MS = 500

        private fun probeDualStack(): Boolean {
            if (!NativeLib.available) return false
            return try {
                NativeUdp("127.0.0.1", 0).use { v4 ->
                    NativeUdp("::", 0).use { v6 ->
                        val tx = TxBatch(1, 64)
                        if (!tx.add(java.nio.ByteBuffer.wrap(PROBE), InetSocketAddress("127.0.0.1", v4.localPort))) return false
                        if (v6.sendBatch(tx, 0, 1) != 1) return false
                        val rx = PacketBatch(1, 64)
                        val n = v4.recvBatch(rx, DUAL_PROBE_MS)
                        n == 1 && rx.length(0) == PROBE.size
                    }
                }
            } catch (e: Throwable) { false }
        }

        private val PROBE = ByteArray(8) { 0x2A }
        /**
         * The kernel's limits for one GSO super-datagram, enforced here when runs are cut ([TxBatch.runEnd]) and again in
         * the library (`udp::send_gso` splits anything larger): `UDP_MAX_SEGMENTS` (64 on older Linux kernels, 128 on
         * recent ones) and the 16-bit IP total length minus the IPv6 (40) and UDP (8) headers. A run beyond either
         * limit got EINVAL / EMSGSIZE from `sendmsg`; EMSGSIZE was not in the library's fallback list.
         */
        const val GSO_MAX_SEGMENTS = 64
        const val GSO_MAX_BYTES = 65_535 - 48
        /** Would-block retries of 100 us before the rest of a flush is dropped (the FEC layer absorbs it). */
        const val MAX_BACKOFF = 1000
        /** `-Dtessera.native.gso=auto|on|off` (default auto = where the kernel segments: Linux `UDP_SEGMENT`, Windows USO). */
        const val GSO_PROPERTY = "tessera.native.gso"

        private fun gsoEnabled(): Boolean = when (System.getProperty(GSO_PROPERTY, "auto").lowercase(Locale.ROOT)) {
            "on", "true" -> true
            "off", "false" -> false
            else -> NativeLib.os == "linux" || NativeLib.os == "windows"
        }
    }
}

/**
 * Public, protocol-free handle on a transport socket (benches, tests): every datagram whose first byte has
 * [Wire.F_INITIAL] (0x80) set is handed to the [onDatagram] handler on the socket's rx thread, exactly as handshake
 * packets are, through whichever [UdpIo] implementation was asked for. What [TesseraServer]/[TesseraClient] use
 * internally, minus the connections.
 */
class Datapath private constructor(private val io: UdpIo) : AutoCloseable {
    /** `native` ([NativeUdpIo]) or `channel` ([ChannelUdpIo]). */
    val implementation: String get() = if (io is NativeUdpIo) "native" else "channel"
    val localAddress: InetSocketAddress get() = io.localAddress

    fun onDatagram(handler: (ByteBuffer, InetSocketAddress) -> Unit) { io.onLongHeader = handler }

    /** Sends `buf[position, limit)` to `to`; the position is advanced to the limit (DatagramChannel semantics). */
    fun send(buf: ByteBuffer, to: InetSocketAddress) = io.send(buf, to)

    /** Deferred sends for the calling thread (native only; no-op on the channel path). */
    fun deferSends(defer: Boolean) { (io as? NativeUdpIo)?.deferSends(defer) }
    fun flush() { (io as? NativeUdpIo)?.flush() }

    /** GSO super-datagrams sent so far (native only; 0 on the channel path). */
    val gsoRuns: Long get() = (io as? NativeUdpIo)?.gsoRuns ?: 0L
    val stats: String get() = (io as? NativeUdpIo)?.stats ?: "channel"

    override fun close() = io.close()

    companion object {
        /** `-Dtessera.native=on|off|auto` (default `auto`): native datapath required / never / when the library loads. */
        const val NATIVE_PROPERTY = "tessera.native"

        internal val openNative = AtomicInteger()
        /** Sockets currently open on the native datapath (tests use it to prove which implementation ran). */
        val openNativeSockets: Int get() = openNative.get()

        val nativeAvailable: Boolean get() = NativeLib.available

        /** What [UdpIo.open] picks right now under `-Dtessera.native`; throws for `on` when the library is missing. */
        fun nativeSelected(): Boolean = when (val mode = System.getProperty(NATIVE_PROPERTY, "auto").lowercase(Locale.ROOT)) {
            "on", "true", "require" -> {
                if (!NativeLib.available) throw IllegalStateException("-D$NATIVE_PROPERTY=$mode but tessera_native did not load", NativeLib.loadError)
                true
            }
            "off", "false" -> false
            "auto" -> NativeLib.available
            else -> throw IllegalArgumentException("-D$NATIVE_PROPERTY=$mode: expected on, off or auto")
        }

        /** Opens and starts a socket on the requested implementation (independent of `-Dtessera.native`). */
        fun open(bind: InetSocketAddress, native: Boolean, name: String = "datapath"): Datapath {
            val io: UdpIo = if (native) { Gf256Native.install(); NativeUdpIo(bind, name) } else ChannelUdpIo(bind, name)
            io.start()
            return Datapath(io)
        }
    }
}
