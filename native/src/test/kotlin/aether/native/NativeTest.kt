package aether.native

import aether.core.GF256
import aether.core.RlncDecoder
import aether.core.RlncEncoder
import java.io.File
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class NativeTest {
    private fun requireNative() = assertTrue(NativeLib.available, "aether_native failed to load: ${NativeLib.loadError}")

    private fun MemorySegment.load(bytes: ByteArray) = MemorySegment.copy(bytes, 0, this, JAVA_BYTE, 0L, bytes.size)

    /** The scalar Kotlin kernel is the oracle for every native path, whatever GF256.kernel currently is. */
    private val scalar = GF256.Scalar

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
                val expected = dst.copyOf().also { scalar.mulAddInto(it, src, c) }

                Arena.ofConfined().use { arena ->
                    val d = arena.allocate(len.toLong()).also { it.load(dst) }
                    val s = arena.allocate(len.toLong()).also { it.load(src) }
                    Gf256Native.mulAddInto(d, s, len.toLong(), c)
                    assertContentEquals(expected, d.toArray(JAVA_BYTE), "segment API len=$len c=$c")
                    // off-heap accumulator + heap source (what RlncDecoder.onRepair uses)
                    d.load(dst)
                    Gf256Native.mulAddInto(d, src, c)
                    assertContentEquals(expected, d.toArray(JAVA_BYTE), "segment/array API len=$len c=$c")
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
                val expected = dst.copyOf().also { scalar.mulAddInto(it, src, c) }
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
                val cache = SockAddrCache()
                var cachedFrom: InetSocketAddress? = null
                while (got.size < n && System.nanoTime() < deadline) {
                    val r = rx.recvBatch(rxBatch, timeoutMs = 500)
                    calls++
                    for (i in 0 until r) {
                        assertEquals(size, rxBatch.length(i))
                        val from = assertNotNull(rxBatch.address(i), "sender address must be filled in")
                        assertEquals(tx.localPort, from.port)
                        assertEquals("127.0.0.1", from.address.hostAddress)
                        // the cached resolver hands out one and the same object for a steady sender
                        val viaCache = assertNotNull(rxBatch.address(i, cache))
                        assertEquals(from, viaCache)
                        if (cachedFrom == null) cachedFrom = viaCache else assertSame(cachedFrom, viaCache)
                        val p = rxBatch.get(i)
                        got[p[0].toInt() and 0xFF] = p
                    }
                }
                assertEquals(n, got.size, "received ${got.size}/$n datagrams")
                assertEquals(1L, cache.misses, "one InetSocketAddress allocation for $n datagrams from one sender")
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

    /** Receives `expected` datagrams on `rx` (in arrival order) as (payload, sender port). */
    private fun receiveAll(rx: NativeUdp, expected: Int): List<Pair<ByteArray, Int>> {
        val batch = PacketBatch(64, 2048)
        val out = ArrayList<Pair<ByteArray, Int>>()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (out.size < expected && System.nanoTime() < deadline) {
            val r = rx.recvBatch(batch, timeoutMs = 200)
            for (i in 0 until r) out += batch.get(i) to assertNotNull(batch.address(i)).port
        }
        return out
    }

    /**
     * TxBatch: contiguous layout, GSO run detection, and both send paths (plain sendmmsg-style and GSO
     * super-datagrams — user-space segmentation on non-Linux, kernel UDP_SEGMENT on Linux) deliver the very same
     * bytes in order to the right sockets.
     */
    @Test
    fun txBatchRunsAndGsoDeliverIdenticalBytesInOrder() {
        requireNative()
        NativeUdp("127.0.0.1", 0).use { tx -> NativeUdp("127.0.0.1", 0).use { rxA -> NativeUdp("127.0.0.1", 0).use { rxB ->
            val a = InetSocketAddress(InetAddress.getByName("127.0.0.1"), rxA.localPort)
            val b = InetSocketAddress(InetAddress.getByName("127.0.0.1"), rxB.localPort)
            val batch = TxBatch(32, 32 * 2048)
            assertTrue(batch.isEmpty)
            //             0      1      2      3      4     5      6      7     8      9      10
            val plan = listOf(1200 to a, 1200 to a, 1200 to a, 1200 to a, 700 to a, 1200 to b, 1200 to b, 100 to a, 1200 to a, 1200 to a, 16 to b)
            val payloads = plan.mapIndexed { i, (len, _) -> ByteArray(len) { j -> (i * 31 + j).toByte() } }
            plan.forEachIndexed { i, (_, to) ->
                // alternate the two add() overloads; the ByteBuffer one must not move the position
                if (i % 2 == 0) assertTrue(batch.add(payloads[i], to)) else {
                    val bb = java.nio.ByteBuffer.wrap(payloads[i]); assertTrue(batch.add(bb, to)); assertEquals(0, bb.position())
                }
            }
            assertEquals(plan.size, batch.count)
            assertEquals(payloads.sumOf { it.size }.toLong(), batch.used)
            for (i in plan.indices) { assertEquals(plan[i].first, batch.length(i)); assertEquals(plan[i].second, batch.address(i)); assertContentEquals(payloads[i], batch.get(i)) }
            assertEquals(batch.offset(3) + 1200, batch.offset(4), "datagrams are laid out back-to-back")
            assertTrue(batch.sameDestination(0, 4)); assertTrue(!batch.sameDestination(4, 5)); assertTrue(batch.sameDestination(5, 10))

            assertEquals(5, batch.runEnd(0, 64, 65_000), "4 x 1200 + a shorter trailing 700 to A")
            assertEquals(5, batch.runEnd(4, 64, 65_000), "a short datagram starts no run (next is larger)")
            assertEquals(7, batch.runEnd(5, 64, 65_000), "2 x 1200 to B, then A")
            assertEquals(8, batch.runEnd(7, 64, 65_000), "100 followed by a larger datagram: no run")
            assertEquals(10, batch.runEnd(8, 64, 65_000), "2 x 1200 to A, then B")
            assertEquals(11, batch.runEnd(10, 64, 65_000))
            assertEquals(2, batch.runEnd(0, 2, 65_000), "maxSegments caps the run")
            assertEquals(2, batch.runEnd(0, 64, 2400), "maxBytes caps the run")

            val expectA = listOf(0, 1, 2, 3, 4, 7, 8, 9).map { payloads[it] }
            val expectB = listOf(5, 6, 10).map { payloads[it] }
            fun check(label: String) {
                val gotA = receiveAll(rxA, expectA.size); val gotB = receiveAll(rxB, expectB.size)
                assertEquals(expectA.size, gotA.size, "$label: datagrams at A"); assertEquals(expectB.size, gotB.size, "$label: datagrams at B")
                for (i in expectA.indices) { assertContentEquals(expectA[i], gotA[i].first, "$label: A[$i]"); assertEquals(tx.localPort, gotA[i].second) }
                for (i in expectB.indices) { assertContentEquals(expectB[i], gotB[i].first, "$label: B[$i]"); assertEquals(tx.localPort, gotB[i].second) }
            }
            // plain path: the whole batch in one call, and a sub-range
            assertEquals(plan.size, tx.sendBatch(batch))
            check("sendBatch")
            assertEquals(3, tx.sendBatch(batch, 5, 3))
            val sub = receiveAll(rxB, 2) + receiveAll(rxA, 1)
            assertEquals(3, sub.size); assertContentEquals(payloads[5], sub[0].first); assertContentEquals(payloads[6], sub[1].first); assertContentEquals(payloads[7], sub[2].first)
            // GSO path for the runs, plain for the singles — same bytes, same order
            assertEquals(5, tx.sendGso(batch, 0, 5))
            assertEquals(2, tx.sendGso(batch, 5, 7))
            assertEquals(1, tx.sendBatch(batch, 7, 1))
            assertEquals(2, tx.sendGso(batch, 8, 10))
            assertEquals(1, tx.sendBatch(batch, 10, 1))
            check("sendGso")

            batch.clear()
            assertTrue(batch.isEmpty); assertEquals(0L, batch.used)
            assertFailsWith<IllegalArgumentException> { batch.length(0) }
            val tiny = TxBatch(2, 100)
            assertTrue(tiny.add(ByteArray(60), a)); assertTrue(!tiny.add(ByteArray(60), a), "no room: bytes"); assertTrue(tiny.add(ByteArray(40), a)); assertTrue(tiny.isFull)
            assertTrue(!tiny.add(ByteArray(1), a), "no room: capacity")
            assertNull(PacketBatch(1, 16).address(0), "unset slot has no address")
        } } }
    }

    /**
     * RLNC on the pluggable kernel: repair()/onRepair() produce bit-identical symbols on the scalar kernel, on the
     * native kernel through the copying ByteArray path, and on the native kernel with the off-heap window mirror;
     * the decoder recovers losses on every kernel. Also the benchmark the SPEC quotes: repair() over a 64-symbol
     * window of 1200-byte symbols, and onRepair() substituting 64 known symbols, per kernel.
     */
    @Test
    fun rlncRepairOnNativeKernelMatchesScalarAndIsFaster() {
        requireNative()
        val symbolSize = 1200; val window = 64
        val rnd = Random(11)
        val symbols = Array(window + 16) { ByteArray(symbolSize).also { rnd.nextBytes(it) } }
        fun encoder(count: Int = window) = RlncEncoder(symbolSize, window).also { e -> for (i in 0 until count) e.push(i.toLong(), symbols[i]) }
        fun bytes(e: RlncEncoder, seed: Int): ByteArray { val r = e.repair(seed); return ByteArray(r.symbol.remaining()).also { r.symbol.duplicate().get(it) } }
        fun decoder(): RlncDecoder = RlncDecoder(symbolSize).also { d -> for (i in 0 until window) d.onSource(i.toLong(), symbols[i]) }
        fun repairTrial(e: RlncEncoder, iters: Int): Double { var seed = 1; val t0 = System.nanoTime(); repeat(iters) { e.repair(seed++) }; return (System.nanoTime() - t0).toDouble() / iters }
        fun decodeTrial(d: RlncDecoder, e: RlncEncoder, iters: Int): Double {
            val frames = Array(16) { e.repair(1000 + it) }
            val t0 = System.nanoTime(); repeat(iters) { d.onRepair(frames[it and 15]) }; return (System.nanoTime() - t0).toDouble() / iters
        }
        /** Loses 4 of 64 sources, feeds 5 repairs: everything must come back. */
        fun recovers(label: String) {
            val e = encoder(); val d = RlncDecoder(symbolSize)
            val lost = setOf(3L, 17L, 40L, 63L)
            for (i in 0 until window) if (i.toLong() !in lost) d.onSource(i.toLong(), symbols[i])
            for (s in 1..5) d.onRepair(e.repair(s * 7919))
            for (l in lost) assertContentEquals(symbols[l.toInt()], d.get(l), "$label: seq $l recovered")
        }
        val copying = GF256.Kernel { d, s, c -> Gf256Native.mulAddInto(d, s, c) } // native SIMD, but through the heap-copying path only

        GF256.useScalar()
        try {
            val seeds = (1..8).map { it * 7919 }
            val scalarEnc = encoder()
            val scalarOut = seeds.map { bytes(scalarEnc, it) }
            recovers("scalar")
            repeat(3) { repairTrial(scalarEnc, 200); decodeTrial(decoder(), scalarEnc, 200) }
            val scalarRepair = (1..5).minOf { repairTrial(scalarEnc, 400) }
            val scalarDecode = (1..5).minOf { decodeTrial(decoder(), scalarEnc, 400) }

            GF256.useNative(copying)
            val copyEnc = encoder()
            seeds.forEachIndexed { i, s -> assertContentEquals(scalarOut[i], bytes(copyEnc, s), "copying path, seed $s") }
            recovers("native-copying")
            repeat(3) { repairTrial(copyEnc, 1000); decodeTrial(decoder(), copyEnc, 1000) }
            val copyRepair = (1..5).minOf { repairTrial(copyEnc, 2000) }
            val copyDecode = (1..5).minOf { decodeTrial(decoder(), copyEnc, 2000) }

            assertTrue(Gf256Native.install()); assertTrue(Gf256Native.installed)
            val nativeEnc = encoder()                       // mirror is created by its first repair()
            seeds.forEachIndexed { i, s -> assertContentEquals(scalarOut[i], bytes(nativeEnc, s), "off-heap path, seed $s") }
            assertContentEquals(scalarOut[0], bytes(scalarEnc, seeds[0]), "an encoder built under the scalar kernel mirrors its live window on switch")
            recovers("native-offheap")
            // the mirror tracks the sliding window: push 16 more, compare against a scalar encoder with the same history
            val slidNative = encoder(window + 16)           // created under the native kernel, mirrored from push #1
            for (i in window until window + 16) nativeEnc.push(i.toLong(), symbols[i])
            GF256.useScalar(); val slidScalar = encoder(window + 16); val slidRef = seeds.map { bytes(slidScalar, it) }; Gf256Native.install()
            seeds.forEachIndexed { i, s -> assertContentEquals(slidRef[i], bytes(nativeEnc, s), "slid window (mirror created mid-way), seed $s"); assertContentEquals(slidRef[i], bytes(slidNative, s), "slid window (mirrored from the start), seed $s") }
            repeat(3) { repairTrial(nativeEnc, 2000); decodeTrial(decoder(), nativeEnc, 2000) }
            val nativeRepair = (1..5).minOf { repairTrial(nativeEnc, 5000) }
            val nativeDecode = (1..5).minOf { decodeTrial(decoder(), nativeEnc, 5000) }

            val report = String.format(Locale.ROOT,
                "RLNC %d x %d B window [kernel=%s]: repair() scalar %.1f us | native copying %.1f us (%.1fx) | native off-heap mirror %.1f us (%.1fx) || " +
                    "onRepair() 64 known: scalar %.1f us | native copying %.1f us (%.1fx) | native off-heap acc %.1f us (%.1fx)",
                window, symbolSize, Gf256Native.implementation,
                scalarRepair / 1e3, copyRepair / 1e3, scalarRepair / copyRepair, nativeRepair / 1e3, scalarRepair / nativeRepair,
                scalarDecode / 1e3, copyDecode / 1e3, scalarDecode / copyDecode, nativeDecode / 1e3, scalarDecode / nativeDecode)
            println(report)
            File("build/reports").mkdirs()
            File("build/reports/rlnc-bench.txt").writeText(report + System.lineSeparator())
            assertTrue(scalarRepair / nativeRepair >= 3.0, "expected the off-heap native path to be >= 3x faster on repair(), measured ${"%.1f".format(scalarRepair / nativeRepair)}x")
        } finally {
            GF256.useScalar()
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
                repeat(iters) { scalar.mulAddInto(dstScalar, srcArr, c); c = if (c == 255) 1 else c + 1 }
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
            val scalarNs = (1..5).minOf { scalarTrial(20_000) }
            val native = (1..5).minOf { nativeTrial(200_000) }
            val nativeArray = (1..5).minOf { nativeArrayTrial(200_000) }

            // Cross-check after the timed runs: same start state, same 255 coefficients, all three paths.
            d.load(dstScalar)
            dstScalar.copyInto(dstArrayApi)
            for (c in 1..255) {
                scalar.mulAddInto(dstScalar, srcArr, c)
                Gf256Native.mulAddInto(d, s, size.toLong(), c)
                Gf256Native.mulAddInto(dstArrayApi, srcArr, c)
            }
            assertContentEquals(dstScalar, d.toArray(JAVA_BYTE), "native off-heap path diverged from scalar")
            assertContentEquals(dstScalar, dstArrayApi, "native ByteArray path diverged from scalar")

            val ratio = scalarNs / native
            val ratioArray = scalarNs / nativeArray
            val report = "GF256 mulAddInto, %d-byte symbols: scalar Kotlin %.3f ns/B | native off-heap %.3f ns/B (%.1fx) | native ByteArray-copy %.3f ns/B (%.1fx) [kernel=%s]"
                .format(size, scalarNs, native, ratio, nativeArray, ratioArray, Gf256Native.implementation)
            println(report)
            File("build/reports").mkdirs()
            File("build/reports/gf256-bench.txt").writeText(report + System.lineSeparator())
            assertTrue(ratio >= 3.0, "expected the native kernel to be >= 3x faster than scalar Kotlin, measured ${"%.1f".format(ratio)}x")
        }
    }
}
