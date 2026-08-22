package aether.core

/**
 * Receiver-driven congestion control (Homa lineage).
 * The receiver issues grants so that bytes-in-flight ≈ BDP at the receiver's observed rate; queues never build.
 * Sender never exceeds outstanding credit. Before the first grant, a small fixed window applies.
 */
class ReceiverCredit(
    private val est: PathEstimator,
    private val overcommitFrac: Double = 1.1,
    private val floorBytes: Long = 10L * Wire.MAX_DATAGRAM,
    private val maxBytes: Long = 8L shl 20,
    private val clock: () -> Long = { System.nanoTime() / 1000 },
) {
    private var granted = 0L
    private var received = 0L
    private var target = floorBytes
    private var lastTickUs = 0L
    private var lastTickReceived = 0L
    /** EWMA of bytes actually arriving on this path (the receiver's own measurement, not the peer's acks). */
    var rxBytesPerSec = 0.0; private set
    val targetBytes: Long get() = target
    val outstanding: Long get() = granted - received

    fun onReceived(bytes: Int) { received += bytes }

    /** Current grant, re-sendable verbatim when the transport suspects the last one was lost. */
    fun currentGrant(): Frame.Grant = Frame.Grant(est.path, (granted - received).coerceAtLeast(floorBytes).toInt(), 0)

    fun tick(): Frame.Grant? = tick(clock())

    /**
     * Call every ~min(srtt/4, 1ms) AND on a timer independent of receive progress. Sizes the target from the
     * receive rate x RTT (BDP), and slow-starts it: if the sender drained nearly all credit since the last tick it
     * was limited by us, so double the target (capped) until loss/ECN backs it off. The old version used
     * est.deliveredBytesPerSec, which on the receive side is only fed by acks of the receiver's own packets, so
     * the BDP collapsed to the floor and in-flight capped at ~23 packets at any RTT (netem finding #1).
     */
    fun tick(nowUs: Long): Frame.Grant? {
        val dtUs = nowUs - lastTickUs
        if (lastTickUs != 0L && dtUs > 0) {
            val inst = (received - lastTickReceived) * 1e6 / dtUs
            rxBytesPerSec = if (rxBytesPerSec == 0.0) inst else 0.8 * rxBytesPerSec + 0.2 * inst
        }
        val rttUs = if (est.minRttUs == Double.MAX_VALUE) 0.0 else est.minRttUs // no sample yet: floor only (100 ms x rate over-granted ~600 KB)
        val bdp = (rxBytesPerSec * rttUs / 1e6 * overcommitFrac).toLong()
        val out = granted - received
        val drained = lastTickUs != 0L && out < target / 4          // sender used >75% of what we gave it: credit-limited
        val congested = est.lossRate > 0.02
        target = when {
            drained && !congested -> (target * 2).coerceAtMost(maxBytes)
            congested -> (target * 0.9).toLong().coerceAtLeast(floorBytes)
            else -> target
        }.coerceAtLeast(maxOf(floorBytes, bdp)).coerceAtMost(maxBytes)
        lastTickUs = nowUs; lastTickReceived = received
        if (out < target / 2) {
            val add = target - out
            granted += add
            return Frame.Grant(est.path, add.toInt(), 0)
        }
        return null
    }
}

class SenderCredit(initialWindow: Int = 10 * Wire.MAX_DATAGRAM) {
    private var credit = initialWindow.toLong()
    var ecnCeSeen = 0L; private set

    fun onGrant(g: Frame.Grant) { credit += g.creditBytes }

    /** L4S-style gentle multiplicative reaction to ECN-CE. */
    fun onAck(a: Frame.Ack) {
        if (a.ecnCe > ecnCeSeen) { credit = (credit * 0.9).toLong(); ecnCeSeen = a.ecnCe }
    }

    fun canSend(bytes: Int) = credit >= bytes
    fun onSent(bytes: Int) { credit -= bytes }
}
