package aether.bench

import aether.core.Handshake
import aether.core.ZeroRtt
import aether.transport.AetherClient
import aether.transport.AetherConnection
import aether.transport.AetherServer
import aether.transport.ConnConfig
import aether.transport.NetemSim
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Connect-cost bench, two layers:
 *  1. CPU only: client builds the instant-connect packet / server accepts it (what remains once the wire is 0 RTT).
 *  2. Over the wire on loopback (or through a [NetemSim] link): from the client.connect() call until (a) the server
 *     application has the 0-RTT payload in hand and (b) the client application has the server's first response.
 *     Fresh PQ vs resumed, p50/p99 over `iters` iterations each. A handshake that times out or whose first response
 *     never arrives is counted (`fail=`), never thrown: the percentiles are over the successful ones.
 */
fun connectBench(cpuIters: Int = 2000, iters: Int = 500, netem: NetemSim? = null) {
    val server = Handshake.generate(); val srv = ZeroRtt.Server(server)
    val data = ByteArray(128)
    val client = LongArray(cpuIters); val accept = LongArray(cpuIters)
    repeat(200) { val b = ZeroRtt.Client(Handshake.initiate(server.x25519Pub, server.kemPub)).initial(data, it.toLong(), it.toLong()); srv.accept(b, it.toLong()) }
    for (i in 0 until cpuIters) {
        val t0 = System.nanoTime()
        val body = ZeroRtt.Client(Handshake.initiate(server.x25519Pub, server.kemPub)).initial(data, 1_000_000L + i, i.toLong())
        val t1 = System.nanoTime()
        requireNotNull(srv.accept(body, 1_000_000L + i))
        accept[i] = System.nanoTime() - t1; client[i] = t1 - t0
    }
    println(String.format(Locale.ROOT, "connect  cpu: client-build p50=%.0fus p99=%.0fus | server-accept p50=%.0fus p99=%.0fus | first-flight budget fresh=%dB resumed=%dB",
        p(client.toList(), .5), p(client.toList(), .99), p(accept.toList(), .5), p(accept.toList(), .99), ZeroRtt.MAX_FIRST_DATA, aether.core.Resumption.MAX_FIRST_DATA))

    // ---- over the wire ----
    val keys = Handshake.generate()
    val cfg = ConnConfig(netem = netem)
    val timeoutMs = if (netem == null) 3_000L else 10_000L     // a 180 ms RTT link with bursty loss needs a few retransmit rounds
    AetherServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { (it * 7).toByte() }, cfg).use { s ->
        AetherClient(cfg = cfg).use { c ->
            val payload = ByteArray(128) { 0x42 }; val response = "hello".toByteArray()
            val serverGot = AtomicLong() // nanoTime when the server app had the 0-RTT payload, per iteration
            val serverRunning = AtomicBoolean(true)
            val st = Thread {
                while (serverRunning.get()) {
                    val sc = s.accept(200) ?: continue
                    val m = sc.receive(3_000); serverGot.set(System.nanoTime())
                    if (m != null) sc.send(response)
                    sc.close() // server side is done (the connection lingers until the response is acked); the client closes its end once measured
                }
            }.apply { isDaemon = true; start() }

            fun run(label: String, resumed: Boolean, ticketSrc: () -> Pair<ByteArray, ByteArray>?) {
                val toServer = ArrayList<Long>(iters); val toClient = ArrayList<Long>(iters)
                var fail = 0; var warmFail = 0
                repeat(20) { if (once(c, s, keys, payload, resumed, ticketSrc, serverGot, timeoutMs) == null) warmFail++ }
                for (i in 0 until iters) {
                    val r = once(c, s, keys, payload, resumed, ticketSrc, serverGot, timeoutMs)
                    if (r == null) fail++ else { toServer += r.first; toClient += r.second }
                }
                println(String.format(Locale.ROOT, "connect  wire %-8s 0-RTT payload at server p50=%.0fus p99=%.0fus | first response at client p50=%.0fus p99=%.0fus (n=%d fail=%d %.2f%% +%d warm-up)",
                    label, p(toServer, .5), p(toServer, .99), p(toClient, .5), p(toClient, .99), iters, fail, 100.0 * fail / iters, warmFail))
            }
            // fresh PQ connects; keep one ticket for the resumed run
            var ticket: Pair<ByteArray, ByteArray>? = null
            run("fresh-PQ", false) { null }
            var first: AetherConnection? = null
            for (attempt in 0 until 5) { try { first = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, payload, timeoutMs); break } catch (e: TimeoutException) { if (attempt == 4) throw e } }
            val ticketConn = first!!
            ticketConn.receive(3_000); ticket = ticketConn.ticket!! to ticketConn.resumptionSecret
            Thread.sleep(if (netem == null) 20 else 1_000) // let path validation + DPLPMTUD settle so the stats line shows the steady state
            val firstStats = ticketConn.stats; ticketConn.close()
            run("resumed", true) { ticket }
            println("connect  ccMode=${firstStats.ccMode} plpmtu=${firstStats.plpmtu} tagLen=${firstStats.tagLen} validated=${firstStats.pathValidated} | $firstStats")
            if (netem != null) println("connect  netem: $netem")
            serverRunning.set(false)
        }
    }
}

/** One handshake + first response; null when the connect timed out or the response never came (counted by the caller). */
private fun once(c: AetherClient, s: AetherServer, keys: Handshake.StaticKeys, payload: ByteArray, resumed: Boolean,
                 ticketSrc: () -> Pair<ByteArray, ByteArray>?, serverGot: AtomicLong, timeoutMs: Long): Pair<Long, Long>? {
    serverGot.set(0)
    val t0 = System.nanoTime()
    val conn = try {
        if (resumed) { val (t, sec) = ticketSrc()!!; c.resume(s.localAddress, t, sec, payload, timeoutMs) }
        else c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, payload, timeoutMs)
    } catch (e: TimeoutException) { return null }
    val resp = conn.receive(5_000)
    val t2 = System.nanoTime()
    if (resp == null || resp.size != 5) { conn.close(); return null }
    var spin = 0; while (serverGot.get() == 0L && spin++ < 1_000_000) Thread.onSpinWait()
    val r = (serverGot.get() - t0) to (t2 - t0)
    conn.close()
    return r
}

private fun p(a: List<Long>, q: Double): Double = if (a.isEmpty()) Double.NaN else a.sorted()[((a.size - 1) * q).toInt()] / 1000.0
