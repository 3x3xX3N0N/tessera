package aether.transport

import aether.core.CompactMsg
import aether.core.ConnId
import aether.core.ConnParams
import aether.core.Frame
import aether.core.FrameCodec
import aether.core.PacketHeader
import aether.core.PathEstimator
import aether.core.PathId
import aether.core.ReceiverCredit
import aether.core.Resumption
import aether.core.RlncDecoder
import aether.core.RlncEncoder
import aether.core.Scheduler
import aether.core.SenderCredit
import aether.core.ShortHeader
import aether.core.VarInt
import aether.core.Wire
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/** Tunables for one connection. Defaults are sized for loopback / LAN; see field docs for WAN guidance. */
class ConnConfig(
    val maxDatagram: Int = Wire.MAX_DATAGRAM,
    /** Ack every N ack-eliciting packets (sent to the peer as our ConnParams offer). Gaps always ack immediately. */
    val ackFreq: Int = 2,
    /** RLNC sliding window in source packets. Must cover > 1 RTT of packets for reactive repair to help. */
    val fecWindow: Int = 32,
    /** Packets past a hole before it counts as lost (QUIC uses 3; loopback never reorders). */
    val reorderThreshold: Int = 1,
    /** Floor for proactive repair ratio. */
    val minRedundancy: Double = 0.02,
    /** Packets per loss observation fed to PathEstimator.onLossObservation (core's r=1e-2 assumes ~10-30). */
    val lossObsWindow: Int = 32,
    val maxReactiveRepairsPerAck: Int = 4,
    /** send() blocks up to this long for receiver credit before throwing. */
    val creditWaitMs: Long = 5_000,
    val idleTimeoutMs: Long = 10_000,
    /** Max time an ack-eliciting packet waits for a piggyback before a standalone ack goes out. */
    val ackDelayUs: Long = 1_000,
    val tagLen: Int = 16,
)

/** Counters; written under the connection lock, read via [AetherConnection.stats] (snapshot). */
class ConnStats {
    var packetsSent = 0L; var sourcesSent = 0L; var repairsProactive = 0L; var repairsReactive = 0L; var repairsTlp = 0L
    var acksSent = 0L; var grantsSent = 0L; var simDropped = 0L; var creditStalls = 0L
    var packetsReceived = 0L; var sourcesReceived = 0L; var repairsReceived = 0L; var recovered = 0L
    var acksReceived = 0L; var grantsReceived = 0L; var authFail = 0L; var dups = 0L; var gapsSeen = 0L
    var messagesDelivered = 0L; var unknownPath = 0L
    fun copy(): ConnStats = ConnStats().also { d ->
        d.packetsSent = packetsSent; d.sourcesSent = sourcesSent; d.repairsProactive = repairsProactive; d.repairsReactive = repairsReactive
        d.repairsTlp = repairsTlp; d.acksSent = acksSent; d.grantsSent = grantsSent; d.simDropped = simDropped; d.creditStalls = creditStalls
        d.packetsReceived = packetsReceived; d.sourcesReceived = sourcesReceived; d.repairsReceived = repairsReceived; d.recovered = recovered
        d.acksReceived = acksReceived; d.grantsReceived = grantsReceived; d.authFail = authFail; d.dups = dups; d.gapsSeen = gapsSeen
        d.messagesDelivered = messagesDelivered; d.unknownPath = unknownPath
    }
    val repairsSent get() = repairsProactive + repairsReactive + repairsTlp
    override fun toString() = "sent=$packetsSent src=$sourcesSent repair(pro=$repairsProactive react=$repairsReactive tlp=$repairsTlp) " +
        "acks=$acksSent grants=$grantsSent dropSim=$simDropped | rcvd=$packetsReceived src=$sourcesReceived repairs=$repairsReceived " +
        "recovered=$recovered gaps=$gapsSeen dups=$dups authFail=$authFail msgs=$messagesDelivered stalls=$creditStalls"
}

/**
 * Per-path state. Everything is per packet-number space; the connection owns FEC, messages and crypto.
 * [[MULTIPATH]] A second path is a second instance of this class registered in AetherConnection.paths; the nonce
 * folds the path id into the top byte so PN spaces never collide on an AEAD nonce (identity for path 0, which keeps
 * core's ZeroRtt.nonceFrom layout intact).
 */
internal class PathState(val id: PathId, connNonce: Long) {
    val nonce: Long = connNonce xor (id.raw.toLong() shl 56)
    val estimator = PathEstimator(id)
    val senderCredit = SenderCredit()
    val receiverCredit = ReceiverCredit(estimator)

