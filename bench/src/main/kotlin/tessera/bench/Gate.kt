package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.NetemSim
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale

/**
 * The perf-regression gate (TEST-PLAN item 5): a fixed scenario set run in-process and compared against a
 * committed baseline, so a change that quietly costs latency or throughput fails loudly instead of being
 * discovered by hand-run A/Bs weeks later. Scenarios and their contracts:
 *
 *   lte-2k / wifi-2k   runTessera at 2000 msg/s over the preset: delivery must be COMPLETE (hard, no
 *                      tolerance — the transport's reliability story), p50 and p99 within bands.
 *   bulk-loopback      20 MB back-to-back send(): complete delivery + a goodput floor (the W2 ceiling).
 *   bulk-transcont     20 MB over the single-sim transcont preset — the horizon + famine regression
 *                      scenario: complete delivery (this wedged twice this project) + a loose goodput floor.
 *
 * Baselines live in bench/gate-baseline.txt (committed, `key value` per line), recorded on the dev machine —
 * they are MACHINE-RELATIVE: after an intentional perf change, or on new hardware, re-record with
 * `bench gate --record`. Tolerances are deliberately wide (latency +/-, throughput floors) to absorb the
 * documented run-to-run variance of real-time in-process benches; delivery completeness is always exact.
 * Exit code 1 on any failure, so CI or a pre-push hook can consume it.
 */
fun gateMain(args: Array<String>) {
    val record = args.contains("--record")
    val baselineFile = File("bench/gate-baseline.txt")
    val results = LinkedHashMap<String, Double>()

    fun latencyRun(name: String, preset: String) {
        val netem = NetemSim.preset(preset)
        try {
            val r = runTessera(n = 5000, gapUs = 500, lossSim = 0.0, size = 1200, cfg = ConnConfig(netem = netem), warmup = 500)
            val ok = r.latencies.filter { it >= 0 }.sorted()
            results["$name.delivered"] = ok.size.toDouble()
            results["$name.p50_ms"] = ok[ok.size / 2] / 1e6
            // p99 is gated only where p99 is actually stable. Measured 2026-08-25, five identical runs:
            // lte p99 spread 116-126 ms (8 %), wifi-busy p99 spread 288-5091 ms — SEVENTEEN-FOLD, on unchanged
            // code. wifi-busy is pareto jitter + 3 % loss + 5 % reorder, and its deep tail is dominated by which
            // burst happens to land where; gating on it produces false failures at any band wide enough to be
            // honest, and a gate that cries wolf gets ignored, which is worse than no gate. Its p50 (124-155 ms)
            // and its delivery are stable, so those still gate. Recorded in BENCH-netem, "The gate's own noise".
            if (name != "wifi-2k") results["$name.p99_ms"] = ok[(ok.size - 1) * 99 / 100] / 1e6
            else results["$name.p99_ms_recorded"] = ok[(ok.size - 1) * 99 / 100] / 1e6
        } finally { netem.close() }
    }

    fun bulkRun(name: String, netem: NetemSim?) {
        val keys = Handshake.generate()
        val cfg = ConnConfig(netem = netem)
        try {
            TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
                TesseraClient(cfg = cfg).use { client ->
                    val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "gate".toByteArray(), timeoutMs = 10_000)
                    val sconn = server.accept(5_000) ?: error("no accept"); sconn.receive(2_000)
                    val size = 1100; val count = 20_000_000 / size
                    var got = 0; var bytes = 0L; var lastNs = 0L
                    val t0 = System.nanoTime()
                    val rx = Thread {
                        while (got < count) {
                            val m = sconn.receive(2_000) ?: if (got > 0 && System.nanoTime() - lastNs > 120_000_000_000L) break else continue
                            got++; bytes += m.size; lastNs = System.nanoTime()
                        }
                    }.apply { isDaemon = true; start() }
                    val msg = ByteArray(size)
                    val tx = Thread { try { repeat(count) { conn.send(msg) } } catch (_: Exception) { } }.apply { isDaemon = true; start() }
                    tx.join(600_000); rx.join(600_000)   // patience, not a deadline: an exact-delivery gate must measure the transport, never its own timeout
                    results["$name.delivered"] = got.toDouble()
                    results["$name.goodput_mbs"] = bytes * 1e9 / (lastNs - t0).coerceAtLeast(1) / 1e6
                    conn.close(); sconn.close()
                }
            }
        } finally { netem?.close() }
    }

    println("gate     running 4 scenarios (~2-4 min) ...")
    latencyRun("lte-2k", "lte")
    latencyRun("wifi-2k", "wifi-busy")
    bulkRun("bulk-loopback", null)
    bulkRun("bulk-transcont", NetemSim.preset("transcont"))

    if (record) {
        baselineFile.writeText(buildString { results.forEach { (k, v) -> appendLine("$k ${"%.3f".format(Locale.ROOT, v)}") } })
        println("gate     baseline recorded to $baselineFile:")
        results.forEach { (k, v) -> println(String.format(Locale.ROOT, "gate       %-28s %.3f", k, v)) }
        return
    }

    require(baselineFile.exists()) { "no baseline at $baselineFile — run `bench gate --record` once on this machine" }
    val base = baselineFile.readLines().filter { it.isNotBlank() }
        .associate { it.substringBefore(' ') to it.substringAfter(' ').toDouble() }
    var failures = 0
    fun check(key: String, pass: Boolean, detail: String) {
        val mark = if (pass) "ok  " else "FAIL"
        if (!pass) failures++
        println(String.format(Locale.ROOT, "gate %s  %-28s %s", mark, key, detail))
    }
    for ((key, v) in results) {
        val b = base[key]
        if (b == null) { check(key, false, "no baseline entry"); continue }
        when {
            key.endsWith(".delivered") -> check(key, v >= b, "%.0f (baseline %.0f, exact)".format(Locale.ROOT, v, b))
            key.endsWith(".p50_ms") -> check(key, v <= b * 1.3, "%.1fms (baseline %.1f, limit +30%%)".format(Locale.ROOT, v, b))
            key.endsWith(".p99_ms") -> check(key, v <= b * 1.8, "%.1fms (baseline %.1f, limit +80%%)".format(Locale.ROOT, v, b))
            key.endsWith(".goodput_mbs") -> check(key, v >= b * 0.5, "%.2fMB/s (baseline %.2f, floor 50%%)".format(Locale.ROOT, v, b))
            // Recorded, never gated: see the note in latencyRun about wifi-busy's p99 variance.
            key.endsWith("_recorded") -> println(String.format(Locale.ROOT, "gate  --   %-28s %.1f (baseline %.1f, recorded only)", key, v, b))
            else -> check(key, false, "unknown metric")
        }
    }
    println(if (failures == 0) "gate     PASS — all metrics within bands" else "gate     FAIL — $failures metric(s) out of band")
    if (failures > 0) kotlin.system.exitProcess(1)
}
