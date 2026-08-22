package aether.transport

import aether.core.AckTracker
import aether.core.CompactMsg
import aether.core.ConnId
import aether.core.ConnParams
import aether.core.CubicCc
import aether.core.Frame
import aether.core.FrameCodec
import aether.core.HandshakeKind
import aether.core.HybridCc
import aether.core.NoopTracer
import aether.core.PacketHeader
import aether.core.PacketProtection
import aether.core.PathEstimator
import aether.core.PathId
import aether.core.PathValidation
import aether.core.PayloadCodec
import aether.core.Pmtud
import aether.core.ReceiverCredit
import aether.core.Resumption
import aether.core.RlncDecoder
import aether.core.RlncEncoder
import aether.core.Scheduler
import aether.core.SenderCredit
import aether.core.ShortHeader
import aether.core.Tracer
import aether.core.VarInt
import aether.core.Wire
import aether.core.ZstdDictCodec
import aether.core.grantIssued
import aether.core.handshake
import aether.core.metrics
import aether.core.packetLost
import aether.core.packetReceived
import aether.core.packetSent
import aether.core.pathAdded
import aether.core.repairDecoded
import aether.core.repairSent
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
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
    /** Upper bound for DPLPMTUD (offered in ConnParams; the negotiated value is the smaller of both sides'). */
    val maxDatagram: Int = Wire.MAX_DATAGRAM,
    /** Ack every N ack-eliciting packets (sent to the peer as our ConnParams offer). Gaps always ack immediately. */
    val ackFreq: Int = 2,
    /** RLNC sliding window in source packets. Must cover > 1 RTT of packets for reactive repair to help. */
    val fecWindow: Int = 32,
    /** Packets past a hole before a rank-deficit repair is sent (QUIC uses 3; loopback never reorders). */
    val reorderThreshold: Int = 1,
    /** Floor for proactive repair ratio. */
    val minRedundancy: Double = 0.02,
    /** Packets per loss observation fed to PathEstimator.onLossObservation (core's r=1e-2 assumes ~10-30). */
    val lossObsWindow: Int = 32,
    val maxReactiveRepairsPerAck: Int = 4,
    /** send() blocks up to this long for receiver credit / cwnd / path validation before throwing. */
    val creditWaitMs: Long = 5_000,
    val idleTimeoutMs: Long = 10_000,
    /** Max time an ack-eliciting packet waits for a piggyback before a standalone ack goes out. */
    val ackDelayUs: Long = 1_000,
    /** AEAD tag length we are willing to use: 8 is negotiated only when both sides offer 8. */
    val tagLen: Int = 16,
    /** Shared zstd dictionary for message payloads; applied iff the peer holds the same one (ConnParams.dictId). */
    val dictionary: ByteArray? = null,
    /** qlog-style tracer; NoopTracer costs a field load per event. */
    val tracer: Tracer = NoopTracer,
    /** Run DPLPMTUD (padded Ping probes) once the path is validated. */
    val pmtud: Boolean = true,
    /**
     * A declared loss is a congestion signal for the CUBIC fallback only when it coincides with queueing delay:
     * srtt - min_rtt > max(this, min_rtt/4). Random loss on an uncongested path is FEC's job (SPEC: the loss-based
     * fallback exists for fairness with CUBIC on shared bottlenecks). 0 = every loss counts.
     */
    val ccLossDelayGateUs: Long = 2_000,
    /** Tail repair timer T = clamp(srtt/8, min, max): a repair follows a source packet that no other source followed within T. */
    val tailRepairMinUs: Long = 500,
    val tailRepairMaxUs: Long = 5_000,
    /** Receiver re-sends its last grant after max(2*srtt, this) without an ack-eliciting packet (backoff, capped). */
    val grantResendMinUs: Long = 25_000,
    /** Sender with exhausted credit probes for a grant after max(2*srtt, this) without one (backoff, capped). */
    val creditProbeMinUs: Long = 50_000,
    val probeBackoffMaxUs: Long = 1_000_000,
) {
    init {
        require(tagLen == 8 || tagLen == 16) { "tagLen must be 8 or 16, got $tagLen" }
        require(maxDatagram in AetherConnection.MIN_DATAGRAM..AetherConnection.MAX_SUPPORTED_DATAGRAM) { "maxDatagram $maxDatagram" }
        require(fecWindow in 1..AetherConnection.MAX_FEC_WINDOW) { "fecWindow $fecWindow" }
    }
    /** One codec per config, shared by its connections (thread-safe; digesting the dictionary is the expensive part). */
    val codec: ZstdDictCodec? by lazy { dictionary?.let { ZstdDictCodec(it) } }
    val dictId: Long by lazy { dictionary?.let { ZstdDictCodec.dictIdOf(it) } ?: 0L }
}

/** Counters; written under the connection lock, read via [AetherConnection.stats] (snapshot). */
class ConnStats {
    var packetsSent = 0L; var sourcesSent = 0L; var repairsProactive = 0L; var repairsReactive = 0L; var repairsTlp = 0L; var repairsTail = 0L
    var sourceResends = 0L; var acksSent = 0L; var grantsSent = 0L; var grantResends = 0L; var simDropped = 0L
    var probesSent = 0L; var probesLost = 0L; var probeBytesSent = 0L; var creditProbes = 0L
    var challengesSent = 0L; var responsesSent = 0L; var replyResends = 0L
    var creditStalls = 0L; var cwndStalls = 0L; var ampStalls = 0L; var ampLimited = 0L
    var bytesSent = 0L; var sourceBytesSent = 0L; var maxDatagramSent = 0; var oversized = 0L
    var payloadBytesIn = 0L; var codecBytesOut = 0L; var codecErrors = 0L
    var packetsReceived = 0L; var sourcesReceived = 0L; var repairsReceived = 0L; var recovered = 0L; var bytesReceived = 0L
    var acksReceived = 0L; var grantsReceived = 0L; var authFail = 0L; var dups = 0L; var gapsSeen = 0L
    var messagesDelivered = 0L; var unknownPath = 0L; var migrations = 0L
    var keyUpdates = 0L; var keyUpdatesFollowed = 0L; var lossesDetected = 0L; var ccLossEvents = 0L; var ccLossIgnored = 0L
    // snapshot-only fields (filled by AetherConnection.stats)
    var ccMode = "UNLIMITED"; var cwndLimited = 0L; var grantLimited = 0L; var cwnd = 0L
    var plpmtu = 0; var pmtudState = ""; var tagLen = 0; var dictId = 0L; var keyGeneration = 0; var pathValidated = false

    fun copy(): ConnStats = ConnStats().also { d ->
        d.packetsSent = packetsSent; d.sourcesSent = sourcesSent; d.repairsProactive = repairsProactive; d.repairsReactive = repairsReactive
        d.repairsTlp = repairsTlp; d.repairsTail = repairsTail; d.sourceResends = sourceResends; d.acksSent = acksSent; d.grantsSent = grantsSent
        d.grantResends = grantResends; d.simDropped = simDropped; d.probesSent = probesSent; d.probesLost = probesLost; d.probeBytesSent = probeBytesSent
        d.creditProbes = creditProbes; d.challengesSent = challengesSent; d.responsesSent = responsesSent; d.replyResends = replyResends
        d.creditStalls = creditStalls; d.cwndStalls = cwndStalls; d.ampStalls = ampStalls; d.ampLimited = ampLimited
        d.bytesSent = bytesSent; d.sourceBytesSent = sourceBytesSent; d.maxDatagramSent = maxDatagramSent; d.oversized = oversized
        d.payloadBytesIn = payloadBytesIn; d.codecBytesOut = codecBytesOut; d.codecErrors = codecErrors
        d.packetsReceived = packetsReceived; d.sourcesReceived = sourcesReceived; d.repairsReceived = repairsReceived; d.recovered = recovered
        d.bytesReceived = bytesReceived; d.acksReceived = acksReceived; d.grantsReceived = grantsReceived; d.authFail = authFail; d.dups = dups
        d.gapsSeen = gapsSeen; d.messagesDelivered = messagesDelivered; d.unknownPath = unknownPath; d.migrations = migrations
        d.keyUpdates = keyUpdates; d.keyUpdatesFollowed = keyUpdatesFollowed; d.lossesDetected = lossesDetected; d.ccLossEvents = ccLossEvents
        d.ccLossIgnored = ccLossIgnored
        d.ccMode = ccMode; d.cwndLimited = cwndLimited; d.grantLimited = grantLimited; d.cwnd = cwnd; d.plpmtu = plpmtu; d.pmtudState = pmtudState
        d.tagLen = tagLen; d.dictId = dictId; d.keyGeneration = keyGeneration; d.pathValidated = pathValidated
    }
    val repairsSent get() = repairsProactive + repairsReactive + repairsTlp + repairsTail
    override fun toString() = "sent=$packetsSent src=$sourcesSent repair(pro=$repairsProactive react=$repairsReactive tlp=$repairsTlp tail=$repairsTail) " +
        "acks=$acksSent grants=$grantsSent(+$grantResends re) probes=$probesSent dropSim=$simDropped bytes=$bytesSent | " +
        "rcvd=$packetsReceived src=$sourcesReceived repairs=$repairsReceived recovered=$recovered gaps=$gapsSeen dups=$dups authFail=$authFail " +
        "msgs=$messagesDelivered bytes=$bytesReceived | stalls(credit=$creditStalls cwnd=$cwndStalls amp=$ampStalls) lost=$lossesDetected " +
        "ccLoss=$ccLossEvents/${ccLossEvents + ccLossIgnored} migrations=$migrations keyUpdates=$keyUpdates | " +
        "ccMode=$ccMode cwnd=$cwnd plpmtu=$plpmtu($pmtudState) tagLen=$tagLen dictId=$dictId"
}

