package aether.bench

import aether.core.Handshake
import aether.core.ZeroRtt
import aether.transport.AetherClient
import aether.transport.AetherServer
import java.net.InetSocketAddress
import java.util.Locale

/**
 * Connect-cost bench, two layers:
 *  1. CPU only: client builds the instant-connect packet / server accepts it (what remains once the wire is 0 RTT).
 *  2. Over the wire on loopback: from the client.connect() call until (a) the server application has the 0-RTT
 *     payload in hand and (b) the client application has the server's first response. Fresh PQ vs resumed,
 *     p50/p99 over `iters` iterations each.
 */
fun connectBench(cpuIters: Int = 2000, iters: Int = 500) {
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
        p(client, .5), p(client, .99), p(accept, .5), p(accept, .99), ZeroRtt.MAX_FIRST_DATA, aether.core.Resumption.MAX_FIRST_DATA))

    // ---- over the wire ----
    val keys = Handshake.generate()
    AetherServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { (it * 7).toByte() }).use { s ->
        AetherClient().use { c ->
            val payload = ByteArray(128) { 0x42 }; val response = "hello".toByteArray()
            val serverGot = LongArray(1) // nanoTime when the server app had the 0-RTT payload, per iteration
            val serverRunning = java.util.concurrent.atomic.AtomicBoolean(true)
            val st = Thread {
                while (serverRunning.get()) {
                    val sc = s.accept(200) ?: continue
                    val m = sc.receive(1_000); serverGot[0] = System.nanoTime()
                    if (m != null) sc.send(response)
                    sc.close() // server side is done; the client closes its end once measured
                }
            }.apply { isDaemon = true; start() }

            fun run(label: String, resumed: Boolean, ticketSrc: () -> Pair<ByteArray, ByteArray>?) {
                val toServer = LongArray(iters); val toClient = LongArray(iters)
                repeat(20) { once(c, s, keys, payload, resumed, ticketSrc, serverGot) }
                for (i in 0 until iters) { val (a, b) = once(c, s, keys, payload, resumed, ticketSrc, serverGot); toServer[i] = a; toClient[i] = b }
                println(String.format(Locale.ROOT, "connect  wire %-8s 0-RTT payload at server p50=%.0fus p99=%.0fus | first response at client p50=%.0fus p99=%.0fus (n=%d)",
                    label, p(toServer, .5), p(toServer, .99), p(toClient, .5), p(toClient, .99), iters))
            }
            // fresh PQ connects; keep one ticket for the resumed run
            var ticket: Pair<ByteArray, ByteArray>? = null
            run("fresh-PQ", false) { null }
            val first = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, payload)
            first.receive(1_000); ticket = first.ticket!! to first.resumptionSecret; first.close()
            run("resumed", true) { ticket }
            serverRunning.set(false)
        }
    }
}

private fun once(c: AetherClient, s: AetherServer, keys: Handshake.StaticKeys, payload: ByteArray, resumed: Boolean,
                 ticketSrc: () -> Pair<ByteArray, ByteArray>?, serverGot: LongArray): Pair<Long, Long> {
    serverGot[0] = 0
    val t0 = System.nanoTime()
    val conn = if (resumed) { val (t, sec) = ticketSrc()!!; c.resume(s.localAddress, t, sec, payload) }
               else c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, payload)
    val resp = conn.receive(2_000) ?: error("no response")
    val t2 = System.nanoTime()
    require(resp.size == 5)
    var spin = 0; while (serverGot[0] == 0L && spin++ < 1_000_000) Thread.onSpinWait()
    val r = (serverGot[0] - t0) to (t2 - t0)
    conn.close()
    return r
}

private fun p(a: LongArray, q: Double) = a.sorted()[((a.size - 1) * q).toInt()] / 1000.0
