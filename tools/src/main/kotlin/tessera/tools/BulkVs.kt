package tessera.tools

import tessera.core.Handshake
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The two-node bulk harness (TODO §2c's remaining action). `bench vsbulk` put both endpoints in one JVM, which
 * on a 1-vCPU node measured CPU starvation, not transport — struck as a comparison. This splits the endpoints:
 * a `bulksink` on one node, a `bulkpush` on another, a real mesh path between them, impairment via
 * `bench/mesh/shape.py` on the PUSHER's egress (data direction; the sink's egress carries only acks/receipts).
 *
 * The sink listens on both transports so the arms share one path and one process lifetime:
 *   - Tessera on `--port` (default 51820)
 *   - TLS 1.3 / kernel TCP on `--port + 1` (51821 — both inside shape.py's port filters)
 * QUIC is absent by classpath: kwik is a bench-only dependency and tools deliberately stay core+transport.
 *
 * Wire protocol, both arms: push sends `totalBytes(8)`, then the payload (Tessera: 32 KB seq-stamped chunks,
 * re-ordered by the sink before hashing, the cost charged to Tessera as in `bench vsbulk`; TLS: the stream);
 * the sink replies with `sha256(32)` once everything arrived. The pusher's clock spans first byte to receipt,
 * so delivery — not transmission — is what is timed. The pusher prints goodput and MATCH/MISMATCH against its
 * own hash; a fast transfer that corrupts is a failure, not a result.
 *
 *   tessera bulksink --token <s> [--port 51820] [--bind ::]
 *   tessera bulkpush --connect <host:port> --peer-key <b64> --token <s> --arm <tessera|tls> [--mb 20] [--chunk 32768]
 */
fun bulkSinkMain(a: Args) {
    val token = a.req("token").toByteArray()
    val port = a.int("port", 51820)
    val bind = a.opt("bind") ?: "::"
    val keys = Handshake.generate()
    val ticketKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    println("bulksink: tessera on $bind:$port, tls on ${port + 1}")
    println("  --peer-key ${Keys.peerKey(keys)}")

    thread(isDaemon = true) { tlsSinkLoop(bind, port + 1) }

    TesseraServer(InetSocketAddress(bind, port), keys, ticketKey, ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 120_000)).use { server ->
        while (true) {
            val conn = server.accept(60_000) ?: continue
            thread(isDaemon = true) {
                try {
                    val hello = conn.receive(10_000) ?: return@thread
                    if (!hello.copyOfRange(8, hello.size).contentEquals(token)) { conn.close(); return@thread }
                    var total = 0L
                    for (i in 0 until 8) total = (total shl 8) or (hello[i].toLong() and 0xFF)
                    val md = MessageDigest.getInstance("SHA-256")
                    val pending = HashMap<Int, ByteArray>()
                    var next = 0; var got = 0L
                    while (got < total) {
                        val m = conn.receive(60_000) ?: throw IllegalStateException("sink timed out at $got/$total bytes")
                        val seq = ((m[0].toInt() and 0xFF) shl 24) or ((m[1].toInt() and 0xFF) shl 16) or ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF)
                        pending[seq] = m; got += m.size - 4
                        while (true) { val c = pending.remove(next) ?: break; md.update(c, 4, c.size - 4); next++ }
                    }
                    conn.send(md.digest())
                    println(String.format(Locale.ROOT, "bulksink: tessera %,d bytes, %d chunks reordered-buffered max %d", total, next, 0))
                    Thread.sleep(3_000)   // let the receipt's retransmits drain before teardown
                    conn.close()
                } catch (e: Exception) { println("bulksink: tessera transfer failed: $e"); runCatching { conn.close() } }
            }
        }
    }
}

private fun tlsSinkLoop(bind: String, port: Int) {
    val (store, pass) = toolsTempKeystore()
    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(store, pass.toCharArray())
    val ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3")
    ctx.init(kmf.keyManagers, null, null)
    val server = ctx.serverSocketFactory.createServerSocket(port, 4, java.net.InetAddress.getByName(bind))
    while (true) {
        val s = try { server.accept() } catch (e: Exception) { return }
        thread(isDaemon = true) {
            try {
                s.soTimeout = 60_000
                val din = DataInputStream(s.inputStream.buffered(1 shl 16))
                val total = din.readLong()
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1 shl 16); var left = total
                while (left > 0) { val r = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); require(r > 0); md.update(buf, 0, r); left -= r }
                s.outputStream.write(md.digest()); s.outputStream.flush()
                println(String.format(Locale.ROOT, "bulksink: tls %,d bytes", total))
                s.close()
            } catch (e: Exception) { println("bulksink: tls transfer failed: $e"); runCatching { s.close() } }
        }
    }
}