/**
 * Per-path state: one packet-number space, one [AckTracker] (both directions), one [HybridCc], one [PathValidation],
 * one [Pmtud]. The connection owns FEC, messages and crypto.
 * [[MULTIPATH]] A second path is a second instance registered in AetherConnection.paths; [pnMask] folds the path id
 * into the top byte of the nonce packet number so PN spaces never collide on an AEAD nonce (identity for path 0).
 */
internal class PathState(val id: PathId, address: InetSocketAddress) {
    val pnMask: Long = id.raw.toLong() shl 56
    /** What fecRedundancy(), the scheduler, CC and the tracer read. */
    val estimator = PathEstimator(id)
    /** Owned by the AckTracker, whose RACK timer reads its RTT; loss observations for [estimator] are aggregated per
     *  ConnConfig.lossObsWindow from AckResults instead of per ack (core's Kalman r assumes 10-30 packet windows). */
    val shadow = PathEstimator(id)
    val senderCredit = SenderCredit()
    val receiverCredit = ReceiverCredit(estimator)
    val pv = PathValidation(id, RNG, address)
    lateinit var tracker: AckTracker
    lateinit var cc: HybridCc
    lateinit var pmtud: Pmtud

    fun setup(peerAckFreq: Int, ackDelayUs: Long, maxDatagram: Int, nowUs: Long) {
        tracker = AckTracker(shadow, peerAckFreq, ackDelayUs)
        cc = HybridCc(estimator, senderCredit, CubicCc(maxDatagram))
        val base = min(BASE_PLPMTU, maxDatagram)
        pmtud = Pmtud(basePlpmtu = base, maxPlpmtu = maxDatagram, minPlpmtu = base)
        lastGrantRxUs = nowUs; setupUs = nowUs
    }

    // ---- tx ----
    var nextPn = 1L                       // pn 0 = handshake packet (initial / reply) in this direction
    val ringPn = LongArray(RING) { -1L }
    val ringTimeUs = LongArray(RING)
    val ringSize = IntArray(RING)
    val ringKind = ByteArray(RING)
    val ringLo = LongArray(RING)          // source: fec seq; repair: window base
    val ringHi = LongArray(RING)          // repair: window end (exclusive)
    private val ackedBits = LongArray(RING / 64)
    var lossExpected = 0; var lossLost = 0
    var lastElicitingSendUs = 0L; var lastDataPn = -1L; var tlpBackoff = 0
    var lastSourceSendUs = 0L; var lastRepairSendUs = 0L; var tailArmed = false
    var lastGrantRxUs = 0L; var lastCreditProbeUs = 0L; var creditProbeBackoffUs = 0L
    var setupUs = 0L; var lastCreditTickUs = 0L
    var waitBytes = 0
    var lastTxUs = 0L

    // ---- rx ----
    var largestSeen = 0L                  // pn 0 (handshake packet) already seen; reference for truncated-pn decoding
    private val rxBits = LongArray(RX_BITS / 64).also { it[0] = 1L }   // anti-replay window (AckTracker keeps ranges, not a set)
    var lastRxUs = 0L; var lastElicitingRxUs = 0L
    var avgRxBytes = Wire.MAX_DATAGRAM.toDouble()
    var lastGrant: Frame.Grant? = null
    var lastGrantResendUs = 0L; var grantResendBackoffUs = 0L; var grantResendsSinceRx = 0
    var lastChallengeUs = 0L; var challengeBackoffUs = 0L

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
        const val BASE_PLPMTU = 1200
        val RNG = SecureRandom()
    }
}

/**
 * One Aether connection (v0.3): header-protected short packets with key-phase updates, packet-level systematic RLNC
 * with adaptive proactive redundancy + ack-driven reactive repair + time-bound tail repair, receiver-driven credit
 * arbitrated with a CUBIC fallback ([HybridCc]), DPLPMTUD-driven datagram size, address validation / migration,
 * optional shared-dictionary payload codec, qlog tracing, arbitrary-size messages.
 *
 * Delivery semantics: [send] accepts a message of any size; it is fragmented into CompactMsg frames (one fragment
 * per packet) and reassembled on the receiver. [receive] hands back whole messages in **message-completion order**,
 * which is not send order: a later small message whose packet arrived intact is delivered before an earlier message
 * that is still waiting on a repair symbol. Ordering/streams are a library above this, per SPEC.
 *
 * Wire (after the handshake): ShortHeader(flags | shortConnId(4) | pn(1..4, from the flags)) | AEAD(frames) where
 *   [0x80 02 fecSeq16]   local extension frame marking a FEC source packet (skippable by FrameCodec)
 *   [0x81 len zeros]     Frame.Padding (header-protection sample, PMTUD probes)
 *   [0x82 00]            local extension frame: credit probe (ack-eliciting; the receiver re-sends its last grant)
 *   CompactMsg*          from Compact.kt
 *   Ack / Grant / Repair / Ping / PathChallenge / PathResponse   from Frames.kt / PathValidation.kt
 * The FEC source symbol is len(2) | plaintext body | zero padding, keyed by fecSeq (contiguous over source packets
 * only, so repair windows stay dense while acks/repairs share the pn space). Repair symbols are trimmed to the largest
 * body in their window (trailing bytes are zero in every source, hence in the combination; the decoder zero-extends),
 * which is what lets the symbol size be fixed at the negotiated maxDatagram while datagrams follow the PLPMTU.
 *
 * Per-packet allocations on the hot path (all via core APIs): AckTracker.onPacketSent (one Sent + TreeMap node per
 * packet), AckTracker.onAck (result lists per ack), Frame.Ack + ranges per ack sent, one symbol array per source
 * packet (kept by RlncEncoder/RlncDecoder), Frame.Repair + symbol per repair, CompactMsg's ByteBuffer wrappers.
 * Everything else (crypto, header protection, rings, padding, tracing when disabled) allocates nothing.
 */
