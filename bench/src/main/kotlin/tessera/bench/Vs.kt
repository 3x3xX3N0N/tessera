package tessera.bench

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Side-by-side echo RTT: Tessera against the transports people actually deploy, under the SAME impairment.
 *
 * `NetemSim` cannot host this comparison — it is a UDP-datagram simulator wired into Tessera's own io hooks,
 * so a TCP socket never passes through it. Kernel `tc netem` impairs every protocol on the interface equally,
 * and it is the arm of the sim-vs-kernel validation that hardware agreed with — so this bench is built to run
 * on a Linux box under `bench/netem/profiles.sh`, all arms on the same loopback under the same qdisc.
 *
 * Arms:
 *   udp      raw datagrams, echoed — the floor; no reliability, loss shows as loss
 *   tls      JDK TLS 1.3 over TCP, length-prefixed 1200 B messages, echoed — the incumbent. TCP retransmission
 *            means loss shows as LATENCY (head-of-line: one hole stalls every message behind it)
 *   quic     kwik (an independent, spec-derived Java QUIC — a bench-only comparator, recorded in NOTICE), one
 *            client-initiated bidirectional stream per message, echoed on the same stream. Stream-per-message is
 *            QUIC's idiomatic request shape and is what earns it its no-head-of-line credit; a lost packet stalls
 *            only the streams whose data it carried
 *   sctp     the kernel's own message transport (com.sun.nio.sctp; Linux only), one association, messages sent
 *            UNORDERED — SCTP's message semantics at their most Tessera-like: per-message reliability with no
 *            cross-message ordering, so no head-of-line by design, recovered by kernel SACK/RTX
 *   tessera  the full transport — loss should show as neither (FEC recovery), at the cost of overhead packets
 *
 * Every arm sends `n` messages of `size` bytes at `1e6/gapUs` msg/s and measures echo RTT per message. Message
 * framing over TLS is a 4-byte length prefix; the echo returns the message verbatim. The TLS handshake happens
 * before the measured window (as Tessera's does), so the comparison is steady-state transport behaviour, not
 * connection setup — `bench connect` and the probe's connect lines already cover setup.
 *
 * The TLS keypair is generated per run with the JDK's own keytool into a temp PKCS12 (EC P-256, self-signed);
 * the client trusts exactly that store. No BouncyCastle cert machinery, no fixed key in the repo.
 *
 * usage: bench vs [--arms udp,tls,tessera] [--n 2000] [--gapUs 20000] [--size 1200] [--warmup 100]
 */
fun vsMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val arms = opt("arms", "udp,tls,quic,tessera").split(",").map { it.trim() }
    val n = opt("n", "2000").toInt()
    val gapUs = opt("gapUs", "20000").toLong()
    val size = opt("size", "1200").toInt()
    val warmup = opt("warmup", "100").toInt()
    require(size >= 8) { "size must fit the sequence stamp" }

    println(String.format(Locale.ROOT, "vs       %d msgs x %d B at %d/s per arm (loopback; impair it with tc netem)",
        n, size, 1_000_000 / gapUs))
    println("arm      delivered  p50_ms  p90_ms  p99_ms  p999_ms  min_ms")

    for (arm in arms) {
        val rtts = when (arm) {
            "udp" -> udpArm(n, gapUs, size, warmup)
            "tls" -> tlsArm(n, gapUs, size, warmup)
            "quic" -> quicArm(n, gapUs, size, warmup)
            "sctp" -> try { sctpArm(n, gapUs, size, warmup) } catch (e: Throwable) { println("sctp: unavailable on this host (${e.javaClass.simpleName}) — Linux with the sctp module only"); continue }
            "tessera" -> tesseraArm(n, gapUs, size, warmup)
            else -> { println("$arm: unknown arm"); continue }
        }
        val d = rtts.filter { it >= 0 }.sorted()
        fun pct(p: Double) = if (d.isEmpty()) 0.0 else d[((d.size - 1) * p).toInt()] / 1e6
        println(String.format(Locale.ROOT, "%-8s %4d/%d  %7.1f %7.1f %7.1f %8.1f %7.1f",
            arm, d.size, n, pct(0.5), pct(0.9), pct(0.99), pct(0.999), (d.firstOrNull() ?: 0L) / 1e6))
    }
}

