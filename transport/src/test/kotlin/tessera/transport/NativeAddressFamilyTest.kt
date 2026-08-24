package tessera.transport

import tessera.native.NativeLib
import tessera.native.PacketBatch
import tessera.native.SockAddrCache
import tessera.native.TxBatch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The native datapath's own sockaddr handling: `PacketDesc`'s `(addr, port, family)` triple, the [SockAddrCache]
 * in front of it, and a real IPv6 datagram exchange on [NativeUdpIo].
 *
 * Runs under both `:transport:test` and `:transport:nativeTest`; skips with a printed reason where `tessera_native`
 * did not load or the host has no IPv6 loopback, so neither task fails for a missing toolchain.
 */
class NativeAddressFamilyTest {

    private fun nativeOrSkip(what: String): Boolean {
        if (NativeLib.available) return true
        println("SKIP $what: tessera_native did not load (${NativeLib.loadError})")
        return false
    }

    private fun ipv6OrSkip(what: String): Boolean {
        if (AddressFamily.ipv6LoopbackAvailable) return true
        println("SKIP $what: no IPv6 loopback on this host")
        return false
    }

    /** Every address the descriptors have to carry survives a write/read round trip, both families, both batch types. */
    @Test fun addressesRoundTripThroughPacketDescriptors() {
        if (!nativeOrSkip("addressesRoundTripThroughPacketDescriptors")) return
        val addrs = buildList {
            add(InetSocketAddress("127.0.0.1", 1))
            add(InetSocketAddress("0.0.0.0", 0))
            add(InetSocketAddress("255.255.255.255", 65_535))
            add(InetSocketAddress("192.0.2.33", 4711))
            add(InetSocketAddress(InetAddress.getByName("::1"), 1))
            add(InetSocketAddress(InetAddress.getByName("::"), 65_535))
            add(InetSocketAddress(InetAddress.getByName("2001:db8::dead:beef"), 4711))
            add(InetSocketAddress(InetAddress.getByName("fe80::1"), 9))
        }
        val rx = PacketBatch(addrs.size, 64)
        addrs.forEachIndexed { i, a -> rx.setAddress(i, a); rx.setLength(i, 8) }
        addrs.forEachIndexed { i, a ->
            val got = assertNotNull(rx.address(i), "descriptor $i lost its address ($a)")
            assertEquals(a, got, "descriptor $i round trip")
            assertEquals(a.address is Inet6Address, got.address is Inet6Address, "family of $a")
        }
        // ...and the cache in front of them must agree with the uncached read for every entry, hits and misses alike.
        val cache = SockAddrCache()
        addrs.forEachIndexed { i, a -> assertEquals(a, rx.address(i, cache), "cached read of descriptor $i") }
        // repeats of the same address must be served without a re-parse
        val before = cache.misses
        repeat(3) { assertEquals(addrs.last(), rx.address(addrs.size - 1, cache)) }
        assertEquals(before, cache.misses, "a repeated address must hit the cache")

        // TxBatch carries the destination the same way
        val tx = TxBatch(addrs.size, addrs.size * 64)
        addrs.forEach { assertTrue(tx.add(ByteBuffer.wrap(ByteArray(16)), it)) }
        addrs.forEachIndexed { i, a -> assertEquals(a, tx.address(i), "tx descriptor $i round trip") }
        assertTrue(!tx.sameDestination(0, 4), "an IPv4 and an IPv6 destination are not the same destination")
    }

