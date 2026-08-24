package tessera.tools

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.TesseraConnection
import tessera.transport.TesseraServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The listening half. Echoes every message straight back, so the probe can measure a round trip against
 * its own clock — no clock synchronisation between the two machines is needed or assumed.
 *
 * Static keys are generated fresh at startup and printed; they are never written to disk. Restarting the
 * echo means re-pasting `--peer-key`, which is the right trade for a test tool.
 */
fun echoMain(a: Args) {
    val token = a.req("token").toByteArray()
    val port = a.int("port", 51820)
    val bind = a.opt("bind") ?: "::"
    val keys = a.opt("key-in")?.let { Keys.read(java.io.File(it)) } ?: Handshake.generate()
    a.opt("key-out")?.let { Keys.write(keys, java.io.File(it)); println("wrote key file $it") }
    val ticketKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val peerKey = Keys.peerKey(keys)

    if (a.flag("also-udp")) startUdpEcho(bind, port + 1)

    TesseraServer(InetSocketAddress(bind, port), keys, ticketKey, ConnConfig()).use { server ->
        println("tessera echo listening on ${server.localAddress}")
        println()
        println("  run this on the other machine:")
        println("  tessera probe --connect <this-host>:$port --peer-key $peerKey --token <token>")
        println()
        if (a.flag("also-udp")) println("  plain-UDP echo (the A/B floor) on port ${port + 1}")
        println("waiting for connections; ctrl-c to stop")

        Runtime.getRuntime().addShutdownHook(Thread { println("\nstopping") })
        while (true) {
            val conn = server.accept(1_000) ?: continue
            thread(isDaemon = true, name = "echo-conn") { serveConnection(conn, token) }
        }
    }
}

private fun serveConnection(conn: TesseraConnection, token: ByteArray) {
    val started = System.nanoTime()
    // The 0-RTT payload must carry the shared token. Anything else is dropped without a reply, so this
    // listener cannot be turned into a reflector and does not answer scanners.
    val first = conn.receive(5_000)
    if (first == null || !first.copyOf(token.size).contentEquals(token)) {
        println("  rejected connection ${conn.connId.raw.toString(16)} (bad or missing token)")
        conn.close(); return
    }
    conn.send(first)   // echo the 0-RTT payload itself: that echo is what the probe times the connect against
    println("  accepted ${conn.connId.raw.toString(16)}")
    var msgs = 0L; var bytes = 0L
    try {
        while (true) {
            val m = conn.receive(30_000) ?: break
            conn.send(m)                       // verbatim echo: the probe matches on the id it embedded
            msgs++; bytes += m.size
        }
    } catch (e: Exception) {
        println("  connection ${conn.connId.raw.toString(16)} ended: ${e.message}")
    } finally {
        val secs = (System.nanoTime() - started) / 1e9
        println(String.format(Locale.ROOT, "  closed %s: echoed %d msgs / %.1f MB in %.1fs",
            conn.connId.raw.toString(16), msgs, bytes / 1e6, secs))
        println("    ${conn.stats}")
        conn.close()
    }
}

/** Plain-UDP echo on a second port: the floor any transport must beat, measured over the identical path. */
private fun startUdpEcho(bind: String, port: Int) = thread(isDaemon = true, name = "udp-echo") {
    val sock = DatagramSocket(InetSocketAddress(bind, port))
    val buf = ByteArray(65535)
    val p = DatagramPacket(buf, buf.size)
    while (true) {
        try {
            sock.receive(p)
            sock.send(DatagramPacket(p.data, p.offset, p.length, p.socketAddress))
        } catch (e: Exception) { /* a probe going away must not kill the echo */ }
    }
}