/** Sends seq-stamped messages at the pace, collects echo RTTs; -1 = never came back within the tail grace. */
private class Collector(val n: Int) {
    val rtts = LongArray(n) { -1L }
    val sentAt = LongArray(n)
    @Volatile var got = 0
    fun sent(i: Int) { sentAt[i] = System.nanoTime() }
    fun echoed(i: Int) { if (i in 0 until n && rtts[i] < 0) { rtts[i] = System.nanoTime() - sentAt[i]; got++ } }
    /** Waits up to 10 s after the last send for stragglers. */
    fun await() { val end = System.nanoTime() + 10_000_000_000L; while (got < n && System.nanoTime() < end) Thread.sleep(20) }
}

private fun stamp(payload: ByteArray, i: Int) { payload[0] = (i shr 24).toByte(); payload[1] = (i shr 16).toByte(); payload[2] = (i shr 8).toByte(); payload[3] = i.toByte() }
private fun unstamp(payload: ByteArray) = ((payload[0].toInt() and 0xFF) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)

private fun udpArm(n: Int, gapUs: Long, size: Int, warmup: Int): LongArray {
    val server = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())
    val echoThread = Thread {
        val buf = ByteArray(65535)
        try { while (true) { val p = DatagramPacket(buf, buf.size); server.receive(p); server.send(p) } }
        catch (e: Exception) { /* socket closed */ }
    }.apply { isDaemon = true; start() }
    val client = DatagramSocket()
    client.soTimeout = 50
    val c = Collector(n)
    val rx = Thread {
        val buf = ByteArray(65535)
        try { while (c.got < n) { val p = DatagramPacket(buf, buf.size); try { client.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }; if (p.length >= 4) c.echoed(unstamp(buf)) } }
        catch (e: Exception) { }
    }.apply { isDaemon = true; start() }
    val payload = ByteArray(size)
    repeat(warmup + n) { k ->
        val i = k - warmup
        stamp(payload, if (i < 0) Int.MAX_VALUE else i)
        if (i >= 0) c.sent(i)
        client.send(DatagramPacket(payload, size, server.localSocketAddress))
        busyWait(gapUs)
    }
    c.await(); client.close(); server.close()
    return c.rtts
}

private fun tlsArm(n: Int, gapUs: Long, size: Int, warmup: Int): LongArray {
    val ks = File.createTempFile("vs-tls", ".p12").also { it.deleteOnExit() }
    ks.delete()
    // the JDK's own tooling, so the bench needs no cert library and carries no fixed key
    val keytool = File(File(System.getProperty("java.home"), "bin"), "keytool").absolutePath
    val gen = ProcessBuilder(keytool, "-genkeypair", "-alias", "vs", "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=vs-bench", "-validity", "1", "-storetype", "PKCS12",
        "-keystore", ks.absolutePath, "-storepass", "vsbench0").redirectErrorStream(true).start()
    check(gen.waitFor() == 0) { "keytool failed: " + gen.inputStream.readBytes().decodeToString().take(300) }

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    val store = java.security.KeyStore.getInstance("PKCS12")
    ks.inputStream().use { store.load(it, "vsbench0".toCharArray()) }
    kmf.init(store, "vsbench0".toCharArray()); tmf.init(store)
    val ctx = SSLContext.getInstance("TLSv1.3")
    ctx.init(kmf.keyManagers, tmf.trustManagers, null)

    val server = ctx.serverSocketFactory.createServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress()) as SSLServerSocket
    val echoThread = Thread {
        try {
            val s = server.accept() as SSLSocket
            s.tcpNoDelay = true
            val din = DataInputStream(s.inputStream.buffered()); val dout = DataOutputStream(s.outputStream.buffered())
            val buf = ByteArray(1 shl 20)
            while (true) {
                val len = din.readInt(); din.readFully(buf, 0, len)
                dout.writeInt(len); dout.write(buf, 0, len); dout.flush()
            }
        } catch (e: Exception) { /* peer closed */ }
    }.apply { isDaemon = true; start() }

    val client = ctx.socketFactory.createSocket(java.net.InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket
    client.tcpNoDelay = true
    client.startHandshake()
    check(client.session.protocol == "TLSv1.3") { "negotiated ${client.session.protocol}, not TLSv1.3" }
    val dout = DataOutputStream(client.outputStream.buffered())
    val din = DataInputStream(client.inputStream.buffered())
    val c = Collector(n)
    val rx = Thread {
        val buf = ByteArray(1 shl 20)
        try { while (c.got < n) { val len = din.readInt(); din.readFully(buf, 0, len); if (len >= 4) c.echoed(unstamp(buf)) } }
        catch (e: Exception) { }
    }.apply { isDaemon = true; start() }
    val payload = ByteArray(size)
    repeat(warmup + n) { k ->
        val i = k - warmup
        stamp(payload, if (i < 0) Int.MAX_VALUE else i)
        if (i >= 0) c.sent(i)
        dout.writeInt(size); dout.write(payload, 0, size); dout.flush()
        busyWait(gapUs)
    }
    c.await()
    runCatching { client.close() }; runCatching { server.close() }
    return c.rtts
}

