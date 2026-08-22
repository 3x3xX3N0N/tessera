package aether.core

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import com.github.luben.zstd.ZstdDictTrainer
import com.github.luben.zstd.ZstdException
import java.lang.ref.Cleaner
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Shared-dictionary payload codec on zstd (zstd-jni). Plugs into [PayloadCodec]; [dictId] is what ConnParams carries.
 *
 * Wire format of [encode] output: one prefix byte, then payload.
 *   `0x00` stored — the input verbatim. Used whenever zstd would not shrink the input, and always for inputs
 *          shorter than [MIN_COMPRESS] bytes (a zstd frame header alone costs ~9 bytes).
 *   `0x01` zstd — a zstd frame in "magicless" format (the prefix already says what it is, so the 4-byte magic is
 *          dropped), carrying the content size and zstd's own 32-bit dictionary id, no checksum (the AEAD tag
 *          covers wire integrity).
 * An empty `dict` means "no dictionary": plain zstd frames and `dictId == 0`, the ConnParams value for "none".
 *
 * Identity: [dictId] = first 8 bytes of SHA-256(dict) as a big-endian Long, masked to 62 bits so it always fits
 * the ConnParams varint (QUIC-style varints carry 0..2^62-1). Both peers derive it from the dictionary bytes.
 *
 * Threading: create one codec per dictionary and share it. The digested dictionaries are immutable and shared;
 * zstd contexts are not thread-safe, so every thread lazily gets its own pair via ThreadLocal, released by a
 * Cleaner once the holder is unreachable (thread gone, or codec collected).
 *
 * Failure: [decode] throws [IllegalStateException] for anything it cannot decode — a frame made with a different
 * or missing dictionary, an unknown prefix, a truncated/corrupt frame, or a declared size above [maxDecodedSize].
 * Trained dictionaries carry a zstd dictionary id that zstd checks against each frame, so a mismatch between
 * trained dictionaries is detected deterministically; raw-content dictionaries have no such id and a mismatch
 * there surfaces only as corruption (usually, not always) — the negotiated [dictId] is the guard for those.
 */
class ZstdDictCodec(
    dict: ByteArray,
    private val level: Int = 3,
    /** Largest content size a zstd frame may declare before [decode] rejects it (allocation-bomb guard). */
    private val maxDecodedSize: Int = DEFAULT_MAX_DECODED,
) : PayloadCodec {
    override val dictId: Long = dictIdOf(dict)
    private val cdict: ZstdDictCompress? = if (dict.isEmpty()) null else ZstdDictCompress(dict, level)
    private val ddict: ZstdDictDecompress? = if (dict.isEmpty()) null else ZstdDictDecompress(dict)

    private val cctx: ThreadLocal<Owned<ZstdCompressCtx>> = ThreadLocal.withInitial {
        val ctx = ZstdCompressCtx().setLevel(level).setMagicless(true).setChecksum(false).setDictID(true).setContentSize(true)
        cdict?.let { ctx.loadDict(it) }
        Owned(ctx)
    }
    private val dctx: ThreadLocal<Owned<ZstdDecompressCtx>> = ThreadLocal.withInitial {
        val ctx = ZstdDecompressCtx().setMagicless(true)
        ddict?.let { ctx.loadDict(it) }
        Owned(ctx)
    }

    override fun encode(src: ByteArray): ByteArray {
        if (src.size >= MIN_COMPRESS) {
            val out = ByteArray(1 + Zstd.compressBound(src.size.toLong()).toInt())
            val n = try { cctx.get().ctx.compressByteArray(out, 1, out.size - 1, src, 0, src.size) }
                    catch (e: ZstdException) { throw IllegalStateException("zstd encode failed: ${e.message}", e) }
            if (n < src.size) { out[0] = ZSTD; return out.copyOf(1 + n) }
        }
        return ByteArray(1 + src.size).also { out -> out[0] = STORED; System.arraycopy(src, 0, out, 1, src.size) }
    }

    /** @throws IllegalStateException on any undecodable input (see class doc). */
    override fun decode(src: ByteArray): ByteArray {
        check(src.isNotEmpty()) { "empty payload" }
        return when (src[0]) {
            STORED -> src.copyOfRange(1, src.size)
            ZSTD -> {
                check(src.size > 1) { "zstd prefix without a frame" }
                // <= 0 means not a frame / size unknown; the header is untrusted, so bound it before allocating.
                val declared = Zstd.getFrameContentSize(src, 1, src.size - 1, true)
                check(declared > 0 && declared <= maxDecodedSize) { "zstd frame declares $declared bytes (limit $maxDecodedSize)" }
                val out = ByteArray(declared.toInt())
                val n = try { dctx.get().ctx.decompressByteArray(out, 0, out.size, src, 1, src.size - 1) }
                        catch (e: ZstdException) { throw IllegalStateException("zstd decode failed: ${e.message}", e) }
                check(n == out.size) { "zstd frame decoded to $n bytes, header declared ${out.size}" }
                out
            }
            else -> throw IllegalStateException("unknown payload prefix ${src[0].toInt() and 0xFF}")
        }
    }

    /**
     * Per-thread context holder, registered with the Cleaner so the native context is freed once the holder is
     * unreachable. The cleaning action must capture only the context, never the holder, or the holder could never
     * become phantom-reachable — hence a separate class rather than a lambda over `this.ctx`.
     */
    private class Owned<T : AutoCloseable>(val ctx: T) { init { CLEANER.register(this, Release(ctx)) } }
    private class Release(private val ctx: AutoCloseable) : Runnable {
        override fun run() { try { ctx.close() } catch (_: Exception) { /* already closed */ } }
    }

    companion object {
        const val STORED: Byte = 0x00
        const val ZSTD: Byte = 0x01
        /** Inputs shorter than this are always stored: a zstd frame header alone costs ~9 bytes. */
        const val MIN_COMPRESS = 16
        const val DEFAULT_MAX_DECODED = 1 shl 20
        private const val VARINT_MAX = 0x3FFF_FFFF_FFFF_FFFFL
        private val CLEANER: Cleaner = Cleaner.create()

        /** zstd without a dictionary (dictId 0). Same framing, so it is the like-for-like baseline for a dictionary. */
        fun noDict(level: Int = 3) = ZstdDictCodec(ByteArray(0), level)

        /** First 8 bytes of SHA-256(dict), big-endian, masked to the 62-bit varint range; 0 for an empty dict. */
        fun dictIdOf(dict: ByteArray): Long =
            if (dict.isEmpty()) 0L else ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(dict)).getLong() and VARINT_MAX
    }
}

/** Trains a zstd dictionary from sample messages (zstd-jni [ZstdDictTrainer], i.e. ZDICT's fastCover trainer). */
object DictTrainer {
    /**
     * @param samples representative messages — a few hundred at least, ideally totalling 10–100x [dictSize].
     * @param dictSize target dictionary size in bytes (zstd minimum 256); the result may come out smaller.
     * @throws IllegalStateException if zstd cannot build a dictionary, typically from too few or too small samples.
     */
    fun train(samples: List<ByteArray>, dictSize: Int = 16 * 1024): ByteArray {
        require(samples.isNotEmpty()) { "no samples" }
        require(dictSize >= 256) { "dictSize must be >= 256 (zstd minimum)" }
        val trainer = ZstdDictTrainer(samples.sumOf { it.size }, dictSize)
        for (s in samples) check(trainer.addSample(s)) { "trainer sample buffer overflow" }
        return try { trainer.trainSamples() } catch (e: ZstdException) {
            throw IllegalStateException("zstd dictionary training failed: ${e.message} (more or larger samples needed?)", e)
        }
    }
}
