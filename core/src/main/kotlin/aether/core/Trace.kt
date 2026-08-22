@file:Suppress("NOTHING_TO_INLINE") // helpers are inline so the `enabled` check lands at the call site

package aether.core

import java.io.Flushable
import java.io.Writer

/**
 * Structured tracing modeled on qlog (main-schema "0.4" era), serialized as JSON Lines: one JSON record per line,
 * a header record first, then events shaped `{"time":<ms>,"name":"...","data":{...}}`. Field names follow qlog
 * (time, name, data); event names are qlog's where the concept is identical (transport:packet_sent,
 * recovery:metrics_updated, ...) and live under "aether:" where it is not (grants, repair, paths, handshake).
 * See [TraceEvents]. Optional RFC 7464 record separators make the stream strict JSON-SEQ.
 *
 * Units: `time` is milliseconds with microsecond precision (qlog convention; time_format "relative" on whatever
 * clock the caller supplies). Everything under `data` uses Aether's native units: RTTs in microseconds, rates in
 * bytes/s, sizes in bytes.
 *
 * Allocation contract on the hot path (enabled tracer, steady state):
 *  - exactly one object per event: the `fill` lambda, which captures the call-site arguments. Nothing else: the
 *    record text goes into one StringBuilder owned by the tracer and reused for every event; longs/ints/doubles are
 *    formatted straight into it (doubles via fixed-point, no JDK Double.toString); strings are escaped char by char
 *    with no copies; the header is built once.
 *  - plus whatever the caller allocates to pass in, e.g. `listOf("msg","repair")` for `frames` -- hoist constant lists.
 *  - sinks: a StringBuilder sink copies in place; a java.io.Writer sink receives a reused char[]; any other Appendable
 *    goes through `append(CharSequence)`, which may allocate inside the sink (PrintStream builds a String).
 *  - [RingTracer]: each slot's StringBuilder grows until it fits the largest record it has held, then stays.
 *  - disabled ([NoopTracer]): the inline helpers test [Tracer.enabled] and return before the lambda exists, so a
 *    call costs a field load and a branch; `nowUs()` is a constant 0 for NoopTracer.
 * One lock per tracer serializes rx/tx threads; `fill` runs under it, so keep builder lambdas short.
 */
interface Tracer {
    /** False for [NoopTracer]. Check it before doing any work to build an event. */
    val enabled: Boolean

    /** Current time in microseconds on this tracer's clock; the helpers use it when no explicit time is given. */
    fun nowUs(): Long

    /**
     * Emit one event. [fill] writes the `data` fields via [TraceBuilder]; it runs under the tracer lock and is never
     * invoked when tracing is off. Ad-hoc call sites should guard with `if (tracer.enabled)` so the lambda is not
     * even allocated when disabled -- the helper extensions ([packetSent], [metrics], ...) do that for you.
     */
    fun event(name: String, timeUs: Long, fill: TraceBuilder.() -> Unit)
}

/** The default tracer: drops everything. [event] never touches the lambda. */
object NoopTracer : Tracer {
    override val enabled: Boolean get() = false
    override fun nowUs(): Long = 0L
    override fun event(name: String, timeUs: Long, fill: TraceBuilder.() -> Unit) {}
}

/** qlog vantage point of the trace. */
enum class VantagePoint(val wire: String) { CLIENT("client"), SERVER("server") }

/** Handshake flavour reported by [handshake]. */
enum class HandshakeKind(val wire: String) { PQ("pq"), RESUME("resume") }

/** Event names. qlog's own names where the semantics match, "aether:" for Aether-specific mechanisms. */
object TraceEvents {
    const val PACKET_SENT = "transport:packet_sent"
    const val PACKET_RECEIVED = "transport:packet_received"
    const val METRICS_UPDATED = "recovery:metrics_updated"
    const val PACKET_LOST = "recovery:packet_lost"
    const val GRANT_ISSUED = "aether:grant_issued"
    const val REPAIR_SENT = "aether:repair_sent"
    const val REPAIR_DECODED = "aether:repair_decoded"
    const val PATH_ADDED = "aether:path_added"
    const val PATH_SWITCHED = "aether:path_switched"
    const val HANDSHAKE = "aether:handshake"
}

