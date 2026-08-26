package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * W4 — idle, then burst. Every workload measured so far sends continuously (W1 paced, W2 bulk) or once (W3
 * connect); nobody had measured what the transport does to the FIRST messages after a quiet gap, which is the
 * shape of almost every real application — a chat client, an RPC channel, a game between rounds.
 *
 * The radio half of W4 (doze, carrier NAT expiry, RRC promotion) needs a real handset and is out of scope here.
 * The local half is: after N seconds of silence, what do the estimators, the credit target and the timers that
 * key on last-send cost the first burst? Each arm is a fresh connection, warmed with a paced stream whose tail
 * gives the steady-state baseline, then silent for the gap, then a back-to-back burst of `burst` messages. What
 * is reported is the first message's one-way latency (the number an app feels), the burst's p50/p99, and the
 * state the burst met: the receiver's credit target and the sender's remaining credit across the gap, the RTT
 * and delivery-rate estimates, and the stall / repair counters charged to the burst alone.
 *
 * The gap is real wall-clock sleep, so `--gaps 0,1,5,30` costs ~36 s of pure waiting; that is the measurement,
 * not overhead.
 *
 * usage: bench idle [--gaps 0,1,5,30] [--burst 50] [--paced 400] [--size 1200] [--netem <preset>]
 *                   [--idleTimeoutMs 120000] [--probeTimeout true]
 */
fun idleMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val gaps = opt("gaps", "0,1,5,30").split(",").map { it.trim().toInt() }
    val burst = opt("burst", "50").toInt()
    val paced = opt("paced", "400").toInt()
    // The paced warm-up never drains 75 % of the credit target, so slow start never fires and the target sits at
    // its 13.5 KB floor — which cannot tell "the target survived the gap" from "there was nothing to lose".
    // --warmGapUs 0 makes the warm-up back-to-back, which grows the target first.
    val warmGapUs = opt("warmGapUs", "1000").toLong()
    val size = opt("size", "1200").toInt()
    val netemName = opt("netem", "")
    // The default idle timeout is 10 s and the protocol has no keepalive, so a 30 s gap on a default connection
    // is a TEARDOWN, not a slow burst (the --probeTimeout arm measures exactly that). The measurement arms raise
    // it: the question here is what idle does to the transport's STATE, not whether the connection survives.
    val idleTimeoutMs = opt("idleTimeoutMs", "120000").toLong()
    val probeTimeout = opt("probeTimeout", "true").toBoolean()
    val keys = Handshake.generate()

    println(String.format(Locale.ROOT, "idle     %s: gaps=%s s, %d warm-up msgs at %d us then a %d x %d B back-to-back burst, idleTimeoutMs=%d",
        if (netemName.isEmpty()) "loopback" else netemName, gaps.joinToString("/"), paced, warmGapUs, burst, size, idleTimeoutMs))

    for (g in gaps) {
        val netem = if (netemName.isEmpty()) null else tessera.transport.NetemSim.preset(netemName)
        try {
            val r = idleArm(keys, ConnConfig(netem = netem, idleTimeoutMs = idleTimeoutMs), g, burst, paced, size, warmGapUs)
            println(String.format(Locale.ROOT,
                "idle     gap=%2ds  steady p50=%.0fus p99=%.0fus | burst first=%.0fus p50=%.0fus p99=%.0fus max=%.0fus delivered=%d/%d",
                g, r.steadyP50, r.steadyP99, r.first, r.burstP50, r.burstP99, r.burstMax, r.delivered, burst))
            println(String.format(Locale.ROOT,
                "idle     gap=%2ds  across the gap: rx creditTarget %d -> %d -> %d B, sender credit %d -> %d B, deliveredRate %.0f -> %.0f -> %.0f B/s (before / at the burst / after it), srtt %.0f -> %.0f us, minRtt %.0f us",
                g, r.targetBefore, r.targetAtBurst, r.targetAfter, r.creditBefore, r.creditAtBurst, r.delivBefore, r.delivAtBurst, r.delivAfter, r.srttBefore, r.srttAfter, r.minRtt))
            println(String.format(Locale.ROOT,
                "idle     gap=%2ds  charged to the burst: creditStalls=%d (%d ms) cwndStalls=%d (%d ms) repairs(pro=%d react=%d tlp=%d tail=%d) resends=%d lost=%d rebinds=%d",
                g, r.creditStalls, r.creditStallMs, r.cwndStalls, r.cwndStallMs, r.repairsPro, r.repairsReact, r.repairsTlp, r.repairsTail, r.resends, r.lost, r.rebinds))
        } finally { netem?.close() }
    }

    if (probeTimeout) {
        // Not a latency question and not measurable as one: at the default 10 s with no keepalive frame in the
        // protocol, an application that simply goes quiet loses the connection.
        val gap = 15
        val r = runCatching { idleArm(keys, ConnConfig(), gap, burst = 4, paced = 100, size = size, warmGapUs = 1000) }
        println(String.format(Locale.ROOT, "idle     timeout probe: %d s gap on a DEFAULT connection (idleTimeoutMs=10000, no keepalive) -> %s",
            gap, r.fold({ "survived, delivered=${it.delivered}/4 first=${"%.0f".format(Locale.ROOT, it.first)}us" }, { "${it.javaClass.simpleName}: ${it.message}" })))
    }
}

private class IdleArm(
    val steadyP50: Double, val steadyP99: Double,
    val first: Double, val burstP50: Double, val burstP99: Double, val burstMax: Double, val delivered: Int,
    val targetBefore: Long, val targetAtBurst: Long, val targetAfter: Long, val creditBefore: Long, val creditAtBurst: Long,
    val delivBefore: Double, val delivAtBurst: Double, val delivAfter: Double, val srttBefore: Double, val srttAfter: Double, val minRtt: Double,
    val creditStalls: Long, val creditStallMs: Long, val cwndStalls: Long, val cwndStallMs: Long,
    val repairsPro: Long, val repairsReact: Long, val repairsTlp: Long, val repairsTail: Long,
    val resends: Long, val lost: Long, val rebinds: Long,
)