    // ---- tx ----
    var nextPn = 1L                       // pn 0 = handshake packet (initial / reply) in this direction
    var largestAcked = -1L
    val ringPn = LongArray(RING) { -1L }
    val ringTimeUs = LongArray(RING)
    val ringSize = IntArray(RING)
    val ringKind = ByteArray(RING)
    val ringLo = LongArray(RING)          // source: fec seq; repair: window base
    val ringHi = LongArray(RING)          // repair: window end (exclusive)
    private val ackedBits = LongArray(RING / 64)
    var lossScanPn = 0L
    var lossExpected = 0; var lossLost = 0
    var ackedBytes = 0L
    var lastElicitingSendUs = 0L; var lastElicitingPn = -1L; var tlpCount = 0
    var lastTxUs = 0L

    // ---- rx ----
    var largestSeen = 0L                  // pn 0 (handshake packet) already seen
    private val rxBits = LongArray(RX_BITS / 64).also { it[0] = 1L }
    var elicitingSinceAck = 0
    var ackPendingSinceUs = 0L
    var lastRxUs = 0L
    var avgRxBytes = Wire.MAX_DATAGRAM.toDouble()

    fun ringIdx(pn: Long) = (pn and (RING - 1L)).toInt()
    fun isAcked(pn: Long): Boolean { val i = ringIdx(pn); return ackedBits[i ushr 6] and (1L shl (i and 63)) != 0L }
    fun setAcked(pn: Long) { val i = ringIdx(pn); ackedBits[i ushr 6] = ackedBits[i ushr 6] or (1L shl (i and 63)) }
    fun clearAcked(pn: Long) { val i = ringIdx(pn); ackedBits[i ushr 6] = ackedBits[i ushr 6] and (1L shl (i and 63)).inv() }

    fun rxSeen(pn: Long): Boolean { val i = (pn and (RX_BITS - 1L)).toInt(); return rxBits[i ushr 6] and (1L shl (i and 63)) != 0L }
    fun rxSet(pn: Long) { val i = (pn and (RX_BITS - 1L)).toInt(); rxBits[i ushr 6] = rxBits[i ushr 6] or (1L shl (i and 63)) }
    private fun rxClear(pn: Long) { val i = (pn and (RX_BITS - 1L)).toInt(); rxBits[i ushr 6] = rxBits[i ushr 6] and (1L shl (i and 63)).inv() }
    /** Advance largestSeen, clearing the ring slots the new pns reuse. */
    fun advanceLargest(pn: Long) {
        if (pn - largestSeen >= RX_BITS) java.util.Arrays.fill(rxBits, 0L)
        else { var p = largestSeen + 1; while (p <= pn) { rxClear(p); p++ } }
        largestSeen = pn
    }

    companion object {
        const val RING = 4096
        const val RX_BITS = 2048
    }
}

/**
 * One Aether connection (v0.2): encrypted short-header packets, packet-level systematic RLNC with adaptive
 * proactive redundancy + ack-driven reactive repair, receiver-driven credit, arbitrary-size messages.
 *
 * Delivery semantics: [send] accepts a message of any size; it is fragmented into CompactMsg frames (one fragment
 * per packet) and reassembled on the receiver. [receive] hands back whole messages in **message-completion order**,
 * which is not send order: a later small message whose packet arrived intact is delivered before an earlier message
 * that is still waiting on a repair symbol. Ordering/streams are a library above this, per SPEC.
 *
 * Wire (after the handshake): ShortHeader | AEAD(frames) where frames are
 *   [0x80 02 fecSeq16]   local extension frame marking a FEC source packet (skippable by FrameCodec)
 *   CompactMsg*          from Compact.kt
 *   Ack / Grant / Repair / Ping   from Frames.kt (Ack bytes are written directly in Frame.Ack.write's format)
 * The FEC source symbol is len(2) | plaintext body | zero padding, keyed by fecSeq (contiguous over source packets
 * only, so repair windows stay dense while acks/repairs share the pn space).
 */