/** Header-record constants. */
object TraceFormat {
    const val QLOG_VERSION = "0.4"
    const val QLOG_FORMAT = "JSON-SEQ"
    /** [Wire.VERSION] as it appears in the header, e.g. "0x41450000". */
    val AETHER_VERSION: String = "0x%08X".format(Wire.VERSION)
    /** RFC 7464 record separator, emitted before each record when requested. */
    val RS: Char = Char(0x1E)
}

/**
 * Writes JSON fields into a tracer-owned, reused StringBuilder. Commas are inferred from the previous character,
 * so nesting via [obj]/[array] needs no state. Doubles are written fixed-point with up to 9 fractional digits
 * (trailing zeros trimmed; NaN/Infinity become null because JSON has no spelling for them).
 */
class TraceBuilder internal constructor(initialCapacity: Int = 512) {
    @PublishedApi internal val sb = StringBuilder(initialCapacity)

    fun field(key: String, value: Long): TraceBuilder { key(key); sb.append(value); return this }
    fun field(key: String, value: Int): TraceBuilder { key(key); sb.append(value); return this }
    fun field(key: String, value: Double): TraceBuilder { key(key); appendDouble(value); return this }
    fun field(key: String, value: Boolean): TraceBuilder { key(key); sb.append(value); return this }
    fun field(key: String, value: String?): TraceBuilder { key(key); if (value == null) sb.append("null") else quote(value); return this }
    fun field(key: String, value: PathId): TraceBuilder = field(key, value.raw)
    fun nullField(key: String): TraceBuilder { key(key); sb.append("null"); return this }

    /** `"key":["a","b"]`. Index loop: no iterator for the usual RandomAccess lists. */
    fun strings(key: String, values: List<String>): TraceBuilder {
        key(key); sb.append('[')
        for (i in values.indices) { if (i > 0) sb.append(','); quote(values[i]) }
        sb.append(']'); return this
    }

    inline fun obj(key: String, fill: TraceBuilder.() -> Unit): TraceBuilder { open(key, '{'); fill(); close('}'); return this }
    inline fun array(key: String, fill: TraceBuilder.() -> Unit): TraceBuilder { open(key, '['); fill(); close(']'); return this }
    fun item(value: Long): TraceBuilder { separator(); sb.append(value); return this }
    fun item(value: Double): TraceBuilder { separator(); appendDouble(value); return this }
    fun item(value: String): TraceBuilder { separator(); quote(value); return this }

    @PublishedApi internal fun open(key: String, bracket: Char) { key(key); sb.append(bracket) }
    @PublishedApi internal fun close(bracket: Char) { sb.append(bracket) }

    // ---- record framing, used by the tracers ----
    internal fun beginRecord(recordSeparator: Boolean) {
        sb.setLength(0)
        if (recordSeparator) sb.append(TraceFormat.RS)
        sb.append('{')
    }
    internal fun endRecord() { sb.append('}').append('\n') }
    internal fun beginEvent(name: String, timeUs: Long, recordSeparator: Boolean) {
        beginRecord(recordSeparator)
        sb.append("\"time\":"); appendTimeMs(timeUs)
        sb.append(",\"name\":"); quote(name)
        sb.append(",\"data\":{")
    }
    internal fun endEvent() { sb.append('}'); endRecord() }

    private fun separator() {
        val n = sb.length
        if (n == 0) return
        val last = sb[n - 1]
        if (last != '{' && last != '[') sb.append(',')
    }

    private fun key(k: String) { separator(); quote(k); sb.append(':') }

    /** Microseconds as qlog milliseconds: 1500250 -> 1500.250. */
    private fun appendTimeMs(timeUs: Long) {
        var us = timeUs
        if (us < 0) { sb.append('-'); us = -us }
        sb.append(us / 1000).append('.')
        val r = (us % 1000).toInt()
        if (r < 100) sb.append('0')
        if (r < 10) sb.append('0')
        sb.append(r)
    }

    private fun appendDouble(v: Double) {
        if (v.isNaN() || v.isInfinite()) { sb.append("null"); return }
        val a = if (v < 0) -v else v
        if (a >= 1e18) { sb.append(v); return } // out of fixed-point range; JDK formatting (allocates, never hit in practice)
        var whole = a.toLong()
        var frac = Math.round((a - whole) * SCALE)
        if (frac >= SCALE) { whole += 1; frac -= SCALE }
        if (v < 0 && (whole != 0L || frac != 0L)) sb.append('-')
        sb.append(whole)
        if (frac != 0L) {
            sb.append('.')
            var width = DECIMALS
            while (frac % 10 == 0L) { frac /= 10; width-- }
            var len = 1; var t = frac
            while (t >= 10) { t /= 10; len++ }
            repeat(width - len) { sb.append('0') }
            sb.append(frac)
        }
    }

