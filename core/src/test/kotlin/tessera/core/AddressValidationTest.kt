package tessera.core

import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressValidationTest {
    private val secret = ByteArray(32) { it.toByte() }
    private fun a(host: String, port: Int) = InetSocketAddress(host, port)

    @Test fun aFreshTokenVerifiesForItsOwnAddress() {
        val t0 = 1_700_000_000_000L
        val tok = RetryToken.mint(secret, a("10.0.0.1", 4433), t0)
        assertEquals(RetryToken.LEN, tok.size)
        assertTrue(RetryToken.verify(secret, a("10.0.0.1", 4433), tok, t0))
        assertTrue(RetryToken.verify(secret, a("10.0.0.1", 4433), tok, t0 + RetryToken.BUCKET_MS))
    }

    @Test fun aTokenIsRejectedForAnotherAddressPortSecretOrShape() {
        val t0 = 1_700_000_000_000L
        val tok = RetryToken.mint(secret, a("10.0.0.1", 4433), t0)
        assertFalse(RetryToken.verify(secret, a("10.0.0.2", 4433), tok, t0), "wrong host")
        assertFalse(RetryToken.verify(secret, a("10.0.0.1", 4434), tok, t0), "wrong port")
        assertFalse(RetryToken.verify(ByteArray(32), a("10.0.0.1", 4433), tok, t0), "wrong secret")
        assertFalse(RetryToken.verify(secret, a("10.0.0.1", 4433), tok.copyOf(RetryToken.LEN - 1), t0), "truncated")
        assertFalse(RetryToken.verify(secret, a("10.0.0.1", 4433), null, t0), "absent")
        for (i in tok.indices) {
            val forged = tok.copyOf().also { it[i] = (it[i] + 1).toByte() }
            assertFalse(RetryToken.verify(secret, a("10.0.0.1", 4433), forged, t0), "forged at byte $i")
        }
    }

    @Test fun aTokenExpiresAfterTwoBuckets() {
        val t0 = 1_700_000_000_000L
        val tok = RetryToken.mint(secret, a("10.0.0.1", 4433), t0)
        assertTrue(RetryToken.verify(secret, a("10.0.0.1", 4433), tok, t0 + RetryToken.BUCKET_MS))
        assertFalse(RetryToken.verify(secret, a("10.0.0.1", 4433), tok, t0 + 3 * RetryToken.BUCKET_MS))
        assertFalse(RetryToken.verify(secret, a("10.0.0.1", 4433), tok, t0 - 2 * RetryToken.BUCKET_MS))
    }

    @Test fun anIdleServerAdmitsAnHonestConnectWithNoRetry() {
        val v = AddressValidator(secret = secret)
        assertEquals(Admission.ADMIT, v.onExpensiveInitial(a("10.0.0.1", 1), false, 1_000))
        assertFalse(v.underPressure)
        assertEquals(0L, v.retried)
    }

    @Test fun onePerSourceBucketCapsOneSourcesKemOps() {
        val v = AddressValidator(secret = secret, perSourcePerSec = 6.0, perSourceBurst = 12.0)
        val src = a("10.0.0.9", 5555)
        var admitted = 0
        repeat(10_000) { if (v.onExpensiveInitial(src, false, 1_000) == Admission.ADMIT) admitted++ }
        assertEquals(12, admitted, "burst only; the clock never advanced so nothing refilled")
        // The other 9988 cost no asymmetric crypto: a Retry while the Retry budget lasts, a silent drop after.
        assertEquals(9_988L, v.retried + v.dropped)
        assertEquals(5_000L, v.retried, "the Retry budget itself is capped")
    }

    @Test fun theGlobalKemBudgetCapsAFloodFromManySources() {
        val v = AddressValidator(secret = secret, globalKemPerSec = 100.0, globalBurst = 100.0, pressureInitialsPerSec = 1e9)
        var admitted = 0
        // 20k distinct sources, one initial each: the per-source bucket never bites, the global budget must.
        repeat(20_000) { i ->
            if (v.onExpensiveInitial(a("10.${i shr 16 and 0xFF}.${i shr 8 and 0xFF}.${i and 0xFF}", 1), false, 1_000) == Admission.ADMIT) admitted++
        }
        assertEquals(100, admitted)
        assertEquals(100L, v.admitted)
    }

    @Test fun underPressureAnUnvalidatedSourceGetsARetryAndAValidatedOneGetsTheKem() {
        val v = AddressValidator(secret = secret)
        v.forcePressure(true)
        val src = a("10.0.0.7", 9)
        assertEquals(Admission.RETRY, v.onExpensiveInitial(src, false, 1_000))
        assertTrue(v.verifyToken(src, v.mintToken(src, 1_000), 1_000))
        assertEquals(Admission.ADMIT, v.onExpensiveInitial(src, true, 1_000))
    }

    @Test fun pressureTripsOnRateAndOnFailureRate() {
        val v = AddressValidator(secret = secret, perSourceBurst = 1e9, perSourcePerSec = 1e9,
            pressureInitialsPerSec = 200.0, pressureFailureMin = 32)
        repeat(300) { v.onExpensiveInitial(a("10.0.0.1", it and 0xFF), false, 1_000) }
        v.onExpensiveInitial(a("10.0.0.1", 1), false, 2_100)   // rolls the window: 300 initials in 1.1 s
        assertTrue(v.underPressure, "rate alone trips pressure")

        val w = AddressValidator(secret = secret, perSourceBurst = 1e9, perSourcePerSec = 1e9,
            pressureInitialsPerSec = 1e9, pressureFailureMin = 32)
        repeat(50) { w.onExpensiveInitial(a("10.0.0.2", it), false, 1_000); w.onFailure(1_000) }
        w.onExpensiveInitial(a("10.0.0.2", 1), false, 2_100)
        assertTrue(w.underPressure, "a 100% failure rate trips pressure even at a low rate")
    }

    @Test fun theSourceTableIsBoundedAndOneFloodDoesNotStarveEveryHonestSource() {
        val v = AddressValidator(secret = secret, sourceSlots = 1024, globalKemPerSec = 1e9, globalBurst = 1e9)
        repeat(200_000) { v.onExpensiveInitial(a("10.1.${it shr 8 and 0xFF}.${it and 0xFF}", 1), true, 1_000) }
        // Nothing to assert about size (the table is a fixed array); what matters is that an honest source that
        // does not collide is still admitted after 200k distinct attackers walked the table.
        var admitted = 0
        repeat(1024) { if (v.onExpensiveInitial(a("192.168.5.5", 1000 + it), true, 1_000) == Admission.ADMIT) admitted++ }
        assertTrue(admitted > 900, "honest sources still admitted after a table walk (got $admitted/1024)")
    }

    @Test fun aResumedInitialSkipsTheKemGateButNotThePerSourceBucket() {
        val v = AddressValidator(secret = secret, perSourceBurst = 3.0, perSourcePerSec = 0.0, globalBurst = 0.0, globalKemPerSec = 0.0)
        v.forcePressure(true)
        val src = a("10.0.0.3", 1)
        assertTrue(v.onCheapInitial(src, 1_000))
        assertTrue(v.onCheapInitial(src, 1_000))
        assertTrue(v.onCheapInitial(src, 1_000))
        assertFalse(v.onCheapInitial(src, 1_000), "per-source bucket still applies to resumption")
    }
}
