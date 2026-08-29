package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * INTEROP L0 (docs/INTEROP.md): capture a complete session for the clean-room decoder.
 *
 * A UDP relay sits between a real client and a real server and logs every datagram in both directions —
 * wire bytes exactly as a network would see them, no internal seams. Alongside, the run writes the one secret
 * the decoder is entitled to (`TesseraConnection.sessionKey` — public API, the root from which SPEC derives
 * every packet key) and the ground truth (the application messages each side sent).
 *
 * What the clean-room implementer gets: `datagrams.jsonl`, `meta.json`, `ground-truth.json`, and SPEC.md.
 * What they must produce: the messages, byte-exact, from the datagrams — which requires varints, headers,
 * header-protection removal, key derivation from the session key, AEAD open, frame parsing and reassembly to
 * all be implementable from the document. Handshake datagrams (flagged F_INITIAL) are parse-only: their
 * decryption needs ephemeral keys no passive observer has, and the capture marks them so.
 *
 * The workload is deliberately small and varied: 20 messages client->server (sizes 1..1500 B, so single- and
 * multi-fragment paths are both on the wire), 20 echoes back, then a clean close (the CLOSE frame and linger
 * behaviour are part of the capture).
 *
 * usage: bench capture [--out interop/vectors/session-1]
 */
fun captureMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val outDir = File(opt("out", "interop/vectors/session-1")).apply { mkdirs() }

    val keys = Handshake.generate()
    val cfg = ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 60_000)
    val log = ConcurrentLinkedQueue<String>()
    val t0 = System.nanoTime()
    fun hex(b: ByteArray, len: Int) = buildString(len * 2) { for (i in 0 until len) append("%02x".format(b[i])) }

    TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
        // The relay: client talks to relayPort, relay forwards to the server, learns the client's address from
        // the first datagram, and logs everything with direction and arrival order.
        val relay = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())
        val serverAddr = server.localAddress
        var clientAddr: InetSocketAddress? = null
        val relayThread = Thread {
            val buf = ByteArray(65535)
            try {
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    relay.receive(p)
                    val from = p.socketAddress as InetSocketAddress
                    val dir: String
                    val to: InetSocketAddress
                    if (from.port == serverAddr.port) { dir = "s2c"; to = clientAddr ?: continue }
                    else { if (clientAddr == null) clientAddr = from; dir = "c2s"; to = serverAddr }
                    log.add(String.format(Locale.ROOT, "{\"seq\":%d,\"tUs\":%d,\"dir\":\"%s\",\"len\":%d,\"hex\":\"%s\"}",
                        log.size, (System.nanoTime() - t0) / 1000, dir, p.length, hex(buf, p.length)))
                    relay.send(DatagramPacket(buf, p.length, to))
                }
            } catch (e: Exception) { /* relay closed */ }
        }.apply { isDaemon = true; start() }

        TesseraClient(cfg = cfg).use { client ->
            val conn = client.connect(
                InetSocketAddress("127.0.0.1", relay.localPort), keys.x25519Pub, keys.kemPub,
                "interop-l0".toByteArray(), timeoutMs = 10_000)
            val sconn = server.accept(5_000) ?: error("no accept")
            val zeroRtt = sconn.receive(2_000) ?: error("no 0-rtt payload")

            // deterministic, size-varied payloads; every byte position is predictable from (index, position)
            fun payload(i: Int, size: Int) = ByteArray(size) { p -> ((i * 31 + p) and 0xFF).toByte() }
            val sizes = listOf(1, 8, 64, 200, 500, 1200, 1201, 1350, 1500, 32, 1024, 900, 700, 300, 150, 77, 1499, 2, 128, 1300)
            val c2s = ArrayList<ByteArray>(); val s2c = ArrayList<ByteArray>()
            for ((i, sz) in sizes.withIndex()) {
                val m = payload(i, sz); c2s.add(m); conn.send(m)
                val got = sconn.receive(5_000) ?: error("server did not get msg $i")
                check(got.contentEquals(m)) { "relay corrupted msg $i" }
                val echo = payload(100 + i, sz); s2c.add(echo); sconn.send(echo)
                check(conn.receive(5_000)?.contentEquals(echo) == true) { "echo $i not received" }
            }
            val sessionKey = conn.sessionKey.copyOf()
            conn.close(); sconn.close()
            Thread.sleep(500)   // let close linger / CLOSE frames traverse the relay
            relay.close()

            File(outDir, "datagrams.jsonl").writeText(log.joinToString("\n") + "\n")
            File(outDir, "meta.json").writeText(String.format(Locale.ROOT,
                "{\n  \"sessionKeyHex\": \"%s\",\n  \"clientIsInitiator\": true,\n  \"tagLen\": %d,\n" +
                "  \"note\": \"datagrams with the top flag bit set (F_INITIAL) are handshake packets: parse-only, their decryption needs ephemeral keys a passive observer does not have. Everything else is decryptable from sessionKey per SPEC.\"\n}\n",
                hex(sessionKey, sessionKey.size), cfg.tagLen))
            fun arr(l: List<ByteArray>) = l.joinToString(",\n    ", "[\n    ", "\n  ]") { "\"" + hex(it, it.size) + "\"" }
            File(outDir, "ground-truth.json").writeText(
                "{\n  \"zeroRttPayloadHex\": \"" + hex(zeroRtt, zeroRtt.size) + "\",\n" +
                "  \"clientToServer\": " + arr(c2s) + ",\n  \"serverToClient\": " + arr(s2c) + "\n}\n")
            println("captured ${log.size} datagrams -> $outDir (sessionKey ${sessionKey.size} B, ${c2s.size}+${s2c.size} messages)")
        }
    }
}