class AetherConnection internal constructor(
    private val io: UdpIo,
    @Volatile var peer: InetSocketAddress,
    val sessionKey: ByteArray,
    private val connNonce: Long,
    val isClient: Boolean,
    /** Short conn id the peer must put on packets sent to us. */
    val localShortId: Int,
    peerShortId: Int,
    peerAckFreq: Int,
    val cfg: ConnConfig = ConnConfig(),
) : AutoCloseable {
    internal val crypto = PacketCrypto(sessionKey, isClient, cfg.tagLen)
    @Volatile var peerShortId: Int = peerShortId; internal set
    @Volatile var peerAckFreq: Int = peerAckFreq; internal set
    /** Resumption ticket issued by the server on a fresh connect (client side only). */
    @Volatile var ticket: ByteArray? = null; internal set
    /** Feed this to AetherClient.resume together with [ticket]. */
    val resumptionSecret: ByteArray get() = Resumption.resumptionSecret(sessionKey)
    val connId: ConnId = ConnId(deriveConnId(sessionKey))
    internal val established = CountDownLatch(1)
    /** The handshake packet we sent (initial on the client, reply on the server), retransmitted on demand. */
    @Volatile internal var handshakePacket: ByteBuffer? = null

    private val lock = ReentrantLock()
    private val creditAvailable = lock.newCondition()
    private val paths = arrayOfNulls<PathState>(8)
    private val path0 = PathState(PathId(0), connNonce).also { paths[0] = it }
    private var pathCount = 1
    private val scheduler = Scheduler().apply { add(path0.estimator) }
    /** Path-0 estimator (RTT, Kalman loss, delivery rate) — what fecRedundancy() reads. */
    val estimator: PathEstimator get() = path0.estimator
    internal val path0Nonce: Long get() = path0.nonce

    /** Symbol = len(2) | body; sized so a Repair frame carrying it still fits one datagram. */
    val symbolSize: Int = cfg.maxDatagram - SHORT_HDR_MAX - cfg.tagLen - REPAIR_FRAME_OVERHEAD
    private val bodyMax = symbolSize - 2
    private val enc = RlncEncoder(symbolSize, cfg.fecWindow)
    private var dec = RlncDecoder(symbolSize)
    private var nextFecSeq = 0L
    private var encBase = 0L
    private var repairCredit = 0.0
    private var repairSeed = 0x5A5A
    private var nextMsgId = if (isClient) 1L else 0L     // client msg 0 = the 0-RTT first flight

    // preallocated rx/tx scratch (BC ciphers need heap arrays)
    private val rxScratch = ByteArray(RX_BUF)
    private val rxPlain = ByteArray(RX_BUF)
    private val rxPlainBuf: ByteBuffer = ByteBuffer.wrap(rxPlain)
    private val txScratch = ByteArray(RX_BUF)
    private var largestFecSeen = -1L
    private val deliveredBits = LongArray(DELIVERED_BITS / 64)
    private var decoderEpoch = 0L
    private val reassembly = HashMap<Long, Reassembly>()
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private val missFec = LongArray(2 * SPAN)
    private val repLo = LongArray(2 * SPAN); private val repHi = LongArray(2 * SPAN); private val repUsed = BooleanArray(2 * SPAN)
    private val statsImpl = ConnStats()
    @Volatile private var closed = false
    private var lastRxUs = nowUs(); private var lastTxUs = nowUs()

    /** Fraction of packets to drop on our send side (bench / tests only; pn still advances so the peer sees loss). */
    @Volatile var lossSim = 0.0
    private val lossRnd = java.util.Random(7)

    val isClosed get() = closed
    val stats: ConnStats get() = lock.withLock { statsImpl.copy() }

    // ------------------------------------------------------------------ app API

    /** Blocks only when receiver credit is exhausted (up to cfg.creditWaitMs). Thread-safe. */
    fun send(msg: ByteArray) {
        check(!closed) { "closed" }
        lock.withLock {
            val msgId = nextMsgId++
            var off = 0
            do {
                val hdrCost = 1 + VarInt.size(msgId) + (if (off > 0) VarInt.size(off.toLong()) else 0)
                val chunk = min(msg.size - off, bodyMax - FEC_FRAME_LEN - hdrCost)
                val fin = off + chunk == msg.size
                val path = pickPath(chunk + 40)
                awaitCredit(path, chunk + 40)
                sendSource(path, msgId, off, msg, chunk, fin)
                off += chunk
            } while (off < msg.size)
        }
    }

    /** Next complete message, or null after timeoutMs. */
    fun receive(timeoutMs: Long): ByteArray? = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        lock.withLock { if (closed) return; closed = true; creditAvailable.signalAll() }
        io.unregister(this)
        // [[CLOSE-HOOK]] no CONNECTION_CLOSE frame in core yet; the peer times out idle (cfg.idleTimeoutMs).
    }

    // ------------------------------------------------------------------ tx

    private fun pickPath(bytes: Int): PathState {
        if (pathCount == 1) return path0          // fast path: Scheduler.pick allocates a filtered list
        val id = scheduler.pick(bytes) ?: PathId(0)
        return paths[id.raw] ?: path0
    }

    private fun awaitCredit(path: PathState, bytes: Int) {
        if (path.senderCredit.canSend(bytes)) return
        statsImpl.creditStalls++
        val deadline = System.nanoTime() + cfg.creditWaitMs * 1_000_000L
        while (!path.senderCredit.canSend(bytes)) {
            val left = deadline - System.nanoTime()
            if (closed) throw IllegalStateException("closed")
            if (left <= 0) throw IllegalStateException("no receiver credit after ${cfg.creditWaitMs}ms")
            creditAvailable.awaitNanos(left)
        }
    }

    private fun sendSource(path: PathState, msgId: Long, off: Int, msg: ByteArray, len: Int, fin: Boolean) {
        val buf = io.pool.acquire()
        try {
            val pn = path.nextPn
            ShortHeader.write(buf, path.id, peerShortId, pn, path.largestAcked)
            val hdrEnd = buf.position()
            val fec = nextFecSeq++
            buf.put(FEC_FRAME_TYPE.toByte()).put(2).putShort(fec.toShort())
            // allocates: HeapByteBuffer wrapper + duplicate() inside CompactMsg.write (core API takes a ByteBuffer)
            CompactMsg.write(buf, msgId, 0, off.toLong(), fin, ByteBuffer.wrap(msg, off, len), last = true)
            val bodyEnd = buf.position()
            val bodyLen = bodyEnd - hdrEnd
            // allocates: one symbol per source packet — RlncEncoder.push keeps the reference (core API)
            val sym = ByteArray(symbolSize)
            sym[0] = (bodyLen shr 8).toByte(); sym[1] = bodyLen.toByte()
            buf.get(hdrEnd, sym, 2, bodyLen)
            enc.push(fec, sym)
            encBase = max(encBase, fec - cfg.fecWindow + 1)
            val end = crypto.seal(buf, 0, hdrEnd, bodyEnd, path.nonce, pn, txScratch)
            crypto.protectHeader(buf, 0, hdrEnd) // [[HP-HOOK]]
            buf.limit(end).position(0)
            transmit(path, buf, pn, KIND_SOURCE, fec, fec + 1, eliciting = true, chargeCredit = true)
            statsImpl.sourcesSent++
            // proactive repair: emit one repair symbol per 1/redundancy source symbols (adaptive, floor minRedundancy)
            repairCredit += max(cfg.minRedundancy, path.estimator.fecRedundancy())
            if (repairCredit >= 1.0) { repairCredit -= 1.0; sendRepair(scheduler.repairPathFor(path.id), REPAIR_PROACTIVE) }
        } finally { io.pool.release(buf) }
    }

    private fun sendRepair(pid: PathId, kind: Int): Boolean {
        if (nextFecSeq == 0L) return false
        val path = paths[pid.raw] ?: path0
        val r = enc.repair(++repairSeed * 0x9E3779B1.toInt()) // allocates: Frame.Repair + symbol array (core API)
        val buf = io.pool.acquire()
        try {
            val pn = path.nextPn
            ShortHeader.write(buf, path.id, peerShortId, pn, path.largestAcked)
            val hdrEnd = buf.position()
            r.write(buf)
            val end = crypto.seal(buf, 0, hdrEnd, buf.position(), path.nonce, pn, txScratch)
            crypto.protectHeader(buf, 0, hdrEnd) // [[HP-HOOK]]
            buf.limit(end).position(0)
            transmit(path, buf, pn, KIND_REPAIR, r.windowBase, r.windowBase + r.windowLen, eliciting = true, chargeCredit = true)
            when (kind) { REPAIR_PROACTIVE -> statsImpl.repairsProactive++; REPAIR_REACTIVE -> statsImpl.repairsReactive++; else -> statsImpl.repairsTlp++ }
        } finally { io.pool.release(buf) }
        return true
    }

    private fun sendAck(path: PathState) {
        val buf = io.pool.acquire()
        try {
            val pn = path.nextPn
            ShortHeader.write(buf, path.id, peerShortId, pn, path.largestAcked)
            val hdrEnd = buf.position()
            writeAckFrame(buf, path)
            val end = crypto.seal(buf, 0, hdrEnd, buf.position(), path.nonce, pn, txScratch)
            crypto.protectHeader(buf, 0, hdrEnd) // [[HP-HOOK]]
            buf.limit(end).position(0)
            transmit(path, buf, pn, KIND_CONTROL, 0, 0, eliciting = false, chargeCredit = false)
            path.elicitingSinceAck = 0; path.ackPendingSinceUs = 0
            statsImpl.acksSent++
        } finally { io.pool.release(buf) }
    }

    private fun sendGrant(path: PathState, g: Frame.Grant) {
        val buf = io.pool.acquire()
        try {
            val pn = path.nextPn
            ShortHeader.write(buf, path.id, peerShortId, pn, path.largestAcked)
            val hdrEnd = buf.position()
            g.write(buf)
            val end = crypto.seal(buf, 0, hdrEnd, buf.position(), path.nonce, pn, txScratch)
            crypto.protectHeader(buf, 0, hdrEnd) // [[HP-HOOK]]
            buf.limit(end).position(0)
            transmit(path, buf, pn, KIND_CONTROL, 0, 0, eliciting = false, chargeCredit = false)
            statsImpl.grantsSent++
        } finally { io.pool.release(buf) }
    }

    /**
     * Same bytes Frame.Ack.write produces (0x02 path largest ecnCe rxTimeUs n {first,last}*), generated straight from
     * the receive bitmap so the rx thread allocates nothing. Ranges newest-first, up to MAX_ACK_RANGES over ACK_SPAN pns.
     * [[ACK-TRACKER-HOOK]] a core AckTracker (ECN counts, ack-delay, frequency negotiation) replaces this + PathState.rx*.
     */
    private fun writeAckFrame(buf: ByteBuffer, path: PathState) {
        buf.put(0x02).put(path.id.raw.toByte()).putInt(path.largestSeen.toInt()).putLong(0L).putLong(nowUs())
        val countPos = buf.position(); buf.put(0)
        var n = 0
        var pn = path.largestSeen
        val floor = max(0L, path.largestSeen - ACK_SPAN + 1)
        while (pn >= floor && n < MAX_ACK_RANGES) {
            while (pn >= floor && !path.rxSeen(pn)) pn--
            if (pn < floor) break
            val last = pn
            while (pn >= floor && path.rxSeen(pn)) pn--
            buf.putInt((pn + 1).toInt()).putInt(last.toInt()); n++
        }
        buf.put(countPos, n.toByte())
    }

    private fun transmit(path: PathState, buf: ByteBuffer, pn: Long, kind: Byte, lo: Long, hi: Long, eliciting: Boolean, chargeCredit: Boolean) {
        val now = nowUs()
        val i = path.ringIdx(pn)
        path.ringPn[i] = pn; path.ringTimeUs[i] = now; path.ringSize[i] = buf.remaining(); path.ringKind[i] = kind
        path.ringLo[i] = lo; path.ringHi[i] = hi
        path.clearAcked(pn)
        path.nextPn = pn + 1
        if (chargeCredit) path.senderCredit.onSent(buf.remaining())
        if (eliciting) { path.lastElicitingSendUs = now; path.lastElicitingPn = pn }
        path.lastTxUs = now; lastTxUs = now
        statsImpl.packetsSent++
        if (lossSim > 0.0 && lossRnd.nextDouble() < lossSim) { statsImpl.simDropped++; return }
        io.send(buf, peer)
    }

    // ------------------------------------------------------------------ rx (called on the endpoint's rx thread)

    /** `buf` = whole datagram, position 0, limit = length. Header not yet unprotected. */
    internal fun onShortPacket(buf: ByteBuffer, from: InetSocketAddress) {
        lock.withLock {
            if (closed) return
            val len = buf.limit()
            crypto.unprotectHeader(buf, 0) // [[HP-HOOK]]
            // zero-alloc mirror of ShortHeader.read (same bit layout; Parsed is a data class)
            val flags = buf.get(0).toInt() and 0xFF
            val pnLen = ((flags shr 5) and 3) + 1
            val path = paths[(flags shr 2) and 7] ?: run { statsImpl.unknownPath++; return }
            // [[PATH-VALIDATION-HOOK]] `from` != peer on a known path, or a packet on an unregistered path id, should
            // queue Frame.PathChallenge(path, nonce) and only switch `peer` once the echoed nonce comes back.
            val hdrEnd = 5 + pnLen
            if (len < hdrEnd + cfg.tagLen) return
            var trunc = 0L
            for (i in 5 until hdrEnd) trunc = (trunc shl 8) or (buf.get(i).toLong() and 0xFF)
            val pn = ShortHeader.decodePn(trunc, pnLen * 8, path.largestSeen)
            if (pn < 0 || pn <= path.largestSeen - PathState.RX_BITS) return
            val n = crypto.open(buf, 0, hdrEnd, len, path.nonce, pn, rxScratch, rxPlain)
            if (n < 0) { statsImpl.authFail++; return }
            val now = nowUs()
            var gap = false; var late = false
            if (pn > path.largestSeen) {
                if (pn > path.largestSeen + 1) {
                    gap = true
                    val missing = pn - path.largestSeen - 1
                    statsImpl.gapsSeen += missing
                    // lost bytes are no longer in flight: hand the credit back (Homa does the same by timeout)
                    path.receiverCredit.onReceived((missing * path.avgRxBytes).toInt())
                }
                path.advanceLargest(pn)
            } else if (path.rxSeen(pn)) { statsImpl.dups++; return } else late = true
            path.rxSet(pn)
            path.lastRxUs = now; lastRxUs = now
            statsImpl.packetsReceived++
            rxPlainBuf.limit(n).position(0)
            val eliciting = parseFrames(rxPlainBuf, path, n, recovered = false)
            if (eliciting) {
                path.receiverCredit.onReceived(len)
                path.avgRxBytes = 0.9 * path.avgRxBytes + 0.1 * len
                path.elicitingSinceAck++
                if (path.ackPendingSinceUs == 0L) path.ackPendingSinceUs = now
                if (gap || late || path.elicitingSinceAck >= peerAckFreq) sendAck(path)
                // grants are never delayed: check inline too, so a coarse timer (Windows sleep(1) ~ 15ms) can't starve the sender
                path.receiverCredit.tick()?.let { sendGrant(path, it) }
            } else if ((gap || late) && path.elicitingSinceAck > 0) sendAck(path)
        }
    }

    /** Returns whether the packet was ack-eliciting. `bodyLen` = plaintext length (for the FEC symbol copy). */
    private fun parseFrames(buf: ByteBuffer, path: PathState, bodyLen: Int, recovered: Boolean): Boolean {
        var prevMsg = 0L; var eliciting = false; var skipMsgs = false
        while (buf.hasRemaining()) {
            val t = buf.get(buf.position()).toInt() and 0xFF
            when {
                t == 0 -> break // padding (recovered symbols are zero-padded)
                t and 0xF8 == 0x10 -> {
                    val m = CompactMsg.read(buf, prevMsg) // allocates Frame.Msg + slice (core API)
                    prevMsg = m.msgId; eliciting = true
                    if (!skipMsgs) onMsgFrame(m)
                }
                t == FEC_FRAME_TYPE -> {
                    buf.position(buf.position() + 2)
                    val fec = ShortHeader.decodePn(buf.getShort().toLong() and 0xFFFF, 16, largestFecSeen)
                    eliciting = true
                    if (!recovered) {
                        statsImpl.sourcesReceived++
                        if (isDelivered(fec)) skipMsgs = true // already recovered via a repair symbol
                        else { storeSource(fec, bodyLen); markDelivered(fec) }
                    }
                }
                t == 0x02 -> onAck(FrameCodec.read(buf) as Frame.Ack) // allocates Ack + ranges (core API); every ackFreq pkts
                t == 0x03 -> {
                    val g = FrameCodec.read(buf) as Frame.Grant
                    paths[g.path.raw and 7]?.senderCredit?.onGrant(g)
                    statsImpl.grantsReceived++; creditAvailable.signalAll()
                }
                t == 0x04 -> { onRepair(FrameCodec.read(buf) as Frame.Repair, path); eliciting = true }
                t == 0x06 -> { buf.get(); eliciting = true }
                else -> { FrameCodec.read(buf) ?: break; eliciting = true } // PathChallenge / unknown ext: [[PATH-VALIDATION-HOOK]]
            }
        }
        return eliciting
    }

    private fun storeSource(fec: Long, bodyLen: Int) {
        if (fec > largestFecSeen) advanceFec(fec)
        if (fec - decoderEpoch >= DECODER_ROTATE) rotateDecoder(fec)
        // allocates: one symbol per received source — RlncDecoder.onSource keeps the reference (core API)
        val sym = ByteArray(symbolSize)
        sym[0] = (bodyLen shr 8).toByte(); sym[1] = bodyLen.toByte()
        System.arraycopy(rxPlain, 0, sym, 2, min(bodyLen, symbolSize - 2))
        dec.onSource(fec, sym)
    }

    /** RlncDecoder.known never evicts (core); start a fresh decoder every DECODER_ROTATE seqs, re-feeding the live window. */
    private fun rotateDecoder(fec: Long) {
        val old = dec
        dec = RlncDecoder(symbolSize)
        var s = max(0L, fec - cfg.fecWindow)
        while (s < fec) { old.get(s)?.let { dec.onSource(s, it) }; s++ }
        decoderEpoch = fec
    }

    private fun onRepair(r: Frame.Repair, path: PathState) {
        statsImpl.repairsReceived++
        if (r.windowLen <= 0 || r.windowLen > 4 * cfg.fecWindow) return
        if (r.windowBase + r.windowLen - 1 - decoderEpoch >= DECODER_ROTATE) rotateDecoder(r.windowBase + r.windowLen - 1)
        dec.onRepair(r)
        for (i in 0 until r.windowLen) {
            val s = r.windowBase + i
            if (isDelivered(s)) continue
            val sym = dec.get(s) ?: continue
            if (s > largestFecSeen) advanceFec(s)
            markDelivered(s)
            statsImpl.recovered++
            val len = ((sym[0].toInt() and 0xFF) shl 8) or (sym[1].toInt() and 0xFF)
            if (len in 1..(symbolSize - 2)) parseFrames(ByteBuffer.wrap(sym, 2, len), path, len, recovered = true)
        }
    }

    private fun isDelivered(fec: Long): Boolean {
        if (fec > largestFecSeen) return false
        if (fec <= largestFecSeen - DELIVERED_BITS) return true
        val i = (fec and (DELIVERED_BITS - 1L)).toInt()
        return deliveredBits[i ushr 6] and (1L shl (i and 63)) != 0L
    }
    private fun markDelivered(fec: Long) { val i = (fec and (DELIVERED_BITS - 1L)).toInt(); deliveredBits[i ushr 6] = deliveredBits[i ushr 6] or (1L shl (i and 63)) }
    private fun advanceFec(fec: Long) {
        if (fec - largestFecSeen >= DELIVERED_BITS) java.util.Arrays.fill(deliveredBits, 0L)
        else { var s = largestFecSeen + 1; while (s <= fec) { val i = (s and (DELIVERED_BITS - 1L)).toInt(); deliveredBits[i ushr 6] = deliveredBits[i ushr 6] and (1L shl (i and 63)).inv(); s++ } }
        largestFecSeen = fec
    }

    private fun onMsgFrame(m: Frame.Msg) {
        val len = m.data.remaining()
        if (m.offset == 0 && m.fin) { // single-fragment fast path: one copy, which the app owns
            val b = ByteArray(len); m.data.get(b); deliver(b); return
        }
        val r = reassembly.getOrPut(m.msgId) { Reassembly() }
        if (r.add(m.offset, m.data, m.fin)) { reassembly.remove(m.msgId); deliver(r.bytes()) }
    }

    internal fun deliver(b: ByteArray) { statsImpl.messagesDelivered++; inbox.put(b) }

    // ------------------------------------------------------------------ ack processing (sender side)

    private fun onAck(a: Frame.Ack) {
        val path = paths[a.path.raw and 7] ?: return
        val now = nowUs()
        statsImpl.acksReceived++
        val largest = a.largest
        var newlyAcked = 0L
        for (r in a.ranges) {
            var pn = max(r.first, largest - SPAN + 1).coerceAtLeast(0)
            val end = min(r.last, largest)
            while (pn <= end) {
                val i = path.ringIdx(pn)
                if (path.ringPn[i] == pn && !path.isAcked(pn)) { path.setAcked(pn); newlyAcked += path.ringSize[i] }
                pn++
            }
        }
        if (largest > path.largestAcked) {
            val i = path.ringIdx(largest)
            if (path.ringPn[i] == largest && path.ringKind[i] != KIND_CONTROL) path.estimator.onRttSample(now - path.ringTimeUs[i])
            path.largestAcked = largest; path.tlpCount = 0
        }
        if (newlyAcked > 0) { path.ackedBytes += newlyAcked; path.estimator.onDelivered(path.ackedBytes, now) }
        path.senderCredit.onAck(a)
        // loss classification: every pn in (lossScanPn, largest - reorderThreshold] is acked or lost, exactly once
        val upto = largest - cfg.reorderThreshold
        var pn = max(path.lossScanPn + 1, largest - SPAN + 1)
        while (pn <= upto) {
            val i = path.ringIdx(pn)
            if (path.ringPn[i] == pn) { path.lossExpected++; if (!path.isAcked(pn)) path.lossLost++ }
            pn++
        }
        if (upto > path.lossScanPn) path.lossScanPn = upto
        if (path.lossExpected >= cfg.lossObsWindow) {
            path.estimator.onLossObservation(path.lossLost.toDouble() / path.lossExpected)
            path.lossExpected = 0; path.lossLost = 0
        }
        repairDeficit(path, largest)
    }

    /**
     * Reactive repair (RACK-style residual ARQ, but the retransmission is a repair symbol): the receiver needs one
     * independent equation per missing source in the window. Count missing sources (not acked, past the reorder
     * threshold, still inside the encoder window) against repairs that were acked or are still in flight and whose
     * window covers them; send the difference. Lost repairs fall out of the "have" side automatically.
     */
    private fun repairDeficit(path: PathState, largest: Long) {
        val thr = cfg.reorderThreshold
        var nMiss = 0; var nRep = 0
        var pn = max(0L, largest - SPAN + 1)
        val hiPn = min(path.nextPn - 1, largest + SPAN)
        while (pn <= hiPn && nMiss < missFec.size && nRep < repLo.size) {
            val i = path.ringIdx(pn)
            if (path.ringPn[i] == pn) {
                when (path.ringKind[i]) {
                    KIND_SOURCE -> if (pn <= largest - thr && !path.isAcked(pn) && path.ringLo[i] >= encBase) missFec[nMiss++] = path.ringLo[i]
                    KIND_REPAIR -> if (path.isAcked(pn) || pn > largest - thr) { repLo[nRep] = path.ringLo[i]; repHi[nRep] = path.ringHi[i]; repUsed[nRep] = false; nRep++ }
                }
            }
            pn++
        }
        if (nMiss == 0) return
        var deficit = 0
        for (m in 0 until nMiss) {
            val f = missFec[m]; var matched = false
            for (r in 0 until nRep) if (!repUsed[r] && f >= repLo[r] && f < repHi[r]) { repUsed[r] = true; matched = true; break }
            if (!matched) deficit++
        }
        var k = min(deficit, cfg.maxReactiveRepairsPerAck)
        while (k-- > 0) sendRepair(scheduler.repairPathFor(path.id), REPAIR_REACTIVE)
    }

    // ------------------------------------------------------------------ timer (endpoint thread, ~1ms)

    internal fun onTick(now: Long) {
        lock.withLock {
            if (closed) return
            for (p in paths) {
                p ?: continue
                if (p.ackPendingSinceUs != 0L && now - p.ackPendingSinceUs >= cfg.ackDelayUs) sendAck(p)
                p.receiverCredit.tick()?.let { sendGrant(p, it) }
                // tail-loss probe: nothing acked for a loss timeout after our last eliciting packet -> repair symbol
                if (p.lastElicitingPn > p.largestAcked && p.tlpCount < 3 && nextFecSeq > 0 &&
                    now - p.lastElicitingSendUs > max(p.estimator.lossTimeoutUs(), 2 * cfg.ackDelayUs)) {
                    p.tlpCount++; sendRepair(p.id, REPAIR_TLP)
                }
            }
            if (now - max(lastRxUs, lastTxUs) > cfg.idleTimeoutMs * 1000) { closed = true; creditAvailable.signalAll(); io.unregister(this) }
        }
    }

    // ------------------------------------------------------------------ handshake helpers

    /** Server: encrypted reply = ConnParams(shortConnId for the client to use, ackFreq) | ticketLen(2) | ticket. */
    internal fun buildHandshakeReply(params: ConnParams, ticket: ByteArray?): ByteBuffer {
        val buf = ByteBuffer.allocate(Wire.MAX_DATAGRAM)
        PacketHeader(Wire.F_INITIAL or Wire.F_HANDSHAKE, connId, PathId(0), 0).write(buf)
        val hdrEnd = buf.position()
        params.write(buf)
        buf.putShort((ticket?.size ?: 0).toShort()); ticket?.let { buf.put(it) }
        val end = crypto.seal(buf, 0, hdrEnd, buf.position(), path0.nonce, 0, txScratch)
        buf.limit(end).position(0)
        handshakePacket = buf
        return buf
    }

    /** Client: decrypt the server reply; returns false if it does not authenticate. */
    internal fun onHandshakeReply(buf: ByteBuffer): Boolean = lock.withLock {
        if (established.count == 0L) return true
        val n = crypto.open(buf, 0, Wire.HEADER_LEN, buf.limit(), path0.nonce, 0, rxScratch, rxPlain)
        if (n < 0) { statsImpl.authFail++; return false }
        val pb = ByteBuffer.wrap(rxPlain, 0, n)
        val p = ConnParams.read(pb)
        val tl = pb.getShort().toInt() and 0xFFFF
        if (tl > 0) ticket = ByteArray(tl).also { pb.get(it) }
        peerShortId = p.shortConnId; peerAckFreq = p.ackFreq
        lastRxUs = nowUs()
        established.countDown()
        true
    }

    internal fun registerPath(p: PathState) { paths[p.id.raw] = p; scheduler.add(p.estimator); pathCount++ } // [[MULTIPATH]]

    private class Reassembly {
        private var buf = ByteArray(0)
        private val ranges = TreeMap<Int, Int>() // start -> end (exclusive), non-overlapping
        private var total = -1
        /** Returns true when the message is complete. */
        fun add(offset: Int, data: ByteBuffer, fin: Boolean): Boolean {
            val len = data.remaining(); val end = offset + len
            if (fin) total = end
            if (buf.size < end) buf = buf.copyOf(if (total > 0) total else max(end, buf.size * 2))
            data.get(buf, offset, len)
            var s = offset; var e = end
            val lo = ranges.floorEntry(s); if (lo != null && lo.value >= s) { s = lo.key; e = max(e, lo.value); ranges.remove(lo.key) }
            while (true) { val nx = ranges.ceilingEntry(s) ?: break; if (nx.key > e) break; e = max(e, nx.value); ranges.remove(nx.key) }
            ranges[s] = e
            return total >= 0 && ranges.size == 1 && ranges.firstKey() == 0 && ranges.firstEntry().value >= total
        }
        fun bytes(): ByteArray = if (buf.size == total) buf else buf.copyOf(total)
    }

    companion object {
        const val SHORT_HDR_MAX = 1 + 4 + 4
        const val REPAIR_FRAME_OVERHEAD = 1 + 8 + 2 + 4 + 2
        const val FEC_FRAME_TYPE = 0x80
        const val FEC_FRAME_LEN = 4
        const val RX_BUF = 2048
        const val SPAN = 64
        const val ACK_SPAN = 128
        const val MAX_ACK_RANGES = 16
        const val DELIVERED_BITS = 4096
        const val DECODER_ROTATE = 4096L
        const val KIND_CONTROL: Byte = 0; const val KIND_SOURCE: Byte = 1; const val KIND_REPAIR: Byte = 2
        const val REPAIR_PROACTIVE = 0; const val REPAIR_REACTIVE = 1; const val REPAIR_TLP = 2

        fun nowUs(): Long = System.nanoTime() / 1000
        /** 64-bit ConnId = first 8 bytes of HKDF(sessionKey, "connid") — derived from the key without exposing key bytes. */
        fun deriveConnId(sessionKey: ByteArray): Long = ByteBuffer.wrap(PacketCrypto.hkdf(sessionKey, "aether-v0.2 connid")).getLong()
    }
}
