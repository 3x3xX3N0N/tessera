package tessera.core

import java.nio.ByteBuffer
import java.util.Locale
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ZstdDictCodecTest {
    private val codec = ZstdDictCodec(dict)

    @Test fun tinyInputsAreStoredRaw() {
        for (src in listOf(ByteArray(0), "hi".toByteArray(), ByteArray(ZstdDictCodec.MIN_COMPRESS - 1) { 0x41.toByte() })) {
            val e = codec.encode(src)
            assertEquals(ZstdDictCodec.STORED, e[0]); assertEquals(src.size + 1, e.size)
            assertContentEquals(src, codec.decode(e))
        }
    }

    @Test fun mediumAndLargeInputsRoundTrip() {
        val medium = samples[700]
        val em = codec.encode(medium)
        assertEquals(ZstdDictCodec.ZSTD, em[0]); assertTrue(em.size < medium.size, "${em.size} vs ${medium.size}")
        assertContentEquals(medium, codec.decode(em))

        val big = ByteArray(10 * 1024).also { b ->   // 10KB of concatenated messages: compressible
            var i = 0; var k = 0
            while (i < b.size) { val m = samples[k++ % samples.size]; val n = minOf(m.size, b.size - i); System.arraycopy(m, 0, b, i, n); i += n }
        }
        val eb = codec.encode(big)
        assertEquals(ZstdDictCodec.ZSTD, eb[0]); assertTrue(eb.size < big.size / 2, "10KB json -> ${eb.size}")
        assertContentEquals(big, codec.decode(eb))

        val noise = ByteArray(10 * 1024).also { Random(3).nextBytes(it) }   // incompressible: falls back to stored
        val en = codec.encode(noise)
        assertEquals(ZstdDictCodec.STORED, en[0]); assertEquals(noise.size + 1, en.size)
        assertContentEquals(noise, codec.decode(en))
    }

    @Test fun dictIdIsDeterministicDistinctAndVarintSafe() {
        assertEquals(codec.dictId, ZstdDictCodec(dict.copyOf()).dictId)
        assertEquals(codec.dictId, ZstdDictCodec.dictIdOf(dict))
        assertNotEquals(0L, codec.dictId)
        assertNotEquals(codec.dictId, ZstdDictCodec(otherDict).dictId)
        assertNotEquals(codec.dictId, ZstdDictCodec(dict.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }).dictId)
        assertEquals(0L, ZstdDictCodec.noDict().dictId)
        assertTrue(codec.dictId in 0L until (1L shl 62), "must fit the ConnParams varint")
        val buf = ByteBuffer.allocate(32); ConnParams(dictId = codec.dictId).write(buf); buf.flip()
        assertEquals(codec.dictId, ConnParams.read(buf).dictId)
    }

    /** Decoding with the wrong (or no) dictionary, or garbage, throws IllegalStateException — never returns garbage. */
    @Test fun wrongDictionaryAndMalformedInputThrow() {
        val frame = codec.encode(samples[800]); assertEquals(ZstdDictCodec.ZSTD, frame[0])
        assertFailsWith<IllegalStateException> { ZstdDictCodec(otherDict).decode(frame) }
        assertFailsWith<IllegalStateException> { ZstdDictCodec.noDict().decode(frame) }
        assertFailsWith<IllegalStateException> { codec.decode(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { codec.decode(byteArrayOf(0x07, 1, 2, 3)) }
        assertFailsWith<IllegalStateException> { codec.decode(byteArrayOf(ZstdDictCodec.ZSTD)) }
        assertFailsWith<IllegalStateException> { codec.decode(frame.copyOf(frame.size - 4)) }
        // forged frame header declaring 2 GiB of content: rejected before any allocation
        val forged = byteArrayOf(ZstdDictCodec.ZSTD, 0xE0.toByte(), 0, 0, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0)
        assertFailsWith<IllegalStateException> { codec.decode(forged) }
    }

    @Test fun trainedDictBeatsPlainZstdByAtLeast30Percent() {
        val heldOut = samples.subList(600, samples.size)
        val plain = ZstdDictCodec.noDict()
        val raw = heldOut.sumOf { it.size }
        val noDict = heldOut.sumOf { plain.encode(it).size }
        val withDict = heldOut.sumOf { codec.encode(it).size }
        assertTrue(withDict <= 0.7 * noDict, "raw=$raw zstd=$noDict zstd+dict=$withDict")
        heldOut.forEach { assertContentEquals(it, codec.decode(codec.encode(it))) }
    }

    @Test fun oneCodecIsSafeToShareAcrossThreads() {
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(4) { t ->
            Thread {
                val rnd = Random(t.toLong())
                repeat(300) {
                    val m = samples[rnd.nextInt(samples.size)]
                    val ok = try { codec.decode(codec.encode(m)).contentEquals(m) } catch (e: Exception) { false }
                    if (!ok) failures.incrementAndGet()
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals(0, failures.get())
    }

    companion object {
        private val STATES = arrayOf("idle", "run", "jump", "shoot", "dead", "crouch")

        /** Small game-telemetry JSON messages, roughly 85–190 bytes, deterministic per seed. */
        fun telemetry(n: Int, seed: Long): List<ByteArray> {
            val rnd = Random(seed)
            fun f(x: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", x)
            return List(n) { i ->
                buildString {
                    append("{\"t\":").append(1_000_000 + i * 50 + rnd.nextInt(50))
                    append(",\"id\":\"player-").append(rnd.nextInt(400)).append('"')
                    append(",\"pos\":[").append(f(rnd.nextDouble() * 2000, 1)).append(',').append(f(rnd.nextDouble() * 2000, 1)).append(',').append(f(rnd.nextDouble() * 120, 1)).append(']')
                    if (rnd.nextInt(4) != 0) append(",\"vel\":[").append(f(rnd.nextGaussian() * 6, 2)).append(',').append(f(rnd.nextGaussian() * 6, 2)).append(',').append(f(rnd.nextGaussian() * 2, 2)).append(']')
                    append(",\"hp\":").append(rnd.nextInt(101))
                    append(",\"state\":\"").append(STATES[rnd.nextInt(STATES.size)]).append('"')
                    if (rnd.nextInt(3) == 0) append(",\"zone\":\"sector-").append(rnd.nextInt(40)).append('"')
                    if (rnd.nextInt(3) == 0) append(",\"ammo\":{\"rifle\":").append(rnd.nextInt(120)).append(",\"pistol\":").append(rnd.nextInt(40)).append('}')
                    append(",\"seq\":").append(i).append('}')
                }.toByteArray()
            }
        }

        // Trained once per JVM: 600 training messages, the rest (400) held out for the ratio test.
        val samples: List<ByteArray> by lazy { telemetry(1000, seed = 7) }
        val dict: ByteArray by lazy { DictTrainer.train(samples.subList(0, 600)) }
        val otherDict: ByteArray by lazy { DictTrainer.train(telemetry(600, seed = 99)) }
    }
}
