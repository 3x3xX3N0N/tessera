package aether.core

/**
 * Receiver-driven congestion control (Homa lineage).
 * The receiver issues grants so that bytes-in-flight ≈ BDP at the receiver's observed rate; queues never build.
 * Sender never exceeds outstanding credit. Before the first grant, a small fixed window applies.
 */
class ReceiverCredit(private val est: PathEstimator, private val overcommitFrac: Double = 1.1) {
    private var granted = 0L
    private var received = 0L

    fun onReceived(bytes: Int) { received += bytes }

    /** Call every ~min(srtt/4, 1ms). Returns a Grant if new credit should be issued. */
    fun tick(): Frame.Grant? {
        val minRtt = if (est.minRttUs == Double.MAX_VALUE) 50_000.0 else est.minRttUs
        val bdp = (est.deliveredBytesPerSec * minRtt / 1e6).coerceAtLeast(10.0 * Wire.MAX_DATAGRAM)
        val target = (bdp * overcommitFrac).toLong()
        val outstanding = granted - received
        if (outstanding < target / 2) {
            val add = target - outstanding
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
