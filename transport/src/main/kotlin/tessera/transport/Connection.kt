package tessera.transport

import tessera.core.AckTracker
import tessera.core.CompactMsg
import tessera.core.ConnId
import tessera.core.ConnParams
import tessera.core.CubicCc
import tessera.core.Frame
import tessera.core.FrameCodec
import tessera.core.HandshakeKind
import tessera.core.HybridCc
import tessera.core.NoopTracer
import tessera.core.PacketHeader
import tessera.core.PacketProtection
import tessera.core.PathEstimator
import tessera.core.PathId
import tessera.core.PathValidation
import tessera.core.PayloadCodec
import tessera.core.Pmtud
import tessera.core.ReceiverCredit
import tessera.core.Resumption
import tessera.core.RlncDecoder
import tessera.core.RlncEncoder
import tessera.core.Scheduler
import tessera.core.SenderCredit
import tessera.core.ShortHeader
import tessera.core.StatelessReset
import tessera.core.Tracer
import tessera.core.VarInt
import tessera.core.Wire
import tessera.core.ZstdDictCodec
import tessera.core.grantIssued
import tessera.core.handshake
import tessera.core.metrics
import tessera.core.packetLost
import tessera.core.packetReceived
import tessera.core.packetSent
import tessera.core.pathAdded
import tessera.core.repairDecoded
import tessera.core.repairSent
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
    /**
     * RLNC sliding window in source packets (64 ms at 2000 msg/s). A burst of b lost sources needs b independent
     * repair symbols emitted while the burst is still inside the window, and every repair also carries the window's
     * other unknowns, so the window must be large against the burst length while the redundancy ratio (burst-aware,
     * PathEstimator.fecRedundancy) keeps the decode delay short. 32 (v0.5) let 62 % of the lte profile's losses fall
     * through to a round-trip re-send; 128 covers every measured burst (SPEC v0.6 table). Reactive repair needs the
     * window to cover > 1 RTT of packets as well.
     */
    val fecWindow: Int = TesseraConnection.MAX_FEC_WINDOW,
    /** Packets past a hole before a rank-deficit repair is sent (QUIC uses 3; loopback never reorders). */
    val reorderThreshold: Int = 1,
    /** Floor for proactive repair ratio. */
    val minRedundancy: Double = 0.02,
    /** Packets per loss observation fed to PathEstimator.onLossObservation (core's r=1e-2 assumes ~10-30). */
    val lossObsWindow: Int = 32,
    val maxReactiveRepairsPerAck: Int = 4,
    /**
     * The ack-driven repair path (the first ack showing a hole fires a repair per missing data packet that no repair
     * covers) bounds its verbatim re-sends by a token bucket refilled at this fraction of the source rate (burst
     * capacity TesseraConnection.GAP_BUDGET_MAX).
     */
    val gapRepairFraction: Double = 0.25,
    /**
     * Shortest confirmed contiguous hole treated as a link outage rather than congestion loss, allowing the ack-driven
     * re-sends to burst instead of being metered by [gapRepairFraction]. [Long.MAX_VALUE] disables the burst entirely.
     */
    val outageDrainMinRun: Long = TesseraConnection.OUTAGE_RUN_MIN,
    /**
     * Most ack ranges carried per ACK (largest first) on a path that delivers in order. AckTracker keeps 32; at
     * 2000 pkt/s with 5 % bursty loss that is ~640 packets of history and 256 B per ACK - 2.6 Mbit/s of the lte
     * profile's 30 Mbit/s. 16 ranges still report every received packet in ~80 consecutive acks (160 ms). A path that
     * has just delivered a packet out of order gets all 32 (TesseraConnection.ACK_FULL_RANGES_US).
     */
    val maxAckRanges: Int = 16,
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
    /** Sender with exhausted credit: after the first probe (half an RTT after the last grant) further probes back off from this (capped). */
    val creditProbeMinUs: Long = 50_000,
    val probeBackoffMaxUs: Long = 1_000_000,
    /**
     * In-process link impairment (bench / tests): every datagram the endpoint sends — handshake packets included —
     * goes through this [NetemSim] before the socket. Attach the same instance to both endpoints for a symmetric link
     * sharing one queue and one loss chain (what one netem qdisc on `lo` does). See also [TesseraConnection.attachNetem].
     */
    val netem: NetemSim? = null,
    /**
     * close() keeps the connection alive (no new sends) until everything it sent is acknowledged — data re-sent by the
     * PTO / repair machinery, the handshake reply re-sent for a retransmitted initial — or this long has passed; only
     * then is the state dropped. The connect bench's server answers and closes at once: without this neither a lost
     * reply (the retransmitted initial hit the 0-RTT replay filter) nor its lost first response was ever re-sent.
     */
    val closeLingerMs: Long = 10_000,
    /**
     * Receive-side flow control (memory bounds). A `Msg` frame carries a wire-controlled `offset`, and reassembly
     * buffers grow to `offset + len`, so without these an authenticated peer can force an arbitrary allocation from a
     * single crafted fragment, or hold open unboundedly many partial messages. Tessera has no `MAX_DATA` frame; these
     * are local caps instead.
     * - [maxMessageBytes]: a fragment whose `offset + len` exceeds this is dropped before any buffer is sized.
     * - [maxConcurrentReassembly]: fragments for a new message id beyond this many in-progress messages are dropped.
     * - [maxReassemblyBytes]: total bytes buffered across all in-progress messages; the fragment that would breach it
     *   drops its whole message. Must be >= maxMessageBytes or a legitimate max-size message could never complete.
     */
    val maxMessageBytes: Int = 16 * 1024 * 1024,
    val maxConcurrentReassembly: Int = 64,
    val maxReassemblyBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(tagLen == 8 || tagLen == 16) { "tagLen must be 8 or 16, got $tagLen" }
        require(maxDatagram in TesseraConnection.MIN_DATAGRAM..TesseraConnection.MAX_SUPPORTED_DATAGRAM) { "maxDatagram $maxDatagram" }
        require(fecWindow in 1..TesseraConnection.MAX_FEC_WINDOW) { "fecWindow $fecWindow" }
        require(maxMessageBytes in 1..Int.MAX_VALUE) { "maxMessageBytes $maxMessageBytes" }
        require(maxConcurrentReassembly >= 1) { "maxConcurrentReassembly $maxConcurrentReassembly" }
        require(maxReassemblyBytes >= maxMessageBytes) { "maxReassemblyBytes must be >= maxMessageBytes" }
    }
    /** One codec per config, shared by its connections (thread-safe; digesting the dictionary is the expensive part). */
    val codec: ZstdDictCodec? by lazy { dictionary?.let { ZstdDictCodec(it) } }
    val dictId: Long by lazy { dictionary?.let { ZstdDictCodec.dictIdOf(it) } ?: 0L }
}

/** Counters; written under the connection lock, read via [TesseraConnection.stats] (snapshot). */
class ConnStats {
    var packetsSent = 0L; var sourcesSent = 0L; var repairsProactive = 0L; var repairsReactive = 0L; var repairsTlp = 0L; var repairsTail = 0L
    var sourceResends = 0L; var acksSent = 0L; var grantsSent = 0L; var grantResends = 0L; var simDropped = 0L
    /** Verbatim re-sends fired by the ack-driven repair path (a hole with no covering repair, v0.6), and deficits left for lack of a token. */
    var gapResends = 0L; var gapThrottled = 0L
    /** Times a confirmed contiguous hole with no queue growth was drained in one burst rather than metered (F9). */
    var outageDrains = 0L
    /** Of the ack-driven re-sends: for a seq the peer reported undelivered (scan) / not covered by a report (scan) / an old hole from the feedback map. */
    var resendKnown = 0L; var resendUnknown = 0L; var resendFeedback = 0L
    /** Sender credit snapshot: the limit and the charged bytes sent. */
    var creditLimit = 0L; var creditSent = 0L
    /** Current credit limit piggybacked on an ACK (cumulative grants, v0.6). */
    var grantsPiggybacked = 0L
    /** Payload bytes handed to the application (messages delivered). */
    var payloadBytesOut = 0L
    /** Receiver snapshot: lowest fec seq not delivered, largest seen, messages still waiting for a fragment. */
    var lowestUndeliveredFec = 0L; var largestFecSeen = -1L; var reassemblyPending = 0
    /** Confirmed-lost sources whose retained symbol was already evicted (BODY_RING): unrecoverable by re-send. */
    var resendEvicted = 0L
    /** Re-send queue activity: queued for lack of a token, drained, cancelled (the packet turned out acked meanwhile). */
    var resendQueued = 0L; var resendDrained = 0L; var resendCancelled = 0L
    /** Source packets skipped by the receiver because their fec was already delivered (re-sends of recovered sources). */
    var skipDelivered = 0L
    var probesSent = 0L; var probesLost = 0L; var probeBytesSent = 0L; var creditProbes = 0L
    var challengesSent = 0L; var responsesSent = 0L; var replyResends = 0L
    var creditStalls = 0L; var cwndStalls = 0L; var ampStalls = 0L; var ampLimited = 0L
    /** Time send() spent blocked on credit / cwnd / validation (by the limiter that bound when it blocked), and the receiver credit's current target (snapshot). */
    var stallUs = 0L; var creditStallUs = 0L; var cwndStallUs = 0L; var creditTargetBytes = 0L
    var bytesSent = 0L; var sourceBytesSent = 0L; var maxDatagramSent = 0; var oversized = 0L
    var payloadBytesIn = 0L; var codecBytesOut = 0L; var codecErrors = 0L
    var packetsReceived = 0L; var sourcesReceived = 0L; var repairsReceived = 0L; var recovered = 0L; var bytesReceived = 0L
    var acksReceived = 0L; var grantsReceived = 0L; var authFail = 0L; var dups = 0L; var gapsSeen = 0L
    var messagesDelivered = 0L; var unknownPath = 0L; var migrations = 0L
    var keyUpdates = 0L; var keyUpdatesFollowed = 0L; var lossesDetected = 0L; var ccLossEvents = 0L; var ccLossIgnored = 0L
    /** Packets the tracker had declared lost (packet threshold) that were acked inside the reordering window: reordering, not loss. */
    var lateAcks = 0L
    /** Exceptions while parsing an authenticated packet's frames, and while parsing a repair-decoded source symbol. */
    var rxErrors = 0L; var decodeErrors = 0L; var firstRxError: String? = null
    /**
     * Flow-control drops: a fragment whose offset+len exceeded maxMessageBytes or contradicted the message's
     * fin-established length / buffered extent, and a fragment refused for lack of a reassembly slot or byte budget.
     */
    var oversizeDropped = 0L; var reassemblyRefused = 0L
    /** CONNECTION_CLOSE frames sent / received, and the last code the peer sent. */
    var closeSent = 0L; var closeReceived = 0L; var peerCloseCode = -1
    /**
     * Stateless resets. [resetsReceived] counts resets this (client) connection recognised and tore down on. [resetsSent]
     * is a connection's own emissions and stays 0 in this design — a stateless reset is emitted by the *endpoint* for a
     * short packet whose id matches no connection (a connection, by definition, is never the one that lost its keys), so
     * the server's emission tally lives on [TesseraServer.resetsSent].
     */
    var resetsSent = 0L; var resetsReceived = 0L
    // snapshot-only fields (filled by TesseraConnection.stats)
    var ccMode = "UNLIMITED"; var cwndLimited = 0L; var grantLimited = 0L; var cwnd = 0L
    var plpmtu = 0; var pmtudState = ""; var tagLen = 0; var dictId = 0L; var keyGeneration = 0; var pathValidated = false
    var reoWndUs = 0L
    var burstMean = 1.0; var burstP95 = 1; var fecRedundancy = 0.0

