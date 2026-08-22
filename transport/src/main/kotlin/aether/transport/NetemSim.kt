package aether.transport

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.PriorityQueue
import java.util.Random
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * In-process link impairment, a `tc netem` lookalike applied on a [UdpIo] send path (bench / tests; no kernel or
 * root needed). Every datagram handed to [submit] is copied and then, in this order like the kernel's `netem_enqueue`:
 *
 *  1. **loss** — Gilbert-Elliott `loss gemodel p r` when [lossR] > 0: a per-packet two-state chain, `p` = good->bad,
 *     `r` = bad->good transition probability, every packet seen in the bad state is lost (average loss p/(p+r), mean
 *     burst 1/r packets — the transition packet itself follows the state it was in, as in `sch_netem.c`); plain
 *     Bernoulli `loss p` when [lossR] == 0;
 *  2. **duplicate** — with [dupProb] a second copy re-enters the pipeline (own loss / delay samples);
 *  3. **reorder** — with [reorderProb] the packet skips the delay queue and goes out at once, overtaking everything
 *     queued (netem `reorder`: "sent immediately"), otherwise
 *  4. **delay + jitter** — `delay + jitter * X` with `X` a zero-mean, unit-spread sample of [jitterDist] (netem's
 *     distribution tables are normalised the same way; [Dist.PARETO] is the shape-3 Pareto, so `delay 8ms 20ms
 *     distribution pareto` clamps a third of the packets to zero delay exactly as the kernel does), optionally
 *     correlated with the previous sample ([jitterCorrelation], netem's `delay ... <corr>%`), never negative;
 *  5. **rate** — with [rateBps] > 0 the departure time is `max(now + delay, departure of the previous queued packet)
 *     + (len + 28) * 8 / rate`: the serialisation delay of the UDP/IP packet behind the queue's tail. That is the
 *     kernel's rule too, with the consequence documented in BENCH-netem.md ("Idle RTT is not loaded RTT"): with a rate
 *     set, positive jitter ratchets into a standing queue at high packet rates and nothing is ever reordered except
 *     by step 3.
 *
 * A dedicated scheduler thread releases packets at their due time (parks until ~2 ms before, then spins, so the
 * timing is sub-millisecond on every OS) and hands them to the sink the caller gave [submit], i.e. the real socket.
 * One instance attached to both endpoints of a connection impairs both directions through **one** queue and **one**
 * loss chain, which is what one netem qdisc on `lo` does (the rate cap and the loss bursts are shared by data and
 * acks); use two instances for independent directions. A sim with no delay, jitter or rate delivers inline on the
 * caller's thread (no thread is started), so [Preset.LAN_CLEAN] costs one copy per datagram.
 *
 * Attach via [ConnConfig.netem] (every datagram the endpoint sends, handshake packets included, goes through the
 * sim) or [AetherConnection.attachNetem] (that connection's packets from then on). [ConnConfig] and the connection
 * keep the older [AetherConnection.lossSim] / txFilter hooks, which drop on the connection's own send path before
 * the sim sees the packet. Deterministic for a given [seed].
 */
class NetemSim(
    val name: String = "netem",
    /** One-way base delay. */
    val delayUs: Long = 0,
    /** Jitter scale (netem's second `delay` argument): delay + jitter * X, X per [jitterDist]. */
    val jitterUs: Long = 0,
    val jitterDist: Dist = Dist.UNIFORM,
    /** netem delay correlation: X = corr * X(previous) + (1 - corr) * fresh sample. */
    val jitterCorrelation: Double = 0.0,
    /** Probability that a packet skips the delay queue (netem `reorder`: sent at once, overtaking queued packets). */
    val reorderProb: Double = 0.0,
    /** Loss: `loss gemodel lossP lossR` when [lossR] > 0 (see class docs), else plain `loss lossP`. */
    val lossP: Double = 0.0,
    val lossR: Double = 0.0,
    /** Rate cap in bit/s (0 = none): serialisation delay of len + 28 header bytes, queued behind the previous packet. */
    val rateBps: Long = 0,
    /** Probability of a duplicate (netem `duplicate`); the copy takes its own loss / delay samples. */
    val dupProb: Double = 0.0,
    val seed: Long = 1,
    /** Queue limit in packets (netem's default `limit 1000`): a packet arriving at a full queue is dropped (tail drop). */
    val limit: Int = 1_000,
) : AutoCloseable {
    enum class Dist { UNIFORM, NORMAL, PARETO }

    init {
        require(delayUs >= 0 && jitterUs >= 0 && rateBps >= 0)
        require(lossP in 0.0..1.0 && lossR in 0.0..1.0 && reorderProb in 0.0..1.0 && dupProb in 0.0..1.0)
        require(jitterCorrelation in 0.0..1.0)
    }

    private class Item(val seq: Long, val dueUs: Long, val bytes: ByteArray, val to: InetSocketAddress,
                       val sink: (ByteBuffer, InetSocketAddress) -> Unit) : Comparable<Item> {
        override fun compareTo(other: Item): Int {
            val c = dueUs.compareTo(other.dueUs)
            return if (c != 0) c else seq.compareTo(other.seq)
        }
    }

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val queue = PriorityQueue<Item>()
    private val rnd = Random(seed)
    private var seq = 0L
    private var bad = false               // Gilbert-Elliott state
    private var prevSample = 0.0; private var hasPrev = false
    private var tailDueUs = 0L            // departure time of the last packet scheduled through the rate cap
    private val delayHist = IntArray(DELAY_HIST_MS + 1)   // imposed one-way delay per packet, 1 ms buckets (what rawudp would see)
    @Volatile private var closed = false
    private val immediate = delayUs == 0L && jitterUs == 0L && rateBps == 0L
    private var thread: Thread? = null

    // counters (under the lock; read racily for display)
    @Volatile var submitted = 0L; private set
    @Volatile var delivered = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var reordered = 0L; private set
    @Volatile var duplicated = 0L; private set
    /** Packets dropped because the queue was at [limit] (overload, not the loss model). */
    @Volatile var queueDrops = 0L; private set
    @Volatile var maxQueued = 0; private set
    /** Packets released by the scheduler later than 1 ms after their due time (a loaded host, not the model). */
    @Volatile var lateReleases = 0L; private set
    val queued: Int get() = lock.withLock { queue.size }
    /** Fraction of submitted packets lost so far. */
    val lossRate: Double get() = submitted.let { if (it == 0L) 0.0 else dropped.toDouble() / it }

    /**
     * Percentile (0..1) of the one-way delay the link imposed on the packets it scheduled so far (queueing included, so
     * under load this is the rate ratchet as well): the latency a raw datagram would have seen. 1 ms resolution.
     */
    fun delayPercentileUs(p: Double): Long = lock.withLock {
        val total = delayHist.sum(); if (total == 0) return 0L
        val target = (total * p.coerceIn(0.0, 1.0)).toLong().coerceAtLeast(1)
        var seen = 0L
        for (ms in delayHist.indices) { seen += delayHist[ms]; if (seen >= target) return ms * 1_000L }
        return DELAY_HIST_MS * 1_000L
    }

    /**
     * Impairs `buf[position, limit)` bound for `to`; delivery (if any) happens through `sink` on the scheduler thread
     * (or inline when the sim has no delay, jitter or rate). The buffer is copied and its position advanced to the
     * limit, like [UdpIo.send].
     */
    fun submit(buf: ByteBuffer, to: InetSocketAddress, sink: (ByteBuffer, InetSocketAddress) -> Unit) {
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        enqueue(bytes, to, sink, allowDup = true)
    }

    private fun enqueue(bytes: ByteArray, to: InetSocketAddress, sink: (ByteBuffer, InetSocketAddress) -> Unit, allowDup: Boolean) {
        var deliverNow = false
        lock.withLock {
            if (closed) return
            submitted++
            if (lossEvent()) { dropped++; return }
            if (allowDup && dupProb > 0.0 && rnd.nextDouble() < dupProb) { duplicated++; enqueueLocked(bytes, to, sink) }
            if (immediate) deliverNow = true else enqueueLocked(bytes, to, sink)
        }
        if (deliverNow) deliver(Item(0, 0, bytes, to, sink))
    }

    /** Caller holds the lock. */
    private fun enqueueLocked(bytes: ByteArray, to: InetSocketAddress, sink: (ByteBuffer, InetSocketAddress) -> Unit) {
        if (immediate) { deliver(Item(0, 0, bytes, to, sink)); return }   // duplicates of an immediate sim
        if (queue.size >= limit) { queueDrops++; dropped++; return }
        val now = nowUs()
        val due = if (reorderProb > 0.0 && rnd.nextDouble() < reorderProb) { reordered++; now } else {
            var d = now + max(0L, delayUs + (jitterUs * sample()).toLong())
            if (rateBps > 0) { d = max(d, tailDueUs) + (bytes.size + 28L) * 8_000_000L / rateBps; tailDueUs = d }
            d
        }
        queue.add(Item(seq++, due, bytes, to, sink))
        delayHist[((due - now) / 1000).toInt().coerceIn(0, DELAY_HIST_MS)]++
        if (queue.size > maxQueued) maxQueued = queue.size
        if (thread == null) thread = Thread(::run, "$name-sched").apply { isDaemon = true; start() }
        cond.signal()
    }

    private fun lossEvent(): Boolean {
        if (lossR > 0.0) {                 // sch_netem.c loss_gilb_ell: transition first, the verdict follows the entry state
            if (!bad) { if (rnd.nextDouble() < lossP) bad = true; return false }
            if (rnd.nextDouble() < lossR) bad = false
            return true
        }
        return lossP > 0.0 && rnd.nextDouble() < lossP
    }

    /** Zero-mean, unit-spread jitter sample (netem's normalised distribution tables), correlated if configured. */
    private fun sample(): Double {
        val fresh = when (jitterDist) {
            Dist.UNIFORM -> rnd.nextDouble() * 2 - 1
            Dist.NORMAL -> rnd.nextGaussian().coerceIn(-4.0, 4.0)
            // Pareto, shape 3, scale 1: mean 1.5, variance 0.75 -> normalised; minimum -0.577, P(X > 3) = 1.5 %
            Dist.PARETO -> ((1.0 - rnd.nextDouble()).pow(-1.0 / 3.0) - 1.5) / sqrt(0.75)
        }
        val x = if (jitterCorrelation > 0.0 && hasPrev) jitterCorrelation * prevSample + (1 - jitterCorrelation) * fresh else fresh
        prevSample = x; hasPrev = true
        return x
    }

    private fun run() {
        while (!closed) {
            var item: Item? = null
            lock.withLock {
                while (item == null) {
                    if (closed) return
                    val head = queue.peek()
                    if (head == null) { cond.await(); continue }
                    val wait = head.dueUs - nowUs()
                    when {
                        wait > SPIN_US -> cond.awaitNanos((wait - SPIN_US) * 1_000)
                        wait > 0 -> { lock.unlock(); try { spinUntil(head.dueUs) } finally { lock.lock() } }   // re-peek: an earlier packet may have arrived
                        else -> item = queue.poll()
                    }
                }
            }
            deliver(item!!)
        }
    }

    private fun spinUntil(dueUs: Long) { while (!closed && nowUs() < dueUs) Thread.onSpinWait() }

    private fun deliver(item: Item) {
        if (item.dueUs != 0L && nowUs() - item.dueUs > 1_000) lateReleases++
        try { item.sink(ByteBuffer.wrap(item.bytes), item.to) } catch (e: Exception) { /* socket closed underneath: drop */ }
        delivered++
    }

    /** Stops the scheduler; queued packets are discarded. */
    override fun close() {
        lock.withLock { closed = true; queue.clear(); cond.signalAll() }
        thread?.join(200)
    }

    override fun toString(): String = "$name(seed=$seed): submitted=$submitted delivered=$delivered dropped=$dropped (${"%.2f".format(java.util.Locale.ROOT, 100 * lossRate)}%) " +
        "reordered=$reordered dup=$duplicated queueDrops=$queueDrops queued=$queued maxQueued=$maxQueued lateReleases=$lateReleases"

    /**
     * The link profiles of bench/netem/profiles.sh, one-way. netem on `lo` sits on the egress path of both directions,
     * so the profile's `delay` is the one-way latency and the measured RTT in BENCH-netem.md is twice it: the presets
     * use the profile's `delay`/jitter values as one-way numbers (= half the documented RTTs, 180 / 72 / 97 / 25 /
     * 27 ms) and expect to be attached to both endpoints, which reproduces those RTTs and the shared rate cap.
     * Loss, reorder and rate are the profile's values; the GE averages are 1.6 % (starlink) and 4.8 % (lte,
     * 5g-mmwave). [oneWayUs] is delay + 2 jitter, the nominal one-way budget a lightly loaded link keeps ~p95 of its
     * packets under; the loaded tails at 2000 msg/s are higher (rate ratchet), which the tests allow for.
     */
    enum class Preset(val profile: String, val delayUs: Long, val jitterUs: Long, val dist: Dist,
                      val lossP: Double, val lossR: Double, val reorderProb: Double, val rateBps: Long) {
        /** Plain loopback: no impairment at all. */
        LAN_CLEAN("lan-clean", 0, 0, Dist.UNIFORM, 0.0, 0.0, 0.0, 0),
        /** Transcontinental fibre: `delay 90ms 2ms loss 0.1% rate 1gbit` -> 180 ms RTT. */
        TRANSCONT("transcont", 90_000, 2_000, Dist.UNIFORM, 0.001, 0.0, 0.0, 1_000_000_000L),
        /** LEO satellite: `delay 35ms 12ms loss gemodel 0.5% 30% rate 100mbit` -> ~72 ms RTT, 1.6 % in ~3-packet bursts. */
        STARLINK("starlink", 35_000, 12_000, Dist.UNIFORM, 0.005, 0.30, 0.0, 100_000_000L),
        /** LTE: `delay 45ms 15ms distribution normal loss gemodel 1% 20% rate 30mbit` -> ~97 ms RTT, 4.8 % in ~5-packet bursts. */
        LTE("lte", 45_000, 15_000, Dist.NORMAL, 0.01, 0.20, 0.0, 30_000_000L),
        /** Busy Wi-Fi: `delay 8ms 20ms distribution pareto loss 3% reorder 5% rate 80mbit` -> ~25 ms RTT idle. */
        WIFI_BUSY("wifi-busy", 8_000, 20_000, Dist.PARETO, 0.03, 0.0, 0.05, 80_000_000L),
        /** 5G mmWave: `delay 12ms 8ms distribution pareto loss gemodel 2% 40% rate 400mbit` -> ~27 ms RTT, 4.8 % in ~2.5-packet bursts. */
        FIVEG_MMWAVE("5g-mmwave", 12_000, 8_000, Dist.PARETO, 0.02, 0.40, 0.0, 400_000_000L);

        /** Nominal one-way budget: delay + 2 jitter. */
        val oneWayUs: Long get() = delayUs + 2 * jitterUs
        /** Average loss fraction of the profile (GE: p / (p + r)). */
        val lossAvg: Double get() = if (lossR > 0.0) lossP / (lossP + lossR) else lossP

        fun sim(seed: Long = 1): NetemSim =
            NetemSim(profile, delayUs, jitterUs, dist, 0.0, reorderProb, lossP, lossR, rateBps, 0.0, seed)
    }

    companion object {
        /** Park until this close to the due time, then spin (Thread.sleep/park granularity is ~1 ms on Windows). */
        const val SPIN_US = 2_000L
        /** Delay histogram range in ms (longer delays land in the last bucket). */
        const val DELAY_HIST_MS = 4_000

        fun nowUs(): Long = System.nanoTime() / 1000

        /** Preset by profile name (`lan-clean`, `transcont`, ...) or enum name, case-insensitive. */
        fun preset(name: String, seed: Long = 1): NetemSim = presetOf(name).sim(seed)

        fun presetOf(name: String): Preset {
            val n = name.trim().lowercase(java.util.Locale.ROOT)
            return Preset.entries.firstOrNull { it.profile == n || it.name.lowercase(java.util.Locale.ROOT) == n || it.name.lowercase(java.util.Locale.ROOT).replace('_', '-') == n }
                ?: throw IllegalArgumentException("unknown netem preset '$name'; known: ${Preset.entries.joinToString { it.profile }}")
        }
    }
}

/** [UdpIo] whose every outgoing datagram goes through a [NetemSim]; everything else delegates to the wrapped socket. */
internal class NetemUdpIo(val inner: UdpIo, val sim: NetemSim) : UdpIo by inner {
    override fun send(buf: ByteBuffer, to: InetSocketAddress) = sim.submit(buf, to, inner::send)
    override fun toString(): String = "NetemUdpIo(${sim.name} over $inner)"
}
