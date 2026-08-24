package tessera.transport

import tessera.core.Handshake
import java.net.Inet6Address
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * The EndpointTest scenarios over one address family, so both loopbacks are exercised: [Ipv4EndpointTest] over
 * `127.0.0.1` and [Ipv6EndpointTest] over `::1`. Whichever datapath the running task selected
 * (`:transport:test` = auto, `:transport:nativeTest` = native) is the one under test, so the native sockaddr
 * handling gets the same coverage.
 *
 * A host with no IPv6 loopback (IPv6 compiled out, `-Djava.net.preferIPv4Stack=true`, some containers) skips the
 * IPv6 half with a printed reason rather than failing: see [enabled].
 */
abstract class EndpointFamilyScenarios(private val host: String) {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 3).toByte() }

    /** IPv6 scenarios need an IPv6 loopback; the IPv4 ones always run. */
    private val enabled: Boolean
        get() {
            if (host == "127.0.0.1") return true
            if (AddressFamily.ipv6LoopbackAvailable) return true
            println("SKIP ${javaClass.simpleName}: no IPv6 loopback on this host (::1 could not be bound) - IPv6 scenarios not run")
            return false
        }

    private fun server(cfg: ConnConfig = ConnConfig()) = TesseraServer(InetSocketAddress(host, 0), keys, ticketKey, cfg)

    /**
     * The client under test: no explicit bind, i.e. exactly what a caller gets by default. Where the selected
     * datapath's wildcard is not dual-stack (a v6-only native socket) the default is `0.0.0.0` by design, and the
     * IPv6 half binds `::` explicitly - the point being tested there is the datapath, not the default.
     */
    private fun client(): TesseraClient {
        val c = TesseraClient()
        if (host == "::1" && c.localAddress.address !is Inet6Address) {
            c.close()
            println("NOTE ${javaClass.simpleName}: the default bind is IPv4 on this datapath; binding :: explicitly")
            return TesseraClient(InetSocketAddress("::", 0))
        }
        return c
    }

    private fun assertFamily(a: InetSocketAddress) {
        val v6 = a.address is Inet6Address
        assertEquals(host == "::1", v6, "endpoint $a should be on the ${if (host == "::1") "IPv6" else "IPv4"} loopback")
    }

    @Test fun freshConnectDeliversZeroRttPayloadAndEchoes() {
        if (!enabled) return
        server().use { s -> client().use { c ->
            assertFamily(s.localAddress)
            val first = "GET /index 0-rtt".toByteArray()
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, first)
            val sc = assertNotNull(s.accept(2_000))
            assertContentEquals(first, sc.receive(1_000))
            assertNotNull(conn.ticket, "fresh connect must yield a resumption ticket")
            assertEquals(sc.connId, conn.connId)
            sc.send("pong".toByteArray())
            assertContentEquals("pong".toByteArray(), conn.receive(1_000))
        } }
    }

    @Test fun resumeWithTicketFromFirstConnection() {
        if (!enabled) return
        server().use { s -> client().use { c ->
            val c1 = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, byteArrayOf(1))
            val s1 = assertNotNull(s.accept(2_000)); s1.receive(1_000)
            val ticket = assertNotNull(c1.ticket); val secret = c1.resumptionSecret
            c1.close(); s1.close()
            val big = ByteArray(1100) { it.toByte() }
            val c2 = c.resume(s.localAddress, ticket, secret, big)
            val s2 = assertNotNull(s.accept(2_000))
            assertContentEquals(big, s2.receive(1_000))
            s2.send("resumed-ok".toByteArray())
            assertContentEquals("resumed-ok".toByteArray(), c2.receive(1_000))
        } }
    }

    /** 10 KB: fragments over several datagrams, so the per-datagram address handling is exercised in both directions. */
    @Test fun tenKilobyteMessageRoundTrips() {
        if (!enabled) return
        server().use { s -> client().use { c ->
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, byteArrayOf())
            val sc = assertNotNull(s.accept(2_000)); assertContentEquals(byteArrayOf(), sc.receive(1_000))
            val msg = ByteArray(10 * 1024) { (it * 31 + 7).toByte() }
            conn.send(msg)
            val got = assertNotNull(sc.receive(2_000)); assertContentEquals(msg, got)
            sc.send(got)
            assertContentEquals(msg, conn.receive(2_000))
        } }
    }

    @Test fun tenPercentLossAllMessagesArrive() {
        if (!enabled) return
        server().use { s -> client().use { c ->
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, byteArrayOf())
            val sc = assertNotNull(s.accept(2_000)); sc.receive(1_000)
            conn.lossSim = 0.10
            val n = 1000
            val seen = BooleanArray(n)
            val rx = Thread {
                var got = 0; val deadline = System.nanoTime() + 15_000_000_000L
                while (got < n && System.nanoTime() < deadline) {
                    val m = sc.receive(50) ?: continue
                    val i = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
                    if (i < n && !seen[i]) { seen[i] = true; got++ }
                }
            }.apply { start() }
            repeat(n) { i -> conn.send(ByteArray(64).also { it[0] = (i shr 8).toByte(); it[1] = i.toByte() }); busySpin(300) }
            rx.join()
            val st = conn.stats
            assertEquals(0, seen.count { !it }, "missing messages with 10% loss over $host; stats=$st")
            assertTrue(st.repairsReactive + st.repairsProactive > 0)
        } }
    }

    private fun busySpin(us: Long) { val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) Thread.onSpinWait() }
}