class AetherConnection internal constructor(
    io: UdpIo,
    @Volatile var peer: InetSocketAddress,
    val sessionKey: ByteArray,
    val isClient: Boolean,
    /** Short conn id the peer must put on packets sent to us. */
    val localShortId: Int,
    val cfg: ConnConfig = ConnConfig(),
) : AutoCloseable {
    @Volatile private var io: UdpIo = io
    internal val crypto = PacketCrypto(sessionKey, isClient)
    private val tracer: Tracer = cfg.tracer
    @Volatile var peerShortId: Int = 0; internal set
    @Volatile var peerAckFreq: Int = cfg.ackFreq; internal set
    /** Resumption ticket issued by the server on a fresh connect (client side only). */
    @Volatile var ticket: ByteArray? = null; internal set
    /** Feed this to AetherClient.resume together with [ticket]. */
    val resumptionSecret: ByteArray get() = Resumption.resumptionSecret(sessionKey)
    val connId: ConnId = ConnId(deriveConnId(sessionKey))
    internal val established = CountDownLatch(1)
    /** The handshake packet we sent (initial on the client, reply on the server), retransmitted on demand. */
    @Volatile internal var handshakePacket: ByteBuffer? = null
    /** Client: what we offered as dictId (the reply must echo it for the codec to engage). */
    internal var offeredDictId: Long = 0L
    internal var handshakeKind: HandshakeKind = HandshakeKind.PQ
    internal var zeroRttBytes: Int = 0

    private val lock = ReentrantLock()
    private val creditAvailable = lock.newCondition()
    private val paths = arrayOfNulls<PathState>(8)
    private val path0 = PathState(PathId(0), peer).also { paths[0] = it }
    private var pathCount = 1
    private val scheduler = Scheduler().apply { add(path0.estimator) }
    /** Path-0 estimator (RTT, Kalman loss, delivery rate) — what fecRedundancy() reads. */
    val estimator: PathEstimator get() = path0.estimator

    // negotiated (applyParams) — ready gates everything that depends on them
    @Volatile private var ready = false
    /** Negotiated tag length (8 or 16). */
    var tagLen: Int = 16; private set
    /** Negotiated datagram upper bound = the PMTUD ceiling and the fixed RLNC symbol size basis. */
    var maxDatagram: Int = cfg.maxDatagram; private set
    /** Negotiated dictionary id; 0 = identity codec. */
    var dictId: Long = 0L; private set
    /** Symbol = len(2) | body; sized from [maxDatagram] so a Repair frame carrying a full symbol fits one datagram. */
    var symbolSize: Int = 0; private set
    private var codec: PayloadCodec = PayloadCodec.Identity
    private lateinit var enc: RlncEncoder
    private var dec: RlncDecoder? = null
    private var nextFecSeq = 0L
    private var encBase = 0L
    private var repairCredit = 0.0
    private var repairSeed = 0x5A5A
    private var nextMsgId = if (isClient) 1L else 0L     // client msg 0 = the 0-RTT first flight
    /** Body length per fec seq (repair trimming) and retained source symbols (PTO retransmission). */
    private val bodyLenRing = IntArray(BODY_RING)
    private val symRing = arrayOfNulls<ByteArray>(BODY_RING)
    private val symRingFec = LongArray(BODY_RING) { -1L }

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
    private var waiters = 0
    /** Server: the handshake reply is acked by the first authenticated short-header packet from the client. */
    private var replyAcked = isClient
    private var lastReplyResendUs = 0L
    /** Client: short packets received before the handshake reply (see [stashEarly]). */
    private val early = ArrayList<kotlin.Pair<ByteArray, InetSocketAddress>>(EARLY_MAX)

    // parse results of the packet being processed (fields, so parseFrames returns nothing and allocates nothing)
    private var pEliciting = false; private var pNonProbing = false; private var pHasChallenge = false
    private var pChallengeNonce = 0L; private var pCreditProbe = false; private var pPrimary = 0

    /** Fraction of packets to drop on our send side (bench / tests only; pn still advances so the peer sees loss). */
    @Volatile var lossSim = 0.0
    private val lossRnd = java.util.Random(7)
    /** Test hook: return true to drop the datagram about to be sent (kind, pn, size); pn advances as for [lossSim]. */
    @Volatile internal var txFilter: ((kind: Byte, pn: Long, size: Int) -> Boolean)? = null
    /** Test hook: hold the next datagram until [releaseHeld] (simulates reordering on loopback). */
    @Volatile internal var holdNextPacket = false
    private var held: ByteBuffer? = null; private var heldTo: InetSocketAddress? = null

    val isClosed get() = closed
    val isEstablished get() = established.count == 0L
    /** Current datagram size bound on path 0 (PLPMTU); probes may exceed it by definition. */
    val plpmtu: Int get() = if (ready) path0.pmtud.plpmtu else PathState.BASE_PLPMTU
    val pathValidated: Boolean get() = path0.pv.validated
    val keyGeneration: Int get() = lock.withLock { crypto.txGeneration }
    val stats: ConnStats
        get() = lock.withLock {
            statsImpl.copy().also { s ->
                s.tagLen = tagLen; s.dictId = dictId; s.keyGeneration = crypto.txGeneration; s.pathValidated = path0.pv.validated
                if (ready) {
                    s.ccMode = path0.cc.mode.name; s.cwndLimited = path0.cc.cwndLimitedCount; s.grantLimited = path0.cc.grantLimitedCount
                    s.cwnd = path0.cc.cwnd; s.plpmtu = path0.pmtud.plpmtu; s.pmtudState = path0.pmtud.state.name
                }
            }
        }

    // ------------------------------------------------------------------ app API

    /** Blocks only when receiver credit / cwnd / path validation holds the send back (up to cfg.creditWaitMs). Thread-safe. */
    fun send(msg: ByteArray) {
        check(!closed) { "closed" }
        check(ready) { "not established" }
        val data = codec.encode(msg)   // identity unless a shared dictionary was negotiated
        lock.withLock {
            statsImpl.payloadBytesIn += msg.size; statsImpl.codecBytesOut += data.size
            val msgId = nextMsgId++
            var off = 0
            do {
                val hdrCost = 1 + VarInt.size(msgId) + (if (off > 0) VarInt.size(off.toLong()) else 0)
                val chunk = min(data.size - off, bodyMax() - FEC_FRAME_LEN - hdrCost)
                val fin = off + chunk == data.size
                val path = pickPath(chunk + 40)
                awaitSendAllowed(path, chunk + 40)
                sendSource(path, msgId, off, data, chunk, fin, nowUs())
                off += chunk
            } while (off < data.size)
        }
    }

    /** Next complete message, or null after timeoutMs. */
    fun receive(timeoutMs: Long): ByteArray? = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Key update (RFC 9001 §6 shape): our next packets carry the flipped key-phase bit under the next generation;
     * the peer follows once one of them authenticates, and keeps the previous generation for reordered packets.
     * Returns false while a previous update is still pending (the peer has not sent with the new phase yet).
     */
    fun updateKeys(): Boolean = lock.withLock {
        if (!ready || crypto.tx.updatePending) return false
        crypto.tx.initiateUpdate(); statsImpl.keyUpdates++
        true
    }

    override fun close() {
        lock.withLock { if (closed) return; closed = true; creditAvailable.signalAll() }
        io.unregister(this)
        // [[CLOSE-HOOK]] no CONNECTION_CLOSE frame in core yet; the peer times out idle (cfg.idleTimeoutMs).
    }

    /** Move this connection to another socket (client rebinding / NAT rebinding as seen by the server). */
    internal fun rebind(newIo: UdpIo) = lock.withLock { io.unregister(this); newIo.register(this); io = newIo }

    // ------------------------------------------------------------------ negotiation

    /** Server: negotiate from the client's offer (first flight); returns the ConnParams to put in the reply. */
    internal fun negotiateAsServer(offer: ConnParams, initialBytes: Int, now: Long = nowUs()): ConnParams = lock.withLock {
        val tag = if (offer.tagLen == 8 && cfg.tagLen == 8) 8 else 16
        val md = min(offer.maxDatagram, cfg.maxDatagram).coerceIn(MIN_DATAGRAM, MAX_SUPPORTED_DATAGRAM)
        val dict = if (offer.dictId != 0L && cfg.dictId == offer.dictId) offer.dictId else 0L
        applyParams(tag, md, dict, offer.shortConnId, offer.ackFreq, now)
        path0.pv.onReceived(initialBytes)
        ConnParams(tagLen = tag, dictId = dict, maxDatagram = md, ackFreq = cfg.ackFreq, shortConnId = localShortId)
    }

    private fun applyParams(tag: Int, md: Int, dict: Long, shortId: Int, ackFreq: Int, now: Long) {
        build(tag, md, ackFreq, now)
        dictId = dict; codec = if (dict != 0L) cfg.codec!! else PayloadCodec.Identity
        peerShortId = shortId
        ready = true
    }

    private var builtTag = 0; private var builtMd = 0; private var builtAckFreq = 0

    /** Everything sized by the negotiated parameters; a no-op when they were already built with the same values ([prepare]). */
    private fun build(tag: Int, md: Int, ackFreq: Int, now: Long) {
        if (tag == builtTag && md == builtMd && ackFreq == builtAckFreq) return
        builtTag = tag; builtMd = md; builtAckFreq = ackFreq
        tagLen = tag; crypto.tagLen = tag
        maxDatagram = md
        symbolSize = md - SHORT_HDR_MAX - MAX_TAG - REPAIR_FRAME_OVERHEAD
        enc = RlncEncoder(symbolSize, cfg.fecWindow); dec = RlncDecoder(symbolSize)
        peerAckFreq = ackFreq
        for (p in paths) p?.setup(ackFreq, cfg.ackDelayUs, md, now)
    }

    /**
     * Client, while the reply is in flight: derive the full key schedule and build the parameter-sized state from our
     * own offer — the reply normally confirms it, so establishment then costs nothing but the AEAD and a few fields.
     */
    internal fun prepare() = lock.withLock {
        crypto.warm()
        if (!ready) build(cfg.tagLen, cfg.maxDatagram, cfg.ackFreq, nowUs())
    }

    /** Largest FEC body that keeps both the source packet and a repair over it within the current PLPMTU and the symbol. */
    private fun bodyMax(): Int = min(path0.pmtud.plpmtu - SHORT_HDR_MAX - tagLen - REPAIR_FRAME_OVERHEAD - 2, symbolSize - 2)

    // ------------------------------------------------------------------ tx

    private fun pickPath(bytes: Int): PathState {
        if (pathCount == 1) return path0          // fast path: Scheduler.pick allocates a filtered list
        val id = scheduler.pick(bytes) ?: PathId(0)
        return paths[id.raw] ?: path0
    }

    private fun sendAllowed(path: PathState, bytes: Int): Boolean =
        path.cc.canSend(path.tracker.bytesInFlight, bytes) && path.pv.canSend(bytes)

    private fun awaitSendAllowed(path: PathState, bytes: Int) {
        if (sendAllowed(path, bytes)) return
        when (path.cc.mode) {
            HybridCc.Mode.GRANT_LIMITED -> statsImpl.creditStalls++
            HybridCc.Mode.CWND_LIMITED -> statsImpl.cwndStalls++
            HybridCc.Mode.UNLIMITED -> statsImpl.ampStalls++
        }
        path.waitBytes = bytes; waiters++
        try {
            val deadline = System.nanoTime() + cfg.creditWaitMs * 1_000_000L
            while (!sendAllowed(path, bytes)) {
                val left = deadline - System.nanoTime()
                if (closed) throw IllegalStateException("closed")
                if (left <= 0) throw IllegalStateException("send blocked for ${cfg.creditWaitMs}ms (${path.cc.mode}, validated=${path.pv.validated})")
                creditAvailable.awaitNanos(min(left, 1_000_000L)) // the timer thread may free in-flight bytes without signalling
            }
        } finally { waiters--; path.waitBytes = 0 }
    }

    /**
     * Builds one short-header packet: header (pn length from ShortHeader.pnLenFor, never assumed) | body | padding so
     * the header-protection sample exists | AEAD under the current tx generation | header protection; then [transmit].
     * Inline so the body lambda costs nothing. Returns the packet number, or -1 if the amplification budget refused it.
     */
    private inline fun packet(path: PathState, kind: Byte, lo: Long, hi: Long, eliciting: Boolean, charge: Boolean,
                              to: InetSocketAddress = peer, body: (ByteBuffer) -> Unit): Long {
        val buf = io.pool.acquire()
        try {
            val pn = path.nextPn
            ShortHeader.write(buf, path.id, peerShortId, pn, path.tracker.largestAcked, crypto.txPhase)
            val hdrEnd = buf.position()
            body(buf)
            padToSample(buf, hdrEnd)
            // anti-amplification (3x received until the path is validated), decided before sealing so a refused packet
            // consumes neither a packet number nor a nonce (BC's AEAD refuses to re-encrypt under a used nonce)
            if (!path.pv.canSend(buf.position() + tagLen)) { statsImpl.ampLimited++; return -1L }
            val end = crypto.seal(buf, 0, hdrEnd, buf.position(), crypto.txKeys(), pn or path.pnMask, tagLen, txScratch)
            crypto.protectHeader(buf, hdrEnd - PacketProtection.SHORT_PN_OFFSET)
            buf.limit(end).position(0)
            transmit(path, buf, pn, kind, lo, hi, eliciting, charge, to)
            return pn
        } finally { io.pool.release(buf) }
    }

    /** Pads the payload so the 16-byte header-protection sample at offset 9 exists (PacketProtection.minPayloadLen). */
    private fun padToSample(buf: ByteBuffer, hdrEnd: Int) {
        val need = PacketProtection.minPayloadLen(hdrEnd - PacketProtection.SHORT_PN_OFFSET, tagLen) - (buf.position() - hdrEnd)
        if (need > 0) Frame.Padding.writeTo(buf, max(need, 2))
    }

    private fun sendSource(path: PathState, msgId: Long, off: Int, msg: ByteArray, len: Int, fin: Boolean, now: Long) {
        val fec = nextFecSeq++
        val sym = ByteArray(symbolSize)   // allocates: one symbol per source packet — RlncEncoder.push keeps the reference (core API)
        packet(path, KIND_SOURCE, fec, fec + 1, eliciting = true, charge = true) { buf ->
            val hdrEnd = buf.position()
            buf.put(FEC_FRAME_TYPE.toByte()).put(2).putShort(fec.toShort())
            val minPayload = PacketProtection.minPayloadLen(hdrEnd - PacketProtection.SHORT_PN_OFFSET, tagLen)
            val hdrCost = 1 + VarInt.size(msgId) + (if (off > 0) VarInt.size(off.toLong()) else 0)
            // a tiny body gets an explicit length so padding can follow the Msg frame (implied length = rest of packet)
            val tiny = FEC_FRAME_LEN + hdrCost + len < minPayload
            // allocates: HeapByteBuffer wrapper + duplicate() inside CompactMsg.write (core API takes a ByteBuffer)
            CompactMsg.write(buf, msgId, 0, off.toLong(), fin, ByteBuffer.wrap(msg, off, len), last = !tiny)
            if (tiny) { val need = minPayload - (buf.position() - hdrEnd); if (need > 0) Frame.Padding.writeTo(buf, max(need, 2)) }
            val bodyLen = buf.position() - hdrEnd
            sym[0] = (bodyLen shr 8).toByte(); sym[1] = bodyLen.toByte()
            buf.get(hdrEnd, sym, 2, bodyLen)
            bodyLenRing[(fec and BODY_RING_MASK).toInt()] = bodyLen
        }
        enc.push(fec, sym)
        val si = (fec and BODY_RING_MASK).toInt(); symRing[si] = sym; symRingFec[si] = fec
        encBase = max(encBase, fec - cfg.fecWindow + 1)
        statsImpl.sourcesSent++
        path.lastSourceSendUs = now; path.tailArmed = true
        // proactive repair: emit one repair symbol per 1/redundancy source symbols (adaptive, floor minRedundancy)
        repairCredit += max(cfg.minRedundancy, path.estimator.fecRedundancy())
        if (repairCredit >= 1.0) { repairCredit -= 1.0; sendRepair(scheduler.repairPathFor(path.id), REPAIR_PROACTIVE, now) }
    }

    /** Retransmits a retained source symbol verbatim (same fec seq, new pn) — a PTO probe that carries the lost data. */
    private fun resendSource(path: PathState, fec: Long, sym: ByteArray, now: Long) {
        val len = ((sym[0].toInt() and 0xFF) shl 8) or (sym[1].toInt() and 0xFF)
        packet(path, KIND_RESEND, fec, fec + 1, eliciting = true, charge = true) { it.put(sym, 2, len) }
        statsImpl.sourceResends++
    }

    private fun sendRepair(pid: PathId, kind: Int, now: Long): Boolean {
        if (nextFecSeq == 0L) return false
        val path = paths[pid.raw] ?: path0
        val r = enc.repair(++repairSeed * 0x9E3779B1.toInt()) // allocates: Frame.Repair + symbol array (core API)
        var maxBody = 0
        for (i in 0 until r.windowLen) maxBody = max(maxBody, bodyLenRing[((r.windowBase + i) and BODY_RING_MASK).toInt()])
        r.symbol.limit(min(2 + maxBody, symbolSize))          // bytes past the largest body are zero in every source symbol
        packet(path, KIND_REPAIR, r.windowBase, r.windowBase + r.windowLen, eliciting = true, charge = true) { r.write(it) }
        when (kind) {
            REPAIR_PROACTIVE -> statsImpl.repairsProactive++; REPAIR_REACTIVE -> statsImpl.repairsReactive++
            REPAIR_TLP -> statsImpl.repairsTlp++; else -> statsImpl.repairsTail++
        }
        path.lastRepairSendUs = now
        tracer.repairSent(path.id, r, now)
        return true
    }

    private fun sendAck(path: PathState, now: Long, force: Boolean = false) {
        val a = path.tracker.ackFrameIfDue(now, force) ?: return   // allocates: Frame.Ack + ranges list (core API)
        packet(path, KIND_ACK, 0, 0, eliciting = false, charge = false) { a.write(it) }
        statsImpl.acksSent++
    }

    private fun sendGrant(path: PathState, g: Frame.Grant, now: Long) {
        packet(path, KIND_GRANT, 0, 0, eliciting = false, charge = false) { g.write(it) }
        path.lastGrant = g; path.lastGrantResendUs = now
        statsImpl.grantsSent++
        tracer.grantIssued(g, now)
    }

    /** Re-sends the current grant (ReceiverCredit.currentGrant: the peer asked via a credit probe, or we saw no traffic for a while). */
    private fun resendGrant(path: PathState, now: Long, solicited: Boolean) {
        if (path.lastGrant == null) return
        if (now - path.lastGrantResendUs < max(2 * path.estimator.srttUs.toLong(), cfg.grantResendMinUs)) return
        val g = path.receiverCredit.currentGrant()   // allocates: Frame.Grant (rare)
        packet(path, KIND_GRANT, 0, 0, eliciting = false, charge = false) { g.write(it) }
        path.lastGrantResendUs = now
        if (!solicited) { path.grantResendsSinceRx++; path.grantResendBackoffUs = min(max(path.grantResendBackoffUs * 2, cfg.grantResendMinUs), cfg.probeBackoffMaxUs) }
        statsImpl.grantResends++
    }

    private fun sendCreditProbe(path: PathState, now: Long) {
        packet(path, KIND_PING, 0, 0, eliciting = true, charge = false) { it.put(CREDIT_PROBE_FRAME.toByte()).put(0); Frame.Ping.write(it) }
        path.lastCreditProbeUs = now
        path.creditProbeBackoffUs = min(max(path.creditProbeBackoffUs * 2, cfg.creditProbeMinUs), cfg.probeBackoffMaxUs)
        statsImpl.creditProbes++
    }

    private fun sendChallenge(path: PathState, now: Long) {
        val c = path.pv.challenge()  // allocates: Frame.PathChallenge (rare)
        packet(path, KIND_PATH, 0, 0, eliciting = true, charge = false) { c.write(it) }
        path.lastChallengeUs = now
        path.challengeBackoffUs = min(max(path.challengeBackoffUs * 2, cfg.grantResendMinUs), cfg.probeBackoffMaxUs)
        statsImpl.challengesSent++
    }

    private fun sendPathResponse(path: PathState, nonce: Long, to: InetSocketAddress) {
        packet(path, KIND_PATH, 0, 0, eliciting = true, charge = false, to = to) { it.put(0x07).put(path.id.raw.toByte()).putLong(nonce) }
        statsImpl.responsesSent++
    }

    /** DPLPMTUD probe: Ping + Padding to exactly `size` datagram bytes. */
    private fun sendPmtuProbe(path: PathState, size: Int, now: Long) {
        val pn = packet(path, KIND_PROBE, 0, 0, eliciting = true, charge = true) { buf ->
            val hdrEnd = buf.position()
            Frame.Ping.write(buf)
            Frame.Padding.writeTo(buf, size - hdrEnd - tagLen - 1)
        }
        if (pn < 0) return
        path.pmtud.onProbeSent(size, pn, now)
        statsImpl.probesSent++; statsImpl.probeBytesSent += size
    }

    private fun transmit(path: PathState, buf: ByteBuffer, pn: Long, kind: Byte, lo: Long, hi: Long, eliciting: Boolean, charge: Boolean, to: InetSocketAddress) {
        val now = nowUs()
        val size = buf.remaining()
        path.pv.onSent(size)
        val i = path.ringIdx(pn)
        path.ringPn[i] = pn; path.ringTimeUs[i] = now; path.ringSize[i] = size; path.ringKind[i] = kind
        path.ringLo[i] = lo; path.ringHi[i] = hi
        path.clearAcked(pn)
        path.nextPn = pn + 1
        path.tracker.onPacketSent(pn, size, now, eliciting)   // allocates: Sent + TreeMap node (core API)
        if (charge) path.cc.onSent(size, now)
        if (eliciting) { path.lastElicitingSendUs = now; if (kind == KIND_SOURCE || kind == KIND_REPAIR || kind == KIND_RESEND) path.lastDataPn = pn }
        path.lastTxUs = now; lastTxUs = now
        statsImpl.packetsSent++; statsImpl.bytesSent += size
        if (kind == KIND_SOURCE || kind == KIND_RESEND) statsImpl.sourceBytesSent += size
        if (kind != KIND_PROBE) { if (size > statsImpl.maxDatagramSent) statsImpl.maxDatagramSent = size; if (size > path.pmtud.plpmtu) statsImpl.oversized++ }
        tracer.packetSent(path.id, pn, size, TX_FRAMES[kind.toInt()], now)
        val f = txFilter
        if (f != null && f(kind, pn, size)) { statsImpl.simDropped++; return }
        if (lossSim > 0.0 && lossRnd.nextDouble() < lossSim) { statsImpl.simDropped++; return }
        if (holdNextPacket) { holdNextPacket = false; held = ByteBuffer.allocate(size).put(buf).flip(); heldTo = to; return }
        io.send(buf, to)
    }

    /** Test hook: sends the datagram held by [holdNextPacket]. */
    internal fun releaseHeld() = lock.withLock { held?.let { io.send(it, heldTo!!) }; held = null }

    // ------------------------------------------------------------------ rx (called on the endpoint's rx thread)

    /** `buf` = whole datagram, position 0, limit = length. Header still protected. */
    internal fun onShortPacket(buf: ByteBuffer, from: InetSocketAddress) {
        lock.withLock {
            if (closed) return
            if (!ready) { stashEarly(buf, from); return }
            val len = buf.limit()
            if (len < PacketCrypto.MIN_PACKET) return
            val path = paths[(buf.get(0).toInt() shr 2) and 7] ?: run { statsImpl.unknownPath++; return } // pathId bits are in the clear
            val pnLen = crypto.unprotectHeader(buf)
            val flags = buf.get(0).toInt() and 0xFF
            val hdrEnd = PacketProtection.SHORT_PN_OFFSET + pnLen
            if (len < hdrEnd + tagLen) return
            var trunc = 0L
            for (i in PacketProtection.SHORT_PN_OFFSET until hdrEnd) trunc = (trunc shl 8) or (buf.get(i).toLong() and 0xFF)
            val pn = ShortHeader.decodePn(trunc, pnLen * 8, path.largestSeen)
            if (pn < 0 || pn <= path.largestSeen - PathState.RX_BITS) return
            if (pn <= path.largestSeen && path.rxSeen(pn)) { statsImpl.dups++; return }
            val n = openShort(buf, hdrEnd, len, flags and 1, pn or path.pnMask)
            if (n < 0) { statsImpl.authFail++; if (!replyAcked) resendReply(from); return }
            replyAcked = true
            val now = nowUs()
            if (pn > path.largestSeen) {
                if (pn > path.largestSeen + 1) {
                    val missing = pn - path.largestSeen - 1
                    statsImpl.gapsSeen += missing
                    // lost bytes are no longer in flight: hand the credit back (Homa does the same by timeout)
                    path.receiverCredit.onReceived((missing * path.avgRxBytes).toInt())
                }
                path.advanceLargest(pn)
            }
            path.rxSet(pn)
            path.lastRxUs = now; lastRxUs = now
            statsImpl.packetsReceived++; statsImpl.bytesReceived += len
            rxPlainBuf.limit(n).position(0)
            pEliciting = false; pNonProbing = false; pHasChallenge = false; pCreditProbe = false; pPrimary = 0
            parseFrames(rxPlainBuf, path, n, recovered = false, now = now)
            tracer.packetReceived(path.id, pn, len, RX_FRAMES[pPrimary], now)
            // [[PATH-VALIDATION-HOOK]] peer seen from a new address with non-probing frames: migrate at once (RFC 9000 §9.3
            // shape), back to unvalidated with a fresh 3x budget, and challenge the new address.
            var migrated = false
            if (!isClient && from != peer && pNonProbing) { migrate(path, from, now); migrated = true }
            path.pv.onReceived(len)
            path.tracker.onPacketReceived(pn, len, false, now, pEliciting)
            if (pEliciting) {
                path.receiverCredit.onReceived(len)
                path.avgRxBytes = 0.9 * path.avgRxBytes + 0.1 * len
                path.lastElicitingRxUs = now; path.grantResendsSinceRx = 0; path.grantResendBackoffUs = 0
            }
            if (migrated) sendChallenge(path, now)
            if (pHasChallenge) sendPathResponse(path, pChallengeNonce, from)
            if (pCreditProbe) resendGrant(path, now, solicited = true)
            path.tracker.ackTimer(now)?.let { if (it <= now) sendAck(path, now) }
            // grants are never delayed: check inline too, so a coarse timer can't starve the sender
            maybeGrant(path, now, timer = false)
        }
    }

    /**
     * ReceiverCredit.tick at its documented cadence (~min(srtt/4, 1 ms), plus every timer tick regardless of receive
     * progress). Not before this side has an RTT sample (bounded by [GRANT_WARMUP_US]): core sizes the BDP target with
     * PathEstimator.INITIAL_RTT_US (100 ms) until then, which turns the first packets' instantaneous receive rate into
     * a grant of hundreds of KB. The sender's initial window covers that first millisecond.
     */
    private fun maybeGrant(path: PathState, now: Long, timer: Boolean) {
        val est = path.estimator
        if (est.minRttUs == Double.MAX_VALUE && now - path.setupUs < GRANT_WARMUP_US) return
        if (!timer && now - path.lastCreditTickUs < (est.srttUs / 4).toLong().coerceIn(100L, 1_000L)) return
        path.lastCreditTickUs = now
        path.receiverCredit.tick(now)?.let { sendGrant(path, it, now) }
    }

    /**
     * Client: a short packet that overtook the handshake reply (0.5-RTT data — the server app sends right after accept;
     * a batching datapath or the network may reorder it before the reply). It cannot be opened before the negotiated
     * parameters are known, so keep a copy (bounded) and replay it once the reply is in. Allocates; handshake only.
     */
    private fun stashEarly(buf: ByteBuffer, from: InetSocketAddress) {
        if (!isClient || early.size >= EARLY_MAX) return
        early += ByteArray(buf.limit()).also { buf.get(0, it) } to from
    }

    /**
     * AEAD under the key phase the packet carries, core's KeyPhaseState trial order: current; else the retained
     * previous generation (reordered packet); else, with no update of ours pending, the pre-derived next generation,
     * following it only once the packet authenticates. Our tx side follows/confirms via onPeerPhase.
     */
    private fun openShort(buf: ByteBuffer, hdrEnd: Int, len: Int, phase: Int, noncePn: Long): Int {
        if (crypto.rxStateOrNull == null && phase == 0) {      // generation 0, no key-phase state built yet: plain keys
            val n = crypto.open(buf, 0, hdrEnd, len, crypto.rxKeys(), noncePn, tagLen, rxScratch, rxPlain)
            if (n >= 0) crypto.txStateOrNull?.onPeerPhase(0)
            return n
        }
        val rx = crypto.rx
        if (phase == rx.currentPhase) {
            val n = crypto.open(buf, 0, hdrEnd, len, rx.current, noncePn, tagLen, rxScratch, rxPlain)
            if (n >= 0) crypto.tx.onPeerPhase(phase)          // clears our pending update once the peer has caught up
            return n
        }
        rx.previous?.let { old -> val n = crypto.open(buf, 0, hdrEnd, len, old, noncePn, tagLen, rxScratch, rxPlain); if (n >= 0) return n }
        if (rx.updatePending) return -1
        val n = crypto.open(buf, 0, hdrEnd, len, rx.next, noncePn, tagLen, rxScratch, rxPlain)
        if (n >= 0) { rx.onPeerPhase(phase); crypto.tx.onPeerPhase(phase); statsImpl.keyUpdatesFollowed++ }
        return n
    }

    private fun migrate(path: PathState, from: InetSocketAddress, now: Long) {
        path.pv.onMigration(from)
        peer = from
        path.challengeBackoffUs = 0
        statsImpl.migrations++
        tracer.pathAdded(path.id, from.toString(), now)   // allocates the address string (rare)
    }

    /** Parses one packet's frames; results land in the p* fields. `bodyLen` = plaintext length (for the FEC symbol copy). */
    private fun parseFrames(buf: ByteBuffer, path: PathState, bodyLen: Int, recovered: Boolean, now: Long) {
        var prevMsg = 0L; var skipMsgs = false
        while (buf.hasRemaining()) {
            val t = buf.get(buf.position()).toInt() and 0xFF
            when {
                t == 0 -> break // trailing zeros (recovered symbols are zero-padded)
                t and 0xF8 == 0x10 -> {
                    val m = CompactMsg.read(buf, prevMsg) // allocates Frame.Msg + slice (core API)
                    prevMsg = m.msgId; pEliciting = true; pNonProbing = true; if (pPrimary == 0) pPrimary = RXF_MSG
                    if (!skipMsgs) onMsgFrame(m)
                }
                t == FEC_FRAME_TYPE -> {
                    buf.position(buf.position() + 2)
                    val fec = ShortHeader.decodePn(buf.getShort().toLong() and 0xFFFF, 16, largestFecSeen)
                    pEliciting = true; pNonProbing = true; if (pPrimary == 0) pPrimary = RXF_FEC
                    if (!recovered) {
                        statsImpl.sourcesReceived++
                        if (isDelivered(fec)) skipMsgs = true // already recovered via a repair symbol
                        else { storeSource(fec, bodyLen); markDelivered(fec) }
                    }
                }
                t == Frame.Padding.TYPE -> { buf.position(buf.position() + 2 + (buf.get(buf.position() + 1).toInt() and 0xFF)); if (pPrimary == 0) pPrimary = RXF_PADDING }
                t == CREDIT_PROBE_FRAME -> { buf.position(buf.position() + 2); pEliciting = true; pNonProbing = true; pCreditProbe = true; if (pPrimary == 0) pPrimary = RXF_PING }
                t == 0x02 -> { onAck(FrameCodec.read(buf) as Frame.Ack, now); pNonProbing = true; if (pPrimary == 0) pPrimary = RXF_ACK } // allocates Ack + ranges (core API)
                t == 0x03 -> {
                    val g = FrameCodec.read(buf) as Frame.Grant
                    paths[g.path.raw and 7]?.let { p -> p.cc.onGrant(g); p.lastGrantRxUs = now; p.creditProbeBackoffUs = 0 }
                    statsImpl.grantsReceived++; creditAvailable.signalAll()
                    pNonProbing = true; if (pPrimary == 0) pPrimary = RXF_GRANT
                }
                t == 0x04 -> { onRepair(FrameCodec.read(buf) as Frame.Repair, path, now); pEliciting = true; pNonProbing = true; if (pPrimary == 0) pPrimary = RXF_REPAIR }
                t == 0x05 -> { buf.get(); buf.get(); pChallengeNonce = buf.getLong(); pHasChallenge = true; pEliciting = true; if (pPrimary == 0) pPrimary = RXF_CHALLENGE }
                t == 0x06 -> { buf.get(); pEliciting = true; pNonProbing = true; if (pPrimary == 0) pPrimary = RXF_PING }
                t == 0x07 -> {
                    buf.get(); val pid = buf.get().toInt() and 0xFF; val nonce = buf.getLong()
                    pEliciting = true; if (pPrimary == 0) pPrimary = RXF_RESPONSE
                    val p = paths[pid and 7]
                    if (p != null && !p.pv.validated && p.pv.onResponse(nonce)) creditAvailable.signalAll()
                }
                else -> { FrameCodec.read(buf) ?: break; pEliciting = true; pNonProbing = true } // unknown extension: skipped
            }
        }
    }

    private fun storeSource(fec: Long, bodyLen: Int) {
        if (fec > largestFecSeen) advanceFec(fec)
        if (fec - decoderEpoch >= DECODER_ROTATE) rotateDecoder(fec)
        // allocates: one symbol per received source — RlncDecoder.onSource keeps the reference (core API)
        val sym = ByteArray(symbolSize)
        sym[0] = (bodyLen shr 8).toByte(); sym[1] = bodyLen.toByte()
        System.arraycopy(rxPlain, 0, sym, 2, min(bodyLen, symbolSize - 2))
        dec!!.onSource(fec, sym)
    }

    /** RlncDecoder.known never evicts (core); start a fresh decoder every DECODER_ROTATE seqs, re-feeding the live window. */
    private fun rotateDecoder(fec: Long) {
        val old = dec!!
        val fresh = RlncDecoder(symbolSize)
        var s = max(0L, fec - cfg.fecWindow)
        while (s < fec) { old.get(s)?.let { fresh.onSource(s, it) }; s++ }
        dec = fresh
        decoderEpoch = fec
    }

    private fun onRepair(r: Frame.Repair, path: PathState, now: Long) {
        statsImpl.repairsReceived++
        if (r.windowLen <= 0 || r.windowLen > 4 * cfg.fecWindow) return
        if (r.windowBase + r.windowLen - 1 - decoderEpoch >= DECODER_ROTATE) rotateDecoder(r.windowBase + r.windowLen - 1)
        val d = dec!!
        d.onRepair(r)
        for (i in 0 until r.windowLen) {
            val s = r.windowBase + i
            if (isDelivered(s)) continue
            val sym = d.get(s) ?: continue
            if (s > largestFecSeen) advanceFec(s)
            markDelivered(s)
            statsImpl.recovered++
            tracer.repairDecoded(path.id, s, now)
            val len = ((sym[0].toInt() and 0xFF) shl 8) or (sym[1].toInt() and 0xFF)
            if (len in 1..(symbolSize - 2)) {
                val save = pEliciting; val saveNp = pNonProbing
                parseFrames(ByteBuffer.wrap(sym, 2, len), path, len, recovered = true, now = now)
                pEliciting = save || pEliciting; pNonProbing = saveNp || pNonProbing
            }
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
            val b = ByteArray(len); m.data.get(b); deliverMsg(b); return
        }
        val r = reassembly.getOrPut(m.msgId) { Reassembly() }
        if (r.add(m.offset, m.data, m.fin)) { reassembly.remove(m.msgId); deliverMsg(r.bytes()) }
    }

    /** A complete message off the wire: through the payload codec, then to the app. */
    private fun deliverMsg(b: ByteArray) {
        val plain = if (codec === PayloadCodec.Identity) b else try { codec.decode(b) } catch (e: IllegalStateException) { statsImpl.codecErrors++; return }
        deliverRaw(plain)
    }

    /** Delivers bytes as-is (the 0-RTT first flight bypasses the codec: it is sent before the dictionary is negotiated). */
    internal fun deliverRaw(b: ByteArray) { statsImpl.messagesDelivered++; inbox.put(b) }

    // ------------------------------------------------------------------ ack processing (sender side)

    private fun onAck(a: Frame.Ack, now: Long) {
        val path = paths[a.path.raw and 7] ?: return
        statsImpl.acksReceived++
        val r = path.tracker.onAck(a, now)   // allocates: AckResult + lists (core API)
        if (r.newlyAcked.isNotEmpty()) {
            var charged = 0
            for (pn in r.newlyAcked) {
                val i = path.ringIdx(pn)
                if (path.ringPn[i] != pn || path.isAcked(pn)) continue
                path.setAcked(pn)
                val size = path.ringSize[i]
                when (path.ringKind[i]) {
                    KIND_PROBE -> { path.pmtud.onProbeAcked(pn); charged += size }
                    KIND_SOURCE, KIND_REPAIR, KIND_RESEND -> { path.pmtud.onPacketAcked(size); charged += size }
                    else -> path.pmtud.onPacketAcked(size)
                }
            }
            val rtt = r.rttSampleUs
            if (rtt != null) { path.estimator.onRttSample(rtt); tracer.metrics(path.estimator, now) }
            path.estimator.onDelivered(path.tracker.cumulativeAckedBytes, now)
            path.cc.onAcked(charged, rtt ?: 0L, now)
            path.tlpBackoff = 0
            creditAvailable.signalAll()
        }
        for (pn in r.lost) onLost(path, pn, now)
        path.cc.onAckFrame(a)
        // loss observation for the real estimator at the cadence core's Kalman filter was tuned for
        path.lossExpected += r.newlyAcked.size + r.lost.size; path.lossLost += r.lost.size
        if (path.lossExpected >= cfg.lossObsWindow) {
            path.estimator.onLossObservation(path.lossLost.toDouble() / path.lossExpected)
            path.lossExpected = 0; path.lossLost = 0
        }
        repairDeficit(path, a.largest)
    }

    /** A packet the AckTracker declared lost (packet or time threshold): PMTUD evidence, gated CC signal, trace. */
    private fun onLost(path: PathState, pn: Long, now: Long) {
        val i = path.ringIdx(pn)
        if (path.ringPn[i] != pn || path.isAcked(pn)) return
        statsImpl.lossesDetected++
        val size = path.ringSize[i]
        when (path.ringKind[i]) {
            KIND_PROBE -> { path.pmtud.onProbeLost(pn); statsImpl.probesLost++ }
            KIND_SOURCE, KIND_REPAIR, KIND_RESEND -> { path.pmtud.onPacketLoss(size); ccLoss(path, size, now) }
            else -> path.pmtud.onPacketLoss(size)
        }
        tracer.packetLost(path.id, pn, timeUs = now)
    }

    private fun ccLoss(path: PathState, bytes: Int, now: Long) {
        val est = path.estimator
        val gate = cfg.ccLossDelayGateUs
        val queued = est.minRttUs != Double.MAX_VALUE && est.srttUs - est.minRttUs > max(gate.toDouble(), est.minRttUs / 4)
        if (gate == 0L || queued) { path.cc.onLoss(bytes, now); statsImpl.ccLossEvents++ } else statsImpl.ccLossIgnored++
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
                    KIND_SOURCE, KIND_RESEND -> if (pn <= largest - thr && !path.isAcked(pn) && path.ringLo[i] >= encBase) missFec[nMiss++] = path.ringLo[i]
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
        val now = nowUs()
        while (k-- > 0 && path.pv.canSend(maxDatagram)) sendRepair(scheduler.repairPathFor(path.id), REPAIR_REACTIVE, now)
    }

    // ------------------------------------------------------------------ timer (endpoint thread, ~1ms)

    internal fun onTick(now: Long) {
        lock.withLock {
            if (closed || !ready) return
            crypto.warm()   // full key-phase states, derived here (timer thread) rather than on the first packets
            for (p in paths) {
                p ?: continue
                val srtt = p.estimator.srttUs.toLong()
                // delayed ack
                p.tracker.ackTimer(now)?.let { if (it <= now) sendAck(p, now) }
                // RACK time-threshold losses the acks did not cover
                p.tracker.lossTimer(now)?.let { due ->
                    if (due <= now) {
                        val lost = p.tracker.onLossTimer(now)
                        if (lost.isNotEmpty()) {
                            for (pn in lost) onLost(p, pn, now)
                            p.lossExpected += lost.size; p.lossLost += lost.size
                            repairDeficit(p, p.tracker.largestAcked)
                            creditAvailable.signalAll()
                        }
                    }
                }
                // receiver credit: runs regardless of receive progress; re-send the last grant into silence (bounded)
                maybeGrant(p, now, timer = true)
                if (p.lastGrant != null && p.grantResendsSinceRx < MAX_UNSOLICITED_GRANT_RESENDS &&
                    now - p.lastElicitingRxUs > max(2 * srtt, cfg.grantResendMinUs) && now - p.lastGrantResendUs >= p.grantResendBackoffUs) {
                    resendGrant(p, now, solicited = false)
                }
                // sender starved of credit: probe for a grant with backoff; never block forever
                if (p.waitBytes > 0 && !p.senderCredit.canSend(p.waitBytes) &&
                    now - p.lastGrantRxUs > max(2 * srtt, cfg.creditProbeMinUs) && now - p.lastCreditProbeUs >= p.creditProbeBackoffUs) {
                    sendCreditProbe(p, now)
                }
                // PTO: data unacked for ptoUs(backoff) after our last ack-eliciting send -> probe that carries the oldest unacked data
                if (p.lastDataPn > p.tracker.largestAcked && nextFecSeq > 0 && p.pv.canSend(maxDatagram) &&
                    now - p.lastElicitingSendUs > max(p.estimator.ptoUs(p.tlpBackoff), 3 * cfg.ackDelayUs)) {
                    p.tlpBackoff++; sendProbeData(p, now)
                }
                // tail repair: a source packet that no other source followed within T gets a trailing repair symbol
                if (p.tailArmed) {
                    val t = (srtt / 8).coerceIn(cfg.tailRepairMinUs, cfg.tailRepairMaxUs)
                    if (now - p.lastSourceSendUs >= t) {
                        p.tailArmed = false
                        if (now - p.lastRepairSendUs >= t && p.pv.canSend(maxDatagram)) sendRepair(scheduler.repairPathFor(p.id), REPAIR_TAIL, now)
                    }
                }
                // path validation: re-challenge an unvalidated path with backoff (server side)
                if (!isClient && !p.pv.validated && now - p.lastChallengeUs >= p.challengeBackoffUs && p.pv.canSend(64)) sendChallenge(p, now)
                // DPLPMTUD once the path is validated and acks are flowing
                if (cfg.pmtud && p.pv.validated) {
                    p.pmtud.probeTimeoutUs = max(4 * p.estimator.lossTimeoutUs(), 20_000L)
                    p.pmtud.onTimer(now)
                    val probe = p.pmtud.nextProbe(now)
                    if (probe != null && p.pv.canSend(probe.size) && p.cc.canSend(p.tracker.bytesInFlight, probe.size)) sendPmtuProbe(p, probe.size, now)
                }
            }
            if (waiters > 0) creditAvailable.signalAll()
            if (now - max(lastRxUs, lastTxUs) > cfg.idleTimeoutMs * 1000) { closed = true; creditAvailable.signalAll(); io.unregister(this) }
        }
    }

    /** PTO probe: a repair covering the oldest unacked source if it is still in the encoder window, else that source verbatim. */
    private fun sendProbeData(path: PathState, now: Long) {
        var pn = max(path.tracker.largestAcked + 1, path.nextPn - 2 * SPAN).coerceAtLeast(0)
        while (pn < path.nextPn) {
            val i = path.ringIdx(pn)
            if (path.ringPn[i] == pn && !path.isAcked(pn) && (path.ringKind[i] == KIND_SOURCE || path.ringKind[i] == KIND_RESEND)) {
                val fec = path.ringLo[i]
                if (fec >= encBase) { sendRepair(path.id, REPAIR_TLP, now); return }
                val si = (fec and BODY_RING_MASK).toInt()
                val sym = symRing[si]
                if (sym != null && symRingFec[si] == fec) { resendSource(path, fec, sym, now); return }
                break
            }
            pn++
        }
        sendRepair(path.id, REPAIR_TLP, now)
    }

    // ------------------------------------------------------------------ handshake helpers

    /** Server: encrypted reply = ConnParams(shortConnId for the client to use, ackFreq, tagLen, maxDatagram, dictId) | ticketLen(2) | ticket. */
    internal fun buildHandshakeReply(params: ConnParams, ticket: ByteArray?): ByteBuffer = lock.withLock {
        val buf = ByteBuffer.allocate(Wire.MAX_DATAGRAM)
        PacketHeader(Wire.F_INITIAL or Wire.F_HANDSHAKE, connId, PathId(0), 0).write(buf)
        val hdrEnd = buf.position()
        params.write(buf)
        buf.putShort((ticket?.size ?: 0).toShort()); ticket?.let { buf.put(it) }
        val end = crypto.seal(buf, 0, hdrEnd, buf.position(), crypto.txKeys(), 0L, MAX_TAG, txScratch)
        buf.limit(end).position(0)
        handshakePacket = buf
        buf
    }


    /** Server: a handshake packet (initial reply / re-send) left; count it against the amplification budget. */
    internal fun onHandshakeSent(bytes: Int) = lock.withLock { path0.pv.onSent(bytes) }

    /** Server: the client retransmitted its initial — our reply was probably lost. */
    internal fun onDuplicateInitial(from: InetSocketAddress, bytes: Int, resend: Boolean) = lock.withLock {
        path0.pv.onReceived(bytes)
        if (resend) resendReply(from)
    }

    private fun resendReply(from: InetSocketAddress) {
        if (replyAcked) return
        val pkt = handshakePacket ?: return
        val now = nowUs()
        if (now - lastReplyResendUs < REPLY_RESEND_MIN_US || !path0.pv.canSend(pkt.remaining())) return
        lastReplyResendUs = now
        path0.pv.onSent(pkt.remaining())
        statsImpl.replyResends++
        io.send(pkt.duplicate(), from)
    }

    /** Server: right after accept. The PathChallenge that validates the client's address goes out on the first timer
     *  tick (<= 1 ms), off the accept path; the 3x amplification budget applies until it is answered. */
    internal fun afterAccept() = lock.withLock { tracer.handshake(handshakeKind, zeroRttBytes) }

    /** Client: decrypt the server reply; returns false if it does not authenticate. */
    internal fun onHandshakeReply(buf: ByteBuffer): Boolean = lock.withLock {
        if (established.count == 0L) return true
        val n = crypto.open(buf, 0, Wire.HEADER_LEN, buf.limit(), crypto.rxKeys(), 0L, MAX_TAG, rxScratch, rxPlain)
        if (n < 0) { statsImpl.authFail++; return false }
        val pb = ByteBuffer.wrap(rxPlain, 0, n)
        val p = ConnParams.read(pb)
        val tl = pb.getShort().toInt() and 0xFFFF
        if (tl > 0) ticket = ByteArray(tl).also { pb.get(it) }
        val now = nowUs()
        applyParams(p.tagLen, min(p.maxDatagram, cfg.maxDatagram).coerceIn(MIN_DATAGRAM, MAX_SUPPORTED_DATAGRAM),
            if (p.dictId != 0L && p.dictId == offeredDictId) p.dictId else 0L, p.shortConnId, p.ackFreq, now)
        path0.pv.markValidated()   // the server answered from this address: validated for us
        lastRxUs = now
        tracer.handshake(handshakeKind, zeroRttBytes, now)
        established.countDown()
        if (early.isNotEmpty()) {
            val replay = ArrayList(early); early.clear()
            for ((bytes, from) in replay) onShortPacket(ByteBuffer.wrap(bytes), from)
        }
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
        const val MAX_TAG = 16
        const val REPAIR_FRAME_OVERHEAD = 1 + 8 + 2 + 4 + 2
        const val FEC_FRAME_TYPE = 0x80
        const val FEC_FRAME_LEN = 4
        const val CREDIT_PROBE_FRAME = 0x82
        const val RX_BUF = 2048
        const val MIN_DATAGRAM = 1200
        const val MAX_SUPPORTED_DATAGRAM = 1500
        const val MAX_FEC_WINDOW = 128
        const val SPAN = 64
        const val DELIVERED_BITS = 4096
        const val DECODER_ROTATE = 4096L
        const val BODY_RING = 256
        const val BODY_RING_MASK = BODY_RING - 1L
        const val MAX_UNSOLICITED_GRANT_RESENDS = 3
        const val GRANT_WARMUP_US = 50_000L
        const val EARLY_MAX = 8
        const val REPLY_RESEND_MIN_US = 5_000L
        const val KIND_ACK: Byte = 0; const val KIND_SOURCE: Byte = 1; const val KIND_REPAIR: Byte = 2; const val KIND_GRANT: Byte = 3
        const val KIND_PROBE: Byte = 4; const val KIND_PATH: Byte = 5; const val KIND_PING: Byte = 6; const val KIND_RESEND: Byte = 7
        const val REPAIR_PROACTIVE = 0; const val REPAIR_REACTIVE = 1; const val REPAIR_TLP = 2; const val REPAIR_TAIL = 3
        // tracer frame lists, hoisted so tracing allocates nothing per packet
        private val TX_FRAMES: Array<List<String>> = arrayOf(listOf("ack"), listOf("fec", "msg"), listOf("repair"), listOf("grant"),
            listOf("ping", "padding"), listOf("path"), listOf("ping"), listOf("fec", "msg"))
        private const val RXF_MSG = 1; private const val RXF_ACK = 2; private const val RXF_GRANT = 3; private const val RXF_REPAIR = 4
        private const val RXF_CHALLENGE = 5; private const val RXF_PING = 6; private const val RXF_RESPONSE = 7; private const val RXF_PADDING = 8
        private const val RXF_FEC = 9
        private val RX_FRAMES: Array<List<String>> = arrayOf(emptyList(), listOf("msg"), listOf("ack"), listOf("grant"), listOf("repair"),
            listOf("path_challenge"), listOf("ping"), listOf("path_response"), listOf("padding"), listOf("fec", "msg"))

        fun nowUs(): Long = System.nanoTime() / 1000
        /** 64-bit ConnId = first 8 bytes of HKDF(sessionKey, "connid") — derived from the key without exposing key bytes. */
        fun deriveConnId(sessionKey: ByteArray): Long = ByteBuffer.wrap(PacketCrypto.hkdf(sessionKey, "aether-v0.2 connid")).getLong()
    }
}
