package tessera.bench

import tessera.transport.ConnConfig
import tessera.transport.NetemSim
import java.util.Locale

/**
 * Packet amplification against send rate: how many packets Tessera puts on the wire per application message,
 * and which mechanism is responsible at each rate.
 *
 * This exists because of a live measurement that was first misread. The IPv6 run of 2026-08-27 concluded
 * "Tessera delivers nothing above ~400 B over IPv6"; the correction (TEST-PLAN, same day) showed the provider's
 * IPv6 path collapses under ~175 pkt/s while its IPv4 path does not — a property of the path. But the reason
 * Tessera *was* offering ~175 pkt/s for 50 messages/s survived the correction as a genuine transport finding:
 * **~3.5 packets on the wire per source at 50 msg/s.** On a bandwidth-limited link that is overhead; on a
 * rate-limited or narrow-uplink link (the `cell-hotspot` shape) it is the difference between a link that works
 * and one that does not, and no bench measured it.
 *
 * Repairs are emitted **per source**, so the redundancy an application pays is a function of its cadence rather
 * than of the link — the same asymmetry the repair clock (`ConnConfig.repairClockEquationsPerRtt`) exists to
 * trade against, seen from the cost side instead of the latency side. This sweep therefore reports the ratio
 * *and* its decomposition, so a change can be attributed rather than merely observed.
 *
 * Every arm is a fresh connection on the same simulated link, so arms differ only in send rate. The reverse
 * direction is reported separately (`ack/src`): on an asymmetric link the ACK stream is charged to the uplink
 * and is part of the same budget.
 *
 * usage: bench amp [--rates 10,25,50,100,500,2000] [--n 600] [--size 1200] [--netem lte] [--loss 0.0]
 *                  [--clock 0] [--warmup 200]
 */
fun ampMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val rates = opt("rates", "10,25,50,100,500,2000").split(",").map { it.trim().toInt() }
    val n = opt("n", "600").toInt()
    val size = opt("size", "1200").toInt()
    val netemName = opt("netem", "lte")
    val loss = opt("loss", "0.0").toDouble()
    val clock = opt("clock", "0").toInt()
    val warmup = opt("warmup", "200").toInt()
    val tailMinLoss = opt("tailMinLoss", "0.0").toDouble()

    println(String.format(Locale.ROOT,
        "amp      link=%s n=%d size=%d B lossSim=%.3f repairClock=%d eq/rtt tailRepairMinLoss=%.4f",
        if (netemName.isEmpty()) "loopback" else netemName, n, size, loss, clock, tailMinLoss))
    println("amp      rate  pkt/src  bytes/payload  src  repair(pro/react/tlp/tail/clock)  resend  ack/src  delivered  p50/p99 ms")

    for (rate in rates) {
        val netem = if (netemName.isEmpty()) null else NetemSim.preset(netemName)
        try {
            val cfg = ConnConfig(netem = netem, repairClockEquationsPerRtt = clock, tailRepairMinLoss = tailMinLoss,
                idleTimeoutMs = 120_000, pingIntervalMs = 0)
            val gapUs = 1_000_000L / rate
            val r = runTessera(n, gapUs, loss, size, cfg, warmup = warmup)
            val c = r.clientStats
            val delivered = r.latencies.filter { it >= 0 }.sorted()
            fun pct(p: Double) = if (delivered.isEmpty()) 0.0 else delivered[((delivered.size - 1) * p).toInt()] / 1e6
            // per SOURCE, not per message: a message larger than the PLPMTU is several sources, and the ratio
            // being asked about is packets-on-the-wire per packet-of-payload.
            val src = c.sourcesSent.coerceAtLeast(1)
            println(String.format(Locale.ROOT,
                "amp      %5d  %7.2f  %13.2f  %4d  %d/%d/%d/%d/%d  %d  %7.2f  %4d/%d  %.1f/%.1f  p999=%.1f gated=%d",
                rate, c.packetsSent.toDouble() / src,
                // payloadBytesOut is charged where messages are DELIVERED, so the denominator is the server's
                if (r.serverStats.payloadBytesOut > 0) c.bytesSent.toDouble() / r.serverStats.payloadBytesOut else Double.NaN,
                c.sourcesSent, c.repairsProactive, c.repairsReactive, c.repairsTlp, c.repairsTail, c.repairsClock,
                c.sourceResends, r.serverStats.acksSent.toDouble() / src,
                delivered.size, n, pct(0.5), pct(0.99), pct(0.999), c.repairsGated))
        } finally { netem?.close() }
    }
}
