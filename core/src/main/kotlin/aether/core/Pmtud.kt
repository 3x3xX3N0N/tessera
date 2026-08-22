package aether.core

/**
 * Datagram PLPMTUD (RFC 8899) for one path: finds the largest datagram the path carries, without trusting ICMP.
 * Pure state machine, no I/O: the transport sends the padded probe packets and reports acks, losses and time.
 *
 * BASE (confirm [basePlpmtu]) -> SEARCHING (binary search up to [maxPlpmtu]; the first probe is optimistically
 * [maxPlpmtu] itself, so a clean 1500-byte path converges in two probes) -> SEARCH_COMPLETE (re-probe upward every
 * [raiseTimerUs]). ERROR when even the base size fails (`plpmtu` = [minPlpmtu]); black-hole detection (packets larger
 * than the base lost [blackHoleThreshold] times in a row while smaller ones are acked) drops back to BASE and waits
 * [raiseTimerUs] before searching again.
 *
 * Sizes are datagram payload sizes (the PLPMTU). A probe is an ordinary packet padded to [Probe.size] (see [padTo]);
 * the transport identifies it by packet number. Each probe size gets at most [maxProbes] attempts; a probe neither
 * acked nor reported lost by [Probe.deadlineUs] counts as lost once [onTimer]/[nextProbe] see that time. Events
 * without a timestamp ([onProbeAcked], [onProbeLost], [onPacketLoss], [onPacketAcked]) use the clock of the latest
 * timestamped call. Single-threaded, like the rest of core.
 */
