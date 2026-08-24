package tessera.transport

import tessera.core.AddressValidator
import tessera.core.Handshake
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F7b — resource exhaustion on the un-authenticated initial path. See `core/AddressValidation.kt` for the design
 * and `docs/TEST-PLAN.md` for what each of these is meant to prove.
 */
class AddressValidationEndpointTest {
    private val ticketKey = ByteArray(32) { (it * 7).toByte() }

    private fun server(v: AddressValidator? = null, keys: Handshake.StaticKeys = Handshake.generate()) =
        keys to TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey, validator = v)

    /** A 1.2 KB initial that is pure garbage past the flags byte: it costs the server a KEM if it is admitted. */
    private fun garbage(rnd: Random): ByteArray = ByteArray(1200).also { rnd.nextBytes(it); it[0] = 0x80.toByte() }

    @Test fun anHonestZeroRttConnectCostsNoExtraRoundTripWhenTheServerIsIdle() {
        val (keys, s) = server()
        s.use { srv ->
            TesseraClient().use { c ->
                val conn = c.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "hello".toByteArray())
                val sc = srv.accept(5_000) ?: error("no accept")
                assertEquals("hello", String(sc.receive(2_000)!!))
                assertEquals(0L, srv.retriesSent, "an idle server must not spend a round trip on Retry")
                assertEquals(0L, c.retriesAnswered)
                assertEquals(1L, srv.validator.admitted)
                conn.close(); sc.close()
            }
        }
    }

    @Test fun underPressureAnHonestClientStillConnectsAtTheCostOfOneRetry() {
        val (keys, s) = server()
        s.use { srv ->
            srv.validator.forcePressure(true)
            TesseraClient().use { c ->
                val conn = c.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "hello".toByteArray())
                val sc = srv.accept(5_000) ?: error("no accept")
                assertEquals("hello", String(sc.receive(2_000)!!), "0-RTT payload survives the Retry")
                assertEquals(1L, srv.retriesSent)
                assertEquals(1L, c.retriesAnswered)
                assertEquals(1L, srv.validator.admitted, "exactly one KEM, and only after the token")
                conn.close(); sc.close()
            }
        }
    }

    @Test fun aSpoofedSourceThatNeverReadsTheReplyNeverReachesTheKem() {
        val (_, s) = server()
        s.use { srv ->
            srv.validator.forcePressure(true)
            val rnd = Random(7)
            DatagramSocket().use { sock ->
                // Stands in for a spoofed source: it sends and never processes what comes back, so it can never
                // present a token. Every one of these initials must be answered with a Retry and nothing else.
                repeat(2_000) { sock.send(DatagramPacket(garbage(rnd), 1200, InetAddress.getLoopbackAddress(), srv.localAddress.port)) }
                Thread.sleep(300)
            }
            assertEquals(0L, srv.validator.admitted, "no KEM for an address that never proved itself")
            assertTrue(srv.retriesSent > 0)
        }
    }

    @Test fun aFloodOfGarbageInitialsCannotDriveKemOpsPastTheBudget() {
        val budget = AddressValidator(perSourcePerSec = 6.0, perSourceBurst = 12.0,
            globalKemPerSec = 50.0, globalBurst = 50.0, pressureInitialsPerSec = 1e9)
        val (_, s) = server(budget)
        s.use { srv ->
            val rnd = Random(11)
            DatagramSocket().use { sock ->
                repeat(4_000) { sock.send(DatagramPacket(garbage(rnd), 1200, InetAddress.getLoopbackAddress(), srv.localAddress.port)) }
                Thread.sleep(500)
            }
            // Not under pressure (so 0-RTT is untouched), yet the per-source bucket alone holds 4000 initials to a
            // dozen-odd KEM operations: burst + refill over the ~1 s the flood and drain take.
            assertTrue(srv.validator.admitted <= 24, "KEM ops ${srv.validator.admitted} exceeded the per-source budget")
            // The rest are refused without asymmetric crypto: a 31-byte Retry while the Retry budget lasts, then
            // silence. Either way the KEM is never reached.
            assertTrue(srv.validator.retried + srv.validator.dropped > 3_000,
                "retried=${srv.validator.retried} dropped=${srv.validator.dropped}")
        }
    }

    @Test fun aRetriedInitialFromTheWrongAddressIsNotValidated() {
        val (keys, s) = server()
        s.use { srv ->
            srv.validator.forcePressure(true)
            // A token minted for one address must not admit another; the server would then be validating nothing.
            val a = InetSocketAddress("127.0.0.1", 1111)
            val b = InetSocketAddress("127.0.0.1", 2222)
            val tok = srv.validator.mintToken(a, System.currentTimeMillis())
            assertTrue(srv.validator.verifyToken(a, tok, System.currentTimeMillis()))
            assertTrue(!srv.validator.verifyToken(b, tok, System.currentTimeMillis()))
            // ...and an honest client still gets through on the real path.
            TesseraClient().use { c ->
                val conn = c.connect(srv.localAddress, keys.x25519Pub, keys.kemPub, "x".toByteArray())
                (srv.accept(5_000) ?: error("no accept")).close(); conn.close()
            }
        }
    }
}
