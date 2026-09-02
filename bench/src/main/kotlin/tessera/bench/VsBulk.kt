package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Locale

/**
 * W2 head-to-head: bulk one-way transfer against the stream transports, under the same qdisc.
 *
 * The message campaigns measured Tessera's home turf; this is the opposite ground — TCP/QUIC's — and the docs
 * predict Tessera loses here (the in-process transcont ceiling is an OPEN item, and that number is the
 * simulator's, not any ISP's: both endpoints share one JVM). Measuring the loss is the point: a claim ladder
 * with only favourable rungs is marketing.
 *
 * Every arm ships the same payload one way (client -> server), the server hashes as it reads, and the arm
 * reports wall-clock goodput plus whether the SHA-256 matched. A transfer that is fast but wrong is worthless,
 * and with FEC recovery in the path, "arrived bit-perfect" is the claim that needs proving.
 *
 *   tls      JDK TLS 1.3 over TCP: one stream, the kernel doing what it has been optimised to do for 30 years
 *   quic     kwik, one bidirectional stream (its buffers provisioned as in `bench vs`)
 *   tessera  chunked `send()` (32 KB messages), reassembly + flow control on the receive side; the receiver
 *            re-orders by a 4-byte chunk seq before hashing, because Tessera delivers messages, not a stream —
 *            the reorder cost is charged to Tessera's arm, as it would be in a real file transfer
 *
 * usage: bench vsbulk [--arms tls,quic,tessera] [--mb 100] [--file <path>] [--chunk 32768]
 *   --file streams that file's bytes (the whole file unless --mb caps it); default is --mb of zeros-free
 *   pseudorandom data generated once and shared by every arm.
 */
fun vsBulkMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val arms = opt("arms", "tls,quic,tessera").split(",").map { it.trim() }
    val chunk = opt("chunk", "32768").toInt()
    val file = opt("file", "")
    val mbOpt = opt("mb", "")
    val maxDatagram = opt("maxDatagram", "1350").toInt()   // tessera arm only: the datagram ceiling both ends offer

    val payload: ByteArray = if (file.isNotEmpty()) {
        val f = File(file)
        require(f.exists()) { "no such file: $file" }
        val cap = if (mbOpt.isNotEmpty()) mbOpt.toLong() * (1 shl 20) else f.length()
        val n = minOf(f.length(), cap).toInt()
        f.inputStream().use { s -> ByteArray(n).also { buf -> var o = 0; while (o < n) { val r = s.read(buf, o, n - o); require(r > 0); o += r } } }
    } else {
        val mb = (if (mbOpt.isEmpty()) "100" else mbOpt).toInt()
        ByteArray(mb shl 20).also { java.util.Random(7).nextBytes(it) }
    }
    val wantHash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    println(String.format(Locale.ROOT, "vsbulk   %,d bytes%s  sha256=%s...", payload.size,
        if (file.isNotEmpty()) " from ${File(file).name}" else " (pseudorandom)", wantHash.take(16)))
    println("arm      seconds   MB/s   sha256")

    for (arm in arms) {
        val r = when (arm) {
            "tls" -> tlsBulk(payload)
            "quic" -> quicBulk(payload)
            "tessera" -> tesseraBulk(payload, chunk, maxDatagram)
            else -> { println("$arm: unknown arm"); continue }
        }
        println(String.format(Locale.ROOT, "%-8s %7.2f %6.1f   %s", arm, r.seconds, payload.size / 1e6 / r.seconds,
            if (r.hash == wantHash) "MATCH" else "MISMATCH(${r.hash.take(16)}...)"))
    }
}

private class BulkResult(val seconds: Double, val hash: String)

private fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }

private fun tlsBulk(payload: ByteArray): BulkResult {
    val (store, pass) = vsTempKeystore()
    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
    val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
    kmf.init(store, pass.toCharArray()); tmf.init(store)
    val ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3")
    ctx.init(kmf.keyManagers, tmf.trustManagers, null)
    val server = ctx.serverSocketFactory.createServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress())
    val hashRes = java.util.concurrent.CompletableFuture<String>()
    Thread {
        try {
            val s = server.accept()
            val md = MessageDigest.getInstance("SHA-256")
            val din = DataInputStream(s.inputStream.buffered(1 shl 16))
            val total = din.readLong()
            val buf = ByteArray(1 shl 16); var left = total
            while (left > 0) { val r = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); require(r > 0); md.update(buf, 0, r); left -= r }
            s.outputStream.write(1); s.outputStream.flush()   // ack full receipt so the timer includes delivery
            hashRes.complete(hex(md.digest()))
        } catch (e: Exception) { hashRes.completeExceptionally(e) }
    }.apply { isDaemon = true; start() }
    val client = ctx.socketFactory.createSocket(java.net.InetAddress.getLoopbackAddress(), server.localPort) as javax.net.ssl.SSLSocket
    client.startHandshake()
    check(client.session.protocol == "TLSv1.3")
    val t0 = System.nanoTime()
    DataOutputStream(client.outputStream.buffered(1 shl 16)).run { writeLong(payload.size.toLong()); write(payload); flush() }
    check(client.inputStream.read() == 1) { "no receipt ack" }
    val dt = (System.nanoTime() - t0) / 1e9
    runCatching { client.close() }; runCatching { server.close() }
    return BulkResult(dt, hashRes.get())
}