fun bulkPushMain(a: Args) {
    val target = a.req("connect")
    val arm = a.req("arm")
    val token = a.req("token").toByteArray()
    val mb = a.int("mb", 20)
    val chunk = a.int("chunk", 32768)
    val host = target.substringBeforeLast(':').trim('[', ']')
    val port = target.substringAfterLast(':').toInt()
    val payload = ByteArray(mb shl 20).also { java.util.Random(7).nextBytes(it) }
    val want = MessageDigest.getInstance("SHA-256").digest(payload)

    val (secs, gotHash) = when (arm) {
        "tessera" -> {
            val keyBytes = Base64.getDecoder().decode(a.req("peer-key"))
            val x = org.bouncycastle.crypto.params.X25519PublicKeyParameters(keyBytes.copyOfRange(0, 32))
            val kem = org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters(
                org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters.ml_kem_768, keyBytes.copyOfRange(32, keyBytes.size))
            TesseraClient(cfg = ConnConfig(pingIntervalMs = 0, idleTimeoutMs = 120_000)).use { client ->
                val conn = client.connect(InetSocketAddress(host, port), x, kem,
                    ByteArray(8 + token.size).also { h -> for (i in 0 until 8) h[i] = (payload.size.toLong() shr (56 - 8 * i)).toByte(); token.copyInto(h, 8) },
                    timeoutMs = 15_000)
                val t0 = System.nanoTime()
                var off = 0; var seq = 0
                while (off < payload.size) {
                    val len = minOf(chunk, payload.size - off)
                    val m = ByteArray(4 + len)
                    m[0] = (seq shr 24).toByte(); m[1] = (seq shr 16).toByte(); m[2] = (seq shr 8).toByte(); m[3] = seq.toByte()
                    System.arraycopy(payload, off, m, 4, len)
                    conn.send(m); off += len; seq++
                }
                val receipt = conn.receive(120_000) ?: throw IllegalStateException("no receipt after ${mb} MB")
                val dt = (System.nanoTime() - t0) / 1e9
                runCatching { conn.close() }
                dt to receipt
            }
        }
        "tls" -> {
            val ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3")
            ctx.init(null, arrayOf(object : javax.net.ssl.X509TrustManager {   // sink's cert is per-run throwaway
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, t: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, t: String) {}
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            }), null)
            val s = ctx.socketFactory.createSocket(host, port) as javax.net.ssl.SSLSocket
            s.soTimeout = 120_000
            s.startHandshake()
            val t0 = System.nanoTime()
            DataOutputStream(s.outputStream.buffered(1 shl 16)).run { writeLong(payload.size.toLong()); write(payload); flush() }
            val receipt = ByteArray(32); DataInputStream(s.inputStream).readFully(receipt)
            val dt = (System.nanoTime() - t0) / 1e9
            runCatching { s.close() }
            dt to receipt
        }
        else -> { System.err.println("tessera: --arm must be tessera or tls"); kotlin.system.exitProcess(2) }
    }
    println(String.format(Locale.ROOT, "bulkpush %-8s %d MB in %7.2f s = %6.2f MB/s  %s",
        arm, mb, secs, payload.size / 1e6 / secs, if (gotHash.contentEquals(want)) "MATCH" else "MISMATCH"))
}

internal fun toolsTempKeystore(): Pair<java.security.KeyStore, String> {
    val ks = java.io.File.createTempFile("bulkvs", ".p12").also { it.deleteOnExit() }
    ks.delete()
    val keytool = java.io.File(java.io.File(System.getProperty("java.home"), "bin"), "keytool").absolutePath
    val gen = ProcessBuilder(keytool, "-genkeypair", "-alias", "vs", "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=bulkvs", "-validity", "1", "-storetype", "PKCS12",
        "-keystore", ks.absolutePath, "-storepass", "vsbench0").redirectErrorStream(true).start()
    check(gen.waitFor() == 0) { "keytool failed" }
    val store = java.security.KeyStore.getInstance("PKCS12")
    ks.inputStream().use { store.load(it, "vsbench0".toCharArray()) }
    return store to "vsbench0"
}