/** The core endpoint scenarios over the IPv4 loopback, with the default (unspecified) client bind. */
class Ipv4EndpointTest : EndpointFamilyScenarios("127.0.0.1")

/**
 * The same over the IPv6 loopback. This is the case the suite had no coverage for at all, and the case
 * `docs/LIVE-TEST.md` recommends for two-machine runs (IPv6 avoids NAT).
 */
class Ipv6EndpointTest : EndpointFamilyScenarios("::1")

/**
 * The address-family behaviour of the bind itself: the default, the dual-stack listener, and the mismatch error.
 *
 * [mode], when non-null, is forced into `-Dtessera.native` for the duration of each test. [ChannelAddressFamilyTest]
 * pins the JDK channel path, where `::` is dual-stack on every platform, so the dual-stack scenarios really run in
 * both Gradle tasks; [AddressFamilyTest] leaves the running task's own selection alone.
 */
abstract class AddressFamilyScenarios(private val mode: String?) {
    private val keys = Handshake.generate()
    private val ticketKey = ByteArray(32) { (it * 5).toByte() }
    private var prev: String? = null

    @BeforeTest fun pin() {
        if (mode == null) return
        prev = System.getProperty(Datapath.NATIVE_PROPERTY); System.setProperty(Datapath.NATIVE_PROPERTY, mode)
    }

    @AfterTest fun unpin() {
        if (mode == null) return
        if (prev == null) System.clearProperty(Datapath.NATIVE_PROPERTY) else System.setProperty(Datapath.NATIVE_PROPERTY, prev)
    }

    private fun ipv6OrSkip(what: String): Boolean {
        if (AddressFamily.ipv6LoopbackAvailable) return true
        println("SKIP $what: no IPv6 loopback on this host (::1 could not be bound)")
        return false
    }