    /** A real IPv6 exchange over [NativeUdpIo]: the datagrams arrive intact and the sender is reported as IPv6. */
    @Test fun nativeDatapathCarriesIpv6Datagrams() {
        if (!nativeOrSkip("nativeDatapathCarriesIpv6Datagrams")) return
        if (!ipv6OrSkip("nativeDatapathCarriesIpv6Datagrams")) return
        Datapath.open(InetSocketAddress("::1", 0), native = true, name = "v6-rx").use { rx ->
            assertEquals("native", rx.implementation)
            assertTrue(rx.localAddress.address is Inet6Address, "the rx socket must be IPv6, was ${rx.localAddress}")
            val q = ConcurrentLinkedQueue<Pair<InetSocketAddress, ByteArray>>()
            rx.onDatagram { buf, from -> q.add(from to ByteArray(buf.remaining()).also { buf.get(it) }) }
            Datapath.open(InetSocketAddress("::1", 0), native = true, name = "v6-tx").use { tx ->
                val payloads = (0 until 8).map { i -> ByteArray(100 + i) { j -> (i * 31 + j).toByte() }.also { it[0] = 0x80.toByte() } }
                payloads.forEach { tx.send(ByteBuffer.wrap(it), rx.localAddress) }
                val deadline = System.nanoTime() + 3_000_000_000L
                while (q.size < payloads.size && System.nanoTime() < deadline) Thread.sleep(1)
                val got = q.toList()
                assertEquals(payloads.size, got.size, "all datagrams over IPv6; ${tx.stats}")
                payloads.forEachIndexed { i, p -> assertContentEquals(p, got[i].second, "IPv6 datagram $i") }
                assertTrue(got.first().first.address is Inet6Address, "the sender must come back as IPv6, was ${got.first().first}")
                assertEquals(tx.localAddress.port, got.first().first.port)
            }
        }
    }

    /**
     * What the native `::` socket actually is on this host, recorded rather than assumed: Rust's `UdpSocket::bind`
     * does not touch `IPV6_V6ONLY`, so it is the OS default (dual-stack on Linux's usual `bindv6only=0`, v6-only on
     * Windows). Whatever it is, [AddressFamily.defaultBind] and [TesseraClient.isDualStack] must agree with it.
     */
    @Test fun nativeWildcardDualStackIsReportedHonestly() {
        if (!nativeOrSkip("nativeWildcardDualStackIsReportedHonestly")) return
        if (!ipv6OrSkip("nativeWildcardDualStackIsReportedHonestly")) return
        val dual = NativeUdpIo.dualStackCapable
        println("native :: socket on this host is ${if (dual) "dual-stack" else "IPv6-only"} (${NativeLib.os})")
        val prev = System.getProperty(Datapath.NATIVE_PROPERTY)
        System.setProperty(Datapath.NATIVE_PROPERTY, "on")
        try {
            assertEquals(dual, AddressFamily.wildcardIsDualStack())
            TesseraClient(InetSocketAddress("::", 0)).use { c -> assertEquals(dual, c.isDualStack) }
            val def = AddressFamily.defaultBind()
            assertEquals(dual, def.address is Inet6Address, "the native default bind must not claim a reach it does not have: $def")
            TesseraClient(InetSocketAddress("0.0.0.0", 0)).use { c -> assertTrue(!c.isDualStack) }
        } finally { if (prev == null) System.clearProperty(Datapath.NATIVE_PROPERTY) else System.setProperty(Datapath.NATIVE_PROPERTY, prev) }
    }

    /** The JDK channel path is dual-stack on every supported platform; that is the assumption the default rests on. */
    @Test fun channelWildcardIsDualStack() {
        if (!ipv6OrSkip("channelWildcardIsDualStack")) return
        val prev = System.getProperty(Datapath.NATIVE_PROPERTY)
        System.setProperty(Datapath.NATIVE_PROPERTY, "off")
        try {
            TesseraClient(InetSocketAddress("::", 0)).use { c ->
                assertTrue(c.isDualStack, "a JDK DatagramChannel bound to :: must be dual-stack")
                assertTrue(c.localAddress.address is Inet6Address)
            }
            TesseraClient(InetSocketAddress("127.0.0.1", 0)).use { c ->
                assertTrue(!c.isDualStack)
                assertTrue(c.localAddress.address is Inet4Address)
            }
        } finally { if (prev == null) System.clearProperty(Datapath.NATIVE_PROPERTY) else System.setProperty(Datapath.NATIVE_PROPERTY, prev) }
    }
}
