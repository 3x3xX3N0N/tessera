package tessera.bench

import tessera.core.Admission
import tessera.core.AddressValidator
import tessera.core.Handshake
import tessera.core.RetryToken
import tessera.core.ZeroRtt
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Random

/**
 * Quantifies the denial-of-service gap on the un-authenticated initial path and the cost of closing it.
 *
 *  1. **Before** — ML-KEM-768 decapsulations per second one core will perform for an attacker who sends nothing
 *     but ~1.2 KB of garbage (`ZeroRtt.Server.accept`, which reaches `Handshake.respond` before anything
 *     authenticates the sender), and the attacker bandwidth that costs.
 *  2. **After** — the same flood through `AddressValidator`, reporting KEM operations actually admitted per
 *     second and the per-packet cost of the gate itself.
 *  3. **Honest client** — over-the-wire connect latency, unattacked (no Retry) and under pressure (one Retry).
 *
 * Not wired into `bench/Main.kt` (that file is not mine to edit). To add it, give `main`'s `when (mode)` one more
 * arm: `"addrval" -> { addressValidationBench(); return }`, and add `addrval` to the usage line. Until then run it
 * with `kotlin -cp <bench classpath> tessera.bench.AddressValidationBenchKt` or call it from a scratch main.
 */
fun addressValidationBench(iterations: Int = 300, connects: Int = 60) {
    println("addrval  ML-KEM-768 decapsulation forced by un-authenticated 1.2 KB initials")

    val keys = Handshake.generate()
    val srv = ZeroRtt.Server(keys)
    val rnd = Random(1)
    // A *well-formed* attack initial: a fresh random ephemeral and KEM ciphertext with a current timestamp, so it
    // passes the timestamp and replay checks and reaches the KEM. Random bytes would be rejected on the timestamp
    // alone and would flatter the result.
    fun attackInitial(now: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(ZeroRtt.PREFIX_LEN + 32)
        val hs = ByteArray(ZeroRtt.HS_LEN).also { rnd.nextBytes(it) }
        b.put(hs).putLong(now).putLong(rnd.nextLong())
        b.put(ByteArray(32).also { rnd.nextBytes(it) })
        return b.array()
    }

    // 1. before: what one core will do for an attacker, no validation at all. Packets are built outside the timed
    // loop so what is measured is the server's cost, not the attacker's.
    val now = System.currentTimeMillis()
    val warm = List(50) { attackInitial(now) }
    val pool = List(iterations) { attackInitial(now) }
    warm.forEach { srv.accept(it, now) }
    var t0 = System.nanoTime()
    for (b in pool) srv.accept(b, now)
    val perOpUs = (System.nanoTime() - t0) / 1e3 / iterations
    val kemPerSec = 1e6 / perOpUs
    println(String.format(Locale.ROOT,
        "addrval  before: %.1f us/initial => %.0f KEM/s/core, forced by %.2f Mbit/s of attacker traffic (1.2 KB each)",
        perOpUs, kemPerSec, kemPerSec * 1200 * 8 / 1e6))

    // 2. after: the same flood, through the gate. One source (the common flood), then 100k distinct spoofed ones.
    val v = AddressValidator()
    val one = InetSocketAddress("203.0.113.9", 4433)
    repeat(10_000) { v.onExpensiveInitial(one, false, 1_000) }
    t0 = System.nanoTime()
    val n = 200_000
    repeat(n) { v.onExpensiveInitial(one, false, 1_000) }
    val gateNs = (System.nanoTime() - t0).toDouble() / n
    println(String.format(Locale.ROOT, "addrval  gate: %.0f ns/initial (%.1f M initials/s/core), one source of %d initials bought %d KEM",
        gateNs, 1e3 / gateNs, n + 10_000, v.admitted))

    val w = AddressValidator()
    w.forcePressure(true)
    var admitted = 0L; var retries = 0L
    repeat(100_000) { i ->
        val a = InetSocketAddress("10.${i shr 16 and 0xFF}.${i shr 8 and 0xFF}.${i and 0xFF}", 1)
        when (w.onExpensiveInitial(a, false, 1_000)) { Admission.ADMIT -> admitted++; Admission.RETRY -> retries++; else -> {} }
    }
    println("addrval  after: 100000 spoofed sources under pressure -> KEM=$admitted retries=$retries (a spoofed source never returns the token)")

    // Verification cost of a token, i.e. what an admitted-but-validated initial adds.
    val secret = RetryToken.deriveSecret(ByteArray(32))
    val tok = RetryToken.mint(secret, one, 1_000)
    repeat(10_000) { RetryToken.verify(secret, one, tok, 1_000) }
    t0 = System.nanoTime()
    repeat(200_000) { RetryToken.verify(secret, one, tok, 1_000) }
    println(String.format(Locale.ROOT, "addrval  token verify: %.0f ns", (System.nanoTime() - t0) / 200_000.0))

    // 3. honest client, over the wire, both regimes.
    for (pressure in listOf(false, true)) {
        val k = Handshake.generate()
        TesseraServer(InetSocketAddress("127.0.0.1", 0), k, ByteArray(32) { it.toByte() }).use { server ->
            server.validator.forcePressure(pressure)
            val lat = ArrayList<Long>(connects)
            TesseraClient().use { c ->
                repeat(connects + 10) { i ->
                    val s0 = System.nanoTime()
                    val conn = c.connect(server.localAddress, k.x25519Pub, k.kemPub, "ping".toByteArray(), timeoutMs = 10_000)
                    val done = System.nanoTime()                   // connect() returned: the handshake reply is in
                    val sc = server.accept(5_000) ?: error("no accept")
                    sc.receive(2_000)
                    if (i >= 10) lat.add(done - s0)                // first 10 are warm-up
                    conn.close(); sc.close()
                    Thread.sleep(20)   // ~50 connects/s from one address: a busy client, not a flood
                }
            }
            lat.sort()
            fun p(q: Double) = lat[((lat.size - 1) * q).toInt()] / 1000.0
            println(String.format(Locale.ROOT, "addrval  honest connect (%s): p50=%.0fus p99=%.0fus over %d connects, retries=%d",
                if (pressure) "under attack, Retry on" else "unattacked", p(0.5), p(0.99), lat.size, server.retriesSent))
        }
    }
}

fun main() = addressValidationBench()
