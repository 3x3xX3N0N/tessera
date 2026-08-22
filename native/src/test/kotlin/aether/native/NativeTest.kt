package aether.native

import aether.core.GF256
import java.io.File
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class NativeTest {
    private fun requireNative() = assertTrue(NativeLib.available, "aether_native failed to load: ${NativeLib.loadError}")

    private fun MemorySegment.load(bytes: ByteArray) = MemorySegment.copy(bytes, 0, this, JAVA_BYTE, 0L, bytes.size)

    @Test
    fun libraryLoadsAndReportsVersion() {
        requireNative()
        assertEquals(0x000100, NativeLib.version, "0.1.0 encoded as major<<16 | minor<<8 | patch")
        assertEquals(PacketDesc.SIZE, NativeLib.packetDescSize, "Kotlin StructLayout must match the #[repr(C)] struct")
        assertEquals(40L, PacketDesc.SIZE)
        println("aether_native ${NativeLib.versionString} on ${NativeLib.platformDir}: gf256 kernel=${Gf256Native.implementation}, lib=${NativeLib.libraryPath}")
    }

    @Test
    fun gf256NativeMatchesKotlinScalar() {
        requireNative()
        val rnd = Random(0x5EED)
        for (len in intArrayOf(1, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 1200, 4096)) {
            // every coefficient on the symbol size that matters (1200) and on a tail-only size (17)
            val coefficients = if (len == 1200 || len == 17) (0..255).toList() else List(8) { rnd.nextInt(256) } + listOf(0, 1, 2, 255)
            val src = ByteArray(len).also { rnd.nextBytes(it) }
            for (c in coefficients) {
                val dst = ByteArray(len).also { rnd.nextBytes(it) }
                val expected = dst.copyOf().also { GF256.mulAddInto(it, src, c) }

                Arena.ofConfined().use { arena ->
                    val d = arena.allocate(len.toLong()).also { it.load(dst) }
                    val s = arena.allocate(len.toLong()).also { it.load(src) }
                    Gf256Native.mulAddInto(d, s, len.toLong(), c)
                    assertContentEquals(expected, d.toArray(JAVA_BYTE), "segment API len=$len c=$c")
                }
                val viaArray = dst.copyOf().also { Gf256Native.mulAddInto(it, src, c) }
                assertContentEquals(expected, viaArray, "ByteArray API len=$len c=$c")
            }
        }
    }

    @Test
    fun gf256HandlesUnalignedSlicesAndRejectsOverlap() {
        requireNative()
        val rnd = Random(99)
        Arena.ofConfined().use { arena ->
            val big = arena.allocate(4096, 64)
            for (off in intArrayOf(1, 3, 7, 13, 17, 31)) {
                val len = 1200
                val d = big.asSlice(off.toLong(), len.toLong())
                val s = big.asSlice((off + 2048).toLong(), len.toLong())
                val dst = ByteArray(len).also { rnd.nextBytes(it) }
                val src = ByteArray(len).also { rnd.nextBytes(it) }
                d.load(dst); s.load(src)
                val c = 1 + rnd.nextInt(255)
                val expected = dst.copyOf().also { GF256.mulAddInto(it, src, c) }
                Gf256Native.mulAddInto(d, s, len.toLong(), c)
                assertContentEquals(expected, d.toArray(JAVA_BYTE), "unaligned offset $off c=$c")
            }
            assertFailsWith<IllegalArgumentException> { Gf256Native.mulAddInto(big, big.asSlice(8), 100, 3) }
            assertFailsWith<IllegalArgumentException> { Gf256Native.mulAddInto(MemorySegment.ofArray(ByteArray(16)), big, 16, 3) }
        }
    }

    @Test
    fun udpLoopbackBatchOf64RoundTrips() {
        requireNative()
        val rnd = Random(64)
        NativeUdp("127.0.0.1", 0).use { tx ->
            NativeUdp("127.0.0.1", 0).use { rx ->
                val n = 64
                val size = 1200
                val to = InetSocketAddress(InetAddress.getByName("127.0.0.1"), rx.localPort)
                val payloads = Array(n) { i -> ByteArray(size).also { rnd.nextBytes(it); it[0] = i.toByte() } }
                val txBatch = PacketBatch(n, 2048)
                val rxBatch = PacketBatch(n, 2048)
                for (i in 0 until n) txBatch.put(i, payloads[i], to)

                assertEquals(n, tx.sendBatch(txBatch, n), "all $n datagrams should be accepted by one sendBatch")

                val got = HashMap<Int, ByteArray>()
                val deadline = System.nanoTime() + 3_000_000_000L
                var calls = 0
                while (got.size < n && System.nanoTime() < deadline) {
                    val r = rx.recvBatch(rxBatch, timeoutMs = 500)
                    calls++
                    for (i in 0 until r) {
                        assertEquals(size, rxBatch.length(i))
                        val from = assertNotNull(rxBatch.address(i), "sender address must be filled in")
                        assertEquals(tx.localPort, from.port)
                        assertEquals("127.0.0.1", from.address.hostAddress)
                        val p = rxBatch.get(i)
                        got[p[0].toInt() and 0xFF] = p
                    }
                }
                assertEquals(n, got.size, "received ${got.size}/$n datagrams")
                for (i in 0 until n) assertContentEquals(payloads[i], got[i], "payload $i")
                println("UDP loopback: $n x $size B sent in 1 sendBatch, received in $calls recvBatch call(s)")

                assertEquals(0, rx.recvBatch(rxBatch, timeoutMs = 0), "nothing left; timeout 0 must not block")
                assertFailsWith<IOException>("binding an in-use port must fail") { NativeUdp("127.0.0.1", rx.localPort) }
            }
        }
    }

    @Test
    fun udpIpv6LoopbackRoundTrips() {
        requireNative()
        val tx = try {
            NativeUdp("::1", 0)
        } catch (e: IOException) {
            println("IPv6 loopback unavailable (${e.message}); skipping")
            return
        }
        tx.use {
            NativeUdp("::1", 0).use { rx ->
                val to = InetSocketAddress(InetAddress.getByName("::1"), rx.localPort)
                val batch = PacketBatch(4, 256)
                for (i in 0 until 4) batch.put(i, ByteArray(100 + i) { (i * 3 + it).toByte() }, to)
                assertEquals(4, tx.sendBatch(batch, 4))
                val rxBatch = PacketBatch(4, 256)
                var got = 0
                val deadline = System.nanoTime() + 3_000_000_000L
                val lengths = ArrayList<Int>()
                while (got < 4 && System.nanoTime() < deadline) {
                    val r = rx.recvBatch(rxBatch, timeoutMs = 500)
                    for (i in 0 until r) {
                        lengths += rxBatch.length(i)
                        val from = assertNotNull(rxBatch.address(i))
                        assertTrue(from.address is Inet6Address, "sender should be IPv6, was $from")
                        assertEquals(tx.localPort, from.port)
                    }
                    got += r
                }
                assertEquals(listOf(100, 101, 102, 103), lengths.sorted())
            }
        }
    }

    @Test
    fun gsoSendDeliversSegments() {
        requireNative()
        NativeUdp("127.0.0.1", 0).use { tx ->
            NativeUdp("127.0.0.1", 0).use { rx ->
                val to = InetSocketAddress(InetAddress.getByName("127.0.0.1"), rx.localPort)
                val total = 1000
                val data = ByteArray(total) { it.toByte() }
                Arena.ofConfined().use { arena ->
                    val seg = arena.allocate(total.toLong()).also { it.load(data) }
                    assertEquals(total, tx.sendGso(seg, total, 400, to))
                }
                val batch = PacketBatch(8, 2048)
                val pieces = ArrayList<ByteArray>()
                val deadline = System.nanoTime() + 3_000_000_000L
                while (pieces.size < 3 && System.nanoTime() < deadline) {
                    val r = rx.recvBatch(batch, timeoutMs = 500)
                    for (i in 0 until r) pieces += batch.get(i)
                }
                assertEquals(listOf(200, 400, 400), pieces.map { it.size }.sorted())
                // Datagrams may arrive in any order; place each piece at the offset whose content it matches.
                val offsets = listOf(0, 400, 800)
                val ordered = pieces.sortedBy { p ->
                    offsets.firstOrNull { o -> data.copyOfRange(o, o + p.size).contentEquals(p) }
                        ?: fail("segment of ${p.size} bytes does not match any slice of the payload")
                }
                val reassembled = ordered.fold(ByteArray(0)) { acc, p -> acc + p }
                assertContentEquals(data, reassembled, "segments must reassemble to the original payload")
                // Linux only; elsewhere a no-op returning 0. Without CAP_NET_ADMIN Linux answers -EPERM.
                val bp = tx.busyPoll(true)
                assertTrue(bp == 0 || bp == -1, "busyPoll returned $bp")
            }
        }
    }

    @Test
    fun microBenchmarkScalarVsNative() {
        requireNative()
        val size = 1200
        val rnd = Random(7)
        val srcArr = ByteArray(size).also { rnd.nextBytes(it) }
        val dstScalar = ByteArray(size).also { rnd.nextBytes(it) }
        val dstArrayApi = dstScalar.copyOf()
        Arena.ofConfined().use { arena ->
            val d = arena.allocate(size.toLong(), 64).also { it.load(dstScalar) }
            val s = arena.allocate(size.toLong(), 64).also { it.load(srcArr) }

            fun scalarTrial(iters: Int): Double {
                var c = 1
                val t0 = System.nanoTime()
                repeat(iters) { GF256.mulAddInto(dstScalar, srcArr, c); c = if (c == 255) 1 else c + 1 }
                return (System.nanoTime() - t0).toDouble() / iters / size
            }
            fun nativeTrial(iters: Int): Double {
                var c = 1
                val t0 = System.nanoTime()
                repeat(iters) { Gf256Native.mulAddInto(d, s, size.toLong(), c); c = if (c == 255) 1 else c + 1 }
                return (System.nanoTime() - t0).toDouble() / iters / size
            }
            fun nativeArrayTrial(iters: Int): Double {
                var c = 1
                val t0 = System.nanoTime()
                repeat(iters) { Gf256Native.mulAddInto(dstArrayApi, srcArr, c); c = if (c == 255) 1 else c + 1 }
                return (System.nanoTime() - t0).toDouble() / iters / size
            }

            repeat(3) { scalarTrial(5_000); nativeTrial(50_000); nativeArrayTrial(50_000) } // JIT warm-up
            val scalar = (1..5).minOf { scalarTrial(20_000) }
            val native = (1..5).minOf { nativeTrial(200_000) }
            val nativeArray = (1..5).minOf { nativeArrayTrial(200_000) }

            // Cross-check after the timed runs: same start state, same 255 coefficients, all three paths.
            d.load(dstScalar)
            dstScalar.copyInto(dstArrayApi)
            for (c in 1..255) {
                GF256.mulAddInto(dstScalar, srcArr, c)
                Gf256Native.mulAddInto(d, s, size.toLong(), c)
                Gf256Native.mulAddInto(dstArrayApi, srcArr, c)
            }
            assertContentEquals(dstScalar, d.toArray(JAVA_BYTE), "native off-heap path diverged from scalar")
            assertContentEquals(dstScalar, dstArrayApi, "native ByteArray path diverged from scalar")

            val ratio = scalar / native
            val ratioArray = scalar / nativeArray
            val report = "GF256 mulAddInto, %d-byte symbols: scalar Kotlin %.3f ns/B | native off-heap %.3f ns/B (%.1fx) | native ByteArray-copy %.3f ns/B (%.1fx) [kernel=%s]"
                .format(size, scalar, native, ratio, nativeArray, ratioArray, Gf256Native.implementation)
            println(report)
            File("build/reports").mkdirs()
            File("build/reports/gf256-bench.txt").writeText(report + System.lineSeparator())
            assertTrue(ratio >= 3.0, "expected the native kernel to be >= 3x faster than scalar Kotlin, measured ${"%.1f".format(ratio)}x")
        }
    }
}
