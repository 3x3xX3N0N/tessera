package tessera.core

import java.io.BufferedWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TraceTest {
    private fun lines(text: CharSequence): List<String> = text.split('\n').filter { it.isNotEmpty() }

    /** Strict structural check plus a parse: every record must be one complete JSON object on one line. */
    private fun record(line: String): Map<String, Any?> {
        assertTrue(line.startsWith("{") && line.endsWith("}"), "not a single-line object: $line")
        assertFalse(line.contains('\n'), "embedded newline: $line")
        @Suppress("UNCHECKED_CAST")
        return JsonParser(line).parse() as Map<String, Any?>
    }

    private fun records(text: CharSequence): List<Map<String, Any?>> = lines(text).map(::record)
    private fun data(rec: Map<String, Any?>): Map<*, *> = rec["data"] as Map<*, *>

    @Test fun noopProducesNothingAndIsCheap() {
        val t: Tracer = NoopTracer
        assertFalse(t.enabled)
        t.event("tessera:never", 0) { fail("fill must not run when tracing is off") }
        t.metrics(PathEstimator(PathId(0)))
        repeat(200_000) { t.packetSent(PathId(0), it.toLong(), 1200) } // warm-up: measure steady state, not JIT
        val start = System.nanoTime()
        repeat(1_000_000) { t.packetSent(PathId(0), it.toLong(), 1200) }
        val ms = (System.nanoTime() - start) / 1e6
        assertTrue(ms < 100.0, "1e6 disabled events took $ms ms")
    }

    @Test fun headerIsEmittedOnceWithQlogFields() {
        val out = StringBuilder()
        val t = JsonLinesTracer(out, { 0L }, vantagePoint = VantagePoint.SERVER, title = "unit")
        assertEquals("", out.toString(), "nothing before the first event")
        repeat(5) { t.packetSent(PathId(0), it.toLong(), 100) }
        val recs = records(out)
        assertEquals(6, recs.size)
        assertEquals(1, recs.count { it.containsKey("qlog_version") }, "header exactly once")
        val h = recs[0]
        assertEquals("0.4", h["qlog_version"])
        assertEquals("JSON-SEQ", h["qlog_format"])
        assertEquals("unit", h["title"])
        assertEquals("0x54530000", h["tessera_version"])
        assertEquals(TraceFormat.TESSERA_VERSION, h["tessera_version"])
        val trace = h["trace"] as Map<*, *>
        assertEquals("server", (trace["vantage_point"] as Map<*, *>)["type"])
        assertEquals(listOf("TESSERA"), (trace["common_fields"] as Map<*, *>)["protocol_type"])
        assertTrue(out.startsWith("{\"qlog_version\":\"0.4\""), "header is the first line")
    }

    @Test fun everyEventLineIsValidJsonWithTimeNameData() {
        val out = StringBuilder()
        var now = 0L
        val t = JsonLinesTracer(out, { now })
        now = 1_500_250; t.packetSent(PathId(1), 42, 1200, listOf("msg", "repair"))
        now = 2_000_000; t.packetReceived(PathId(1), 7, 64)
        t.event("tessera:custom", -250) { field("x", 1) }
        val recs = records(out).drop(1)
        assertEquals(3, recs.size)
        recs.forEach { assertEquals(listOf("time", "name", "data"), it.keys.toList(), "qlog field order") }
        assertEquals(1500.25, recs[0]["time"])
        assertEquals(TraceEvents.PACKET_SENT, recs[0]["name"])
        assertEquals(mapOf("path_id" to 1L, "packet_number" to 42L, "length" to 1200L, "frames" to listOf("msg", "repair")), data(recs[0]))
        assertEquals(2000.0, recs[1]["time"])
        assertEquals(TraceEvents.PACKET_RECEIVED, recs[1]["name"])
        assertEquals(emptyList<String>(), data(recs[1])["frames"])
        assertEquals(-0.25, recs[2]["time"])
        assertTrue(lines(out)[1].contains("\"time\":1500.250,\"name\":\"transport:packet_sent\",\"data\":{"), lines(out)[1])
    }

    @Test fun metricsCarriesAllSixNumericFields() {
        val est = PathEstimator(PathId(3)).apply {
            onRttSample(20_000); onRttSample(24_000)
            repeat(10) { onLossObservation(0.02) }
            onDelivered(0, 1_000); onDelivered(120_000, 101_000)
        }
        val out = StringBuilder()
        val t = JsonLinesTracer(out, { 99_000L })
        t.metrics(est)
        val rec = records(out)[1]
        assertEquals(TraceEvents.METRICS_UPDATED, rec["name"])
        assertEquals(99.0, rec["time"])
        val d = data(rec)
        assertEquals(3L, d["path_id"])
        for (k in listOf("srtt", "rttvar", "min_rtt", "loss_rate", "delivery_rate", "fec_redundancy")) {
            assertTrue(d[k] is Number, "$k should be numeric, was ${d[k]}")
        }
        fun num(k: String) = (d[k] as Number).toDouble()
        assertEquals(est.srttUs, num("srtt"), 1e-6)
        assertEquals(est.rttVarUs, num("rttvar"), 1e-6)
        assertEquals(20_000.0, num("min_rtt"), 1e-6)
        assertEquals(est.lossRate, num("loss_rate"), 1e-9)
        assertEquals(1_200_000.0, num("delivery_rate"), 1e-3)
        assertEquals(est.fecRedundancy(), num("fec_redundancy"), 1e-9)
        // before any RTT sample min_rtt is unknown: omitted rather than the Double.MAX_VALUE sentinel
        val fresh = StringBuilder()
        JsonLinesTracer(fresh, { 0L }).metrics(PathEstimator(PathId(0)))
        assertNull(data(records(fresh)[1])["min_rtt"])
    }

    @Test fun ringKeepsExactlyLastN() {
        val ring = RingTracer(4, { 0L }, title = "ring")
        repeat(10) { ring.packetSent(PathId(0), it.toLong(), 100) }
        assertEquals(4, ring.size)
        val out = StringBuilder(); ring.dump(out)
        val recs = records(out)
        assertEquals(5, recs.size, "header + 4 events")
        assertEquals("ring", recs[0]["title"])
        assertEquals(listOf(6L, 7L, 8L, 9L), recs.drop(1).map { data(it)["packet_number"] })
        assertEquals(4, ring.snapshot().size)
        assertTrue(ring.snapshot()[0].contains("\"packet_number\":6"))
        // wrap-around keeps working after many more events
        repeat(103) { ring.packetSent(PathId(0), 1000L + it, 100) }
        assertEquals(listOf(1099L, 1100L, 1101L, 1102L), ring.snapshot().map { data(record(it.trimEnd())) ["packet_number"] })
        ring.clear(); assertEquals(0, ring.size)
        // fewer events than capacity
        val small = RingTracer(8, { 0L }); repeat(3) { small.packetLost(PathId(0), it.toLong()) }
        val dump = StringBuilder(); small.dump(dump)
        assertEquals(4, records(dump).size)
    }

    @Test fun builderEscapesStringsAndFormatsNumbers() {
        val out = StringBuilder()
        val t = JsonLinesTracer(out, { 0L })
        val nasty = "he said \"hi\" \\ tab\t nl\n cr\r bs\b ff" + Char(0x0C) + " ctl" + Char(1) + " unicode é中"
        t.event("tessera:custom", 0) {
            field("s", nasty).field("small", 0.000123).field("neg", -1.5).field("tiny", 1e-12).field("nan", Double.NaN)
            field("big", 1.0e20).field("int", 7).field("long", -9_007_199_254_740_993L).field("flag", false)
            nullField("n").field("nothing", null as String?)
            obj("o") { field("k", true).obj("inner") { field("z", 0.5) } }
            array("a") { item(1L); item("x"); item(2.5) }
            strings("frames", listOf("msg", "repair", "qu\"ote"))
        }
        val d = data(records(out)[1])
        assertEquals(nasty, d["s"])
        assertEquals(0.000123, d["small"])
        assertEquals(-1.5, d["neg"])
        assertEquals(0L, d["tiny"])
        assertNull(d["nan"]); assertTrue(d.containsKey("nan"))
        assertEquals(1.0e20, d["big"])
        assertEquals(7L, d["int"])
        assertEquals(-9_007_199_254_740_993L, d["long"])
        assertEquals(false, d["flag"])
        assertNull(d["n"]); assertNull(d["nothing"]); assertTrue(d.containsKey("nothing"))
        assertEquals(mapOf("k" to true, "inner" to mapOf("z" to 0.5)), d["o"])
        assertEquals(listOf(1L, "x", 2.5), d["a"])
        assertEquals(listOf("msg", "repair", "qu\"ote"), d["frames"])
        val raw = lines(out)[1]
        assertTrue(raw.contains("\"small\":0.000123,"), raw)
        assertTrue(raw.contains("\"tiny\":0,"), raw)
        assertTrue(raw.contains("\\u0001"), raw)
        assertFalse(raw.contains(Char(1)), "raw control char leaked")
    }

    @Test fun helpersEmitDocumentedNamesAndFields() {
        val out = StringBuilder()
        val t = JsonLinesTracer(out, { 5_000L })
        t.packetLost(PathId(2), 17)
        t.grantIssued(Frame.Grant(PathId(1), 9000, 2))
        t.repairSent(PathId(1), Frame.Repair(100, 16, 0x5EED, java.nio.ByteBuffer.allocate(0)))
        t.repairDecoded(PathId(0), 104)
        t.pathAdded(PathId(1), remote = "[2001:db8::1]:4433")
        t.pathSwitched(PathId(0), PathId(1), reason = "loss")
        t.handshake(HandshakeKind.PQ, zeroRttBytes = 184)
        t.handshake(HandshakeKind.RESUME, zeroRttBytes = 1288, timeUs = 7_000)
        val recs = records(out).drop(1)
        val byName = recs.groupBy { it["name"] as String }
        assertEquals(mapOf("path_id" to 2L, "packet_number" to 17L, "trigger" to "time_threshold"), data(byName.getValue(TraceEvents.PACKET_LOST).single()))
        assertEquals(mapOf("path_id" to 1L, "credit_bytes" to 9000L, "priority" to 2L), data(byName.getValue(TraceEvents.GRANT_ISSUED).single()))
        assertEquals(mapOf("path_id" to 1L, "window_base" to 100L, "window_len" to 16L, "seed" to 0x5EEDL), data(byName.getValue(TraceEvents.REPAIR_SENT).single()))
        assertEquals(mapOf("path_id" to 0L, "recovered_seq" to 104L), data(byName.getValue(TraceEvents.REPAIR_DECODED).single()))
        assertEquals(mapOf("path_id" to 1L, "remote" to "[2001:db8::1]:4433"), data(byName.getValue(TraceEvents.PATH_ADDED).single()))
        assertEquals(mapOf("from_path" to 0L, "to_path" to 1L, "reason" to "loss"), data(byName.getValue(TraceEvents.PATH_SWITCHED).single()))
        val hs = byName.getValue(TraceEvents.HANDSHAKE)
        assertEquals(mapOf("kind" to "pq", "zero_rtt_bytes" to 184L), data(hs[0]))
        assertEquals(5.0, hs[0]["time"])
        assertEquals(mapOf("kind" to "resume", "zero_rtt_bytes" to 1288L), data(hs[1]))
        assertEquals(7.0, hs[1]["time"], "explicit timeUs overrides the clock")
        assertTrue(recs.all { (it["name"] as String).startsWith("tessera:") || (it["name"] as String).startsWith("recovery:") })
    }

    @Test fun twoThreadsInterleaveWithoutCorruption() {
        val out = StringBuilder()
        val t = JsonLinesTracer(out, { 0L })
        val n = 2000
        val threads = (0 until 2).map { id ->
            Thread { repeat(n) { i -> t.event("tessera:thread", i.toLong()) { field("thread", id).field("i", i).strings("frames", listOf("msg")) } } }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val recs = records(out)
        assertEquals(2 * n + 1, recs.size)
        assertEquals(1, recs.count { it.containsKey("qlog_version") })
        val perThread = recs.drop(1).groupBy { data(it)["thread"] }
        assertEquals(setOf(0L, 1L), perThread.keys)
        perThread.values.forEach { evs -> assertEquals((0 until n).map { it.toLong() }, evs.map { data(it)["i"] }) }
    }

    @Test fun writerSinkDisableToggleAndRecordSeparator() {
        val sw = StringWriter()
        JsonLinesTracer(BufferedWriter(sw), { 1_000L }).use { t ->
            t.packetSent(PathId(0), 1, 10)
            t.enabled = false
            t.packetSent(PathId(0), 2, 10)
            t.event("tessera:never", 0) { fail("disabled tracer must not run fill") }
            t.enabled = true
            t.packetSent(PathId(0), 3, 10)
            t.flush()
            assertEquals(3, lines(sw.toString()).size, "flush pushes through the BufferedWriter")
        }
        val recs = records(sw.toString())
        assertEquals(3, recs.size)
        assertEquals(listOf(1L, 3L), recs.drop(1).map { data(it)["packet_number"] })

        val seq = StringBuilder()
        JsonLinesTracer(seq, { 0L }, recordSeparator = true).packetSent(PathId(0), 1, 10)
        val raw = lines(seq)
        assertEquals(2, raw.size)
        raw.forEach { assertEquals(Char(0x1E), it[0], "RFC 7464 record separator") }
        raw.forEach { assertNotNull(record(it.substring(1))) }
    }

    /** Minimal strict JSON parser (RFC 8259) for validating tracer output: objects -> Map, arrays -> List,
     *  integers -> Long, other numbers -> Double, strings, booleans, null. */
    private class JsonParser(private val s: String) {
        private var i = 0

        fun parse(): Any? {
            val v = value(); ws()
            check(i == s.length) { "trailing garbage at $i in: $s" }
            return v
        }

        private fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }
        private fun peek(): Char { check(i < s.length) { "unexpected end of input" }; return s[i] }

        private fun value(): Any? {
            ws()
            return when (peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? { check(s.startsWith(word, i)) { "bad literal at $i" }; i += word.length; return v }

        private fun obj(): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws(); check(peek() == '"') { "key expected at $i" }
                val k = str(); ws(); check(peek() == ':') { "colon expected at $i" }; i++
                check(!m.containsKey(k)) { "duplicate key $k" }
                m[k] = value(); ws()
                when (peek()) { ',' -> i++; '}' -> { i++; return m }; else -> error("bad object at $i") }
            }
        }

        private fun arr(): List<Any?> {
            i++; val l = ArrayList<Any?>(); ws()
            if (peek() == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (peek()) { ',' -> i++; ']' -> { i++; return l }; else -> error("bad array at $i") }
            }
        }

        private fun str(): String {
            i++; val sb = StringBuilder()
            while (true) {
                val c = peek(); i++
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> when (val e = peek().also { i++ }) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append(Char(0x0C)); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("bad escape \\$e at $i")
                    }
                    c < ' ' -> error("raw control character in string at ${i - 1}")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = i
            if (peek() == '-') i++
            check(peek().isDigit()) { "digit expected at $i" }
            if (s[i] == '0') i++ else while (i < s.length && s[i].isDigit()) i++
            var real = false
            if (i < s.length && s[i] == '.') { real = true; i++; check(peek().isDigit()) { "fraction digit expected" }; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                real = true; i++
                if (peek() == '+' || peek() == '-') i++
                check(peek().isDigit()) { "exponent digit expected" }
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            return if (real) text.toDouble() else text.toLong()
        }
    }
}