    private fun quote(s: CharSequence) {
        sb.append('"')
        for (i in 0 until s.length) {
            when (val c = s[i]) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                FORM_FEED -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF]) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private companion object {
        const val DECIMALS = 9
        const val SCALE = 1_000_000_000L
        const val HEX = "0123456789abcdef"
        val FORM_FEED: Char = Char(0x0C)
    }
}

/** qlog-style header record: version/format/title, vantage point, protocol and Aether wire version. */
private fun writeHeader(b: TraceBuilder, title: String, vantage: VantagePoint, recordSeparator: Boolean) {
    b.beginRecord(recordSeparator)
    b.field("qlog_version", TraceFormat.QLOG_VERSION)
        .field("qlog_format", TraceFormat.QLOG_FORMAT)
        .field("title", title)
        .field("aether_version", TraceFormat.AETHER_VERSION)
        .obj("trace") {
            obj("vantage_point") { field("type", vantage.wire) }
            obj("common_fields") { field("time_format", "relative"); array("protocol_type") { item("AETHER") } }
        }
    b.endRecord()
}

/**
 * Streams records to [sink] as they happen. Safe to call from the rx and tx threads concurrently (one lock).
 * The header is written lazily before the first event. [flush]/[close] forward to the sink when it supports them.
 * [enabled] can be toggled at runtime.
 */
class JsonLinesTracer(
    private val sink: Appendable,
    private val clock: () -> Long = { System.nanoTime() / 1_000 },
    val vantagePoint: VantagePoint = VantagePoint.CLIENT,
    val title: String = "aether",
    private val recordSeparator: Boolean = false,
) : Tracer, AutoCloseable {
    @Volatile override var enabled: Boolean = true
    private val lock = Any()
    private val builder = TraceBuilder()
    private val writer: Writer? = sink as? Writer
    private var chars = CharArray(0)
    private var headerWritten = false

    override fun nowUs(): Long = clock()

    override fun event(name: String, timeUs: Long, fill: TraceBuilder.() -> Unit) {
        if (!enabled) return
        synchronized(lock) {
            if (!headerWritten) {
                writeHeader(builder, title, vantagePoint, recordSeparator)
                emit()
                headerWritten = true
            }
            builder.beginEvent(name, timeUs, recordSeparator)
            builder.fill() // if this throws, the partial record is never emitted; the next event resets the builder
            builder.endEvent()
            emit()
        }
    }

    private fun emit() {
        val sb = builder.sb
        val w = writer
        if (w != null) {
            val n = sb.length
            if (chars.size < n) chars = CharArray(maxOf(n, chars.size * 2))
            sb.getChars(0, n, chars, 0)
            w.write(chars, 0, n)
        } else {
            sink.append(sb)
        }
    }

    fun flush() { synchronized(lock) { (sink as? Flushable)?.flush() } }

    override fun close() {
        synchronized(lock) { (sink as? Flushable)?.flush(); (sink as? AutoCloseable)?.close() }
    }
}

/**
 * Keeps the last [capacity] events in memory (one reused StringBuilder per slot) for post-mortem dumps.
 * [dump] writes a complete trace: header first, then the retained events oldest to newest.
 */
class RingTracer(
    val capacity: Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000 },
    val vantagePoint: VantagePoint = VantagePoint.CLIENT,
    val title: String = "aether",
) : Tracer {
    init { require(capacity > 0) { "capacity must be positive" } }

    @Volatile override var enabled: Boolean = true
    private val lock = Any()
    private val builder = TraceBuilder()
    private val slots = Array(capacity) { StringBuilder() }
    private var next = 0
    private var count = 0

    /** Number of retained events (at most [capacity]). */
    val size: Int get() = synchronized(lock) { count }

    override fun nowUs(): Long = clock()

    override fun event(name: String, timeUs: Long, fill: TraceBuilder.() -> Unit) {
        if (!enabled) return
        synchronized(lock) {
            builder.beginEvent(name, timeUs, false)
            builder.fill()
            builder.endEvent()
            val slot = slots[next]
            slot.setLength(0)
            slot.append(builder.sb)
            next = (next + 1) % capacity
            if (count < capacity) count++
        }
    }

    fun dump(sink: Appendable) {
        synchronized(lock) {
            writeHeader(builder, title, vantagePoint, false)
            sink.append(builder.sb)
            val first = (next - count + capacity) % capacity
            for (i in 0 until count) sink.append(slots[(first + i) % capacity])
        }
    }

    /** Retained event records, oldest first, each a full line. Allocates; for inspection, not the hot path. */
    fun snapshot(): List<String> = synchronized(lock) {
        val first = (next - count + capacity) % capacity
        List(count) { slots[(first + it) % capacity].toString() }
    }

    fun clear() { synchronized(lock) { next = 0; count = 0 } }
}

