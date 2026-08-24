package tessera.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.util.Arrays
import java.nio.ByteBuffer

/**
 * Stateless reset (RFC 9000 §10.3 shape). The complement to CONNECTION_CLOSE ([Frame.Close], 0x08): that frame is the
 * *both-sides-have-keys* teardown; a stateless reset is the *lost-keys* case — a restarted or crashed server that no
 * longer holds a connection's keys and so cannot authenticate any frame, not even a CLOSE. Without it the client keeps
 * retransmitting into a black hole until its idle timeout ([tessera.transport.ConnConfig.idleTimeoutMs], 10 s).
 *
 * The token is a truncated HMAC-SHA256 over the 4 big-endian bytes of the server-assigned short connection id, under a
 * secret the server derives from its ticket key ([deriveSecret]). Two properties make it work statelessly:
 *
 *  - **The secret survives a restart.** It comes from the operator-provided ticket key (the same key that already
 *    outlives a restart for resumption tickets and Retry tokens), so a restarted server recomputes the same tokens.
 *  - **The 4-byte connId is the only per-connection input a restarted server has.** It rides in the clear on every
 *    short packet — header protection masks the packet number and two flag bits, never the connId — so the server can
 *    recompute the token for a connection it has otherwise entirely forgotten. That id is the one the *client* addresses
 *    the server with (the server's assigned [tessera.core.ConnParams.shortConnId]); after a restart the server sees
 *    exactly that id on the client's retransmits.
 *
 * The token reaches the client confidentially at handshake — appended to the already-encrypted handshake reply body
 * (see `TesseraConnection.buildHandshakeReply`) — so an off-path observer never learns it and cannot forge a reset. It
 * appears in the clear only inside a reset packet, which is otherwise random, so an observer cannot even link a reset
 * back to the connId that produced it.
 */
object StatelessReset {
    /** Token length, on the wire in the handshake reply and at the tail of a reset packet. Matches RFC 9000's 16 bytes. */
    const val TOKEN_LEN = 16

    /**
     * Server reset secret, derived from the ticket key so tokens survive a restart that keeps the same ticket key —
     * the same rationale as [RetryToken.deriveSecret], under a distinct label so the two secrets never coincide.
     */
    fun deriveSecret(ticketKey: ByteArray): ByteArray {
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ticketKey, null, "tessera stateless-reset".toByteArray())) }
            .generateBytes(out, 0, out.size)
        return out
    }

    /**
     * The reset token for [shortConnId]: the first [TOKEN_LEN] bytes of HMAC-SHA256(secret, connId as 4 big-endian
     * bytes). Deterministic — the 4-byte connId is the only per-connection input the restarted server has, so it is the
     * only input here besides the server-held secret.
     */
    fun token(secret: ByteArray, shortConnId: Int): ByteArray {
        val h = HMac(SHA256Digest()).apply { init(KeyParameter(secret)) }
        val id = ByteBuffer.allocate(4).putInt(shortConnId).array()
        h.update(id, 0, id.size)
        val full = ByteArray(h.macSize)
        h.doFinal(full, 0)
        return full.copyOf(TOKEN_LEN)
    }

    /**
     * Constant-time equality of a [TOKEN_LEN]-byte [expected] token against a [candidate] (e.g. the trailing bytes of a
     * received packet). Constant-time so a peer cannot learn a token by timing a comparison against a near-miss guess.
     */
    fun matches(expected: ByteArray, candidate: ByteArray): Boolean = Arrays.constantTimeAreEqual(expected, candidate)
}
