package aether.transport

import aether.core.Wire
import aether.native.Gf256Native
import aether.native.NativeLib
import aether.native.NativeUdp
import aether.native.PacketBatch
import aether.native.SockAddrCache
import aether.native.TxBatch
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport

/**
 * Implementation selection behind [UdpIo.open]: `-Daether.native=on|off|auto` (default `auto`). [NativeUdpIo] when
 * allowed and `aether_native` loaded — installing [Gf256Native] as the process-wide GF(256) kernel on the way, so the
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
 * `-Daether.native.gso=off` disables that; `on` forces the code path elsewhere, where the library segments in user
 * space (same bytes on the wire either way).
 */
internal class NativeUdpIo(bind: InetSocketAddress, name: String, cfg: ConnConfig = ConnConfig()) : UdpIo {
    private class TxState(val batch: TxBatch) { var deferred = false }

    private val sock: NativeUdp
    override val localAddress: InetSocketAddress
    override val pool = BufferPool(64, SLOT)
    override val byShort = ConcurrentHashMap<Int, AetherConnection>()
    override val byConnId = ConcurrentHashMap<Long, AetherConnection>()
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
    /** GSO runs sent so far (each is one super-datagram of >= 2 segments). */
    val gsoRuns: Long get() = gsoRunsOut.sum()
    val dropped: Long get() = droppedOut.sum()

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
    fun flush() = flush(txLocal.get())

    /** Deferred mode for the calling thread: sends queue up and go out on [flush] or when the batch is full. */
    fun deferSends(defer: Boolean) { val t = txLocal.get(); if (!defer) flush(t); t.deferred = defer }

    override fun register(c: AetherConnection) { byShort[c.localShortId] = c }
    override fun unregister(c: AetherConnection) { byShort.remove(c.localShortId, c); byConnId.remove(c.connId.raw, c) }

    private fun flush(t: TxState) {
        val b = t.batch
        if (b.isEmpty) return
        try { drain(b) } catch (e: Exception) { if (running) throw e } finally { b.clear() }
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
            done += sock.sendBatch(b, done, end - done)
            if (done < end && !backoff(waits++)) { droppedOut.add((end - done).toLong()); break }
        }
        datagramsOut.add((done - from).toLong())
    }

    private fun sendRun(b: TxBatch, from: Int, end: Int) {
        gsoRunsOut.increment()
        val n = sock.sendGso(b, from, end)
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
        while (running) {
            try { Thread.sleep(1) } catch (e: InterruptedException) { return }
            val now = AetherConnection.nowUs()
            for (c in byShort.values) c.onTick(now)
            flush(t)
        }
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

    val stats: String get() = "in=$datagramsIn batches=$rxBatches addrMiss=$addressMisses | out=${datagramsOut.sum()} flushes=${flushes.sum()} gsoRuns=$gsoRuns dropped=$dropped gso=${if (gso) "on" else "off"}"

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
        /** `UDP_MAX_SEGMENTS` and `IP_MAX_MTU` minus headers: the kernel's limits for one GSO super-datagram. */
        const val GSO_MAX_SEGMENTS = 64
        const val GSO_MAX_BYTES = 65_000
        /** Would-block retries of 100 us before the rest of a flush is dropped (the FEC layer absorbs it). */
        const val MAX_BACKOFF = 1000
        /** `-Daether.native.gso=auto|on|off` (default auto = where the kernel segments: Linux `UDP_SEGMENT`, Windows USO). */
        const val GSO_PROPERTY = "aether.native.gso"

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
 * packets are, through whichever [UdpIo] implementation was asked for. What [AetherServer]/[AetherClient] use
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
        /** `-Daether.native=on|off|auto` (default `auto`): native datapath required / never / when the library loads. */
        const val NATIVE_PROPERTY = "aether.native"

        internal val openNative = AtomicInteger()
        /** Sockets currently open on the native datapath (tests use it to prove which implementation ran). */
        val openNativeSockets: Int get() = openNative.get()

        val nativeAvailable: Boolean get() = NativeLib.available

        /** What [UdpIo.open] picks right now under `-Daether.native`; throws for `on` when the library is missing. */
        fun nativeSelected(): Boolean = when (val mode = System.getProperty(NATIVE_PROPERTY, "auto").lowercase(Locale.ROOT)) {
            "on", "true", "require" -> {
                if (!NativeLib.available) throw IllegalStateException("-D$NATIVE_PROPERTY=$mode but aether_native did not load", NativeLib.loadError)
                true
            }
            "off", "false" -> false
            "auto" -> NativeLib.available
            else -> throw IllegalArgumentException("-D$NATIVE_PROPERTY=$mode: expected on, off or auto")
        }

        /** Opens and starts a socket on the requested implementation (independent of `-Daether.native`). */
        fun open(bind: InetSocketAddress, native: Boolean, name: String = "datapath"): Datapath {
            val io: UdpIo = if (native) { Gf256Native.install(); NativeUdpIo(bind, name) } else ChannelUdpIo(bind, name)
            io.start()
            return Datapath(io)
        }
    }
}
