package tessera.native

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * `tessera_aead_seal` / `tessera_aead_open`: ChaCha20-Poly1305 packet protection (RFC 8439 §2.8),
 * implemented in the native crate from the document.
 *
 * **Why this exists.** `bench profile` measured the JVM AEAD at 16.6 us per 1200-byte message — 7.1 us to seal
 * and 8.6 us to open, ~165 MB/s — against 0.14 us for all of RLNC. The AEAD, not the codec at large, is the
 * per-packet compute cost of the transport, and a native ChaCha20 was the measured, known-technique half of
 * that bill. The Rust side seals the same packet in 2.64 us (454 MB/s) and opens it in 2.70 us.
 *
 * **Correctness before speed.** A hand-written AEAD fails silently when it is wrong: the ciphertext still looks
 * like ciphertext. Three layers guard it — the RFC 8439 vectors in the crate's own unit tests (block function,
 * keystream, all three Poly1305 vectors, the §2.8.2 AEAD), the tamper and boundary sweeps beside them, and
 * `AeadNativeTest`, which differentially fuzzes this binding against BouncyCastle over random keys, nonces,
 * AAD and lengths. BouncyCastle stays the reference implementation and the fallback: nothing here replaces it
 * unless [available] and the differential test agrees with it.
 *
 * All four buffers must be off-heap ([MemorySegment.isNative]) — JDK 21 cannot pass heap segments to a
 * downcall. Callers that hold heap arrays should keep a per-thread native scratch segment and copy in; at 1200
 * bytes that copy is a few tens of nanoseconds against microseconds of cipher.
 */
object AeadNative {
    /** Tag length the crate computes; a caller may compare a shorter transmitted prefix (the transport's 8). */
    const val TAG_LEN = 16

    val available: Boolean = NativeLib.available

