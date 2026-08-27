package tessera.transport

import tessera.core.Handshake
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The repair clock ([ConnConfig.repairClockEquationsPerRtt]) puts a time floor under the rate at which repair
 * symbols are emitted, so a slowly-driven lossy link stops accumulating equations at the *application's*
 * cadence. It is off by default and costs bandwidth when on, so what has to be pinned is not "it makes things
 * faster" — the measured effect lives in BENCH-netem and depends on the link — but that it engages only where
 * it is meant to. Each guard here was either found by measurement or is the reason a measurement went wrong:
 *
 *  - **disabled by default**: no existing deployment starts paying for it silently;
 *  - **never on a fast stream**: at a high send rate the source cadence already beats the clock, so firing
 *    would be pure duplication;
 *  - **never on a clean link**: with no loss the extra equations can only cost bytes;
 *  - **capped per source interval**: the first version had no cap, and on 5g-mmwave (short srtt against a
 *    20 ms send gap) it emitted ~10 equations per source, drove overhead to 7.1x and made every percentile
 *    WORSE — repairs queued in front of the traffic they were protecting.
 */
class RepairClockTest {
    private val keys = Handshake.generate()

    private class Run(val clockRepairs: Long, val proactive: Long, val tail: Long, val delivered: Int)

    /** One arm: `n` messages at `gapUs` over `preset`, returning the client's repair accounting. */
    private fun run(preset: NetemSim.Preset?, perRtt: Int, n: Int, gapUs: Long, seed: Long = 5L): Run {
        val netem = preset?.let { NetemSim.preset(it.name.lowercase().replace('_', '-'), seed) }
        val cfg = ConnConfig(netem = netem, repairClockEquationsPerRtt = perRtt)
        try {
            TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
                TesseraClient(cfg = cfg).use { client ->
                    val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "rc".toByteArray(), timeoutMs = 10_000)
                    val sc = server.accept(5_000) ?: error("no accept"); sc.receive(2_000)
                    var got = 0
                    val rx = Thread { while (got < n) { sc.receive(200) ?: continue; got++ } }.apply { isDaemon = true; start() }
                    repeat(n) {
                        conn.send(ByteArray(1200) { b -> b.toByte() })
                        val end = System.nanoTime() + gapUs * 1000
                        while (System.nanoTime() < end) Thread.onSpinWait()
                    }
                    rx.join(8_000)
                    val cs = conn.stats
                    val r = Run(cs.repairsClock, cs.repairsProactive, cs.repairsTail, got)
                    conn.close(); sc.close()
                    return r
                }
            }
        } finally { netem?.close() }
    }

    /** Off unless asked for: the default must not change any existing deployment's wire behaviour. */
    @Test fun isOffByDefault() {
        assertEquals(0, ConnConfig().repairClockEquationsPerRtt, "the repair clock must default to off")
        val r = run(NetemSim.Preset.LTE, perRtt = 0, n = 120, gapUs = 20_000)
        assertEquals(0L, r.clockRepairs, "the clock emitted repairs while disabled")
        assertTrue(r.proactive + r.tail > 0, "the ordinary repair paths should still have run")
    }

    /**
     * A fast stream must not engage it. At a 500 us send gap the source cadence is far below any srtt/perRtt
     * period, so the guard is `sendGapEwmaUs <= period` and firing here would duplicate equations the stream
     * already produces.
     */
    @Test @Tag("timing") fun neverEngagesOnAFastStream() {
        val r = run(NetemSim.Preset.LTE, perRtt = 12, n = 1500, gapUs = 500)
        assertEquals(0L, r.clockRepairs, "the clock fired on a fast stream, where the source cadence already beats it")
    }

    /** A clean link has nothing for the extra equations to recover, so the loss gate must keep them off. */
    @Test @Tag("timing") fun neverEngagesOnACleanLink() {
        val r = run(NetemSim.Preset.LAN_CLEAN, perRtt = 12, n = 200, gapUs = 20_000)
        assertEquals(0L, r.clockRepairs, "the clock fired on a clean link")
    }

    /**
     * On the link it exists for — a lossy radio driven slowly — it engages, and stays inside the per-source
     * ceiling. The upper bound is the guard that was missing when an uncapped clock made 5g-mmwave worse; it is
     * asserted with headroom because the tick is ~1 ms and the cap is enforced per period, not per source.
     */
    @Test @Tag("timing") fun engagesOnASlowLossyLinkAndStaysUnderThePerSourceCeiling() {
        val n = 400
        val r = run(NetemSim.Preset.LTE, perRtt = 12, n = n, gapUs = 20_000)
        assertTrue(r.clockRepairs > 0, "the clock never engaged on a slow lossy link: ${r.clockRepairs}")
        val ceiling = TesseraConnection.CLOCK_MAX_PER_SOURCE.toLong() * n + n / 2
        assertTrue(r.clockRepairs <= ceiling, "the clock emitted ${r.clockRepairs} repairs for $n sources, past the ceiling $ceiling")
    }
}
