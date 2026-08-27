package tessera.bench

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import tessera.core.GF256
import tessera.core.PacketKeys
import tessera.core.PacketProtection
import tessera.core.RlncDecoder
import tessera.core.RlncEncoder
import tessera.transport.ConnConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.max

/**
 * Where does Tessera's per-message cost over plain UDP actually go?
 *
 * The E4 mesh campaign put Tessera 0.40 ms above ICMP on a 30-region WAN matrix, and the obvious next move —
 * an AF_XDP / eBPF datapath — only pays for the SYSCALL-AND-COPY half of that number. eBPF removes trips
 * through the kernel's socket layer; it does nothing for ChaCha20-Poly1305 or GF(256) arithmetic, which are
 * compute and would cost the same in a userspace poll-mode driver. So the decision needs the split, not the
 * total: if the cost is codec, XDP buys nothing; if it is plumbing, XDP is worth writing.
 *
 * Three stages, all local — no cloud, no link:
 *   crypto     ChaCha20-Poly1305 seal + open and QUIC-style header protect + unprotect, per packet. This is
 *              every packet's unavoidable floor, charged once on the sender and once on the receiver. Two
 *              variants are reported and only one is charged: the DATAPATH pattern (a cipher and KeyParameter
 *              reused across packets, what a live connection does) and the COLD one (core's [tessera.core.Aead],
 *              a fresh cipher and key per call — correct for the handshake, several times the cost per packet).
 *              The first version of this bench charged the cold path to every message and overstated the codec.
 *   rlnc       encoder push + repair(window) and decoder onRepair, per symbol. Charged only on the FEC
 *              fraction: at the estimator's 0.02 floor a repair covers 50 sources, at its 0.5 cap, 2. The
 *              report scales it by --redundancy so the per-message figure is comparable with the others.
 *   loopback   the same one-way measurement `bench rawudp` and `bench tessera` do, run back to back in one
 *              JVM so the two arms share JIT state, CPU governor and clock. The delta between them is the
 *              whole per-message cost of being Tessera rather than a datagram.
 *
 * The attribution at the end is subtraction, and it is stated as such: plumbing = loopback delta - (crypto +
 * rlnc). "Plumbing" is everything the microbenches do not cover — syscalls, buffer copies, the ack/credit/
 * estimator bookkeeping, JVM scheduling between the send thread and the rx thread, and the queueing the
 * loopback path adds. Only part of that is what XDP could take, so treat the plumbing figure as an UPPER
 * BOUND on the XDP prize, not an estimate of it. A loopback delta is also not a WAN delta: loopback has no
 * propagation to hide cost behind, so this over-attributes rather than under-attributes.
 *
 * usage: bench profile [--n 200000] [--size 1200] [--window 32] [--redundancy 0.02] [--msgs 20000] [--gapUs 200]
 */
