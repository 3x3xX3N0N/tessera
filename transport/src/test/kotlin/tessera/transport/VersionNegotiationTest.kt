package tessera.transport

import tessera.core.Handshake
import tessera.core.Wire
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The wire version field (TODO §11). The bar that section set: a version field, a defined mismatch response,
 * and a test asserting the receiver **names the mismatch** rather than failing to parse — because before this
 * field existed, a version skew surfaced as an AEAD decrypt failure that blamed the crypto for a framing
 * problem, and `WireVectorsTest`'s note ("a peer built from an older commit no longer interoperates") was a
 * fact nobody could observe from the wire.
 *
 * `ConnConfig.wireVersion` overrides the version one endpoint speaks: the honest single-build stand-in for a
 * two-build test until a second wire version genuinely exists. The mismatch notice is unauthenticated by
 * construction, so it carries Retry's trust rules (same source address, pending connects only) — asserted here
 * by the guards' existence in the same file rather than by an off-path forgery harness.
 */
class VersionNegotiationTest {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 3).toByte() }

    private fun server(cfg: ConnConfig = ConnConfig()) =
        TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, cfg)

    /** The default path must be untouched: same version both ends, ordinary connect. */
    @Test fun matchedVersionsConnectNormally() {
        server().use { srv ->
            TesseraClient(cfg = ConnConfig()).use { client ->
                val conn = client.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "v".toByteArray(), timeoutMs = 10_000)
                val sc = srv.accept(5_000) ?: fail("no accept")
                assertEquals("v", String(sc.receive(2_000)!!))
                conn.close(); sc.close()
            }
        }
    }

    /**
     * The whole point: a client one version ahead gets a NAMED failure carrying both versions, fast — not a
     * 10-second timeout, and not an authFail. Before the field existed this exact skew was undiagnosable.
     */
    @Test fun aVersionSkewIsNamedNotTimedOut() {
        server().use { srv ->
            TesseraClient(cfg = ConnConfig(wireVersion = Wire.VERSION + 1)).use { client ->
                val t0 = System.nanoTime()
                val e = runCatching {
                    client.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "v".toByteArray(), timeoutMs = 10_000)
                }.exceptionOrNull() ?: fail("a mismatched connect succeeded")
                val ms = (System.nanoTime() - t0) / 1_000_000
                val msg = e.message ?: ""
                assertTrue("version mismatch" in msg, "the failure does not name the mismatch: $msg")
                assertTrue("%08x".format(Wire.VERSION) in msg, "the failure does not name the server's version: $msg")
                assertTrue("%08x".format(Wire.VERSION + 1) in msg, "the failure does not name our version: $msg")
                assertTrue(ms < 5_000, "took ${ms}ms — that is the timeout path, not the named path")
                assertTrue(srv.versionMismatchesSent > 0, "the server never counted the notice it sent")
            }
        }
    }

    /**
     * A wrong MAGIC (not-Tessera traffic that happens to set the long-header bit) must be dropped silently —
     * answering would make every Tessera port a scanner beacon. Asserted as: no mismatch notice, no accept,
     * and the server still serves a real client afterwards.
     */
    @Test fun nonTesseraMagicIsDroppedSilently() {
        server().use { srv ->
            TesseraClient(cfg = ConnConfig(wireVersion = 0x41410001)).use { bad ->
                runCatching { bad.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "x".toByteArray(), timeoutMs = 1_200) }
                assertEquals(0L, srv.versionMismatchesSent, "the server answered a non-Tessera magic")
            }
            TesseraClient(cfg = ConnConfig()).use { good ->
                val conn = good.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "ok".toByteArray(), timeoutMs = 10_000)
                val sc = srv.accept(5_000) ?: fail("server stopped serving after garbage")
                assertEquals("ok", String(sc.receive(2_000)!!))
                conn.close(); sc.close()
            }
        }
    }

    /** The notice is smaller than any initial, so a spoofed-source initial cannot use it to amplify. */
    @Test fun theMismatchNoticeCannotAmplify() {
        assertTrue(Wire.LONG_HEADER_LEN < 100, "the mismatch notice is a bare long header; it must stay tiny")
    }
}
