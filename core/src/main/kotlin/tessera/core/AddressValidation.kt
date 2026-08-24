package tessera.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Address validation for the un-authenticated initial path.
 *
 * The problem it closes: [ZeroRtt.Server.accept] checks a timestamp window and a replay set — both a handful of
 * nanoseconds — and then runs [Handshake.respond], which is an X25519 agreement **plus an ML-KEM-768
 * decapsulation**, before anything about the sender is authenticated. The responder's public key is meant to be
 * public (`tessera echo` prints it), so anybody can spend ~1.2 KB of garbage to buy a post-quantum key exchange on
 * the listener. The `--token` shared secret does not help: it is inside the AEAD, i.e. behind the KEM. The 3x
 * amplification limit and ticket binding, which `docs/SPEC.md` used to cite as covering this, bound *reflected
 * bytes*, not *CPU*.
 *
 * The design is adaptive, because a Retry that always costs a round trip would destroy 0-RTT — the protocol's
 * headline property:
 *
 *  1. **Always on and cheap.** A per-source token bucket ([sourceSlots], O(1), fixed memory) plus a global budget
 *     of un-authenticated KEM operations per second. Neither can be grown by an attacker.
 *  2. **Under pressure only.** When the un-authenticated initial rate or the *failure* rate crosses a threshold,
 *     un-validated addresses stop getting a KEM and get a [RetryToken] instead — a truncated MAC over
 *     (source address, coarse time bucket) under a server-held secret. No per-attempt state is kept. The client
 *     re-sends its initial with the token; verification is one HMAC-SHA256 over ~24 bytes. A spoofed source never
 *     receives the token, so it never reaches the KEM.
 *  3. Retry costs one round trip only *while under attack*, and only for a client with no valid token.
 *
 * Memory cost: [sourceSlots] fixed slots of 32 bytes (a keyed 64-bit fingerprint, two token counts, a refill stamp);
 * the default 8192 slots is ~260 KB and never grows. Slots are shared on hash collision rather than evicted — an
 * attacker cannot make the table grow, and cannot aim at a particular victim's slot either, because the slot index
 * is keyed with the server secret. The cost of that choice is that ~1/8192 of honest sources share a bucket with a
 * flooding one while the flood lasts; they are then rate-limited, not refused, and a Retry still lets them through.
 */
object RetryToken {
    /** Wire length of a token: 4-byte bucket id + 12-byte truncated HMAC-SHA256. */
    const val LEN = 16
    /** Coarse time bucket. A token is accepted for its own bucket and the previous one: 15..30 s of validity. */
    const val BUCKET_MS = 15_000L

    /** Server retry secret, derived from the ticket key so tokens survive a restart that keeps the same ticket key. */
    fun deriveSecret(ticketKey: ByteArray): ByteArray {
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ticketKey, null, "tessera-retry".toByteArray())) }
            .generateBytes(out, 0, 32)
        return out
    }

    fun mint(secret: ByteArray, addr: InetSocketAddress, nowMs: Long): ByteArray = mintForBucket(secret, addr, nowMs / BUCKET_MS)

    private fun mintForBucket(secret: ByteArray, addr: InetSocketAddress, bucket: Long): ByteArray {
        val tag = mac(secret, addrBytes(addr), bucket)
        return ByteBuffer.allocate(LEN).putInt(bucket.toInt()).put(tag, 0, LEN - 4).array()
    }

    /** True when [token] is this server's token for [addr] and is at most one bucket old. Constant-time compare. */
    fun verify(secret: ByteArray, addr: InetSocketAddress, token: ByteArray?, nowMs: Long): Boolean {
        if (token == null || token.size != LEN) return false
        val cur = nowMs / BUCKET_MS
        val claimed = ByteBuffer.wrap(token).getInt().toLong() and 0xFFFF_FFFFL
        // The wire carries the low 32 bits of the bucket; reconstruct against the two buckets we accept.
        for (b in longArrayOf(cur, cur - 1)) {
            if ((b and 0xFFFF_FFFFL) != claimed) continue
            if (eq(mintForBucket(secret, addr, b), token)) return true
        }
        return false
    }

    private fun eq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    private fun mac(secret: ByteArray, addr: ByteArray, bucket: Long): ByteArray {
        val h = HMac(SHA256Digest()).apply { init(KeyParameter(secret)) }
        h.update(addr, 0, addr.size)
        val bb = ByteBuffer.allocate(8).putLong(bucket).array()
        h.update(bb, 0, 8)
        val out = ByteArray(h.macSize)
        h.doFinal(out, 0)
        return out
    }

    /** IP bytes + port. The port is included: NAT rebinding costs the client one extra Retry, spoofing gains nothing. */
    fun addrBytes(addr: InetSocketAddress): ByteArray {
        val ip = addr.address?.address ?: addr.hostString.toByteArray()
        return ByteBuffer.allocate(ip.size + 2).put(ip).putShort(addr.port.toShort()).array()
    }
}