fun profileMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    val n = opt("n", "200000").toInt()
    val size = opt("size", "1200").toInt()
    val window = opt("window", "32").toInt()
    val redundancy = opt("redundancy", "0.02").toDouble()
    val msgs = opt("msgs", "20000").toInt()
    val gapUs = opt("gapUs", "200").toLong()

    println(String.format(Locale.ROOT, "profile  %s | %d B packets, crypto/rlnc n=%d, rlnc window=%d, redundancy=%.3f, loopback %d msgs at %d us",
        System.getProperty("os.name"), size, n, window, redundancy, msgs, gapUs))

    // Loopback FIRST, deliberately: opening a connection is what installs the native GF(256) kernel (Datapath ->
    // Gf256Native.install()). Run the microbenches ahead of it and they measure the scalar kernel while the
    // loopback arm they are being subtracted from ran on the native one — an attribution that silently compares
    // two different codecs. The first run of this bench did exactly that and reported kernel=Scalar.
    val loop = loopbackStage(msgs, size, gapUs)
    val kernel = GF256.kernel.javaClass.simpleName
    val crypto = cryptoStage(n, size)
    val rlnc = rlncStage(n, size, window)

    // Per message: one seal + one open + one protect + one unprotect (sender and receiver each pay their half).
    // The JCE figures are the ones charged, because that is what the transport calls: PacketCrypto moved to the
    // JDK provider on 2026-08-27. Charging the BouncyCastle numbers here (as this bench did before the move)
    // would overstate the codec by ~10 us and leave the attribution describing a datapath that no longer exists.
    val cryptoPerMsgUs = (crypto.jceSealNs + crypto.jceOpenNs + crypto.hpPairNs) / 1000.0
    // Per message: the encoder pushes every source; a repair is encoded and decoded once per 1/redundancy of them.
    val rlncPerMsgUs = (rlnc.pushNs + redundancy * (rlnc.repairNs + rlnc.onRepairNs)) / 1000.0
    val computeUs = cryptoPerMsgUs + rlncPerMsgUs
    val deltaUs = loop.tesseraP50Us - loop.udpP50Us
    val plumbingUs = deltaUs - computeUs

    println()
    println(String.format(Locale.ROOT, "profile  crypto:   datapath (%s) seal %6.2f us  open %6.2f us  header protect+unprotect %5.2f us  => %6.2f us/msg  (%.0f MB/s sealed)",
        crypto.provider, crypto.jceSealNs / 1e3, crypto.jceOpenNs / 1e3, crypto.hpPairNs / 1e3, cryptoPerMsgUs, size / (crypto.jceSealNs / 1e9) / 1e6))
    println(String.format(Locale.ROOT, "profile  crypto:   bouncycastle seal %6.2f us  open %6.2f us  => %5.2f us/msg  (what the datapath used before 2026-08-27; the JDK provider is",
        crypto.hotSealNs / 1e3, crypto.hotOpenNs / 1e3, (crypto.hotSealNs + crypto.hotOpenNs) / 1e3))
    println(String.format(Locale.ROOT, "profile            %.1fx faster because HotSpot intrinsifies it, worth %.1f us/msg; BouncyCastle remains the truncated-tag open and the fallback)",
        (crypto.hotSealNs + crypto.hotOpenNs) / (crypto.jceSealNs + crypto.jceOpenNs), (crypto.hotSealNs + crypto.hotOpenNs - crypto.jceSealNs - crypto.jceOpenNs) / 1e3))
    println(String.format(Locale.ROOT, "profile  crypto:   cold-cipher (core Aead, fresh cipher+key per call: the handshake/0-RTT path) seal %6.2f us  open %6.2f us",
        crypto.sealNs / 1e3, crypto.openNs / 1e3))
    println(String.format(Locale.ROOT, "profile  rlnc:     push %6.2f us  repair %6.2f us (w=%d)  onRepair %6.2f us  => %6.2f us/msg at redundancy %.3f  (gf256 kernel=%s)",
        rlnc.pushNs / 1e3, rlnc.repairNs / 1e3, window, rlnc.onRepairNs / 1e3, rlncPerMsgUs, redundancy, kernel))
    println(String.format(Locale.ROOT, "profile  loopback: udp p50 %6.1f us  tessera p50 %6.1f us  => delta %6.1f us/msg   (udp p99 %.1f, tessera p99 %.1f, delivered %d/%d)",
        loop.udpP50Us, loop.tesseraP50Us, deltaUs, loop.udpP99Us, loop.tesseraP99Us, loop.delivered, msgs))
    println()
    println(String.format(Locale.ROOT, "profile  attribution of the %.1f us one-way delta:  codec %.2f us (%.0f%%: crypto %.2f + rlnc %.2f)  |  plumbing <= %.1f us (%.0f%%)",
        deltaUs, computeUs, 100.0 * computeUs / max(deltaUs, 0.001), cryptoPerMsgUs, rlncPerMsgUs, plumbingUs, 100.0 * plumbingUs / max(deltaUs, 0.001)))
    println("profile  plumbing is a residual, not a measurement: syscalls, copies, ack/credit/estimator bookkeeping and")
    println("profile  JVM scheduling all land in it. It bounds what an AF_XDP datapath could remove; it does not predict it.")
}

private class CryptoResult(val sealNs: Double, val openNs: Double, val hpPairNs: Double, val hotSealNs: Double, val hotOpenNs: Double, val jceSealNs: Double, val jceOpenNs: Double, val provider: String)

/**
 * The AEAD and header-protection cost of one packet, using the same [PacketProtection] entry points the transport
 * calls. Keys come from the public [PacketKeys] secret constructor (the same HKDF the handshake feeds), so this is
 * the production key schedule, not a stub. tagLen 16 is the default negotiation.
 */