    private val sealHandle: MethodHandle? = if (available) {
        NativeLib.downcall(
            "tessera_aead_seal",
            // key, nonce, aad, aad_len, buf, len, tag
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS),
            Linker.Option.isTrivial(), // bounded, non-blocking, never calls back
        )
    } else null

    private val openHandle: MethodHandle? = if (available) {
        NativeLib.downcall(
            "tessera_aead_open",
            // key, nonce, aad, aad_len, buf, len, tag, tag_len
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG),
            Linker.Option.isTrivial(),
        )
    } else null

    /**
     * Encrypts `buf[0, len)` in place under `key` (32 B) and `nonce` (12 B) with `aad[0, aadLen)` as additional
     * data, writing the 16-byte tag to `tag`. `aad` may be [MemorySegment.NULL] when `aadLen` is 0.
     */
    fun seal(key: MemorySegment, nonce: MemorySegment, aad: MemorySegment, aadLen: Long, buf: MemorySegment, len: Long, tag: MemorySegment) {
        val h = sealHandle ?: throw IllegalStateException("tessera_native is not available", NativeLib.loadError)
        checkArgs(key, nonce, aad, aadLen, buf, len)
        require(tag.isNative && tag.byteSize() >= TAG_LEN) { "tag must be a native segment of at least $TAG_LEN bytes" }
        val rc = h.invoke(key, nonce, aad, aadLen, buf, len, tag) as Int
        check(rc == 0) { "tessera_aead_seal returned $rc" }
    }

    /**
     * Verifies `tag[0, tagLen)` and, only on a match, decrypts `buf[0, len)` in place. Returns false and leaves
     * `buf` untouched when the tag does not match — a refused open must never hand back partial plaintext.
     * `tagLen` may be shorter than [TAG_LEN]: the comparison then covers exactly the transmitted bytes.
     */
    fun open(key: MemorySegment, nonce: MemorySegment, aad: MemorySegment, aadLen: Long, buf: MemorySegment, len: Long, tag: MemorySegment, tagLen: Long): Boolean {
        val h = openHandle ?: throw IllegalStateException("tessera_native is not available", NativeLib.loadError)
        checkArgs(key, nonce, aad, aadLen, buf, len)
        require(tagLen in 1..TAG_LEN.toLong()) { "tagLen must be 1..$TAG_LEN, got $tagLen" }
        require(tag.isNative && tag.byteSize() >= tagLen) { "tag must be a native segment of at least $tagLen bytes" }
        val rc = h.invoke(key, nonce, aad, aadLen, buf, len, tag, tagLen) as Int
        check(rc >= 0) { "tessera_aead_open returned $rc" }
        return rc == 1
    }

    private fun checkArgs(key: MemorySegment, nonce: MemorySegment, aad: MemorySegment, aadLen: Long, buf: MemorySegment, len: Long) {
        require(key.isNative && key.byteSize() >= 32) { "key must be a native segment of at least 32 bytes" }
        require(nonce.isNative && nonce.byteSize() >= 12) { "nonce must be a native segment of at least 12 bytes" }
        require(aadLen >= 0 && len >= 0) { "lengths must be >= 0" }
        require(aadLen == 0L || (aad.isNative && aad.byteSize() >= aadLen)) { "aad must be a native segment of at least $aadLen bytes" }
        require(len == 0L || (buf.isNative && buf.byteSize() >= len)) { "buf must be a native segment of at least $len bytes" }
    }

    /**
     * Convenience for tests and cold paths: seal a heap array, returning `ciphertext || tag(16)`. Allocates a
     * confined arena per call, so it is emphatically not the datapath entry point — [seal] is.
     */
    fun sealBytes(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        Arena.ofConfined().use { a ->
            val k = a.allocate(32).also { it.copyFrom(MemorySegment.ofArray(key)) }
            val n = a.allocate(12).also { it.copyFrom(MemorySegment.ofArray(nonce)) }
            val ad = if (aad.isEmpty()) MemorySegment.NULL else a.allocate(aad.size.toLong()).also { it.copyFrom(MemorySegment.ofArray(aad)) }
            val b = if (plaintext.isEmpty()) MemorySegment.NULL else a.allocate(plaintext.size.toLong()).also { it.copyFrom(MemorySegment.ofArray(plaintext)) }
            val t = a.allocate(TAG_LEN.toLong())
            seal(k, n, ad, aad.size.toLong(), b, plaintext.size.toLong(), t)
            val out = ByteArray(plaintext.size + TAG_LEN)
            if (plaintext.isNotEmpty()) MemorySegment.ofArray(out).copyFrom(b.asSlice(0, plaintext.size.toLong()))
            MemorySegment.ofArray(out).asSlice(plaintext.size.toLong(), TAG_LEN.toLong()).copyFrom(t)
            out
        }

    /**
     * Convenience for tests and cold paths: open `ciphertext || tag(tagLen)`, returning the plaintext or null.
     * Allocates per call; see [sealBytes].
     */
    fun openBytes(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray, tagLen: Int = TAG_LEN): ByteArray? {
        if (sealed.size < tagLen) return null
        val ctLen = sealed.size - tagLen
        return Arena.ofConfined().use { a ->
            val k = a.allocate(32).also { it.copyFrom(MemorySegment.ofArray(key)) }
            val n = a.allocate(12).also { it.copyFrom(MemorySegment.ofArray(nonce)) }
            val ad = if (aad.isEmpty()) MemorySegment.NULL else a.allocate(aad.size.toLong()).also { it.copyFrom(MemorySegment.ofArray(aad)) }
            val b = if (ctLen == 0) MemorySegment.NULL else a.allocate(ctLen.toLong()).also { seg ->
                seg.copyFrom(MemorySegment.ofArray(sealed).asSlice(0, ctLen.toLong()))
            }
            val t = a.allocate(tagLen.toLong()).also { it.copyFrom(MemorySegment.ofArray(sealed).asSlice(ctLen.toLong(), tagLen.toLong())) }
            if (!open(k, n, ad, aad.size.toLong(), b, ctLen.toLong(), t, tagLen.toLong())) null
            else ByteArray(ctLen).also { if (ctLen > 0) MemorySegment.ofArray(it).copyFrom(b.asSlice(0, ctLen.toLong())) }
        }
    }
}