private fun quicBulk(payload: ByteArray): BulkResult {
    val (store, pass) = vsTempKeystore()
    val logger = tech.kwik.core.log.NullLogger()
    val config = tech.kwik.core.server.ServerConnectionConfig.builder()
        .maxOpenPeerInitiatedBidirectionalStreams(16)
        .maxConnectionBufferSize(256L shl 20).maxBidirectionalStreamBufferSize(64L shl 20)
        .maxIdleTimeoutInSeconds(300).build()
    val port = java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
    val hashRes = java.util.concurrent.CompletableFuture<String>()
    val server = tech.kwik.core.server.ServerConnector.builder()
        .withPort(port).withKeyStore(store, "vs", pass.toCharArray())
        .withConfiguration(config).withLogger(logger).build()
    server.registerApplicationProtocol("vs-bulk", object : tech.kwik.core.server.ApplicationProtocolConnectionFactory {
        override fun maxConcurrentPeerInitiatedBidirectionalStreams() = 16
        override fun createConnection(protocol: String, conn: tech.kwik.core.QuicConnection) =
            object : tech.kwik.core.server.ApplicationProtocolConnection {
                override fun acceptPeerInitiatedStream(stream: tech.kwik.core.QuicStream) {
                    Thread {
                        try {
                            val md = MessageDigest.getInstance("SHA-256")
                            val inp = stream.inputStream
                            val buf = ByteArray(1 shl 16)
                            while (true) { val r = inp.read(buf); if (r < 0) break; md.update(buf, 0, r) }
                            stream.outputStream.write(1); stream.outputStream.close()
                            hashRes.complete(hex(md.digest()))
                        } catch (e: Exception) { hashRes.completeExceptionally(e) }
                    }.apply { isDaemon = true }.start()
                }
            }
    })
    server.start()
    val client = tech.kwik.core.QuicClientConnection.newBuilder()
        .uri(java.net.URI("vs-bulk://127.0.0.1:$port"))
        .applicationProtocol("vs-bulk").noServerCertificateCheck()
        .defaultStreamReceiveBufferSize(64L shl 20)
        .maxIdleTimeout(java.time.Duration.ofSeconds(300))
        .connectTimeout(java.time.Duration.ofSeconds(10)).logger(logger).build()
    client.connect()
    val st = client.createStream(true)
    val t0 = System.nanoTime()
    st.outputStream.use { it.write(payload) }
    check(st.inputStream.read() == 1) { "no receipt ack" }
    val dt = (System.nanoTime() - t0) / 1e9
    runCatching { client.close() }; runCatching { server.close() }
    return BulkResult(dt, hashRes.get())
}

private fun tesseraBulk(payload: ByteArray, chunk: Int, maxDatagram: Int): BulkResult {
    val keys = Handshake.generate()
    val cfg = ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 300_000, maxDatagram = maxDatagram)
    TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
        TesseraClient(cfg = cfg).use { client ->
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "vb".toByteArray(), timeoutMs = 10_000)
            val sconn = server.accept(5_000) ?: error("no accept"); sconn.receive(2_000)
            val nChunks = (payload.size + chunk - 1) / chunk
            val hashRes = java.util.concurrent.CompletableFuture<String>()
            Thread {
                try {
                    // messages can arrive out of order; re-order by chunk seq before hashing — that cost is
                    // Tessera's to pay, since a stream transport gets ordering for free
                    val md = MessageDigest.getInstance("SHA-256")
                    val pending = HashMap<Int, ByteArray>()
                    var next = 0
                    while (next < nChunks) {
                        val m = sconn.receive(30_000) ?: throw IllegalStateException("receive timed out at chunk $next/$nChunks")
                        val seq = ((m[0].toInt() and 0xFF) shl 24) or ((m[1].toInt() and 0xFF) shl 16) or ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF)
                        pending[seq] = m
                        while (true) { val c = pending.remove(next) ?: break; md.update(c, 4, c.size - 4); next++ }
                    }
                    sconn.send(ByteArray(1))   // receipt
                    hashRes.complete(hex(md.digest()))
                } catch (e: Exception) { hashRes.completeExceptionally(e) }
            }.apply { isDaemon = true; start() }
            val t0 = System.nanoTime()
            var off = 0; var seq = 0
            while (off < payload.size) {
                val len = minOf(chunk, payload.size - off)
                val m = ByteArray(4 + len)
                m[0] = (seq shr 24).toByte(); m[1] = (seq shr 16).toByte(); m[2] = (seq shr 8).toByte(); m[3] = seq.toByte()
                System.arraycopy(payload, off, m, 4, len)
                conn.send(m)
                off += len; seq++
            }
            check(conn.receive(120_000) != null) { "no receipt ack" }
            val dt = (System.nanoTime() - t0) / 1e9
            // evidence, not trust: what DPLPMTUD settled on and the largest datagram actually sent (BASE is where
            // the search starts, so this is read after the transfer, not after connect)
            println("negotiated plpmtu=" + conn.stats.toString().substringAfter("plpmtu=", "?").take(22) + " maxDatagramSent=" + conn.stats.maxDatagramSent)
            val h = hashRes.get()
            runCatching { conn.close() }; runCatching { sconn.close() }
            return BulkResult(dt, h)
        }
    }
}

/** Shared with the vs arms conceptually; duplicated here to keep the files independent. */
internal fun vsTempKeystore(): Pair<java.security.KeyStore, String> {
    val ks = File.createTempFile("vsb-tls", ".p12").also { it.deleteOnExit() }
    ks.delete()
    val keytool = File(File(System.getProperty("java.home"), "bin"), "keytool").absolutePath
    val gen = ProcessBuilder(keytool, "-genkeypair", "-alias", "vs", "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=vs-bench", "-validity", "1", "-storetype", "PKCS12",
        "-keystore", ks.absolutePath, "-storepass", "vsbench0").redirectErrorStream(true).start()
    check(gen.waitFor() == 0) { "keytool failed" }
    val store = java.security.KeyStore.getInstance("PKCS12")
    ks.inputStream().use { store.load(it, "vsbench0".toCharArray()) }
    return store to "vsbench0"
}