/**
 * One arm: warm a fresh connection with `paced` messages at 1 ms, sleep `gapSec`, then send `burst` messages
 * back-to-back. One-way latency is read off the index carried in the first two payload bytes (same host, shared
 * clock). The steady-state baseline is the last half of the warm-up, after the credit target has grown. The
 * credit target is read from the SERVER connection — it is the receiver that sizes it, and the client's own
 * `creditTargetBytes` is what the client grants the server, which nothing in this workload uses.
 */
private fun idleArm(keys: Handshake.StaticKeys, cfg: ConnConfig, gapSec: Int, burst: Int, paced: Int, size: Int, warmGapUs: Long): IdleArm {
    require(size >= 2 && paced + burst < 65535)
    TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
        TesseraClient(cfg = cfg).use { client ->
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "w4".toByteArray(), timeoutMs = 15_000)
            val sconn = server.accept(5_000) ?: error("server did not accept"); sconn.receive(2_000)
            val total = paced + burst
            val sent = LongArray(total); val lat = LongArray(total) { -1L }
            val got = AtomicInteger()
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val rx = Thread {
                while (!stop.get()) {
                    val m = sconn.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < total && lat[i] < 0) { lat[i] = System.nanoTime() - sent[i]; got.incrementAndGet() }
                }
            }.apply { isDaemon = true; start() }

            fun put(i: Int) {
                val p = ByteArray(size); p[0] = (i shr 8).toByte(); p[1] = i.toByte()
                sent[i] = System.nanoTime(); conn.send(p)
            }
            repeat(paced) { i -> put(i); if (warmGapUs > 0) busyWait(warmGapUs) }
            // Let the warm-up's own acks and grants land before the gap starts, or the "before" snapshot is really
            // a mid-flight one and the first second of the gap is spent finishing the warm-up.
            Thread.sleep(300)
            val before = conn.stats
            val targetBefore = sconn.stats.creditTargetBytes
            val creditBefore = before.creditLimit - before.creditSent
            val delivBefore = conn.estimator.deliveredBytesPerSec
            val srttBefore = conn.estimator.srttUs

            if (gapSec > 0) Thread.sleep(gapSec * 1000L)

            val atBurst = conn.stats
            // Sampled at the burst, not after it: [PathEstimator.onDelivered] only closes a rate window when an
            // ack arrives, so the gap itself cannot move this — the collapse, if any, is the FIRST ack after the
            // gap dividing the burst's bytes by a window as long as the gap.
            val delivAtBurst = conn.estimator.deliveredBytesPerSec
            // The target is the value with a real decay path: its BDP floor is the receiver's arrival-rate EWMA,
            // which the credit tick keeps decaying through silent rate windows for the whole gap.
            val targetAtBurst = sconn.stats.creditTargetBytes
            repeat(burst) { i -> put(paced + i) }
            // 5 s is far beyond any post-idle recovery seen on loopback; an arm that needs it has a finding in it.
            val deadline = System.nanoTime() + 5_000_000_000L
            while (got.get() < total && System.nanoTime() < deadline) Thread.sleep(5)
            stop.set(true); rx.join(2_000)
            val after = conn.stats

            fun q(a: List<Long>, p: Double) = if (a.isEmpty()) 0.0 else a[((a.size - 1) * p).toInt()] / 1000.0
            val steady = (paced / 2 until paced).mapNotNull { lat[it].takeIf { l -> l >= 0 } }.sorted()
            val b = (paced until total).mapNotNull { lat[it].takeIf { l -> l >= 0 } }.sorted()
            val arm = IdleArm(
                steadyP50 = q(steady, 0.5), steadyP99 = q(steady, 0.99),
                first = if (lat[paced] >= 0) lat[paced] / 1000.0 else -1.0,
                burstP50 = q(b, 0.5), burstP99 = q(b, 0.99), burstMax = (b.lastOrNull() ?: 0L) / 1000.0, delivered = b.size,
                targetBefore = targetBefore, targetAtBurst = targetAtBurst, targetAfter = sconn.stats.creditTargetBytes,
                creditBefore = creditBefore, creditAtBurst = atBurst.creditLimit - atBurst.creditSent,
                delivBefore = delivBefore, delivAtBurst = delivAtBurst, delivAfter = conn.estimator.deliveredBytesPerSec,
                srttBefore = srttBefore, srttAfter = conn.estimator.srttUs,
                minRtt = if (conn.estimator.minRttUs == Double.MAX_VALUE) 0.0 else conn.estimator.minRttUs,
                creditStalls = after.creditStalls - atBurst.creditStalls, creditStallMs = (after.creditStallUs - atBurst.creditStallUs) / 1000,
                cwndStalls = after.cwndStalls - atBurst.cwndStalls, cwndStallMs = (after.cwndStallUs - atBurst.cwndStallUs) / 1000,
                repairsPro = after.repairsProactive - atBurst.repairsProactive, repairsReact = after.repairsReactive - atBurst.repairsReactive,
                repairsTlp = after.repairsTlp - atBurst.repairsTlp, repairsTail = after.repairsTail - atBurst.repairsTail,
                resends = after.sourceResends - atBurst.sourceResends, lost = after.lossesDetected - atBurst.lossesDetected,
                rebinds = after.rebinds - atBurst.rebinds)
            conn.close(); sconn.close()
            return arm
        }
    }
}