/** What the server should do with an un-authenticated initial. */
enum class Admission {
    /** Run the (expensive) handshake. */
    ADMIT,
    /** Reply with a stateless retry token and do no asymmetric crypto. */
    RETRY,
    /** Drop silently: this source, or the server as a whole, is over budget. */
    DROP,
}

/**
 * The policy in front of the handshake. Every operation is O(1) and allocation-free on the hot path.
 *
 * Thread-safety: guarded by an intrinsic lock. In practice one rx thread calls it, and the critical section is a
 * few arithmetic operations, so the lock is uncontended.
 */
class AddressValidator(
    val secret: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    /** Sustained KEM operations allowed to one *un-validated* source address, and the burst it may take at once. */
    val perSourcePerSec: Double = 50.0,
    val perSourceBurst: Double = 100.0,
    /**
     * The same, for a source that has proved its address with a token. It is a separate bucket, not a discount:
     * a client behind a busy NAT legitimately opens many connections from one address, and must not be throttled
     * into the ground by a neighbour on the same public IP - it pays one Retry and then gets this allowance.
     */
    val perValidatedSourcePerSec: Double = 200.0,
    val perValidatedSourceBurst: Double = 400.0,
    /** Ceiling on Retry packets minted per second (they are ~31 B, but each still costs a send). */
    val retryPerSec: Double = 5_000.0,
    /**
     * Ceiling on *un-authenticated* KEM operations per second across all sources. One ML-KEM-768 decapsulation plus
     * the X25519 agreement measures ~0.5 ms on one core here (see the bench), so 200/s is ~10 % of a core - the most
     * an un-authenticated peer may cost before it has to prove its address.
     */
    val globalKemPerSec: Double = 200.0,
    val globalBurst: Double = 400.0,
    /** Fixed slot table; memory is `sourceSlots * 24 B` and never grows. Must be a power of two. */
    val sourceSlots: Int = 8192,
    /** Pressure trips above this many un-authenticated initials per second... */
    val pressureInitialsPerSec: Double = 200.0,
    /** ...or when this share of the last window's initials failed to authenticate (with at least [pressureFailureMin]). */
    val pressureFailureRate: Double = 0.5,
    val pressureFailureMin: Int = 32,
) {
    init { require(sourceSlots > 0 && sourceSlots and (sourceSlots - 1) == 0) { "sourceSlots must be a power of two" } }

    private val fp = LongArray(sourceSlots)
    private val tokens = DoubleArray(sourceSlots) { perSourceBurst }
    private val vtokens = DoubleArray(sourceSlots) { perValidatedSourceBurst }
    private val stamp = LongArray(sourceSlots)

    private var globalTokens = globalBurst
    private var globalStamp = 0L
    private var retryTokens = retryPerSec
    private var retryStamp = 0L

    private var windowStart = 0L
    private var windowInitials = 0
    private var windowFailures = 0
    @Volatile private var pressureOn = false

    // counters, for tests and for the bench
    @Volatile var admitted = 0L; private set
    @Volatile var retried = 0L; private set
    @Volatile var dropped = 0L; private set
    /** Whether the server is currently in the "retry un-validated addresses" regime. */
    val underPressure: Boolean get() = pressureOn

    fun mintToken(addr: InetSocketAddress, nowMs: Long): ByteArray = RetryToken.mint(secret, addr, nowMs)
    fun verifyToken(addr: InetSocketAddress, token: ByteArray?, nowMs: Long): Boolean = RetryToken.verify(secret, addr, token, nowMs)

    /**
     * Decide what to do with an initial from [addr] that would cost a KEM.
     * [validated] is true when the initial carried a token this server minted for this address.
     */
    fun onExpensiveInitial(addr: InetSocketAddress, validated: Boolean, nowMs: Long): Admission = synchronized(this) {
        roll(nowMs)
        windowInitials++
        if (!takeSource(addr, validated, nowMs)) return overBudget(validated, nowMs)
        if (pressureOn && !validated) return retry(nowMs)
        // Saturated globally: an un-validated sender is asked to prove its address (which throttles it a round trip
        // too); a validated one is dropped and will retransmit - dropping is the only honest backpressure left.
        if (!takeGlobal(nowMs)) return overBudget(validated, nowMs)
        admitted++
        return Admission.ADMIT
    }

    private fun overBudget(validated: Boolean, nowMs: Long): Admission {
        if (validated) { dropped++; return Admission.DROP }
        return retry(nowMs)
    }

    /** A Retry, if the Retry budget allows one; otherwise a silent drop. */
    private fun retry(nowMs: Long): Admission {
        if (retryStamp == 0L) retryStamp = nowMs
        retryTokens = minOf(retryPerSec, retryTokens + (nowMs - retryStamp) / 1000.0 * retryPerSec)
        retryStamp = nowMs
        if (retryTokens < 1.0) { dropped++; return Admission.DROP }
        retryTokens -= 1.0
        retried++
        return Admission.RETRY
    }

    /**
     * A resumed initial: no KEM, one AEAD open of the ticket. Only the per-source bucket applies — see the note on
     * tickets and prior validation in `docs/SPEC.md`. Returns false to drop.
     */
    fun onCheapInitial(addr: InetSocketAddress, nowMs: Long): Boolean = synchronized(this) {
        roll(nowMs)
        windowInitials++
        if (!takeSource(addr, false, nowMs)) { dropped++; return false }
        return true
    }

    /** An admitted initial failed to authenticate. Drives the failure-rate half of the pressure test. */
    fun onFailure(nowMs: Long) = synchronized(this) { roll(nowMs); windowFailures++ }

    /** Test/bench hook: pin the pressure regime on or off regardless of the measured rates; null returns to auto. */
    fun forcePressure(on: Boolean?) { forced = on; if (on != null) pressureOn = on }
    private var forced: Boolean? = null

    private fun roll(nowMs: Long) {
        if (windowStart == 0L) { windowStart = nowMs; return }
        val dt = nowMs - windowStart
        if (dt < 1_000) return
        val rate = windowInitials * 1000.0 / dt
        val failing = windowInitials >= pressureFailureMin && windowFailures.toDouble() / windowInitials >= pressureFailureRate
        pressureOn = forced ?: (rate >= pressureInitialsPerSec || failing)
        windowStart = nowMs; windowInitials = 0; windowFailures = 0
    }

    private fun takeGlobal(nowMs: Long): Boolean {
        if (globalStamp == 0L) globalStamp = nowMs
        globalTokens = minOf(globalBurst, globalTokens + (nowMs - globalStamp) / 1000.0 * globalKemPerSec)
        globalStamp = nowMs
        if (globalTokens < 1.0) return false
        globalTokens -= 1.0
        return true
    }

    private fun takeSource(addr: InetSocketAddress, validated: Boolean, nowMs: Long): Boolean {
        val h = fingerprint(addr)
        val i = ((h ushr 32) xor h).toInt() and (sourceSlots - 1)
        if (fp[i] != h) {                                                            // slot reused (or first use)
            fp[i] = h; tokens[i] = perSourceBurst; vtokens[i] = perValidatedSourceBurst; stamp[i] = nowMs
        } else {
            val dt = (nowMs - stamp[i]) / 1000.0
            tokens[i] = minOf(perSourceBurst, tokens[i] + dt * perSourcePerSec)
            vtokens[i] = minOf(perValidatedSourceBurst, vtokens[i] + dt * perValidatedSourcePerSec)
        }
        stamp[i] = nowMs
        val b = if (validated) vtokens else tokens
        if (b[i] < 1.0) return false
        b[i] -= 1.0
        return true
    }

    /**
     * Secret-keyed 64-bit fingerprint of the source address. Not a MAC — it only has to be unpredictable enough
     * that an attacker cannot compute which slot a chosen victim lands in, which the secret gives it; a per-packet
     * HMAC here would cost more than the check is worth.
     */
    private fun fingerprint(addr: InetSocketAddress): Long {
        val k0 = ByteBuffer.wrap(secret).getLong(0)
        val k1 = ByteBuffer.wrap(secret).getLong(8)
        var h = k0
        val b = RetryToken.addrBytes(addr)
        for (x in b) { h = (h xor (x.toLong() and 0xFF)) * -0x340d631b7bdddcdbL }
        h = h xor k1
        h *= -0x7ee3623a03d3c83fL
        h = h xor (h ushr 29)
        return if (h == 0L) 1L else h
    }
}
