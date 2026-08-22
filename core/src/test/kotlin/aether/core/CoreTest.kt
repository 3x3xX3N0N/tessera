package aether.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test fun frameRoundTrip() {
        val buf = ByteBuffer.allocate(256)
        Frame.Msg(7, 0, true, ByteBuffer.wrap("hi".toByteArray())).write(buf)
        Frame.Ack(PathId(1), 42, listOf(40L..42L), 0, 123).write(buf)
        Frame.Grant(PathId(1), 9000, 0).write(buf)
        buf.flip()
        val m = FrameCodec.read(buf) as Frame.Msg; assertEquals(7, m.msgId); assertTrue(m.fin)
        val a = FrameCodec.read(buf) as Frame.Ack; assertEquals(42, a.largest)
        val g = FrameCodec.read(buf) as Frame.Grant; assertEquals(9000, g.creditBytes)
        assertNull(FrameCodec.read(buf))
    }

    @Test fun rlncRecoversTwoLossesFromTwoRepairs() {
        val enc = RlncEncoder(8); val dec = RlncDecoder(8)
        val syms = (0 until 6).map { i -> ByteArray(8) { (i * 10 + it).toByte() } }
        syms.forEachIndexed { i, s -> enc.push(i.toLong(), s) }
        listOf(0, 1, 3, 5).forEach { dec.onSource(it.toLong(), syms[it]) } // lose 2 and 4
        dec.onRepair(enc.repair(seed = 99))
        dec.onRepair(enc.repair(seed = 7))
        assertContentEquals(syms[2], dec.get(2))
        assertContentEquals(syms[4], dec.get(4))
    }

    @Test fun hybridHandshakeAgreesAndFitsOneDatagram() {
        val r = Handshake.generate()
        val i = Handshake.initiate(r.x25519Pub, r.kemPub)
        val k = Handshake.respond(r, i.ePub, i.kemCt)
        assertContentEquals(i.key, k)
        assertTrue(i.ePub.size + i.kemCt.size < Wire.MAX_DATAGRAM - Wire.HEADER_LEN)
    }

    @Test fun estimatorFecGrowsWithLoss() {
        val e = PathEstimator(PathId(0))
        repeat(50) { e.onLossObservation(0.0) }; val lo = e.fecRedundancy()
        repeat(50) { e.onLossObservation(0.1) }; val hi = e.fecRedundancy()
        assertTrue(hi > lo)
    }

    @Test fun schedulerPrefersFasterPath() {
        val a = PathEstimator(PathId(0)).apply { onRttSample(80_000) }
        val b = PathEstimator(PathId(1)).apply { onRttSample(20_000) }
        val s = Scheduler().apply { add(a); add(b) }
        assertEquals(PathId(1), s.pick(1000))
        assertEquals(PathId(0), s.repairPathFor(PathId(1)))
    }
}