private fun cryptoStage(n: Int, size: Int): CryptoResult {
    val keys = PacketKeys(ByteArray(32) { (it * 7 + 1).toByte() }, 16)
    val payload = ByteArray(size) { it.toByte() }
    val hdrLen = PacketProtection.SHORT_PN_OFFSET + 4
    val header = ByteBuffer.allocate(hdrLen)
    header.put(0, (0x40 or (3 shl 5)).toByte())          // short form, pnLen 4 (bits 5-6 == 3)
    val sealed = PacketProtection.seal(keys, 1L, header, payload)

    var sink = 0L
    val sealNs = timed(n) { i -> sink += PacketProtection.seal(keys, i.toLong(), header, payload).size }
    val openNs = timed(n) { _ -> sink += (PacketProtection.open(keys, 1L, header, sealed)?.size ?: 0) }

    // Header protection works in place on a whole packet: header || ciphertext. Protect and unprotect are
    // inverses of each other, so a loop that measured one alone would drift the packet out of shape; they are
    // measured as a pair and charged as a pair, which is also exactly what one message costs end to end
    // (the sender protects, the receiver unprotects).
    val pkt = ByteArray(hdrLen + sealed.size)
    header.duplicate().get(pkt, 0, hdrLen); sealed.copyInto(pkt, hdrLen)
    val hpPairNs = timed(n) { _ ->
        PacketProtection.protectHeader(keys, pkt, PacketProtection.SHORT_PN_OFFSET, 4)
        PacketProtection.unprotectHeader(keys, pkt, PacketProtection.SHORT_PN_OFFSET)
        sink += pkt[0].toLong()
    }
    // The datapath pattern, mirroring the transport's internal PacketCrypto: one ChaCha20Poly1305 instance and one
    // cached KeyParameter per direction, re-inited per packet with a fresh nonce, output written into a scratch
    // array the connection already owns. (transport.PacketCrypto is internal, so this reproduces its shape rather
    // than calling it; the ciphers, the AEADParameters and the buffer reuse are the same.)
    val kp = KeyParameter(keys.key)
    val txCipher = ChaCha20Poly1305(); val rxCipher = ChaCha20Poly1305()
    val aad = ByteArray(hdrLen); header.duplicate().get(aad, 0, hdrLen)
    val scratch = ByteArray(size + 16)
    val hotSealNs = timed(n) { i ->
        txCipher.init(true, AEADParameters(kp, 128, keys.nonce(i.toLong()), null))
        txCipher.processAADBytes(aad, 0, hdrLen)
        val c = txCipher.processBytes(payload, 0, size, scratch, 0)
        sink += txCipher.doFinal(scratch, c).toLong()
    }
    txCipher.init(true, AEADParameters(kp, 128, keys.nonce(1L), null))
    txCipher.processAADBytes(aad, 0, hdrLen)
    val ct = ByteArray(size + 16)
    txCipher.doFinal(ct, txCipher.processBytes(payload, 0, size, ct, 0))
    val plain = ByteArray(size + 16)
    val hotOpenNs = timed(n) { _ ->
        rxCipher.init(false, AEADParameters(kp, 128, keys.nonce(1L), null))
        rxCipher.processAADBytes(aad, 0, hdrLen)
        val c = rxCipher.processBytes(ct, 0, ct.size, plain, 0)
        sink += rxCipher.doFinal(plain, c).toLong()
    }
    // The JDK provider, which is what PacketCrypto calls now. The nonce varies per iteration because SunJCE
    // refuses to re-initialise for encryption under a key+nonce it has already used - the same guard the
    // transport satisfies by never reusing a packet number.
    val sk = SecretKeySpec(keys.key, "ChaCha20")
    val encC = Cipher.getInstance("ChaCha20-Poly1305")
    val decC = Cipher.getInstance("ChaCha20-Poly1305")
    val provider = encC.provider.name
    val jceOut = ByteArray(size + 16)
    val jceSealNs = timed(n) { i ->
        encC.init(Cipher.ENCRYPT_MODE, sk, IvParameterSpec(keys.nonce(i.toLong())))
        encC.updateAAD(aad, 0, hdrLen)
        sink += encC.doFinal(payload, 0, size, jceOut, 0).toLong()
    }
    encC.init(Cipher.ENCRYPT_MODE, sk, IvParameterSpec(keys.nonce(-1L)))
    encC.updateAAD(aad, 0, hdrLen)
    val jceCt = ByteArray(size + 16).also { encC.doFinal(payload, 0, size, it, 0) }
    val jcePlain = ByteArray(size + 16)
    val jceOpenNs = timed(n) { _ ->
        decC.init(Cipher.DECRYPT_MODE, sk, IvParameterSpec(keys.nonce(-1L)))
        decC.updateAAD(aad, 0, hdrLen)
        sink += decC.doFinal(jceCt, 0, jceCt.size, jcePlain, 0).toLong()
    }
    if (sink == Long.MIN_VALUE) println("unreachable $sink")   // keep the JIT from eliminating the work
    return CryptoResult(sealNs, openNs, hpPairNs, hotSealNs, hotOpenNs, jceSealNs, jceOpenNs, provider)
}

