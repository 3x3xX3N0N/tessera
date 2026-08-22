package aether.bench

import aether.core.Handshake
import aether.core.ZeroRtt
import java.util.Locale

/**
 * Connect-cost bench: CPU time for the client to build an instant-connect packet and for the server to accept it.
 * On the wire this is 0 RTT; what remains is this compute, which must stay well under 1ms to be "instant" on LAN.
 */
fun connectBench(iters: Int = 2000) {
    val server = Handshake.generate(); val srv = ZeroRtt.Server(server)
    val data = ByteArray(128)
    val client = LongArray(iters); val accept = LongArray(iters)
    repeat(200) { val b = ZeroRtt.Client(Handshake.initiate(server.x25519Pub, server.kemPub)).initial(data, it.toLong(), it.toLong()); srv.accept(b, it.toLong()) }
    for (i in 0 until iters) {
        val t0 = System.nanoTime()
        val body = ZeroRtt.Client(Handshake.initiate(server.x25519Pub, server.kemPub)).initial(data, 1_000_000L + i, i.toLong())
        val t1 = System.nanoTime()
        requireNotNull(srv.accept(body, 1_000_000L + i))
        accept[i] = System.nanoTime() - t1; client[i] = t1 - t0
    }
    fun p(a: LongArray, q: Double) = a.sorted()[((a.size - 1) * q).toInt()] / 1000.0
    println(String.format(Locale.ROOT, "connect  client-build p50=%.0fus p99=%.0fus | server-accept p50=%.0fus p99=%.0fus | first-flight data budget=%dB",
        p(client, .5), p(client, .99), p(accept, .5), p(accept, .99), ZeroRtt.MAX_FIRST_DATA))
}