    fun copy(): ConnStats = ConnStats().also { d ->
        d.packetsSent = packetsSent; d.sourcesSent = sourcesSent; d.repairsProactive = repairsProactive; d.repairsReactive = repairsReactive
        d.repairsTlp = repairsTlp; d.repairsTail = repairsTail; d.sourceResends = sourceResends; d.resendEvicted = resendEvicted; d.resendQueued = resendQueued; d.resendDrained = resendDrained; d.resendCancelled = resendCancelled; d.skipDelivered = skipDelivered; d.acksSent = acksSent; d.grantsSent = grantsSent
        d.grantResends = grantResends; d.simDropped = simDropped; d.probesSent = probesSent; d.probesLost = probesLost; d.probeBytesSent = probeBytesSent
        d.creditProbes = creditProbes; d.challengesSent = challengesSent; d.responsesSent = responsesSent; d.replyResends = replyResends
        d.creditStalls = creditStalls; d.cwndStalls = cwndStalls; d.ampStalls = ampStalls; d.ampLimited = ampLimited; d.stallUs = stallUs; d.creditStallUs = creditStallUs; d.cwndStallUs = cwndStallUs; d.creditTargetBytes = creditTargetBytes
        d.bytesSent = bytesSent; d.sourceBytesSent = sourceBytesSent; d.maxDatagramSent = maxDatagramSent; d.oversized = oversized
        d.payloadBytesIn = payloadBytesIn; d.codecBytesOut = codecBytesOut; d.codecErrors = codecErrors
        d.packetsReceived = packetsReceived; d.sourcesReceived = sourcesReceived; d.repairsReceived = repairsReceived; d.recovered = recovered
        d.bytesReceived = bytesReceived; d.acksReceived = acksReceived; d.grantsReceived = grantsReceived; d.authFail = authFail; d.dups = dups
        d.gapsSeen = gapsSeen; d.messagesDelivered = messagesDelivered; d.unknownPath = unknownPath; d.migrations = migrations
        d.keyUpdates = keyUpdates; d.keyUpdatesFollowed = keyUpdatesFollowed; d.lossesDetected = lossesDetected; d.ccLossEvents = ccLossEvents
        d.ccLossIgnored = ccLossIgnored; d.lateAcks = lateAcks; d.rxErrors = rxErrors; d.decodeErrors = decodeErrors; d.firstRxError = firstRxError
        d.oversizeDropped = oversizeDropped; d.reassemblyRefused = reassemblyRefused
        d.closeSent = closeSent; d.closeReceived = closeReceived; d.peerCloseCode = peerCloseCode
        d.resetsSent = resetsSent; d.resetsReceived = resetsReceived
        d.ccMode = ccMode; d.cwndLimited = cwndLimited; d.grantLimited = grantLimited; d.cwnd = cwnd; d.plpmtu = plpmtu; d.pmtudState = pmtudState
        d.tagLen = tagLen; d.dictId = dictId; d.keyGeneration = keyGeneration; d.pathValidated = pathValidated; d.reoWndUs = reoWndUs
        d.gapResends = gapResends; d.gapThrottled = gapThrottled; d.outageDrains = outageDrains; d.grantsPiggybacked = grantsPiggybacked; d.payloadBytesOut = payloadBytesOut
        d.resendKnown = resendKnown; d.resendUnknown = resendUnknown; d.resendFeedback = resendFeedback; d.creditLimit = creditLimit; d.creditSent = creditSent
        d.burstMean = burstMean; d.burstP95 = burstP95; d.fecRedundancy = fecRedundancy
        d.lowestUndeliveredFec = lowestUndeliveredFec; d.largestFecSeen = largestFecSeen; d.reassemblyPending = reassemblyPending
    }
    val repairsSent get() = repairsProactive + repairsReactive + repairsTlp + repairsTail
    override fun toString() = "sent=$packetsSent src=$sourcesSent repair(pro=$repairsProactive react=$repairsReactive tlp=$repairsTlp tail=$repairsTail) resend=$sourceResends(ack-driven=$gapResends: known=$resendKnown unknown=$resendUnknown feedback=$resendFeedback; throttled=$gapThrottled drains=$outageDrains evicted=$resendEvicted q=$resendQueued d=$resendDrained x=$resendCancelled) skipDelivered=$skipDelivered " +
        "acks=$acksSent grants=$grantsSent(+$grantResends re, $grantsPiggybacked in acks) probes=$probesSent dropSim=$simDropped bytes=$bytesSent | " +
        "rcvd=$packetsReceived src=$sourcesReceived repairs=$repairsReceived recovered=$recovered gaps=$gapsSeen dups=$dups authFail=$authFail " +
        "msgs=$messagesDelivered bytes=$bytesReceived payload=$payloadBytesOut fec(lowestUndelivered=$lowestUndeliveredFec largest=$largestFecSeen reassembling=$reassemblyPending) | stalls(credit=$creditStalls/${creditStallUs / 1000}ms cwnd=$cwndStalls/${cwndStallUs / 1000}ms amp=$ampStalls, total ${stallUs / 1000}ms) credit(target=$creditTargetBytes limit=$creditLimit sent=$creditSent) lost=$lossesDetected lateAcks=$lateAcks reoWnd=${reoWndUs}us " +
        String.format(java.util.Locale.ROOT, "burst(mean=%.1f p95=%d) fec=%.3f ", burstMean, burstP95, fecRedundancy) +
        "ccLoss=$ccLossEvents/${ccLossEvents + ccLossIgnored} migrations=$migrations keyUpdates=$keyUpdates rxErrors=$rxErrors decodeErrors=$decodeErrors oversizeDropped=$oversizeDropped reassemblyRefused=$reassemblyRefused close(sent=$closeSent rcvd=$closeReceived code=$peerCloseCode) reset(sent=$resetsSent rcvd=$resetsReceived)${firstRxError?.let { " first=$it" } ?: ""} | " +
        "ccMode=$ccMode cwnd=$cwnd plpmtu=$plpmtu($pmtudState) tagLen=$tagLen dictId=$dictId"
}

/**
 * Per-path state: one packet-number space, one [AckTracker] (both directions), one [HybridCc], one [PathValidation],
 * one [Pmtud]. The connection owns FEC, messages and crypto.
 * [[MULTIPATH]] A second path is a second instance registered in TesseraConnection.paths; [pnMask] folds the path id
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
        pmtud = Pmtud(basePlpmtu = base, maxPlpmtu = maxDatagram, minPlpmtu = base, raiseMinUs = TesseraConnection.PMTU_RAISE_MIN_US)
        lastGrantRxUs = nowUs; setupUs = nowUs; rateStartUs = nowUs
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
    /** Token bucket for the ack-driven path's verbatim re-sends: cfg.gapRepairFraction per source (+ a trickle per tick), capped at GAP_BUDGET_MAX. */
    var gapBudget = 4.0
    /** Lowest-undelivered seq at which an outage burst was last granted: one grant per hole, re-armed as the edge moves. */
    var outageDrainedThrough = -1L
    /** A deficit found no token: the timer re-runs the accounting once the bucket has one (nothing else would, once the acks have dried up). */
    var deficitPending = false
    var lastFeedbackRunUs = 0L
    var lossExpected = 0; var lossLost = 0
    /** Spurious losses (acked after all) whose window was already observed: paid off against the next windows' losses. */
    var lossDebt = 0
    var lastElicitingSendUs = 0L; var lastDataPn = -1L; var tlpBackoff = 0
    var lastSourceSendUs = 0L; var lastRepairSendUs = 0L; var tailArmed = false
    var lastGrantRxUs = 0L; var lastCreditProbeUs = 0L; var creditProbeBackoffUs = 0L
    /** When the credit limit last grew (diagnostics). */
    var lastLimitGrowthUs = 0L
    /** When the current send() started waiting for credit (0 = not waiting on credit), and whether any send waited on credit since the last probe. */
    var blockedSinceUs = 0L; var stalledSinceProbe = false
    var setupUs = 0L; var lastCreditTickUs = 0L
    var waitBytes = 0
    var lastTxUs = 0L
    /** EWMA of the gap between consecutive source sends: a steady stream (gap < 2 T) needs no per-packet tail repair. */
    var sendGapEwmaUs = 0.0
    /**
     * Reordering window (RACK reo_wnd shape). A packet the tracker declares lost by packet threshold is confirmed lost only
     * once rtt + reoWnd have passed since it was sent; an ack that arrives meanwhile ("late ack") was reordering, not loss,
     * and widens the window (capped at srtt). 0 until reordering has been seen. Declared-but-unconfirmed losses wait in
     * the [pendPn]/[pendDue] ring (oldest first).
     */
    var reoWndUs = 0L; var lastRttSampleUs = 0L; var lastLateAckUs = 0L
    val pendPn = LongArray(PENDING); val pendDue = LongArray(PENDING); var pendHead = 0; var pendCount = 0
    /** Recently confirmed losses (pn, time), newest at lostNext - 1: an ack for one of them is the DSACK equivalent. */
    val lostPn = LongArray(LOST_RING) { -1L }; val lostAt = LongArray(LOST_RING); var lostNext = 0; var lostCount = 0
    /** Windowed send / delivery rates (bytes/s, EWMA over max(2 srtt, 20 ms) windows) for the congestion test in ccLoss. */
    var sendBytesPerSec = 0.0; var deliveredBytesPerSec = 0.0
    var rateStartUs = 0L; var rateSentBytes = 0L; var rateAckedBytes = 0L
    /** Consecutive windows in which delivery fell below CC_DELIVERY_FRAC of the send rate. */
    var starvedWindows = 0
    /** Re-sends held back by the amplification limit (fec seqs, oldest first); drained as soon as the budget allows. */
    val resendQ = LongArray(RESEND_Q); var resendQHead = 0; var resendQCount = 0

    // ---- rx ----
    var largestSeen = 0L                  // pn 0 (handshake packet) already seen; reference for truncated-pn decoding
    private val rxBits = LongArray(RX_BITS / 64).also { it[0] = 1L }   // anti-replay window (AckTracker keeps ranges, not a set)
    var lastRxUs = 0L; var lastElicitingRxUs = 0L
    var avgRxBytes = Wire.MAX_DATAGRAM.toDouble()
    var lastGrant: Frame.Grant? = null
    var lastGrantResendUs = 0L; var grantResendBackoffUs = 0L; var grantResendsSinceRx = 0
    var lastChallengeUs = 0L; var challengeBackoffUs = 0L
    /** Last time a packet arrived below the largest seen (filled a hole): the path reorders, so ACKs carry every range. */
    var lastLateArrivalUs = 0L

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
        /** Tx packet ring (pns); must outlast TesseraConnection.BODY_RING's retention so a re-sent source can still be tracked. */
        const val RING = 8192
        const val RX_BITS = 2048
        /** Capacity of the deferred-loss ring (a reorder window's worth of in-flight packets; the oldest is confirmed when full). */
        const val PENDING = 512
        /** Confirmed losses remembered for spurious-loss detection (an ack arriving for one of them): must outlast the
         *  reordering extent at full rate (a reorder burst can confirm hundreds of packets at once before the window is learned). */
        const val LOST_RING = 1024
        /** Capacity of the re-send queue; beyond it a confirmed loss is re-sent at once rather than forgotten. */
        const val RESEND_Q = 1024
        const val BASE_PLPMTU = 1200
        val RNG = SecureRandom()
    }
}