private class RlncResult(val pushNs: Double, val repairNs: Double, val onRepairNs: Double)

/** Encoder push and repair over a full window, and the decoder's substitution pass for one repair. */
private fun rlncStage(n: Int, size: Int, window: Int): RlncResult {
    val enc = RlncEncoder(size, window)
    var seq = 0L
    val sym = ByteArray(size) { it.toByte() }
    val pushNs = timed(n) { _ -> enc.push(seq++, sym) }

    var sink = 0L
    val repairNs = timed(n) { i -> sink += enc.repair(i).windowLen.toLong() }

    // A decoder that already knows every source in the window: onRepair then does the full substitution pass
    // (window multiply-accumulates) and reduces to no unknowns — the hot path, and the one the mesh ran.
    val dec = RlncDecoder(size)
    val base = seq - window
    for (i in 0 until window) dec.onSource(base + i, sym)
    val repairs = Array(64) { enc.repair(it) }
    val onRepairNs = timed(n) { i -> dec.onRepair(repairs[i and 63]) }
    if (sink == Long.MIN_VALUE) println("unreachable $sink")
    return RlncResult(pushNs, repairNs, onRepairNs)
}

/** Median nanoseconds per iteration over five passes, after a warm-up pass of the same shape (JIT, first-call allocation). */
private inline fun timed(n: Int, body: (Int) -> Unit): Double {
    val warm = (n / 10).coerceAtLeast(1000)
    for (i in 0 until warm) body(i)
    val samples = DoubleArray(5)
    for (s in samples.indices) {
        val t0 = System.nanoTime()
        for (i in 0 until n) body(i)
        samples[s] = (System.nanoTime() - t0).toDouble() / n
    }
    samples.sort()
    return samples[2]
}

private class LoopbackResult(val udpP50Us: Double, val udpP99Us: Double, val tesseraP50Us: Double, val tesseraP99Us: Double, val delivered: Int)

/**
 * Both arms in one JVM, plain UDP first. Same host, so the send and receive timestamps share a clock and the
 * one-way number is real rather than half an RTT.
 */
private fun loopbackStage(msgs: Int, size: Int, gapUs: Long): LoopbackResult {
    val udp = rawUdpOneWay(msgs, size, gapUs)
    val t = runTessera(msgs, gapUs, 0.0, size, cfg = ConnConfig(), warmup = 2000)
    val tl = t.latencies.filter { it >= 0 }.sorted()
    fun pct(l: List<Long>, p: Double) = if (l.isEmpty()) 0.0 else l[((l.size - 1) * p).toInt()] / 1000.0
    return LoopbackResult(udp.first, udp.second, pct(tl, 0.5), pct(tl, 0.99), tl.size)
}

/** p50/p99 one-way microseconds for plain datagrams over loopback — the floor the transport is measured against. */
private fun rawUdpOneWay(n: Int, size: Int, gapUs: Long): Pair<Double, Double> {
    require(n < 65535)
    val rx = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())
    val tx = DatagramSocket()
    val addr = rx.localSocketAddress as InetSocketAddress
    val sent = LongArray(n)
    val lat = LongArray(n) { -1L }
    val t = Thread {
        repeat(n) { i ->
            val p = ByteArray(size); p[0] = (i shr 8).toByte(); p[1] = i.toByte()
            sent[i] = System.nanoTime()
            tx.send(DatagramPacket(p, size, addr))
            busyWait(gapUs)
        }
    }.apply { start() }
    rx.soTimeout = 50
    val buf = ByteArray(size); var got = 0
    val deadline = System.nanoTime() + n * gapUs * 1000 + 10_000_000_000L
    while (got < n && System.nanoTime() < deadline) {
        try { rx.receive(DatagramPacket(buf, size)) } catch (e: java.net.SocketTimeoutException) { continue }
        val i = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (i < n && lat[i] < 0) { lat[i] = System.nanoTime() - sent[i]; got++ }
    }
    t.join(); rx.close(); tx.close()
    val l = lat.filter { it >= 0 }.sorted()
    fun pct(p: Double) = if (l.isEmpty()) 0.0 else l[((l.size - 1) * p).toInt()] / 1000.0
    return pct(0.5) to pct(0.99)
}
