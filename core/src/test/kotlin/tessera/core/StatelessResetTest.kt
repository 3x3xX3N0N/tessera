package tessera.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Token derivation for stateless reset (see [StatelessReset]; the endpoint wiring is exercised by
 * `transport/StatelessResetTest`). The token must be deterministic from (secret, connId) so a restarted server
 * recomputes it, and must differ across ids and secrets so it actually identifies a connection.
 */
class StatelessResetTest {
    private val ticketKey = ByteArray(32) { (it * 5 + 1).toByte() }

    @Test fun tokenIsSixteenBytesAndDeterministic() {
        val secret = StatelessReset.deriveSecret(ticketKey)
        assertEquals(16, StatelessReset.TOKEN_LEN)
        val a = StatelessReset.token(secret, 0x11223344)
        val b = StatelessReset.token(secret, 0x11223344)
        assertEquals(StatelessReset.TOKEN_LEN, a.size)
        assertContentEquals(a, b, "same secret + id must give the same token")
    }

    @Test fun deriveSecretIsStableAcrossRestartsSoTokensAre() {
        // A restarted server re-derives the same secret from the same ticket key, hence the same tokens.
        assertContentEquals(StatelessReset.deriveSecret(ticketKey), StatelessReset.deriveSecret(ticketKey))
        val s1 = StatelessReset.deriveSecret(ticketKey)
        val s2 = StatelessReset.deriveSecret(ticketKey)
        assertContentEquals(StatelessReset.token(s1, 7), StatelessReset.token(s2, 7))
    }

    @Test fun tokenDiffersForDifferentIdAndSecret() {
        val secret = StatelessReset.deriveSecret(ticketKey)
        assertFalse(StatelessReset.token(secret, 1).contentEquals(StatelessReset.token(secret, 2)), "different id")
        val other = StatelessReset.deriveSecret(ByteArray(32) { (it * 9).toByte() })
        assertFalse(StatelessReset.token(secret, 1).contentEquals(StatelessReset.token(other, 1)), "different secret")
        // The reset secret must not coincide with the Retry secret from the same ticket key (distinct HKDF labels),
        // or a leak of one would compromise the other.
        assertFalse(secret.contentEquals(RetryToken.deriveSecret(ticketKey)), "reset and retry secrets must differ")
    }

    @Test fun matchesIsTrueOnlyForTheExactToken() {
        val secret = StatelessReset.deriveSecret(ticketKey)
        val t = StatelessReset.token(secret, 0x0A0B0C0D)
        assertTrue(StatelessReset.matches(t, t.copyOf()))
        for (i in t.indices) {
            val forged = t.copyOf().also { it[i] = (it[i] + 1).toByte() }
            assertFalse(StatelessReset.matches(t, forged), "single-bit change at byte $i must not match")
        }
        assertFalse(StatelessReset.matches(t, t.copyOf(t.size - 1)), "wrong length must not match")
    }
}