// ---- one-liner helpers for the common events; all bail out before allocating when the tracer is disabled ----

inline fun Tracer.packetSent(path: PathId, pn: Long, bytes: Int, frames: List<String> = emptyList(), timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.PACKET_SENT, timeUs) {
        field("path_id", path).field("packet_number", pn).field("length", bytes).strings("frames", frames)
    }
}

inline fun Tracer.packetReceived(path: PathId, pn: Long, bytes: Int, frames: List<String> = emptyList(), timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.PACKET_RECEIVED, timeUs) {
        field("path_id", path).field("packet_number", pn).field("length", bytes).strings("frames", frames)
    }
}

/**
 * Snapshot of a [PathEstimator]: srtt, rttvar, min_rtt (microseconds), loss_rate, delivery_rate (bytes/s),
 * fec_redundancy. `min_rtt` is omitted until the first RTT sample, qlog-style, rather than reporting the sentinel.
 */
inline fun Tracer.metrics(est: PathEstimator, timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.METRICS_UPDATED, timeUs) {
        field("path_id", est.path).field("srtt", est.srttUs).field("rttvar", est.rttVarUs)
        if (est.minRttUs != Double.MAX_VALUE) field("min_rtt", est.minRttUs)
        field("loss_rate", est.lossRate).field("delivery_rate", est.deliveredBytesPerSec).field("fec_redundancy", est.fecRedundancy())
    }
}

/** [trigger] follows qlog: "time_threshold" (RACK timer, Aether's default), "reordering_threshold", "pto_expired". */
inline fun Tracer.packetLost(path: PathId, pn: Long, trigger: String = "time_threshold", timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.PACKET_LOST, timeUs) { field("path_id", path).field("packet_number", pn).field("trigger", trigger) }
}

inline fun Tracer.grantIssued(path: PathId, creditBytes: Long, priority: Int, timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.GRANT_ISSUED, timeUs) { field("path_id", path).field("credit_bytes", creditBytes).field("priority", priority) }
}

inline fun Tracer.grantIssued(grant: Frame.Grant, timeUs: Long = nowUs()) =
    grantIssued(grant.path, grant.creditBytes, grant.priority, timeUs)

inline fun Tracer.repairSent(path: PathId, windowBase: Long, windowLen: Int, seed: Int, timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.REPAIR_SENT, timeUs) {
        field("path_id", path).field("window_base", windowBase).field("window_len", windowLen).field("seed", seed)
    }
}

inline fun Tracer.repairSent(path: PathId, repair: Frame.Repair, timeUs: Long = nowUs()) =
    repairSent(path, repair.windowBase, repair.windowLen, repair.seed, timeUs)

/** A source symbol recovered by the RLNC decoder; [path] is where the repair symbol arrived. */
inline fun Tracer.repairDecoded(path: PathId, recoveredSeq: Long, timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.REPAIR_DECODED, timeUs) { field("path_id", path).field("recovered_seq", recoveredSeq) }
}

inline fun Tracer.pathAdded(path: PathId, remote: String? = null, timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.PATH_ADDED, timeUs) { field("path_id", path); if (remote != null) field("remote", remote) }
}

inline fun Tracer.pathSwitched(from: PathId, to: PathId, reason: String? = null, timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.PATH_SWITCHED, timeUs) { field("from_path", from).field("to_path", to); if (reason != null) field("reason", reason) }
}

inline fun Tracer.handshake(kind: HandshakeKind, zeroRttBytes: Int, timeUs: Long = nowUs()) {
    if (!enabled) return
    event(TraceEvents.HANDSHAKE, timeUs) { field("kind", kind.wire).field("zero_rtt_bytes", zeroRttBytes) }
}
