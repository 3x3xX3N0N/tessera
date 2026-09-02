package tessera.native

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * `tessera_aead_seal` / `tessera_aead_open`: RFC 8439 ChaCha20-Poly1305 (RustCrypto's `chacha20poly1305`,
 * runtime-dispatched SSE2/AVX2) in place on one off-heap scratch segment — one FFI crossing per packet and no
 * allocation. The JDK provider cannot be driven this way: every `Cipher.init` rebuilds the Poly1305 state, and
 * its decrypt buffers the whole ciphertext through a `ByteArrayOutputStream` before the tag check; those were
 * the top allocation sites of the bulk profile (BENCH-netem, "The throughput profile"). Same bytes as SunJCE
 * and BouncyCastle: `JceAeadEquivalenceTest` pins all three against each other.
 *
 * Scratch layout: key at 0 (32 B), nonce at 32 (12 B), packet bytes (AAD, then body) from [DATA]. Callers copy
 * in, call, copy out — two copies per packet, the count the JCE path already paid before its internal ones.
 * Not thread-safe: one instance per `PacketCrypto`, used under its connection lock.
 */
class NativeAead private constructor() {
    private var seg: MemorySegment = Arena.ofAuto().allocate(DATA + INITIAL_CAPACITY, 64)
    private var keySeg = seg.asSlice(0, 32)
    private var nonceSeg = seg.asSlice(32, 12)
    private var dataSeg = seg.asSlice(DATA)

    /** Grows the scratch (doubling) when a packet larger than any before arrives; never shrinks. */
    private fun ensure(bytes: Int) {
        if (DATA + bytes <= seg.byteSize()) return
        var cap = seg.byteSize() - DATA
        while (cap < bytes) cap *= 2
        seg = Arena.ofAuto().allocate(DATA + cap, 64)
        keySeg = seg.asSlice(0, 32); nonceSeg = seg.asSlice(32, 12); dataSeg = seg.asSlice(DATA)
    }

    /**
     * AAD `src[0, hdrLen)`, plaintext `src[hdrLen, hdrLen + ptLen)`; writes `ct || tag16` to `out[0, ptLen + 16)`
     * and returns `ptLen + 16`. A refused call (a bad length) throws: the transport's length bookkeeping makes
     * that unreachable, and silently sending garbage is the one thing an AEAD must never do.
     */
    fun seal(key: ByteArray, nonce: ByteArray, src: ByteArray, hdrLen: Int, ptLen: Int, out: ByteArray): Int {
        ensure(hdrLen + ptLen + 16)
        MemorySegment.copy(key, 0, keySeg, JAVA_BYTE, 0L, 32)
        MemorySegment.copy(nonce, 0, nonceSeg, JAVA_BYTE, 0L, 12)
        MemorySegment.copy(src, 0, dataSeg, JAVA_BYTE, 0L, hdrLen + ptLen)
        val r = SEAL.invoke(keySeg, nonceSeg, dataSeg, hdrLen.toLong(), dataSeg.asSlice(hdrLen.toLong()), ptLen.toLong()) as Int
        check(r == ptLen + 16) { "tessera_aead_seal returned $r for ptLen=$ptLen hdrLen=$hdrLen" }
        MemorySegment.copy(dataSeg, JAVA_BYTE, hdrLen.toLong(), out, 0, r)
        return r
    }

    /** Returns the plaintext length written to `out[0, n)`, or -1 when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, src: ByteArray, hdrLen: Int, ctLen: Int, out: ByteArray): Int {
        if (ctLen < 16) return -1
        ensure(hdrLen + ctLen)
        MemorySegment.copy(key, 0, keySeg, JAVA_BYTE, 0L, 32)
        MemorySegment.copy(nonce, 0, nonceSeg, JAVA_BYTE, 0L, 12)
        MemorySegment.copy(src, 0, dataSeg, JAVA_BYTE, 0L, hdrLen + ctLen)
        val r = OPEN.invoke(keySeg, nonceSeg, dataSeg, hdrLen.toLong(), dataSeg.asSlice(hdrLen.toLong()), ctLen.toLong()) as Int
        if (r < 0) return -1
        MemorySegment.copy(dataSeg, JAVA_BYTE, hdrLen.toLong(), out, 0, r)
        return r
    }

    companion object {
        private const val DATA = 64L
        private const val INITIAL_CAPACITY = 4096L
        private val DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)

        // Resolved lazily, and a library without these symbols (an older build) reads as "unavailable" rather
        // than failing the connection: the JDK provider is always there to fall back to.
        private val handles: Pair<MethodHandle, MethodHandle>? by lazy {
            if (!NativeLib.available) null else try {
                NativeLib.downcall("tessera_aead_seal", DESC, Linker.Option.isTrivial()) to
                    NativeLib.downcall("tessera_aead_open", DESC, Linker.Option.isTrivial())
            } catch (t: Throwable) { null }
        }
        private val SEAL: MethodHandle get() = handles!!.first
        private val OPEN: MethodHandle get() = handles!!.second

        /** The library loaded and exports the AEAD entry points. */
        val available: Boolean get() = handles != null

        /**
         * The transport's default: native when [available], unless `-Dtessera.native=off` (no native anything)
         * or `-Dtessera.native.aead=off` (this half only — the A/B switch against the JDK provider).
         */
        val enabledByDefault: Boolean
            get() = System.getProperty("tessera.native") != "off" && System.getProperty("tessera.native.aead") != "off" && available

        /** An instance, or null when the library is unavailable — callers fall back to the JDK provider. */
        fun createOrNull(): NativeAead? = if (available) NativeAead() else null
    }
}