/** Generates the same throwaway PKCS12 the TLS arm uses; shared so both arms present identical credentials. */
private fun tempKeystore(): Pair<java.security.KeyStore, String> {
    val ks = File.createTempFile("vs-tls", ".p12").also { it.deleteOnExit() }
    ks.delete()
    val keytool = File(File(System.getProperty("java.home"), "bin"), "keytool").absolutePath
    val gen = ProcessBuilder(keytool, "-genkeypair", "-alias", "vs", "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=vs-bench", "-validity", "1", "-storetype", "PKCS12",
        "-keystore", ks.absolutePath, "-storepass", "vsbench0").redirectErrorStream(true).start()
    check(gen.waitFor() == 0) { "keytool failed: " + gen.inputStream.readBytes().decodeToString().take(300) }
    val store = java.security.KeyStore.getInstance("PKCS12")
    ks.inputStream().use { store.load(it, "vsbench0".toCharArray()) }
    return store to "vsbench0"
}

private fun quicArm(n: Int, gapUs: Long, size: Int, warmup: Int): LongArray {
    val (store, pass) = tempKeystore()
    val logger = tech.kwik.core.log.NullLogger()
    // kwik's defaults are sized for a handful of HTTP/3 streams, not 1600 message-streams: with them the
    // transcont arm delivered 594/1500 at a 13.7 s p50 and wifi-busy delivered nothing — connection-level flow
    // control starvation, not the QUIC protocol. A comparator only comparse fairly when it is provisioned.
    val config = tech.kwik.core.server.ServerConnectionConfig.builder()
        .maxOpenPeerInitiatedBidirectionalStreams(30_000)
        .maxConnectionBufferSize(256L shl 20)
        .maxBidirectionalStreamBufferSize(1L shl 20)
        .maxIdleTimeoutInSeconds(120)
        .build()
    // kwik's builder validates `port` even when handed a socket, so pick a free port the racy-but-local way
    val port = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
    val server = tech.kwik.core.server.ServerConnector.builder()
        .withPort(port).withKeyStore(store, "vs", pass.toCharArray())
        .withConfiguration(config).withLogger(logger)
        .build()
    server.registerApplicationProtocol("vs-echo", object : tech.kwik.core.server.ApplicationProtocolConnectionFactory {
        override fun maxConcurrentPeerInitiatedBidirectionalStreams() = 2048
        override fun createConnection(protocol: String, conn: tech.kwik.core.QuicConnection) =
            object : tech.kwik.core.server.ApplicationProtocolConnection {
                override fun acceptPeerInitiatedStream(stream: tech.kwik.core.QuicStream) {
                    Thread {
                        try {
                            val data = stream.inputStream.readBytes()
                            stream.outputStream.write(data); stream.outputStream.close()
                        } catch (e: Exception) { }
                    }.apply { isDaemon = true }.start()
                }
            }
    })
    server.start()

    val client = tech.kwik.core.QuicClientConnection.newBuilder()
        .uri(java.net.URI("vs-echo://127.0.0.1:" + port))
        .applicationProtocol("vs-echo").noServerCertificateCheck()
        .maxOpenPeerInitiatedBidirectionalStreams(30_000)
        .defaultStreamReceiveBufferSize(1L shl 20)
        .maxIdleTimeout(java.time.Duration.ofSeconds(120))
        .connectTimeout(java.time.Duration.ofSeconds(10)).logger(logger)
        .build()
    client.connect()
    val c = Collector(n)
    val payload = ByteArray(size)
    repeat(warmup + n) { k ->
        val i = k - warmup
        stamp(payload, if (i < 0) Int.MAX_VALUE else i)
        val copy = payload.copyOf()
        if (i >= 0) c.sent(i)
        // one bidirectional stream per message; the echo comes back on the same stream, read on its own thread
        try {
            val st = client.createStream(true)
            st.outputStream.write(copy); st.outputStream.close()
            Thread {
                try { val echoed = st.inputStream.readBytes(); if (echoed.size >= 4) c.echoed(unstamp(echoed)) }
                catch (e: Exception) { }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) { /* stream refused: counted as never echoed */ }
        busyWait(gapUs)
    }
    c.await()
    runCatching { client.close() }; runCatching { server.close() }
    return c.rtts
}

private fun sctpArm(n: Int, gapUs: Long, size: Int, warmup: Int): LongArray {
    val server = com.sun.nio.sctp.SctpServerChannel.open()
        .bind(InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))
    val port = (server.allLocalAddresses.first() as InetSocketAddress).port
    val echoThread = Thread {
        try {
            val ch = server.accept()
            val buf = java.nio.ByteBuffer.allocate(65536)
            while (true) {
                buf.clear()
                val info = ch.receive(buf, null, null) ?: continue
                if (info.bytes() < 0) break
                buf.flip()
                ch.send(buf, com.sun.nio.sctp.MessageInfo.createOutgoing(null, 0).unordered(info.isUnordered))
            }
        } catch (e: Exception) { }
    }.apply { isDaemon = true; start() }
    val client = com.sun.nio.sctp.SctpChannel.open(InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 1, 1)
    val c = Collector(n)
    val rx = Thread {
        try {
            val buf = java.nio.ByteBuffer.allocate(65536)
            while (c.got < n) {
                buf.clear()
                client.receive(buf, null, null) ?: continue
                buf.flip()
                if (buf.remaining() >= 4) { val b = ByteArray(4); buf.get(b); c.echoed(unstamp(b)) }
            }
        } catch (e: Exception) { }
    }.apply { isDaemon = true; start() }
    val payload = ByteArray(size)
    // unordered on purpose: SCTP's per-message reliability without cross-message ordering is the semantics
    // closest to Tessera's, and ordered SCTP would just re-measure TCP-style head-of-line
    val out = com.sun.nio.sctp.MessageInfo.createOutgoing(null, 0).unordered(true)
    repeat(warmup + n) { k ->
        val i = k - warmup
        stamp(payload, if (i < 0) Int.MAX_VALUE else i)
        if (i >= 0) c.sent(i)
        try { client.send(java.nio.ByteBuffer.wrap(payload, 0, size), out) } catch (e: Exception) { }
        busyWait(gapUs)
    }
    c.await()
    runCatching { client.close() }; runCatching { server.close() }
    return c.rtts
}