/**
 * One Tessera connection (v0.3): header-protected short packets with key-phase updates, packet-level systematic RLNC
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
 *   [0x82 00]            local extension frame: credit probe (ack-eliciting; the receiver doubles its target and re-sends the limit)
 *   [0x83 24 lowest16 largest16 bits(32)]   FEC feedback, on every ACK: the lowest fec seq not yet delivered (everything
 *                        below it is), the largest seen, and a 256-bit delivered map for [lowest, lowest+256) - delivered
 *                        = the source arrived or was recovered from repairs. Anchored at the oldest hole (like SACK blocks
 *                        above the cumulative ack) so the seqs that matter are reported exactly however large the
 *                        bandwidth-delay product is; the sender's ack-driven repair path never re-sends what is reported
 *                        delivered and re-sends what is reported undelivered once no repair still in flight can bring it
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
class TesseraConnection internal constructor(
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
    /**
     * Client side: the 16-byte stateless-reset token the server minted for its own [ConnParams.shortConnId] and sent us
     * at handshake ([onHandshakeReply]). If that server restarts and loses our keys, it re-emits this token in a reset
     * packet; recognising it lets us tear down at once instead of retransmitting until the idle timeout. Null when the
     * server offered none (an older server, or one with no reset secret). See [StatelessReset].
     */
    @Volatile internal var peerResetToken: ByteArray? = null
    /**
     * Server side: the reset secret to mint *our own* token from at handshake, set by [TesseraServer] at accept. It is
     * derived from the ticket key, so it survives a restart and lets a restarted server recompute the token statelessly.
     * Null on the client and wherever no reset secret is configured (then [buildHandshakeReply] appends no token).
     */
    @Volatile internal var ownResetSecret: ByteArray? = null
    /** Feed this to TesseraClient.resume together with [ticket]. */
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
    /** Successor decoder during a rotation overlap (see [maybeRotateDecoder]): learns every source and every repair whose window lies at or above [decNextBase]. */
    private var decNext: RlncDecoder? = null
    private var decNextBase = 0L
    private var decNextTakeoverAt = 0L
    private var nextFecSeq = 0L
    private var encBase = 0L
    private var repairCredit = 0.0
    private var repairSeed = 0x5A5A
    private var nextMsgId = if (isClient) 1L else 0L     // client msg 0 = the 0-RTT first flight
    /** Body length per fec seq (repair trimming) and retained source symbols (PTO retransmission). */
    private val bodyLenRing = IntArray(BODY_RING)
    private val symRing = arrayOfNulls<ByteArray>(BODY_RING)
    private val symRingFec = LongArray(BODY_RING) { -1L }
    /** When the retained symbol last left (original send or re-send): the feedback-driven re-send waits a loss timeout after it. */
    private val symRingSentUs = LongArray(BODY_RING)

    // preallocated rx/tx scratch (BC ciphers need heap arrays)
    private val rxScratch = ByteArray(RX_BUF)
    private val rxPlain = ByteArray(RX_BUF)
    private val rxPlainBuf: ByteBuffer = ByteBuffer.wrap(rxPlain)
    private val txScratch = ByteArray(RX_BUF)
    private var largestFecSeen = -1L
    private val deliveredBits = LongArray(DELIVERED_BITS / 64)
    /** Lowest fec seq not yet delivered to the application (received or recovered): the cumulative edge of the FEC feedback frame. */
    private var lowestUndeliveredFec = 0L
    // what the peer last reported about our sources (FEC feedback, see [onFecFeedback]); peerLargestFec < 0 until the first report
    private var peerLargestFec = -1L; private var peerLowestUndelivered = 0L; private val peerBits = LongArray(FEC_FEEDBACK_WORDS)
    private var decoderEpoch = 0L
    private val reassembler = Reassembler(cfg.maxMessageBytes, cfg.maxConcurrentReassembly, cfg.maxReassemblyBytes)
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private val missFec = LongArray(DEFICIT_SCAN_BACK + DEFICIT_SCAN_FWD)
    private val repLo = LongArray(DEFICIT_SCAN_BACK + DEFICIT_SCAN_FWD); private val repHi = LongArray(DEFICIT_SCAN_BACK + DEFICIT_SCAN_FWD); private val repUsed = BooleanArray(DEFICIT_SCAN_BACK + DEFICIT_SCAN_FWD)
    private val statsImpl = ConnStats()
    @Volatile private var closed = false
    /** close() called: no new sends; the connection lingers until nothing it sent needs re-sending (ConnConfig.closeLingerMs). */
    @Volatile private var closing = false; private var closeStartUs = 0L
    /** The peer sent a CONNECTION_CLOSE: we free state at once rather than lingering, and do not send a CLOSE back. */
    @Volatile private var peerClosed = false
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
    /** Test hook: drop every Grant frame we would send - standalone grant packets and the limit piggybacked on ACKs (a grant blackout). */
    @Volatile internal var suppressGrants = false
    private var held: ByteBuffer? = null; private var heldTo: InetSocketAddress? = null

    /** True once close() was called (the connection may still linger to get its last packets acknowledged). */
    val isClosed get() = closed || closing
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
                    s.cwnd = path0.cc.cwnd; s.plpmtu = path0.pmtud.plpmtu; s.pmtudState = path0.pmtud.state.name; s.reoWndUs = path0.reoWndUs
                    s.burstMean = path0.estimator.burstMean; s.burstP95 = path0.estimator.burstP95; s.fecRedundancy = path0.estimator.fecRedundancy()
                    s.creditTargetBytes = path0.receiverCredit.targetBytes; s.creditLimit = path0.senderCredit.limit; s.creditSent = path0.senderCredit.sent
                    s.lowestUndeliveredFec = lowestUndeliveredFec; s.largestFecSeen = largestFecSeen; s.reassemblyPending = reassembler.pending
                    s.oversizeDropped = reassembler.oversizeDropped; s.reassemblyRefused = reassembler.refused
                }
            }
        }

    // ------------------------------------------------------------------ app API

    /** Blocks only when receiver credit / cwnd / path validation holds the send back (up to cfg.creditWaitMs). Thread-safe. */
    fun send(msg: ByteArray) {
        check(!closed && !closing) { "closed" }
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
            io.flush()   // the last message of a stream must reach the socket even if this thread was in a deferred datapath mode
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

    /**
     * Graceful close: no more sends, but the connection stays registered and keeps its timers until everything it sent
     * is acknowledged (data through the PTO / repair machinery; on the server also the handshake reply, re-sent for a
     * retransmitted initial) or [ConnConfig.closeLingerMs] has passed. What was received is acknowledged on the way out
     * so the peer's own linger ends at once. Dropping the state immediately was netem finding 3: a server that answers
     * and closes could neither re-send a lost reply (the retransmitted initial hit the 0-RTT replay filter) nor its lost
     * first data.
     */
    override fun close() {
        lock.withLock {
            if (closed || closing) return
            closing = true; closeStartUs = nowUs()
            creditAvailable.signalAll()
            if (ready) for (p in paths) { p ?: continue; if (p.tracker.ackTimer(closeStartUs) != null) sendAck(p, closeStartUs, force = true) }
            io.flush()   // this thread's send() may have been the last producer; never leave its bytes parked (native TxBatch)
            if (peerClosed || !lingerNeeded()) finishClose()
        }
    }

    /**
     * The peer sent a CONNECTION_CLOSE. Free our state at once rather than lingering for its idle timeout. Called under
     * the lock from [parseFrames]; the packet that carried this is eliciting, so an ack still goes out afterward and
     * the peer's own linger ends. Already-delivered messages remain readable in the inbox.
     */
    private fun onPeerClose(f: Frame.Close) {
        if (peerClosed) return
        peerClosed = true; statsImpl.closeReceived++; statsImpl.peerCloseCode = f.code
        creditAvailable.signalAll()
        if (!closed) { closing = true; closeStartUs = nowUs(); finishClose() }
    }

    /**
     * A stateless reset arrived for this connection: a restarted/crashed server that lost our keys proved it once knew
     * this connection by echoing the reset token it gave us at handshake ([peerResetToken]). It cannot send an
     * authenticated CONNECTION_CLOSE, so this is the only teardown signal it can produce. Mirror [onPeerClose] — free
     * our state at once rather than retransmitting into a black hole until the idle timeout. We send nothing back (the
     * peer holds no keys); marking [peerClosed] both suppresses the CLOSE frame in [finishClose] and is idempotent
     * against a duplicate reset. Already-delivered messages stay readable in the inbox. Called from the rx thread's
     * unmatched-short hook (no lock held there); takes the lock like [onPeerClose].
     */
    internal fun onStatelessReset() = lock.withLock {
        if (peerClosed || closed) return@withLock
        peerClosed = true; statsImpl.resetsReceived++
        creditAvailable.signalAll()
        closing = true; closeStartUs = nowUs(); finishClose()
    }

    private fun finishClose() {
        // Announce the close once, only when we initiated it (not on an idle timeout, and never back to a peer that
        // already told us it is closing) and only now that any linger is done — telling the peer earlier, while our
        // own reply/data was still unacked and being re-sent, would make it drop before that data arrived. Best effort
        // and non-eliciting: we are unregistering, so we do not wait for an ack; a lost CLOSE falls back to the peer's
        // idle timeout.
        if (ready && closing && !peerClosed && statsImpl.closeSent == 0L) {
            packet(path0, KIND_PING, 0, 0, eliciting = false, charge = false) { Frame.Close(0, "").write(it) }
            statsImpl.closeSent++
        }
        closed = true; creditAvailable.signalAll(); io.flush(); io.unregister(this)
    }

    /** Something we sent may still need re-sending: unacked data (or a probe carrying it) or, on the server, the handshake reply. */
    private fun lingerNeeded(): Boolean {
        if (!ready) return false
        if (!isClient && !replyAcked) return true
        for (p in paths) { p ?: continue; if (p.tracker.bytesInFlight > 0 || p.lastDataPn > p.tracker.largestAcked || p.pendCount > 0 || p.resendQCount > 0 || p.deficitPending) return true }
        return false
    }

    /** Move this connection to another socket (client rebinding / NAT rebinding as seen by the server). */
    internal fun rebind(newIo: UdpIo) = lock.withLock { io.unregister(this); newIo.register(this); io = newIo }

    /**
     * Routes this connection's packets (from now on: short packets and handshake-reply re-sends; the endpoint sends the
     * initial / first reply itself) through [sim]. [ConnConfig.netem] impairs the whole endpoint instead. Test/bench hook.
     */
    fun attachNetem(sim: NetemSim) = lock.withLock { io = NetemUdpIo(io, sim) }

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
        enc = RlncEncoder(symbolSize, cfg.fecWindow); dec = RlncDecoder(symbolSize, fecValidator)
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
        val mode = path.cc.mode
        when (mode) {
            HybridCc.Mode.GRANT_LIMITED -> statsImpl.creditStalls++
            HybridCc.Mode.CWND_LIMITED -> statsImpl.cwndStalls++
            HybridCc.Mode.UNLIMITED -> statsImpl.ampStalls++
        }
        path.waitBytes = bytes; waiters++
        val t0 = System.nanoTime()
        if (mode == HybridCc.Mode.GRANT_LIMITED) { path.blockedSinceUs = nowUs(); path.stalledSinceProbe = true }
        try {
            val deadline = t0 + cfg.creditWaitMs * 1_000_000L
            while (!sendAllowed(path, bytes)) {
                val left = deadline - System.nanoTime()
                if (closed) throw IllegalStateException("closed")
                if (left <= 0) throw IllegalStateException("send blocked for ${cfg.creditWaitMs}ms (${path.cc.mode}, validated=${path.pv.validated})")
                creditAvailable.awaitNanos(min(left, 1_000_000L)) // the timer thread may free in-flight bytes without signalling
            }
        } finally {
            waiters--; path.waitBytes = 0; path.blockedSinceUs = 0
            val us = (System.nanoTime() - t0) / 1000
            statsImpl.stallUs += us
            if (mode == HybridCc.Mode.GRANT_LIMITED) statsImpl.creditStallUs += us else if (mode == HybridCc.Mode.CWND_LIMITED) statsImpl.cwndStallUs += us
        }
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
        val si = (fec and BODY_RING_MASK).toInt(); symRing[si] = sym; symRingFec[si] = fec; symRingSentUs[si] = now
        encBase = max(encBase, fec - cfg.fecWindow + 1)
        statsImpl.sourcesSent++
        if (path.lastSourceSendUs > 0) {   // inter-send gap EWMA (samples capped at 8x so one pause does not mask the stream for long)
            val gap = (now - path.lastSourceSendUs).toDouble()
            path.sendGapEwmaUs = if (path.sendGapEwmaUs == 0.0) gap else 0.8 * path.sendGapEwmaUs + 0.2 * min(gap, 8 * path.sendGapEwmaUs)
        }
        path.lastSourceSendUs = now; path.tailArmed = true
        path.gapBudget = min(path.gapBudget + cfg.gapRepairFraction, GAP_BUDGET_MAX)
        // proactive repair: emit one repair symbol per 1/redundancy source symbols (adaptive, floor minRedundancy)
        repairCredit += max(cfg.minRedundancy, path.estimator.fecRedundancy())
        if (repairCredit >= 1.0) { repairCredit -= 1.0; sendRepair(scheduler.repairPathFor(path.id), REPAIR_PROACTIVE, now) }
    }

    /** Retransmits a retained source symbol verbatim (same fec seq, new pn) — a PTO probe that carries the lost data. */
    private fun resendSource(path: PathState, fec: Long, sym: ByteArray, now: Long) {
        val len = ((sym[0].toInt() and 0xFF) shl 8) or (sym[1].toInt() and 0xFF)
        packet(path, KIND_RESEND, fec, fec + 1, eliciting = true, charge = true) { it.put(sym, 2, len) }
        symRingSentUs[(fec and BODY_RING_MASK).toInt()] = now
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

    /**
     * ACK, with the ranges capped at cfg.maxAckRanges (largest first) — unless the path has delivered a packet out of
     * order in the last [ACK_FULL_RANGES_US]: under reordering a late packet lands in an old range, and a cap that drops
     * it from the report before the sender has seen it acked turns reordering into spurious loss (netem wifi-busy) —
     * and, once this side has issued a grant, the current cumulative credit limit piggybacked as a Grant frame: grants
     * are idempotent (v0.6), so every ACK re-advertises the limit and a lost standalone grant is superseded by the next
     * ACK ~1 ms later on a busy stream.
     */
    private fun sendAck(path: PathState, now: Long, force: Boolean = false) {
        val a0 = path.tracker.ackFrameIfDue(now, force) ?: return   // allocates: Frame.Ack + ranges list (core API)
        val cap = if (now - path.lastLateArrivalUs < ACK_FULL_RANGES_US) AckTracker.MAX_RANGES else cfg.maxAckRanges
        val a = if (a0.ranges.size > cap) Frame.Ack(a0.path, a0.largest, a0.ranges.subList(0, cap), a0.ecnCe, a0.rxTimeUs) else a0
        val piggyback = path.receiverCredit.hasGranted && !suppressGrants
        val limit = path.receiverCredit.limit
        packet(path, KIND_ACK, 0, 0, eliciting = false, charge = false) { buf ->
            a.write(buf)
            if (piggyback) buf.put(0x03).put(path.id.raw.toByte()).putLong(limit).put(0)   // Frame.Grant, written inline (no allocation)
            if (largestFecSeen >= 0) writeFecFeedback(buf)
        }
        statsImpl.acksSent++
        if (piggyback) statsImpl.grantsPiggybacked++
    }

    /** FEC feedback extension frame (see the class docs): 38 bytes, written inline. */
    private fun writeFecFeedback(buf: ByteBuffer) {
        val lowest = lowestUndeliveredFec
        buf.put(FEC_FEEDBACK_FRAME.toByte()).put(FEC_FEEDBACK_LEN.toByte())
        buf.putShort(lowest.toShort()).putShort(largestFecSeen.toShort())
        for (w in 0 until FEC_FEEDBACK_WORDS) {
            var word = 0L
            for (b in 0 until 64) { val seq = lowest + w * 64 + b; if (seq <= largestFecSeen && isDelivered(seq)) word = word or (1L shl b) }
            buf.putLong(word)
        }
    }

    /** Advances the cumulative delivered edge (after every packet that stored or recovered sources). */
    private fun advanceLowestUndelivered() { while (lowestUndeliveredFec <= largestFecSeen && isDelivered(lowestUndeliveredFec)) lowestUndeliveredFec++ }

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

    /**
     * Credit probe: "I am blocked on credit" (ack-eliciting, so the ACK brings the current limit too). The receiver
     * doubles its target on it — this is the slow-start signal now that the limit slides with every ACK and the
     * receiver can no longer see a drained grant — and re-sends the limit. Sent whenever a send ran dry since the
     * last probe, at most every half RTT, so the target doubles up to 4x per RTT while sends keep running dry and
     * overshoots the needed 2 x BDP by at most 4x. (Probing every quarter RTT doubled it for every probe in flight
     * and hit the 8 MB cap; requiring a quarter RTT of continuous blocking never fired against a target near the
     * BDP, which shows as hundreds of millisecond stalls rather than one long one — 232 stalls on the starlink
     * profile — and left the sender pacing at the receiver's guess.) Probes back off only while they go unanswered
     * (no grant since the last one: a blackout).
     */
    private fun sendCreditProbe(path: PathState, now: Long) {
        packet(path, KIND_PING, 0, 0, eliciting = true, charge = false) { it.put(CREDIT_PROBE_FRAME.toByte()).put(0); Frame.Ping.write(it) }
        path.creditProbeBackoffUs = if (path.lastGrantRxUs >= path.lastCreditProbeUs) 0L else min(max(path.creditProbeBackoffUs * 2, cfg.creditProbeMinUs), cfg.probeBackoffMaxUs)
        path.lastCreditProbeUs = now
        statsImpl.creditProbes++
    }

    /** `copies` > 1 sends the same challenge (one nonce) in a train: PathValidation keeps only the last 3 nonces, and minting one
     *  per copy would evict the nonce a slow (high-RTT) answer is about to carry. */
    private fun sendChallenge(path: PathState, now: Long, copies: Int = 1) {
        val c = path.pv.challenge()  // allocates: Frame.PathChallenge (rare)
        repeat(copies) { packet(path, KIND_PATH, 0, 0, eliciting = true, charge = false) { c.write(it) } }
        path.lastChallengeUs = now
        path.challengeBackoffUs = min(max(path.challengeBackoffUs * 2, cfg.grantResendMinUs), cfg.probeBackoffMaxUs)
        statsImpl.challengesSent += copies
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
        path.rateSentBytes += size
        if (eliciting) { path.lastElicitingSendUs = now; if (kind == KIND_SOURCE || kind == KIND_REPAIR || kind == KIND_RESEND) path.lastDataPn = pn }
        path.lastTxUs = now; lastTxUs = now
        statsImpl.packetsSent++; statsImpl.bytesSent += size
        if (kind == KIND_SOURCE || kind == KIND_RESEND) statsImpl.sourceBytesSent += size
        if (kind != KIND_PROBE) { if (size > statsImpl.maxDatagramSent) statsImpl.maxDatagramSent = size; if (size > path.pmtud.plpmtu) statsImpl.oversized++ }
        tracer.packetSent(path.id, pn, size, TX_FRAMES[kind.toInt()], now)
        val f = txFilter
        if (f != null && f(kind, pn, size)) { statsImpl.simDropped++; return }
        if (kind == KIND_GRANT && suppressGrants) { statsImpl.simDropped++; return }
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
            } else path.lastLateArrivalUs = now
            path.rxSet(pn)
            path.lastRxUs = now; lastRxUs = now
            statsImpl.packetsReceived++; statsImpl.bytesReceived += len
            rxPlainBuf.limit(n).position(0)
            pEliciting = false; pNonProbing = false; pHasChallenge = false; pCreditProbe = false; pPrimary = 0
            try { parseFrames(rxPlainBuf, path, n, recovered = false, now = now) } catch (e: Exception) { rxError(e) }
            advanceLowestUndelivered()
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
            if (pCreditProbe) { path.receiverCredit.onSenderBlocked(); maybeGrant(path, now, timer = true); resendGrant(path, now, solicited = true) }
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
                        if (isDelivered(fec)) { skipMsgs = true; statsImpl.skipDelivered++ } // already delivered (recovered via repair, or a re-send)
                        else { storeSource(fec, bodyLen); markDelivered(fec) }
                    }
                }
                t == Frame.Padding.TYPE -> { buf.position(buf.position() + 2 + (buf.get(buf.position() + 1).toInt() and 0xFF)); if (pPrimary == 0) pPrimary = RXF_PADDING }
                t == CREDIT_PROBE_FRAME -> { buf.position(buf.position() + 2); pEliciting = true; pNonProbing = true; pCreditProbe = true; if (pPrimary == 0) pPrimary = RXF_PING }
                t == FEC_FEEDBACK_FRAME -> {
                    buf.get(); val len = buf.get().toInt() and 0xFF; val end = buf.position() + len
                    if (len >= FEC_FEEDBACK_LEN) {
                        val lowest = ShortHeader.decodePn(buf.getShort().toLong() and 0xFFFF, 16, nextFecSeq - 1)   // relative to what we have sent
                        val largest = ShortHeader.decodePn(buf.getShort().toLong() and 0xFFFF, 16, nextFecSeq - 1)
                        onFecFeedback(lowest, largest, buf)
                    }
                    buf.position(end); pNonProbing = true; if (pPrimary == 0) pPrimary = RXF_ACK
                }
                t == 0x02 -> { onAck(FrameCodec.read(buf) as Frame.Ack, now); pNonProbing = true; if (pPrimary == 0) pPrimary = RXF_ACK } // allocates Ack + ranges (core API)
                t == 0x03 -> {
                    val g = FrameCodec.read(buf) as Frame.Grant
                    paths[g.path.raw and 7]?.let { p ->
                        val before = p.senderCredit.limit
                        p.cc.onGrant(g); p.lastGrantRxUs = now; p.creditProbeBackoffUs = 0
                        if (p.senderCredit.limit > before) p.lastLimitGrowthUs = now
                    }
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
                t == Frame.Close.TYPE -> { onPeerClose(FrameCodec.read(buf) as Frame.Close); pEliciting = true; if (pPrimary == 0) pPrimary = RXF_ACK }
                else -> { FrameCodec.read(buf) ?: break; pEliciting = true; pNonProbing = true } // unknown extension: skipped
            }
        }
    }

    private fun storeSource(fec: Long, bodyLen: Int) {
        if (fec > largestFecSeen) advanceFec(fec)
        maybeRotateDecoder(fec)
        // allocates: one symbol per received source — RlncDecoder.onSource keeps the reference (core API)
        val sym = ByteArray(symbolSize)
        sym[0] = (bodyLen shr 8).toByte(); sym[1] = bodyLen.toByte()
        System.arraycopy(rxPlain, 0, sym, 2, min(bodyLen, symbolSize - 2))
        dec!!.onSource(fec, sym)
        decNext?.let { if (fec >= decNextBase) it.onSource(fec, sym) }
    }

    /**
     * RlncDecoder.known never evicts (core), so the decoder is replaced every DECODER_ROTATE seqs. v0.5 cut over at
     * once, re-feeding the last window's sources into the fresh decoder: the equations still being solved were lost
     * with the old decoder, and a repair arriving after the cut whose window reached below the re-fed range (a repair
     * is emitted up to MAX_FEC_WINDOW sources after its oldest source and then spends an RTT in flight) brought
     * sources the fresh decoder did not know, i.e. a useless equation — ~6 of the sources lost around every rotation
     * were never recovered, and the sender's deficit accounting, seeing their repairs acked, never re-sent them. Now
     * the successor runs alongside its predecessor for DECODER_OVERLAP seqs: started empty at `fec`, it learns every
     * source and every repair whose window lies entirely at or above its base, and takes over once every repair still
     * to arrive (windows of MAX_FEC_WINDOW sources, at most ~an RTT of sources in flight) lies within what it knows.
     */
    private fun maybeRotateDecoder(fec: Long) {
        val next = decNext
        if (next != null) {
            if (fec >= decNextTakeoverAt) { dec = next; decNext = null; decoderEpoch = decNextBase }
        } else if (fec - decoderEpoch >= DECODER_ROTATE) {
            decNext = RlncDecoder(symbolSize, fecValidator); decNextBase = fec; decNextTakeoverAt = fec + DECODER_OVERLAP
        }
    }

    private fun onRepair(r: Frame.Repair, path: PathState, now: Long) {
        statsImpl.repairsReceived++
        if (r.windowLen <= 0 || r.windowLen > MAX_FEC_WINDOW) return
        maybeRotateDecoder(r.windowBase + r.windowLen - 1)
        val d = dec!!
        d.onRepair(r)
        syncDecoderCounters(d)
        val next = decNext
        if (next != null && r.windowBase >= decNextBase) { next.onRepair(r); syncDecoderCounters(next) }
        for (i in 0 until r.windowLen) {
            val s = r.windowBase + i
            if (isDelivered(s)) continue
            val sym = d.get(s) ?: (if (next != null && s >= decNextBase) next.get(s) else null) ?: continue
            val len = ((sym[0].toInt() and 0xFF) shl 8) or (sym[1].toInt() and 0xFF)
            if (len !in 1..(symbolSize - 2)) { decodeError(s, "length $len"); continue }
            val save = pEliciting; val saveNp = pNonProbing
            // deliver first, mark second: a symbol that does not parse stays undelivered so a verbatim re-send can still bring it
            try { parseFrames(ByteBuffer.wrap(sym, 2, len), path, len, recovered = true, now = now) } catch (e: Exception) { decodeError(s, e.toString()); continue }
            pEliciting = save || pEliciting; pNonProbing = saveNp || pNonProbing
            if (s > largestFecSeen) advanceFec(s)
            markDelivered(s)
            statsImpl.recovered++
            tracer.repairDecoded(path.id, s, now)
        }
    }

    private fun rxError(e: Exception) { statsImpl.rxErrors++; if (statsImpl.firstRxError == null) statsImpl.firstRxError = "rx: $e" }
    /**
     * Integrity check on every repair-solved symbol (core RlncDecoder validator): the symbol is len(2) | body and the
     * body starts with the FEC extension frame [0x80 02 fecSeq16]; a GF-multiple wrong solve c*X (c != 1) cannot keep
     * 0x80 in place, so this rejects any mis-solve before it can be learned. Rejections surface as decodeErrors.
     */
    private val fecValidator = RlncDecoder.SymbolValidator { seq, sym ->
        val len = ((sym[0].toInt() and 0xFF) shl 8) or (sym[1].toInt() and 0xFF)
        len in (FEC_FRAME_LEN + 1)..(symbolSize - 2) &&
            (sym[2].toInt() and 0xFF) == FEC_FRAME_TYPE && sym[3].toInt() == 2 &&
            (((sym[4].toInt() and 0xFF) shl 8) or (sym[5].toInt() and 0xFF)) == (seq and 0xFFFF).toInt()
    }
    private var decRejectedSeen = 0L; private var decInconsistentSeen = 0L
    /** Fold the decoder's own integrity counters into decodeErrors (called after each onRepair). */
    private fun syncDecoderCounters(d: RlncDecoder) {
        val r = d.rejected; val i = d.inconsistent
        if (r > decRejectedSeen) { statsImpl.decodeErrors += r - decRejectedSeen; if (statsImpl.firstRxError == null) statsImpl.firstRxError = "decoder rejected $r"; decRejectedSeen = r }
        if (i > decInconsistentSeen) { statsImpl.decodeErrors += i - decInconsistentSeen; if (statsImpl.firstRxError == null) statsImpl.firstRxError = "decoder inconsistent $i"; decInconsistentSeen = i }
    }
    private fun decodeError(fec: Long, what: String) { statsImpl.decodeErrors++; if (statsImpl.firstRxError == null) statsImpl.firstRxError = "decode fec=$fec: $what" }

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
        reassembler.onFragment(m.msgId, m.offset, m.data, m.fin)?.let { deliverMsg(it) }
    }

    /** A complete message off the wire: through the payload codec, then to the app. */
    private fun deliverMsg(b: ByteArray) {
        val plain = if (codec === PayloadCodec.Identity) b else try { codec.decode(b) } catch (e: IllegalStateException) { statsImpl.codecErrors++; return }
        deliverRaw(plain)
    }

    /** Delivers bytes as-is (the 0-RTT first flight bypasses the codec: it is sent before the dictionary is negotiated). */
    internal fun deliverRaw(b: ByteArray) { statsImpl.messagesDelivered++; statsImpl.payloadBytesOut += b.size; inbox.put(b) }

    // ------------------------------------------------------------------ ack processing (sender side)

    /** The peer's delivered state of our sources (cumulative edge, largest seen, 256-bit map above the edge); stale reports are ignored. */
    private fun onFecFeedback(lowest: Long, largest: Long, buf: ByteBuffer) {
        if (largest < peerLargestFec || largest >= nextFecSeq || lowest < peerLowestUndelivered) { buf.position(buf.position() + 8 * FEC_FEEDBACK_WORDS); return }
        peerLargestFec = largest; peerLowestUndelivered = lowest
        for (w in 0 until FEC_FEEDBACK_WORDS) peerBits[w] = buf.getLong()
    }

    /** 1 = the peer reported seq `f` delivered, 0 = reported undelivered, -1 = not covered by any report (in flight, or above the map). */
    private fun peerState(f: Long): Int {
        if (peerLargestFec < 0) return -1
        if (f < peerLowestUndelivered) return 1
        if (f > peerLargestFec) return -1
        val off = f - peerLowestUndelivered
        if (off >= 64L * FEC_FEEDBACK_WORDS) return -1
        return if ((peerBits[(off ushr 6).toInt()] ushr (off and 63).toInt()) and 1L != 0L) 1 else 0
    }

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
                path.rateAckedBytes += size
                when (path.ringKind[i]) {
                    KIND_PROBE -> { path.pmtud.onProbeAcked(pn); charged += size }
                    KIND_SOURCE, KIND_REPAIR, KIND_RESEND -> { path.pmtud.onPacketAcked(size); charged += size }
                    else -> path.pmtud.onPacketAcked(size)
                }
            }
            val rtt = r.rttSampleUs
            if (rtt != null) { path.lastRttSampleUs = rtt; path.estimator.onRttSample(rtt); tracer.metrics(path.estimator, now) }
            path.estimator.onDelivered(path.tracker.cumulativeAckedBytes, now)
            path.cc.onAcked(charged, rtt ?: 0L, now)
            path.tlpBackoff = 0
            creditAvailable.signalAll()
        }
        if (path.pendCount > 0) lateAcks(path, a, now)
        if (path.lostCount > 0) spuriousLosses(path, a, now)
        for (pn in r.lost) deferLoss(path, pn, now)
        path.cc.onAckFrame(a)
        // loss observation for the real estimator at the cadence core's Kalman filter was tuned for: delivered packets
        // count here, losses once confirmed (confirmLoss), late acks when they arrive (lateAcks)
        path.lossExpected += r.newlyAcked.size
        observeLoss(path)
        feedbackResends(path, now)
        repairDeficit(path, a.largest, now)
    }

    /**
     * Exact residual ARQ on the peer's FEC feedback: every seq the peer reports undelivered whose retained symbol last
     * left more than a loss timeout plus the window's span ago is re-sent verbatim, bucket permitting, oldest first.
     * The extra span: the report in an ACK is a snapshot taken when the hole appeared at the receiver, before the
     * proactive repairs emitted over the next window (64 ms at 2000 msg/s) had arrived, and it reaches the sender a
     * round trip after the loss; a report still saying undelivered a loss timeout after the last of those repairs
     * left post-dates everything that could have brought the seq (re-sending on the first report was 80 % spurious
     * on the lte profile; waiting two loss timeouts put its p999 at 420 ms). The recent holes are handled
     * sooner by [repairDeficit]'s in-flight accounting; this path is what that packet-number scan cannot do for an
     * old hole: its window is DEFICIT_SCAN_BACK packets below the latest ack, so a source that stayed undelivered through
     * its repairs and a stale accounting fell out of it and was never retried — the peer's delivered edge then stuck
     * at that seq for the rest of the connection and its map covered nothing useful.
     */
    /**
     * A blackout is not congestion, and the peer has already said so.
     *
     * The gap token bucket exists to stop *speculative* repairs amplifying a path that is dropping packets because it
     * is congested. But [feedbackResends] walks the peer's own delivered map: those sequences are confirmed missing,
     * not guessed. When that map shows a long unbroken run **and** the path shows no queue growth, the link went away
     * for a while — a satellite handover, a radio gap, an interface flap — and metering the recovery only lengthens
     * the outage. Bursting is safe here because nothing else was competing for a queue that never formed, and because
     * receiver credit still bounds bytes in flight independently of this bucket.
     *
     * Returns the budget the hole justifies, or 0 if this looks like congestion (queueing delay above the same gate
     * [HybridCc] uses) or like ordinary scattered loss (no long run).
     */
    private fun outageDrainBudget(path: PathState): Double {
        val est = path.estimator
        if (est.srttUs <= 0.0 || est.minRttUs == Double.MAX_VALUE) return 0.0
        // Do NOT test queueing delay here. During a blackout srtt is inflated by the blackout itself — measured on the
        // starlink profile, srtt - minRtt climbs from 17 ms to 38 ms across the hole — so a delay test rejects exactly
        // the case it is meant to admit. The uncontaminated signal is [HybridCc.engaged], which is set only by real
        // congestion evidence: an ECN-CE mark, or loss accompanied by queueing delay that was already there.
        if (path.cc.engaged) return 0.0
        var run = 0L
        val span = min(peerLargestFec - peerLowestUndelivered + 1, 64L * FEC_FEEDBACK_WORDS)
        while (run < span && (peerBits[(run ushr 6).toInt()] ushr (run and 63).toInt()) and 1L == 0L) run++
        return if (run >= cfg.outageDrainMinRun) min(run.toDouble(), GAP_BUDGET_OUTAGE_MAX) else 0.0
    }

    private fun feedbackResends(path: PathState, now: Long) {
        path.lastFeedbackRunUs = now
        if (peerLargestFec < 0) return
        if (peerLowestUndelivered > path.outageDrainedThrough) {
            val burst = outageDrainBudget(path)
            if (burst > path.gapBudget) {
                path.gapBudget = burst; path.outageDrainedThrough = peerLowestUndelivered; statsImpl.outageDrains++
            }
        }
        if (path.gapBudget < 1.0) return
        // the report must post-date the arrival of the last proactive repair emitted for the seq (the window after it) -
        // a loss timeout plus the time the window spans, each capped by the other
        val lossTimeout = max(path.estimator.lossTimeoutUs(), (if (path.lastRttSampleUs > 0) path.lastRttSampleUs else path.estimator.srttUs.toLong()) + path.reoWndUs)
        val wait = lossTimeout + min((cfg.fecWindow * path.sendGapEwmaUs).toLong(), lossTimeout)
        val end = min(peerLargestFec, peerLowestUndelivered + 64L * FEC_FEEDBACK_WORDS - 1)
        var f = peerLowestUndelivered
        while (f <= end && path.gapBudget >= 1.0) {
            val off = f - peerLowestUndelivered
            if ((peerBits[(off ushr 6).toInt()] ushr (off and 63).toInt()) and 1L == 0L) {
                val si = (f and BODY_RING_MASK).toInt()
                if (symRingFec[si] == f && now - symRingSentUs[si] >= wait) {
                    path.gapBudget -= 1.0; statsImpl.gapResends++; statsImpl.resendFeedback++
                    resendFec(path, f, now)
                }
            }
            f++
        }
        if (path.gapBudget < 1.0) path.deficitPending = true
    }
    private fun observeLoss(path: PathState) {
        if (path.lossExpected >= cfg.lossObsWindow) {
            val pay = min(path.lossDebt, path.lossLost); path.lossDebt -= pay; path.lossLost -= pay   // spurious losses of earlier windows
            path.estimator.onLossObservation(path.lossLost.toDouble() / path.lossExpected)
            path.lossExpected = 0; path.lossLost = 0
        }
    }

    /**
     * A late ack is a delivered packet: an RTT sample the tracker could not take (the packet had left its in-flight set).
     * Without it only the fast, reordered packets keep producing samples and srtt collapses below the real round trip
     * (wifi-busy: 50 ms against a 250 ms loaded RTT), which then caps the reordering window and hides further late acks.
     */
    private fun lateAckRtt(path: PathState, i: Int, now: Long) {
        val rtt = max(now - path.ringTimeUs[i], 1L)
        path.lastRttSampleUs = rtt
        path.estimator.onRttSample(rtt); path.shadow.onRttSample(rtt)
    }

    /**
     * The tracker declared `pn` lost. By packet threshold that can be reordering — netem wifi-busy: a packet that skips
     * the queue is acked ~100 ms before its predecessors, which then all look lost, the estimator saw 95 % loss and the
     * CUBIC fallback crawled — so the loss is confirmed only once rtt + reoWnd have passed since the packet was sent
     * (RACK's reo_wnd rule). With no reordering ever seen reoWnd = 0 and the ack that triggered the declaration already
     * satisfies that, so nothing is delayed. Returns true when the loss was confirmed right away.
     */
    private fun deferLoss(path: PathState, pn: Long, now: Long): Boolean {
        val i = path.ringIdx(pn)
        if (path.ringPn[i] != pn || path.isAcked(pn)) return false
        val rtt = if (path.lastRttSampleUs > 0) path.lastRttSampleUs else path.estimator.srttUs.toLong()
        val due = path.ringTimeUs[i] + rtt + path.reoWndUs
        if (due <= now) { confirmLoss(path, pn, now); return true }
        if (path.pendCount == PathState.PENDING) {   // full: the oldest is confirmed now
            val old = path.pendPn[path.pendHead]; path.pendHead = (path.pendHead + 1) and (PathState.PENDING - 1); path.pendCount--
            if (!path.isAcked(old) && path.ringPn[path.ringIdx(old)] == old) confirmLoss(path, old, now)
        }
        val slot = (path.pendHead + path.pendCount) and (PathState.PENDING - 1)
        path.pendPn[slot] = pn; path.pendDue[slot] = due; path.pendCount++
        return false
    }

    /** Confirms the deferred losses whose reordering window has passed; true if any was (the caller re-runs the repair accounting). */
    private fun processPending(path: PathState, now: Long): Boolean {
        var any = false
        while (path.pendCount > 0 && path.pendDue[path.pendHead] <= now) {
            val pn = path.pendPn[path.pendHead]; path.pendHead = (path.pendHead + 1) and (PathState.PENDING - 1); path.pendCount--
            if (!path.isAcked(pn) && path.ringPn[path.ringIdx(pn)] == pn) { confirmLoss(path, pn, now); any = true }
        }
        return any
    }

    /** Acks for packets waiting in the deferred-loss ring: reordering, not loss. The window grows by what the packet needed. */
    private fun lateAcks(path: PathState, a: Frame.Ack, now: Long) {
        val est = path.estimator
        val minRtt = if (est.minRttUs == Double.MAX_VALUE) 0L else est.minRttUs.toLong()
        val n = path.pendCount; var kept = 0
        for (k in 0 until n) {
            val slot = (path.pendHead + k) and (PathState.PENDING - 1)
            val pn = path.pendPn[slot]
            var hit = pn == a.largest
            if (!hit) for (r in a.ranges) if (pn >= r.first && pn <= r.last) { hit = true; break }
            if (!hit) {
                val dst = (path.pendHead + kept) and (PathState.PENDING - 1)
                if (dst != slot) { path.pendPn[dst] = pn; path.pendDue[dst] = path.pendDue[slot] }
                kept++; continue
            }
            val i = path.ringIdx(pn)
            if (path.ringPn[i] != pn || path.isAcked(pn)) continue
            path.setAcked(pn)
            path.rateAckedBytes += path.ringSize[i]
            if (path.ringKind[i] == KIND_PROBE) path.pmtud.onProbeAcked(pn) else path.pmtud.onPacketAcked(path.ringSize[i])
            if ((path.ringKind[i] == KIND_SOURCE || path.ringKind[i] == KIND_RESEND) && path.resendQCount > 0) cancelQueuedResend(path, path.ringLo[i])
            path.lossExpected++
            statsImpl.lateAcks++
            lateAckRtt(path, i, now)
            // the window was wide enough for this one; keep at least the RACK floor and refresh the decay clock
            widenReoWnd(path, minRtt / 4, now)
        }
        path.pendCount = kept
    }

    /**
     * Acks for packets already confirmed lost: the DSACK equivalent. Until reordering has been seen reoWnd is 0 and a
     * declared loss is confirmed at once, so this is the only way the first spurious loss can be recognised; the window
     * then opens and later declarations wait in the deferred ring instead.
     */
    private fun spuriousLosses(path: PathState, a: Frame.Ack, now: Long) {
        val est = path.estimator
        val horizon = now - 4 * max(est.srttUs.toLong(), 1_000L)
        val n = min(path.lostCount, PathState.LOST_RING)
        for (k in 1..n) {
            val slot = (path.lostNext - k) and (PathState.LOST_RING - 1)
            if (path.lostAt[slot] < horizon) break
            val pn = path.lostPn[slot]
            if (pn < 0) continue
            var hit = pn == a.largest
            if (!hit) for (r in a.ranges) if (pn >= r.first && pn <= r.last) { hit = true; break }
            if (!hit) continue
            path.lostPn[slot] = -1
            val i = path.ringIdx(pn)
            if (path.ringPn[i] != pn || path.isAcked(pn)) continue
            path.setAcked(pn)
            path.rateAckedBytes += path.ringSize[i]
            if (path.ringKind[i] != KIND_PROBE) path.pmtud.onPacketAcked(path.ringSize[i])
            if ((path.ringKind[i] == KIND_SOURCE || path.ringKind[i] == KIND_RESEND) && path.resendQCount > 0) cancelQueuedResend(path, path.ringLo[i])
            if (path.lossLost > 0) path.lossLost-- else path.lossDebt++
            statsImpl.lateAcks++
            lateAckRtt(path, i, now)
            // the window fell short by exactly the time between confirming the loss and this ack
            widenReoWnd(path, path.reoWndUs + (now - path.lostAt[slot]), now)
        }
    }

    /** Reordering evidence: the window covers the observed extent (at least minRtt/4, at most srtt) from now on. */
    private fun widenReoWnd(path: PathState, extentUs: Long, now: Long) {
        val est = path.estimator
        val minRtt = if (est.minRttUs == Double.MAX_VALUE) 0L else est.minRttUs.toLong()
        // cap at 2 srtt rather than RACK's srtt: srtt itself is biased low under reordering (the fast packets dominate the samples)
        path.reoWndUs = min(max(path.reoWndUs, max(extentUs, minRtt / 4)), max(2 * est.srttUs.toLong(), 1_000L))
        path.lastLateAckUs = now
    }

    /**
     * A confirmed loss: PMTUD evidence, gated CC signal, loss observation, trace — and, for a source that has already left
     * the encoder window (a repair symbol can no longer cover it; at 2000 msg/s the 32-packet window is 16 ms, less than
     * any WAN RTT), the retained symbol re-sent verbatim: the SPEC's RACK-style residual ARQ. In-window losses are
     * covered by [repairDeficit]'s repair symbols.
     */
    private fun confirmLoss(path: PathState, pn: Long, now: Long) {
        val i = path.ringIdx(pn)
        if (path.ringPn[i] != pn || path.isAcked(pn)) return
        statsImpl.lossesDetected++
        path.lossExpected++; path.lossLost++
        if (path.reoWndUs == 0L) path.estimator.onLoss(pn)   // burst statistics (runs of consecutive lost pns); not under reordering, where spurious losses read as 15-packet bursts
        path.lostPn[path.lostNext] = pn; path.lostAt[path.lostNext] = now
        path.lostNext = (path.lostNext + 1) and (PathState.LOST_RING - 1); path.lostCount++
        // the window halves after 16 srtt without reordering evidence (RACK resets reo_wnd after 16 rounds without DSACK)
        val srtt = max(path.estimator.srttUs.toLong(), 1_000L)
        if (path.reoWndUs > 0 && now - path.lastLateAckUs > 16 * srtt) { path.reoWndUs /= 2; path.lastLateAckUs = now }
        val size = path.ringSize[i]
        val kind = path.ringKind[i]
        when (kind) {
            KIND_PROBE -> { path.pmtud.onProbeLost(pn); statsImpl.probesLost++ }
            KIND_SOURCE, KIND_REPAIR, KIND_RESEND -> { path.pmtud.onPacketLoss(size); ccLoss(path, size, now) }
            else -> path.pmtud.onPacketLoss(size)
        }
        tracer.packetLost(path.id, pn, timeUs = now)
        // no re-send here (v0.6): [repairDeficit] re-sends a confirmed or merely gap-visible source the moment no repair
        // symbol or re-send covers it, and not at all when one does - a blind re-send at confirmation was spurious for
        // 60 % of the lte profile's losses once the window covered its bursts (the receiver had recovered them long before)
    }

    /** Re-sends the retained symbol of `fec` verbatim (queued while the amplification limit holds it back); counts it as evicted (unrecoverable by re-send) when it is gone. */
    private fun resendFec(path: PathState, fec: Long, now: Long) {
        val si = (fec and BODY_RING_MASK).toInt()
        val sym = symRing[si]
        if (sym != null && symRingFec[si] == fec) { if (path.pv.canSend(sym.size)) resendSource(path, fec, sym, now) else enqueueResend(path, fec, now) }
        else statsImpl.resendEvicted++
    }

    private fun enqueueResend(path: PathState, fec: Long, now: Long) {
        if (path.resendQCount == PathState.RESEND_Q) { resendFec(path, fec, now); return }   // full: send rather than forget
        path.resendQ[(path.resendQHead + path.resendQCount) and (PathState.RESEND_Q - 1)] = fec; path.resendQCount++
        statsImpl.resendQueued++
    }

    /** A queued re-send whose packet turned out to be acked after all (reordering) is withdrawn. */
    private fun cancelQueuedResend(path: PathState, fec: Long) {
        for (k in 0 until path.resendQCount) {
            val slot = (path.resendQHead + k) and (PathState.RESEND_Q - 1)
            if (path.resendQ[slot] == fec) { path.resendQ[slot] = -1L; statsImpl.resendCancelled++ }
        }
    }

    /** Drains the amplification-limited re-send queue as far as the budget allows. */
    private fun drainResends(path: PathState, now: Long) {
        while (path.resendQCount > 0 && path.pv.canSend(maxDatagram)) {
            val fec = path.resendQ[path.resendQHead]; path.resendQHead = (path.resendQHead + 1) and (PathState.RESEND_Q - 1); path.resendQCount--
            if (fec < 0) continue
            statsImpl.resendDrained++
            resendFec(path, fec, now)
        }
    }

    /**
     * Loss as a congestion signal for the CUBIC fallback only when it coincides with queueing delay above the gate AND
     * the path delivers clearly less than we send (bytes acked vs bytes sent over the same windows). A standing queue
     * alone is no evidence that we are the cause (netem's rate + jitter ratchet queues at any load; so does a
     * bufferbloated last mile): on the lte / wifi-busy profiles every random loss used to engage CUBIC, which then
     * crawled (582 cwnd stalls, 70 % of a 2000 msg/s stream delivered late). A saturated bottleneck shows both signs.
     */
    private fun ccLoss(path: PathState, bytes: Int, now: Long) {
        val est = path.estimator
        val gate = cfg.ccLossDelayGateUs
        val queued = est.minRttUs != Double.MAX_VALUE && est.srttUs - est.minRttUs > max(gate.toDouble(), est.minRttUs / 4)
        val starved = path.starvedWindows >= CC_STARVED_WINDOWS
        if (gate == 0L || (queued && starved)) { path.cc.onLoss(bytes, now); statsImpl.ccLossEvents++ } else statsImpl.ccLossIgnored++
    }

    /**
     * The ack-driven repair path (RACK-style residual ARQ whose retransmission is a repair symbol where possible), run
     * on every ack — i.e. it fires on the first ack that shows a hole, without waiting for the tracker's loss
     * declaration (three later packets) or the reordering window: a repair or a duplicate source is harmless if the
     * packet was merely reordered (the receiver skips delivered sources), so on a path that has never shown reordering
     * nothing is gained by waiting; once reordering has been observed (reoWnd > 0) a hole counts only after rtt + reoWnd,
     * because netem's `reorder 5 %` exposes ~100 spurious holes per overtaking packet.
     *
     * The receiver needs one independent equation per missing source. Over the last DEFICIT_SCAN_BACK packet numbers, count
     * data packets that are missing (unacked with a later packet acked) and match each fec seq against a covering
     * packet that is acked or still in flight: a repair symbol whose window holds it, or a data packet carrying it
     * (the source itself acked — a re-send's original —, or a re-send in flight). Every unmatched seq is a deficit:
     * still inside the encoder window it gets a repair symbol (bounded by maxReactiveRepairsPerAck per ack; a burst of
     * b in-window losses gets b), past the window — at 2000 msg/s the 128-source window is 64 ms, less than a WAN
     * RTT — the retained source symbol is re-sent verbatim (bounded by the gap token bucket, cfg.gapRepairFraction of
     * the source rate). Matching against covering repairs is what keeps this from re-sending sources the receiver has
     * long recovered from the proactive stream (v0.5 re-sent blindly at loss confirmation: 60 % spurious on the lte
     * profile once the window covered its bursts); a lost covering packet stops matching once acks have passed it, so
     * the deficit reappears and the next ack retries.
     *
     * The FEC feedback the peer puts on every ACK makes the missing side exact: a seq it reports delivered is never
     * missing (before the feedback a recovered source looked like a hole for ever and consumed a covering repair in
     * the match, so the match ran out and re-sent sources the receiver had). Acked repairs still count as covers for
     * a seq reported undelivered: they are equations the receiver holds which did not suffice *alone* — together
     * with the ones in flight they usually do (counting only in-flight covers re-sent 80 % of the lte profile's
     * reported holes for nothing). What the greedy match cannot prove — it is a matching, not a rank proof: several
     * bursts inside one window can leave the system singular with an equation per missing source — is caught by
     * [feedbackResends]. Loss *confirmation* (CC, PMTUD, the loss estimator) stays behind the reordering window in
     * [confirmLoss].
     */
    private fun repairDeficit(path: PathState, largest: Long, now: Long) {
        val thr = cfg.reorderThreshold
        val settle = if (path.reoWndUs > 0) max(path.lastRttSampleUs, path.estimator.srttUs.toLong()) + path.reoWndUs else 0L   // the latest sample may be an overtaking packet's
        var nMiss = 0; var nRep = 0
        path.deficitPending = false
        // back over more than an RTT plus the encoder window of packets (a hole's repairs are emitted over the window
        // after it and then spend an RTT in flight) and ahead over everything in flight: the repairs that cover a
        // hole are still on their way when its first ack arrives
        var pn = max(0L, largest - DEFICIT_SCAN_BACK + 1)
        val hiPn = min(path.nextPn - 1, largest + DEFICIT_SCAN_FWD)
        while (pn <= hiPn && nRep < repLo.size) {
            val i = path.ringIdx(pn)
            if (path.ringPn[i] == pn) {
                val kind = path.ringKind[i]
                val covering = path.isAcked(pn) || pn > largest - thr   // delivered, or not yet reachable by this ack
                if (kind == KIND_REPAIR) {
                    if (covering) { repLo[nRep] = path.ringLo[i]; repHi[nRep] = path.ringHi[i]; repUsed[nRep] = false; nRep++ }
                } else if (kind == KIND_SOURCE || kind == KIND_RESEND) {
                    val f = path.ringLo[i]
                    if (covering) { repLo[nRep] = f; repHi[nRep] = f + 1; repUsed[nRep] = false; nRep++ }
                    else if (nMiss < GAP_SCAN_MAX && now - path.ringTimeUs[i] >= settle && peerState(f) != 1) {   // reported delivered: nothing to do
                        var dup = false
                        for (m in 0 until nMiss) if (missFec[m] == f) { dup = true; break }   // a lost source and its lost re-send: one seq
                        if (!dup) missFec[nMiss++] = f
                    }
                }
            }
            pn++
        }
        if (nMiss == 0) return
        var repairs = 0
        for (m in 0 until nMiss) {
            val f = missFec[m]; var matched = false
            for (r in 0 until nRep) if (!repUsed[r] && f >= repLo[r] && f < repHi[r]) { repUsed[r] = true; matched = true; break }
            if (matched) continue
            if (f >= encBase) {
                if (repairs < cfg.maxReactiveRepairsPerAck && path.pv.canSend(maxDatagram) && sendRepair(scheduler.repairPathFor(path.id), REPAIR_REACTIVE, now)) repairs++
            } else if (path.gapBudget >= 1.0) {
                path.gapBudget -= 1.0; statsImpl.gapResends++
                if (peerState(f) == 0) statsImpl.resendKnown++ else statsImpl.resendUnknown++
                resendFec(path, f, now)
            } else { statsImpl.gapThrottled++; path.deficitPending = true }
        }
    }

    // ------------------------------------------------------------------ timer (endpoint thread, ~1ms)

    internal fun onTick(now: Long) {
        lock.withLock {
            if (closed || !ready) return
            if (closing && (now - closeStartUs > cfg.closeLingerMs * 1000 || !lingerNeeded())) { finishClose(); return }
            crypto.warm()   // full key-phase states, derived here (timer thread) rather than on the first packets
            for (p in paths) {
                p ?: continue
                val srtt = p.estimator.srttUs.toLong()
                // delayed ack
                p.tracker.ackTimer(now)?.let { if (it <= now) sendAck(p, now) }
                // RACK time-threshold losses the acks did not cover, then deferred losses whose reordering window has passed
                var confirmed = false
                p.tracker.lossTimer(now)?.let { due ->
                    if (due <= now) for (pn in p.tracker.onLossTimer(now)) if (deferLoss(p, pn, now)) confirmed = true
                }
                if (processPending(p, now)) confirmed = true
                p.gapBudget = min(p.gapBudget + GAP_REFILL_PER_TICK, GAP_BUDGET_MAX)
                if (p.resendQCount > 0) drainResends(p, now)
                if (confirmed) { observeLoss(p); repairDeficit(p, p.tracker.largestAcked, now); creditAvailable.signalAll() }
                else if (p.deficitPending && p.gapBudget >= 1.0) { p.deficitPending = false; feedbackResends(p, now); repairDeficit(p, p.tracker.largestAcked, now) }
                // holes the peer reported that were not yet due for a re-send when its last ack arrived: the acks may have
                // dried up (end of a stream), so the feedback path is re-run from the timer while the map shows any hole
                else if (peerLargestFec >= 0 && peerLowestUndelivered < nextFecSeq && p.gapBudget >= 1.0 && now - p.lastFeedbackRunUs >= FEEDBACK_RETRY_US) feedbackResends(p, now)
                // windowed send / delivery rates for ccLoss's congestion test
                if (now - p.rateStartUs >= max(2 * srtt, RATE_WINDOW_MIN_US)) {
                    val dt = (now - p.rateStartUs).toDouble()
                    val sent = p.rateSentBytes * 1e6 / dt; val acked = p.rateAckedBytes * 1e6 / dt
                    p.sendBytesPerSec = if (p.sendBytesPerSec == 0.0) sent else 0.8 * p.sendBytesPerSec + 0.2 * sent
                    p.deliveredBytesPerSec = if (p.deliveredBytesPerSec == 0.0) acked else 0.8 * p.deliveredBytesPerSec + 0.2 * acked
                    // persistent shortfall, not the one-RTT lag of the first window or a single burst
                    p.starvedWindows = if (p.rateSentBytes >= 4L * maxDatagram && acked < CC_DELIVERY_FRAC * sent) p.starvedWindows + 1 else 0
                    p.rateStartUs = now; p.rateSentBytes = 0; p.rateAckedBytes = 0
                }
                if (!closing) {
                    // receiver credit: runs regardless of receive progress; re-send the last grant into silence (bounded)
                    maybeGrant(p, now, timer = true)
                    if (p.lastGrant != null && p.grantResendsSinceRx < MAX_UNSOLICITED_GRANT_RESENDS &&
                        now - p.lastElicitingRxUs > max(2 * srtt, cfg.grantResendMinUs) && now - p.lastGrantResendUs >= p.grantResendBackoffUs) {
                        resendGrant(p, now, solicited = false)
                    }
                    // a send ran dry on credit since the last probe (however briefly: a target near the BDP shows as many short
                    // stalls, not one long one): probe (see sendCreditProbe), at most one per half RTT - the target doubles
                    // per probe, i.e. up to 4x per RTT while sends keep running dry; never block forever
                    if ((p.stalledSinceProbe || (p.waitBytes > 0 && p.blockedSinceUs > 0 && !p.senderCredit.canSend(p.waitBytes))) &&
                        now - p.lastCreditProbeUs >= max(srtt / 2, CREDIT_PROBE_INTERVAL_US) + p.creditProbeBackoffUs) {
                        p.stalledSinceProbe = false
                        sendCreditProbe(p, now)
                    }
                }
                // PTO: data unacked for ptoUs(backoff) after our last ack-eliciting send -> probes carrying the oldest unacked data,
                // PTO_TRAIN of them (QUIC's rule: a second probe survives the burst that took the first)
                if (p.lastDataPn > p.tracker.largestAcked && nextFecSeq > 0 && p.pv.canSend(maxDatagram) &&
                    now - p.lastElicitingSendUs > max(p.estimator.ptoUs(p.tlpBackoff), 3 * cfg.ackDelayUs)) {
                    val copies = if (p.tlpBackoff < 2) PTO_TRAIN + 1 else PTO_TRAIN   // the first probes face the burst that took the data
                    p.tlpBackoff++; sendProbeData(p, now, copies)
                }
                // tail repair: a source packet that no other source followed within T gets a trailing repair symbol. On a
                // steady stream (recent send gap < TAIL_STREAM_FACTOR x T) the next source or proactive repair follows anyway
                // and only the last packet needs one, so wait for the stream to actually stop (netem finding: at 2000 msg/s
                // T's 500 us floor equalled the send gap and a tenth of the sources got a trailing repair for nothing)
                if (p.tailArmed) {
                    val t = (srtt / 8).coerceIn(cfg.tailRepairMinUs, cfg.tailRepairMaxUs)
                    val steady = p.sendGapEwmaUs > 0.0 && p.sendGapEwmaUs < TAIL_STREAM_FACTOR * t
                    val wait = if (steady) max(t, (TAIL_STREAM_FACTOR * p.sendGapEwmaUs).toLong()) else t
                    if (now - p.lastSourceSendUs >= wait) {
                        p.tailArmed = false
                        if (now - p.lastRepairSendUs >= t && p.pv.canSend(maxDatagram)) sendRepair(scheduler.repairPathFor(p.id), REPAIR_TAIL, now)
                    }
                }
                // path validation: re-challenge an unvalidated path with backoff (server side), as a pair once one went unanswered
                if (!isClient && !p.pv.validated && now - p.lastChallengeUs >= p.challengeBackoffUs && p.pv.canSend(64)) {
                    sendChallenge(p, now, copies = if (p.challengeBackoffUs > 0) 2 else 1)
                }
                // DPLPMTUD once the path is validated and acks are flowing; the probe deadline is the PTO, backed off per attempt
                if (cfg.pmtud && p.pv.validated && !closing) {
                    p.pmtud.probeTimeoutUs = max(p.estimator.ptoUs(p.pmtud.probeAttempts), PMTU_PROBE_TIMEOUT_MIN_US)
                    val probe = p.pmtud.nextProbe(now)   // runs the deadline / hold / raise timers as well
                    if (probe != null && p.pv.canSend(probe.size) && p.cc.canSend(p.tracker.bytesInFlight, probe.size)) sendPmtuProbe(p, probe.size, now)
                }
            }
            if (waiters > 0) creditAvailable.signalAll()
            if (now - max(lastRxUs, lastTxUs) > cfg.idleTimeoutMs * 1000) finishClose()
        }
    }

    /**
     * PTO probes: up to `copies` distinct unacked sources above the largest acked pn, oldest first — re-sent verbatim
     * from the retained symbols, or as a repair symbol each while still inside the encoder window — so a stream's tail
     * drains several losses per PTO instead of re-sending one source `copies` times; whatever is left of the train goes
     * out as TLP repairs over the current window.
     */
    private fun sendProbeData(path: PathState, now: Long, copies: Int) {
        var sent = 0
        val done = LongArray(copies) { -1L }
        var pn = max(path.tracker.largestAcked + 1, path.nextPn - DEFICIT_SCAN_BACK).coerceAtLeast(0)
        while (pn < path.nextPn && sent < copies) {
            val i = path.ringIdx(pn)
            if (path.ringPn[i] == pn && !path.isAcked(pn) && (path.ringKind[i] == KIND_SOURCE || path.ringKind[i] == KIND_RESEND)) {
                val fec = path.ringLo[i]
                var seen = false
                for (k in 0 until sent) if (done[k] == fec) { seen = true; break }
                if (!seen) {
                    if (fec >= encBase) { sendRepair(path.id, REPAIR_TLP, now); done[sent++] = fec }
                    else {
                        val si = (fec and BODY_RING_MASK).toInt()
                        val sym = symRing[si]
                        if (sym != null && symRingFec[si] == fec) { resendSource(path, fec, sym, now); done[sent++] = fec }
                        else statsImpl.resendEvicted++
                    }
                }
            }
            pn++
        }
        while (sent++ < copies) sendRepair(path.id, REPAIR_TLP, now)
    }

    // ------------------------------------------------------------------ handshake helpers

    /**
     * Server: encrypted reply = ConnParams(shortConnId for the client to use, ackFreq, tagLen, maxDatagram, dictId) |
     * ticketLen(2) | ticket | resetToken(16). The 16-byte stateless-reset token is appended inside the AEAD so it
     * reaches the client confidentially; it is minted from [ownResetSecret] and our assigned [localShortId] (=
     * `params.shortConnId`), the id the client will address us with. Omitted when no reset secret is configured, which
     * an older client tolerates: [onHandshakeReply] only reads a token when 16 trailing bytes are present.
     */
    internal fun buildHandshakeReply(params: ConnParams, ticket: ByteArray?): ByteBuffer = lock.withLock {
        val buf = ByteBuffer.allocate(Wire.MAX_DATAGRAM)
        PacketHeader(Wire.F_INITIAL or Wire.F_HANDSHAKE, connId, PathId(0), 0).write(buf)
        val hdrEnd = buf.position()
        params.write(buf)
        buf.putShort((ticket?.size ?: 0).toShort()); ticket?.let { buf.put(it) }
        ownResetSecret?.let { buf.put(StatelessReset.token(it, localShortId)) }
        val end = crypto.seal(buf, 0, hdrEnd, buf.position(), crypto.txKeys(), 0L, MAX_TAG, txScratch)
        buf.limit(end).position(0)
        handshakePacket = buf
        buf
    }


    /** Server: a handshake packet (initial reply / re-send) left; count it against the amplification budget. */
    internal fun onHandshakeSent(bytes: Int) = lock.withLock { path0.pv.onSent(bytes) }

    /** Server: the client retransmitted its initial — our reply was probably lost. `drop` is the test hook (TesseraServer.dropReplies). */
    internal fun onDuplicateInitial(from: InetSocketAddress, bytes: Int, drop: () -> Boolean) = lock.withLock {
        path0.pv.onReceived(bytes)
        resendReply(from, drop)
    }

    /**
     * Re-sends the handshake reply (rate-limited) as a train of [REPLY_TRAIN] copies: under bursty loss a single re-send
     * is lost with the burst's persistence (80 % per packet on the lte profile) and the whole handshake with it.
     */
    private fun resendReply(from: InetSocketAddress, drop: () -> Boolean = { false }) {
        if (replyAcked) return
        val pkt = handshakePacket ?: return
        val now = nowUs()
        val len = pkt.remaining()
        if (now - lastReplyResendUs < REPLY_RESEND_MIN_US || !path0.pv.canSend(len)) return
        lastReplyResendUs = now
        if (drop()) return
        val copies = if (path0.pv.canSend(REPLY_TRAIN * len)) REPLY_TRAIN else 1
        repeat(copies) { path0.pv.onSent(len); io.send(pkt.duplicate(), from) }
        statsImpl.replyResends++
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
        // Trailing stateless-reset token (v0.7). Guarded: a reply from an older server, or one with no reset secret,
        // ends after the ticket and leaves us with no token — exactly as before.
        if (pb.remaining() >= StatelessReset.TOKEN_LEN) peerResetToken = ByteArray(StatelessReset.TOKEN_LEN).also { pb.get(it) }
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
        /** The message length once a fin fragment established it, else -1. */
        var total = -1; private set
        /** Largest buffered end so far (ranges are coalesced and non-overlapping, so the last one ends furthest). */
        val extent: Int get() = if (ranges.isEmpty()) 0 else ranges.lastEntry().value
        /** Current buffer allocation, for the manager's byte accounting. */
        fun capacity(): Int = buf.size
        /**
         * Returns true when the message is complete. Caller has already bounded `offset + len` to maxMessageBytes
         * AND checked the fragment against [total]/[extent]: once a fin set [total] the buffer is clamped to it, so
         * an unchecked fragment past it would write out of bounds.
         */
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

    /**
     * Bounded message reassembly. A `Msg` frame's `offset` is wire-controlled and buffers grow to `offset + len`, so
     * an authenticated peer could otherwise force an arbitrary allocation from one crafted fragment (offset ~ 2^31,
     * fin set) or pin memory with unboundedly many never-completed messages. This enforces three local caps in place
     * of a `MAX_DATA` wire mechanism, and reports drops through [ConnStats]. Not thread-safe: called under the
     * connection lock, exactly like the map it replaces.
     */
    internal class Reassembler(
        private val maxMessageBytes: Int,
        private val maxConcurrent: Int,
        private val maxBytes: Long,
    ) {
        private val partial = HashMap<Long, Reassembly>()
        private var bufferedBytes = 0L
        var oversizeDropped = 0L; private set
        var refused = 0L; private set
        val pending: Int get() = partial.size
        val bytes: Long get() = bufferedBytes

        /** Returns the completed message bytes, or null if still incomplete or the fragment was dropped by a cap. */
        fun onFragment(msgId: Long, offset: Int, data: ByteBuffer, fin: Boolean): ByteArray? {
            val len = data.remaining()
            // offset+len computed in Long: offset is a non-negative Int (parser-checked), but the sum can exceed Int.
            val end = offset.toLong() + len
            if (offset < 0 || end > maxMessageBytes) { oversizeDropped++; return null }
            val existing = partial[msgId]
            // Fragments that contradict what already arrived: past the fin-established length (Reassembly clamps its
            // buffer to that length, so the write would go out of bounds), or a fin below the buffered extent (the
            // completion check would pass and bytes() truncate what arrived). An honest sender produces neither —
            // its fin is the furthest byte of the message. end == total stays legal (the fin itself, re-received).
            if (existing != null && ((existing.total >= 0 && end > existing.total) || (fin && end < existing.extent))) {
                oversizeDropped++; return null
            }
            if (existing == null && partial.size >= maxConcurrent) { refused++; return null }
            val r = existing ?: Reassembly()
            val before = r.capacity()
            val done = r.add(offset, data, fin)
            val grew = r.capacity() - before
            if (grew > 0 && bufferedBytes + grew > maxBytes) {
                // This fragment breached the global byte budget: drop the whole message rather than hold it.
                // maxBytes >= maxMessageBytes (config invariant), so a single legitimate message never trips this.
                if (existing != null) { partial.remove(msgId); bufferedBytes -= before }
                refused++; return null
            }
            bufferedBytes += grew
            if (done) { partial.remove(msgId); bufferedBytes -= r.capacity(); return r.bytes() }
            partial[msgId] = r
            return null
        }
    }

    companion object {
        const val SHORT_HDR_MAX = 1 + 4 + 4
        const val MAX_TAG = 16
        const val REPAIR_FRAME_OVERHEAD = 1 + 8 + 2 + 4 + 2
        const val FEC_FRAME_TYPE = 0x80
        const val FEC_FRAME_LEN = 4
        const val CREDIT_PROBE_FRAME = 0x82
        const val FEC_FEEDBACK_FRAME = 0x83
        /** Payload of the FEC feedback frame: lowest16, largest16, 256-bit delivered map (4 words). */
        const val FEC_FEEDBACK_WORDS = 4
        const val FEC_FEEDBACK_LEN = 2 + 2 + 8 * FEC_FEEDBACK_WORDS
        const val RX_BUF = 2048
        const val MIN_DATAGRAM = 1200
        const val MAX_SUPPORTED_DATAGRAM = 1500
        const val MAX_FEC_WINDOW = 128
        const val SPAN = 64
        /**
         * Packet numbers the deficit accounting looks back over (and the PTO): more than an RTT plus the encoder window at
         * 2000 msg/s (640 pns ~ 270 ms), so a hole stays in view until the last repair emitted for it has been acked or
         * declared lost; and ahead, over what is in flight.
         */
        const val DEFICIT_SCAN_BACK = 5 * MAX_FEC_WINDOW
        const val DEFICIT_SCAN_FWD = 2 * MAX_FEC_WINDOW + SPAN
        /** Ack-driven re-sends: bucket capacity (a burst's worth), per-tick trickle (~1 ms) for a quiet stream, and the most missing seqs considered per ack. */
        const val GAP_BUDGET_MAX = 32.0
        /** A confirmed contiguous hole this long, with the CUBIC fallback not engaged, is a link outage rather than
         *  congestion loss: 64 back-to-back losses is 80 ms of solid nothing at 800 msg/s, which a queue does not do. */
        const val OUTAGE_RUN_MIN = 64L
        const val GAP_BUDGET_OUTAGE_MAX = 512.0
        const val GAP_REFILL_PER_TICK = 0.05
        const val GAP_SCAN_MAX = 2 * SPAN
        /** Timer cadence for re-running the feedback-driven re-sends while the peer's map shows a hole. */
        const val FEEDBACK_RETRY_US = 5_000L
        /** After an out-of-order arrival, ACKs carry every range (AckTracker.MAX_RANGES) for this long instead of cfg.maxAckRanges. */
        const val ACK_FULL_RANGES_US = 2_000_000L
        /** Receiver's delivered-source bitmap; must cover the sender's BODY_RING so a late re-send is not mistaken for an old delivery. */
        const val DELIVERED_BITS = 8192
        const val DECODER_ROTATE = 4096L
        /** Seqs the successor decoder runs alongside its predecessor: more than an RTT of sources in flight plus the window. */
        const val DECODER_OVERLAP = 1024L
        /**
         * Source symbols retained for verbatim re-sends (residual ARQ): 2 s of packets at 2000 msg/s. A re-send is itself
         * confirmed lost only after rtt + reoWnd (+ PTO backoff at a stream's tail), so several rounds at a loaded WAN RTT
         * must fit (1024 = 512 ms lost 2 of 2000 messages on wifi-busy). Costs up to BODY_RING x symbolSize (~5 MB) of
         * retained symbols per connection at full rate; they are allocated per source anyway.
         */
        const val BODY_RING = 4096
        const val BODY_RING_MASK = BODY_RING - 1L
        const val MAX_UNSOLICITED_GRANT_RESENDS = 3
        const val GRANT_WARMUP_US = 50_000L
        /** A send that ran dry on credit probes for a grant, at most every max(srtt/2, this) while answered (cfg.creditProbeMinUs backoff while not). */
        const val CREDIT_PROBE_INTERVAL_US = 5_000L
        const val EARLY_MAX = 8
        const val REPLY_RESEND_MIN_US = 5_000L
        /** Copies per handshake-reply re-send and probes per PTO: retransmit trains survive loss bursts a single packet does not. */
        const val REPLY_TRAIN = 2
        const val PTO_TRAIN = 2
        /** First re-probe backoff after a PMTUD black hole / failed size (doubles up to Pmtud.raiseTimerUs). */
        const val PMTU_RAISE_MIN_US = 1_000_000L
        /** Floor for the PMTUD probe deadline (the PTO, backed off per attempt, is used above it). */
        const val PMTU_PROBE_TIMEOUT_MIN_US = 20_000L
        /** A stream whose inter-send gap is below this times T needs no per-packet tail repair; the tail then waits this times the gap. */
        const val TAIL_STREAM_FACTOR = 2
        /** Minimum window for the send / delivery rate EWMAs. */
        const val RATE_WINDOW_MIN_US = 20_000L
        /** A loss counts for CUBIC only while delivery has fallen below this fraction of the send rate for this many consecutive windows (and the delay gate holds). */
        const val CC_DELIVERY_FRAC = 0.8
        const val CC_STARVED_WINDOWS = 2
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
        fun deriveConnId(sessionKey: ByteArray): Long = ByteBuffer.wrap(PacketCrypto.hkdf(sessionKey, "tessera-v0.2 connid")).getLong()
    }
}