class Pmtud(
    val basePlpmtu: Int = 1200,
    val maxPlpmtu: Int = 1500,
    val minPlpmtu: Int = 1200,
    /** Backoff before probing upward again after SEARCH_COMPLETE, a black hole or ERROR (RFC PMTU_RAISE_TIMER). */
    val raiseTimerUs: Long = 600_000_000L,
    /** Attempts per probe size before the size is declared unsupported (RFC MAX_PROBES). */
    val maxProbes: Int = 3,
    /** Consecutive lost packets larger than [basePlpmtu] (with a smaller packet acked meanwhile) that mean black hole. */
    val blackHoleThreshold: Int = 3,
    /** Search resolution: probe sizes are multiples of this, so the result is exact for the usual 4-byte-aligned MTUs. */
    val stepBytes: Int = 4,
) {
    enum class State { BASE, SEARCHING, SEARCH_COMPLETE, ERROR }

    /** A probe to send (`pn == null`) or in flight. Unacked at [deadlineUs], it counts as lost (see [onTimer]). */
    data class Probe(val size: Int, val pn: Long? = null, val deadlineUs: Long)

    init {
        require(minPlpmtu in 1..basePlpmtu && basePlpmtu <= maxPlpmtu) { "need 1 <= min <= base <= max, got $minPlpmtu/$basePlpmtu/$maxPlpmtu" }
        require(maxProbes >= 1 && blackHoleThreshold >= 1 && stepBytes >= 1 && raiseTimerUs > 0)
    }

    /** Backstop for probes the transport's loss detection never reports on; set it from the RTO. */
    var probeTimeoutUs: Long = 1_000_000L

    var state = State.BASE; private set
    /** Largest datagram size currently known to work; what the packetizer should use. */
    var plpmtu = basePlpmtu; private set
    /** Probe in flight, if any. At most one at a time. */
    var outstanding: Probe? = null; private set
    /** Times black-hole detection (or a failed confirmation probe) sent us back to BASE. */
    var blackHoles = 0; private set
    /** Probes sent so far at the size currently being searched. */
    val probeAttempts: Int get() = attempts

    private var candidate = basePlpmtu     // size being probed; 0 when the state wants no probes
    private var attempts = 0
    private var low = basePlpmtu           // largest size confirmed in this search
    private var high = maxPlpmtu + 1       // smallest size known to fail (exclusive bound)
    private var pending: Probe? = null     // handed out by nextProbe, not yet sent
    private var holdUntilUs: Long? = null  // BASE/ERROR backoff: no probing before this
    private var raiseAtUs: Long? = null    // SEARCH_COMPLETE: when to search upward again
    private var lastNowUs = 0L
    private var bigLossRun = 0             // consecutive losses of packets > basePlpmtu (a big ack resets)
    private var smallAckSeen = false       // a packet <= basePlpmtu was acked during the current run

    /** Earliest time at which [onTimer] should be called, or null when nothing is scheduled. */
    fun nextTimerUs(): Long? = listOfNotNull(outstanding?.deadlineUs, holdUntilUs, raiseAtUs).minOrNull()

    /**
     * Runs due timers, then returns the probe to send now, or null (nothing to learn, backoff, or a probe is already
     * outstanding). The same probe is returned again until [onProbeSent] commits it.
     */
    fun nextProbe(nowUs: Long): Probe? {
        advance(nowUs)
        pending = if (wantsProbe()) Probe(candidate, null, nowUs + probeTimeoutUs) else null
        return pending
    }

    /** Padding bytes that grow a packet of `len` bytes to the size of the probe last returned by [nextProbe]; 0 if none. */
    fun padTo(len: Int): Int = pending?.let { (it.size - len).coerceAtLeast(0) } ?: 0

    /** The probe from [nextProbe] went out as packet `pn` with `size` payload bytes. */
    fun onProbeSent(size: Int, pn: Long, nowUs: Long) {
        lastNowUs = nowUs
        check(outstanding == null) { "probe pn=${outstanding?.pn} already outstanding" }
        check(wantsProbe()) { "no probe wanted in $state" }
        require(size == candidate) { "probe size $size != wanted $candidate" }
        pending = null; attempts++
        outstanding = Probe(size, pn, nowUs + probeTimeoutUs)
    }

    /** Probe `pn` was acknowledged: its size works. Unknown or stale pns are ignored. */
    fun onProbeAcked(pn: Long) {
        val o = outstanding ?: return; if (o.pn != pn) return
        outstanding = null; sizeConfirmed(o.size)
    }

    /** Probe `pn` was declared lost. Re-probes the same size until [maxProbes], then steps the search down. */
    fun onProbeLost(pn: Long) {
        val o = outstanding ?: return; if (o.pn != pn) return
        outstanding = null
        if (attempts >= maxProbes) sizeFailed(o.size)
    }

    /** Probe deadline, backoff and raise timers. Also run by [nextProbe]. */
    fun onTimer(nowUs: Long) = advance(nowUs)

    /** A non-probe packet of `size` bytes was declared lost (black-hole detection, RFC 8899 section 4.3). */
    fun onPacketLoss(size: Int) {
        if (!detecting() || size <= basePlpmtu) return
        bigLossRun++; checkBlackHole()
    }

    /** A non-probe packet of `size` bytes was acknowledged. Small acks during a run of big losses are the black-hole evidence. */
    fun onPacketAcked(size: Int) {
        if (!detecting()) return
        if (size > basePlpmtu) resetBlackHole()
        else if (bigLossRun > 0) { smallAckSeen = true; checkBlackHole() }
    }

    private fun wantsProbe() =
        outstanding == null && holdUntilUs == null && candidate > 0 && (state == State.BASE || state == State.SEARCHING)

    private fun advance(nowUs: Long) {
        lastNowUs = nowUs
        outstanding?.let { if (nowUs >= it.deadlineUs) onProbeLost(it.pn!!) }
        holdUntilUs?.let { if (nowUs >= it) { holdUntilUs = null; if (state == State.ERROR) enterBase() } }
        raiseAtUs?.let { if (nowUs >= it) { raiseAtUs = null; startSearch() } }
    }

    private fun sizeConfirmed(size: Int) {
        when (state) {
            State.BASE -> if (basePlpmtu >= maxPlpmtu) complete() else startSearch()
            State.SEARCHING -> {
                if (size > plpmtu) { plpmtu = size; resetBlackHole() }
                low = maxOf(low, size)
                if (low >= maxPlpmtu) complete() else nextSize()
            }
            else -> {}
        }
    }

    private fun sizeFailed(size: Int) {
        when (state) {
            State.BASE -> enterError()
            // A failed probe at a size we already use means the path shrank: same handling as a black hole.
            State.SEARCHING -> if (size <= plpmtu) blackHole() else { high = minOf(high, size); nextSize() }
            else -> {}
        }
    }

    /** Binary search step: probe the [stepBytes]-aligned midpoint of (low, high), or finish if none is left. */
    private fun nextSize() {
        val mid = (low + high) / 2
        val aligned = mid / stepBytes * stepBytes
        if (aligned > low && aligned < high) { candidate = aligned; attempts = 0 } else complete()
    }

    private fun startSearch() {
        state = State.SEARCHING; low = plpmtu; high = maxPlpmtu + 1
        candidate = maxPlpmtu; attempts = 0; pending = null
    }

    private fun complete() {
        state = State.SEARCH_COMPLETE; candidate = 0; attempts = 0; pending = null
        raiseAtUs = lastNowUs + raiseTimerUs
    }

    private fun enterBase(hold: Boolean = false) {
        state = State.BASE; plpmtu = basePlpmtu; candidate = basePlpmtu; attempts = 0
        low = basePlpmtu; high = maxPlpmtu + 1
        outstanding = null; pending = null; raiseAtUs = null
        holdUntilUs = if (hold) lastNowUs + raiseTimerUs else null
        resetBlackHole()
    }

    private fun enterError() {
        state = State.ERROR; plpmtu = minPlpmtu; candidate = 0; attempts = 0
        outstanding = null; pending = null; raiseAtUs = null
        holdUntilUs = lastNowUs + raiseTimerUs
    }

    private fun blackHole() { blackHoles++; enterBase(hold = true) }

    private fun detecting() = plpmtu > basePlpmtu && (state == State.SEARCHING || state == State.SEARCH_COMPLETE)
    private fun checkBlackHole() { if (bigLossRun >= blackHoleThreshold && smallAckSeen) blackHole() }
    private fun resetBlackHole() { bigLossRun = 0; smallAckSeen = false }
}