private fun tesseraArm(n: Int, gapUs: Long, size: Int, warmup: Int): LongArray {
    val keys = Handshake.generate()
    val cfg = ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 120_000)
    TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { it.toByte() }, cfg).use { server ->
        TesseraClient(cfg = cfg).use { client ->
            val conn = client.connect(server.localAddress, keys.x25519Pub, keys.kemPub, "vs".toByteArray(), timeoutMs = 10_000)
            val sconn = server.accept(5_000) ?: error("no accept"); sconn.receive(2_000)
            val echoThread = Thread {
                try { while (true) { val m = sconn.receive(100) ?: continue; sconn.send(m) } } catch (e: Exception) { }
            }.apply { isDaemon = true; start() }
            val c = Collector(n)
            val rx = Thread {
                try { while (c.got < n) { val m = conn.receive(50) ?: continue; if (m.size >= 4) c.echoed(unstamp(m)) } }
                catch (e: Exception) { }
            }.apply { isDaemon = true; start() }
            val payload = ByteArray(size)
            repeat(warmup + n) { k ->
                val i = k - warmup
                stamp(payload, if (i < 0) Int.MAX_VALUE else i)
                if (i >= 0) c.sent(i)
                conn.send(payload)
                busyWait(gapUs)
            }
            c.await()
            val r = c.rtts.copyOf()
            runCatching { conn.close() }; runCatching { sconn.close() }
            return r
        }
    }
}