    /** The regression itself: a default client must reach an IPv6 peer. Before the fix this timed out after 10 s. */
    @Test fun defaultClientReachesAnIpv6Server() {
        if (!ipv6OrSkip("defaultClientReachesAnIpv6Server")) return
        if (!dualStackWildcardAvailable()) {
            println("SKIP defaultClientReachesAnIpv6Server: the selected datapath's :: socket is IPv6-only, so the default bind is IPv4 here")
            return
        }
        TesseraServer(InetSocketAddress("::1", 0), keys, ticketKey).use { s ->
            TesseraClient().use { c ->
                assertTrue(s.localAddress.address is Inet6Address)
                val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, "v6".toByteArray(), timeoutMs = 3_000)
                val sc = assertNotNull(s.accept(2_000))
                assertContentEquals("v6".toByteArray(), sc.receive(1_000))
                conn.close()
            }
        }
    }

    /** ...and must still reach an IPv4 peer from the same default socket (the v4-mapped path of a dual-stack bind). */
    @Test fun defaultClientStillReachesAnIpv4Server() {
        TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey).use { s ->
            TesseraClient().use { c ->
                val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, "v4".toByteArray(), timeoutMs = 3_000)
                val sc = assertNotNull(s.accept(2_000))
                assertContentEquals("v4".toByteArray(), sc.receive(1_000))
                conn.close()
            }
        }
    }

    /** One default client, both families, one socket - what makes the wildcard default preferable to a deferred bind. */
    @Test fun oneDefaultClientServesBothFamilies() {
        if (!ipv6OrSkip("oneDefaultClientServesBothFamilies")) return
        if (!dualStackWildcardAvailable()) {
            println("SKIP oneDefaultClientServesBothFamilies: the selected datapath's :: socket is IPv6-only")
            return
        }
        TesseraServer(InetSocketAddress("::1", 0), keys, ticketKey).use { s6 ->
        TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey).use { s4 ->
            TesseraClient().use { c ->
                c.connect(s6.localAddress, keys.x25519Pub, keys.kemPub, "a".toByteArray(), timeoutMs = 3_000)
                c.connect(s4.localAddress, keys.x25519Pub, keys.kemPub, "b".toByteArray(), timeoutMs = 3_000)
                assertContentEquals("a".toByteArray(), assertNotNull(s6.accept(2_000)).receive(1_000))
                assertContentEquals("b".toByteArray(), assertNotNull(s4.accept(2_000)).receive(1_000))
            }
        } }
    }

    /** A `::` listener accepts an IPv4-only client; the JDK reports the peer back as a plain IPv4 address. */
    @Test fun dualStackListenerAcceptsIpv4Client() {
        if (!ipv6OrSkip("dualStackListenerAcceptsIpv4Client")) return
        if (!dualStackWildcardAvailable()) {
            println("SKIP dualStackListenerAcceptsIpv4Client: the selected datapath's :: socket is IPv6-only on this host")
            return
        }
        TesseraServer(InetSocketAddress("::", 0), keys, ticketKey).use { s ->
            val port = s.localAddress.port
            TesseraClient(InetSocketAddress("0.0.0.0", 0)).use { c ->
                val conn = c.connect(InetSocketAddress("127.0.0.1", port), keys.x25519Pub, keys.kemPub, "mapped".toByteArray(), timeoutMs = 3_000)
                val sc = assertNotNull(s.accept(2_000), "the :: listener must accept an IPv4 client")
                assertContentEquals("mapped".toByteArray(), sc.receive(1_000))
                sc.send("ok".toByteArray())
                assertContentEquals("ok".toByteArray(), conn.receive(1_000))
            }
        }
    }

    /** An explicit IPv4 bind + an IPv6 destination fails immediately with a diagnostic, not after the retransmit train. */
    @Test fun ipv4ClientToIpv6PeerFailsFastWithANamedDiagnostic() {
        if (!ipv6OrSkip("ipv4ClientToIpv6PeerFailsFastWithANamedDiagnostic")) return
        TesseraServer(InetSocketAddress("::1", 0), keys, ticketKey).use { s ->
            TesseraClient(InetSocketAddress("127.0.0.1", 0)).use { c ->
                val t0 = System.nanoTime()
                val e = assertFailsWith<IllegalArgumentException> {
                    c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, byteArrayOf(), timeoutMs = 10_000)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue(ms < 1_000, "the mismatch must be reported immediately, took ${ms}ms")
                val m = assertNotNull(e.message)
                assertTrue(m.contains("address family mismatch"), m)
                assertTrue(m.contains("IPv4") && m.contains("IPv6"), "the message must name both families: $m")
                assertTrue(m.contains(s.localAddress.toString()), "the message must name the destination: $m")
            }
        }
        // resume() goes through the same check
        TesseraServer(InetSocketAddress("::1", 0), keys, ticketKey).use { s ->
            TesseraClient(InetSocketAddress("127.0.0.1", 0)).use { c ->
                assertFailsWith<IllegalArgumentException> { c.resume(s.localAddress, ByteArray(64), ByteArray(32), byteArrayOf()) }
            }
        }
    }

    /** The reverse mismatch, only meaningful where the datapath's IPv6 sockets are v6-only. */
    @Test fun v6OnlyClientToIpv4PeerFailsFastWhereTheSocketIsV6Only() {
        if (!ipv6OrSkip("v6OnlyClientToIpv4PeerFailsFastWhereTheSocketIsV6Only")) return
        if (dualStackWildcardAvailable()) {
            println("SKIP v6OnlyClientToIpv4PeerFailsFast: :: is dual-stack on the selected datapath, so there is no mismatch to report")
            return
        }
        TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ticketKey).use { s ->
            TesseraClient(InetSocketAddress("::", 0)).use { c ->
                val e = assertFailsWith<IllegalArgumentException> {
                    c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, byteArrayOf(), timeoutMs = 10_000)
                }
                assertTrue(assertNotNull(e.message).contains("address family mismatch"), e.message!!)
            }
        }
    }

    /** [AddressFamily.defaultBind] is the wildcard of a family that can reach both, never a loopback literal. */
    @Test fun defaultBindIsAWildcard() {
        val b = AddressFamily.defaultBind()
        assertEquals(0, b.port)
        assertTrue(assertNotNull(b.address).isAnyLocalAddress, "the default bind must be a wildcard, was $b")
        if (AddressFamily.ipv6Available && dualStackWildcardAvailable())
            assertTrue(b.address is Inet6Address, "on a dual-stack host the default bind should be ::, was $b")
    }

    /** Whether the datapath currently selected gives a dual-stack `::` socket. */
    private fun dualStackWildcardAvailable(): Boolean =
        TesseraClient(InetSocketAddress("::", 0)).use { it.isDualStack }
}

/** The address-family scenarios on whatever datapath the running Gradle task selected. */
class AddressFamilyTest : AddressFamilyScenarios(null)

/** The same pinned to [ChannelUdpIo], where the `::` wildcard is dual-stack on every supported platform. */
class ChannelAddressFamilyTest : AddressFamilyScenarios("off")
