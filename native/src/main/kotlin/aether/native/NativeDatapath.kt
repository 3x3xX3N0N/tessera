package aether.native

import aether.core.GF256
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Panama FFM bindings for the aether_native cdylib (native/rust). JDK 21 ships the FFM API under
 * its third-preview names (Linker.Option.isTrivial, allocateUtf8String); no --enable-preview is
 * needed at runtime because these classes are not preview-versioned class files.
 */

/**
 * Locates and loads `aether_native`, and creates downcall handles. Loading happens once, at first
 * use of this object; failure is recorded in [loadError] rather than thrown so callers can fall
 * back to the pure-Kotlin path via [available].
 *
 * Search order: `-Daether.native.path=<file or directory>`, then the classpath resource
 * `/native/<os>-<arch>/<library>` (extracted to a content-addressed directory under
 * `java.io.tmpdir`, reused across runs).
 */
object NativeLib {
    const val PATH_PROPERTY = "aether.native.path"

    val os: String = System.getProperty("os.name").lowercase(Locale.ROOT).let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") || it.contains("darwin") -> "macos"
            else -> "linux"
        }
    }
    val arch: String = when (val a = System.getProperty("os.arch").lowercase(Locale.ROOT)) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }
    val platformDir: String = "$os-$arch"
    val libraryFileName: String = when (os) {
        "windows" -> "aether_native.dll"
        "macos" -> "libaether_native.dylib"
        else -> "libaether_native.so"
    }

    internal val linker: Linker = Linker.nativeLinker()
    private val lookupOrNull: SymbolLookup?
    val loadError: Throwable?
    val libraryPath: Path?

    init {
        var lookup: SymbolLookup? = null
        var error: Throwable? = null
        var path: Path? = null
        try {
            path = locate()
            lookup = SymbolLookup.libraryLookup(path, Arena.global())
        } catch (t: Throwable) {
            error = t
        }
        lookupOrNull = lookup
        loadError = error
        libraryPath = path
    }

    val available: Boolean get() = lookupOrNull != null

    /** `major << 16 | minor << 8 | patch` from `aether_version()`, or -1 when unavailable. */
    val version: Int by lazy {
        if (available) downcall("aether_version", FunctionDescriptor.of(JAVA_INT), Linker.Option.isTrivial()).invoke() as Int else -1
    }
    val versionString: String get() = "${version ushr 16}.${(version ushr 8) and 0xFF}.${version and 0xFF}"

    /** `size_of::<PacketDesc>()` reported by the library (must equal [PacketDesc.SIZE]). */
    val packetDescSize: Long by lazy {
        if (available) downcall("aether_packet_desc_size", FunctionDescriptor.of(JAVA_LONG)).invoke() as Long else -1L
    }

    internal fun downcall(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle {
        val lookup = lookupOrNull ?: throw IllegalStateException("aether_native is not loaded", loadError)
        val symbol = lookup.find(name).orElseThrow { UnsatisfiedLinkError("symbol $name missing from $libraryPath") }
        return linker.downcallHandle(symbol, descriptor, *options)
    }

    private fun locate(): Path {
        System.getProperty(PATH_PROPERTY)?.let { explicit ->
            val p = Paths.get(explicit)
            val file = if (Files.isDirectory(p)) p.resolve(libraryFileName) else p
            if (!Files.isRegularFile(file)) throw UnsatisfiedLinkError("-D$PATH_PROPERTY=$explicit does not point at $libraryFileName")
            return file
        }
        val resource = "/native/$platformDir/$libraryFileName"
        val bytes = NativeLib::class.java.getResourceAsStream(resource)?.use { it.readAllBytes() }
            ?: throw UnsatisfiedLinkError("aether_native not found on the classpath at $resource and -D$PATH_PROPERTY is not set")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "aether-native-$digest")
        Files.createDirectories(dir)
        val target = dir.resolve(libraryFileName)
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.size.toLong()) {
            val tmp = Files.createTempFile(dir, "extract-", ".tmp")
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                // Another process may hold the DLL open (Windows). Whatever is there has the same hash.
                Files.deleteIfExists(tmp)
                if (!Files.isRegularFile(target)) throw e
            }
        }
        return target
    }

    /** Human-readable rendering of a (positive) OS error code returned by the library. */
    fun errorString(code: Long): String {
        val name = when (code) {
            1L -> "EPERM"; 11L, 10035L -> "EAGAIN/WSAEWOULDBLOCK"; 13L -> "EACCES"; 22L, 10022L -> "EINVAL/WSAEINVAL"
            98L, 10048L -> "EADDRINUSE"; 99L, 10049L -> "EADDRNOTAVAIL"; 10040L -> "WSAEMSGSIZE"; 10054L -> "WSAECONNRESET"
            97L, 10047L -> "EAFNOSUPPORT"; else -> null
        }
        return if (name != null) "$name (OS error $code)" else "OS error $code"
    }
}

/**
 * `aether_gf256_muladd`: `dst[i] ^= src[i] * c` over GF(256)/0x11D, bit-identical to
 * [aether.core.GF256.Scalar] but SIMD (AVX2/SSSE3/NEON) and one FFI crossing per symbol.
 *
 * It is a [GF256.OffHeapKernel]: [install] plugs it into [GF256.kernel], after which `RlncEncoder`/`RlncDecoder`
 * keep their accumulators (and the encoder its window) off-heap and call the segment overloads — no copies on the
 * `repair()` hot loop. The `ByteArray` overload stays available for the remaining heap call sites.
 */
object Gf256Native : GF256.OffHeapKernel {
    val available: Boolean = NativeLib.available

    /**
     * Makes this the process-wide GF(256) kernel (`GF256.useNative(Gf256Native)`). Idempotent. Returns false, and
     * leaves the scalar kernel in place, when the native library did not load (see [NativeLib.loadError]).
     */
    fun install(): Boolean {
        if (!available) return false
        if (GF256.kernel !== this) GF256.useNative(this)
        return true
    }

    /** True while [GF256.kernel] is this object. */
    val installed: Boolean get() = GF256.kernel === this

    private val mulAddHandle: MethodHandle? = if (available) {
        NativeLib.downcall(
            "aether_gf256_muladd",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, JAVA_BYTE),
            Linker.Option.isTrivial(), // short, non-blocking, never calls back: skip the thread-state transition
        )
    } else null

    /** Kernel chosen by the library for this CPU: `scalar`, `ssse3`, `avx2`, `neon`, or `unavailable`. */
    val implementation: String = if (!available) "unavailable" else {
        when (NativeLib.downcall("aether_gf256_impl", FunctionDescriptor.of(JAVA_INT)).invoke() as Int) {
            0 -> "scalar"; 1 -> "ssse3"; 2 -> "avx2"; 3 -> "neon"; else -> "unknown"
        }
    }

    /**
     * Off-heap hot path: `dst[0..len) ^= src[0..len) * c`. Both segments must be native (JDK 21
     * cannot hand heap segments to a downcall) and must not overlap. `c` is taken modulo 256;
     * `c == 0` is a no-op, as in the Kotlin implementation.
     */
    override fun mulAddInto(dst: MemorySegment, src: MemorySegment, len: Long, c: Int) {
        val handle = mulAddHandle ?: throw IllegalStateException("aether_native is not available", NativeLib.loadError)
        require(len >= 0) { "len must be >= 0" }
        require(len <= dst.byteSize() && len <= src.byteSize()) { "len $len exceeds dst (${dst.byteSize()}) or src (${src.byteSize()})" }
        require(dst.isNative && src.isNative) { "dst and src must be off-heap (native) segments" }
        if ((c and 0xFF) == 0 || len == 0L) return
        val d = dst.address()
        val s = src.address()
        require(d + len <= s || s + len <= d) { "dst and src must not overlap" }
        handle.invoke(dst, src, len, c.toByte())
    }

    private class Scratch {
        var segment: MemorySegment = Arena.ofAuto().allocate(2L * 4096, 64)
        fun ensure(bytes: Long): MemorySegment {
            if (segment.byteSize() < bytes) segment = Arena.ofAuto().allocate(maxOf(bytes, segment.byteSize() * 2), 64)
            return segment
        }
    }
    private val scratch: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

    /**
     * `ByteArray` convenience with the same contract as [aether.core.GF256.mulAddInto]
     * (`dst.size` bytes are processed; `src.size >= dst.size`).
     *
     * Cost: JDK 21 downcalls cannot pin heap arrays, so this copies `dst` and `src` into a
     * thread-local off-heap scratch buffer, runs the kernel, and copies `dst` back — three extra
     * `memcpy`s of `dst.size` bytes on top of the kernel (measurably slower than the
     * [MemorySegment] overload, still far faster than scalar Kotlin on 1200-byte symbols).
     * Prefer keeping symbols in an [Arena] and using the segment overload on the hot path.
     */
    override fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int) {
        require(src.size >= dst.size) { "src (${src.size}) shorter than dst (${dst.size})" }
        if ((c and 0xFF) == 0 || dst.isEmpty()) return
        val n = dst.size.toLong()
        val s = scratch.get().ensure(2 * n)
        val dSeg = s.asSlice(0, n)
        val sSeg = s.asSlice(n, n)
        MemorySegment.copy(dst, 0, dSeg, JAVA_BYTE, 0L, dst.size)
        MemorySegment.copy(src, 0, sSeg, JAVA_BYTE, 0L, dst.size)
        mulAddInto(dSeg, sSeg, n, c)
        MemorySegment.copy(dSeg, JAVA_BYTE, 0L, dst, 0, dst.size)
    }

    /**
     * `dst[0, src.size) ^= src * c` with an off-heap accumulator and a heap source: one `memcpy` of `src` into the
     * thread-local scratch, then the kernel — what `RlncDecoder.onRepair` uses to fold known symbols in.
     */
    override fun mulAddInto(dst: MemorySegment, src: ByteArray, c: Int) {
        val n = src.size.toLong()
        require(n <= dst.byteSize()) { "dst (${dst.byteSize()}) shorter than src ($n)" }
        if ((c and 0xFF) == 0 || n == 0L) return
        val sSeg = scratch.get().ensure(n).asSlice(0, n)
        MemorySegment.copy(src, 0, sSeg, JAVA_BYTE, 0L, src.size)
        mulAddInto(dst, sSeg, n, c)
    }
}

/**
 * Layout of the Rust `#[repr(C)] struct PacketDesc { buf: *mut u8, len: u32, cap: u32, addr: [u8; 16], port: u16, family: u8 }`
 * (40 bytes on 64-bit targets, trailing padding included).
 */
object PacketDesc {
    val LAYOUT: StructLayout = MemoryLayout.structLayout(
        ADDRESS.withName("buf"),
        JAVA_INT.withName("len"),
        JAVA_INT.withName("cap"),
        MemoryLayout.sequenceLayout(16, JAVA_BYTE).withName("addr"),
        JAVA_SHORT.withName("port"),
        JAVA_BYTE.withName("family"),
        MemoryLayout.paddingLayout(5),
    )
    val SIZE: Long = LAYOUT.byteSize()
    val OFF_BUF: Long = LAYOUT.byteOffset(PathElement.groupElement("buf"))
    val OFF_LEN: Long = LAYOUT.byteOffset(PathElement.groupElement("len"))
    val OFF_CAP: Long = LAYOUT.byteOffset(PathElement.groupElement("cap"))
    val OFF_ADDR: Long = LAYOUT.byteOffset(PathElement.groupElement("addr"))
    val OFF_PORT: Long = LAYOUT.byteOffset(PathElement.groupElement("port"))
    val OFF_FAMILY: Long = LAYOUT.byteOffset(PathElement.groupElement("family"))

    const val FAMILY_NONE: Byte = 0
    const val FAMILY_IPV4: Byte = 4
    const val FAMILY_IPV6: Byte = 6

    fun writeAddress(descs: MemorySegment, base: Long, addr: InetSocketAddress) {
        val ip: InetAddress = addr.address ?: throw IllegalArgumentException("unresolved address: $addr")
        val bytes = ip.address
        descs.asSlice(base + OFF_ADDR, 16).fill(0)
        MemorySegment.copy(bytes, 0, descs, JAVA_BYTE, base + OFF_ADDR, bytes.size)
        descs.set(JAVA_SHORT, base + OFF_PORT, addr.port.toShort())
        descs.set(JAVA_BYTE, base + OFF_FAMILY, if (ip is Inet6Address) FAMILY_IPV6 else FAMILY_IPV4)
    }

    fun readAddress(descs: MemorySegment, base: Long): InetSocketAddress? {
        val family = descs.get(JAVA_BYTE, base + OFF_FAMILY)
        val size = when (family) { FAMILY_IPV4 -> 4; FAMILY_IPV6 -> 16; else -> return null }
        val bytes = ByteArray(size)
        MemorySegment.copy(descs, JAVA_BYTE, base + OFF_ADDR, bytes, 0, size)
        val port = descs.get(JAVA_SHORT, base + OFF_PORT).toInt() and 0xFFFF
        return InetSocketAddress(InetAddress.getByAddress(bytes), port)
    }
}

/**
 * A fixed-size batch of datagram slots: `capacity` descriptors plus `capacity × bufferSize` bytes of
 * payload, all off-heap in one [Arena] so a whole batch crosses the FFI boundary as two pointers.
 * Not thread-safe; use one batch per direction per thread.
 */
class PacketBatch(val capacity: Int, val bufferSize: Int = DEFAULT_BUFFER_SIZE, arena: Arena = Arena.ofAuto()) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(bufferSize > 0) { "bufferSize must be > 0" }
    }

    internal val descs: MemorySegment = arena.allocate(PacketDesc.SIZE * capacity, 8)
    private val buffers: MemorySegment = arena.allocate(bufferSize.toLong() * capacity, 64)

    init {
        for (i in 0 until capacity) {
            val base = i * PacketDesc.SIZE
            descs.set(ADDRESS, base + PacketDesc.OFF_BUF, buffer(i))
            descs.set(JAVA_INT, base + PacketDesc.OFF_LEN, 0)
            descs.set(JAVA_INT, base + PacketDesc.OFF_CAP, bufferSize)
            descs.set(JAVA_BYTE, base + PacketDesc.OFF_FAMILY, PacketDesc.FAMILY_NONE)
        }
    }

    private fun base(i: Int): Long {
        require(i in 0 until capacity) { "slot $i out of range 0..${capacity - 1}" }
        return i * PacketDesc.SIZE
    }

    /** The full `bufferSize`-byte payload slot `i` (write into it, then [setLength]). */
    fun buffer(i: Int): MemorySegment = buffers.asSlice(i.toLong() * bufferSize, bufferSize.toLong())

    fun length(i: Int): Int = descs.get(JAVA_INT, base(i) + PacketDesc.OFF_LEN)

    fun setLength(i: Int, length: Int) {
        require(length in 0..bufferSize) { "length $length outside 0..$bufferSize" }
        descs.set(JAVA_INT, base(i) + PacketDesc.OFF_LEN, length)
    }

    /** Destination (before send) or sender (after receive) of slot `i`; null if unset. */
    fun address(i: Int): InetSocketAddress? = PacketDesc.readAddress(descs, base(i))

    /** Like [address] but allocation-free while consecutive datagrams come from the same sender (see [SockAddrCache]). */
    fun address(i: Int, cache: SockAddrCache): InetSocketAddress? = cache.resolve(descs, base(i))

    fun setAddress(i: Int, address: InetSocketAddress) = PacketDesc.writeAddress(descs, base(i), address)

    /** Copies `data[offset, offset + length)` into slot `i` and sets its destination. */
    fun put(i: Int, data: ByteArray, to: InetSocketAddress, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "bad range $offset+$length for ${data.size} bytes" }
        require(length <= bufferSize) { "payload $length exceeds bufferSize $bufferSize" }
        MemorySegment.copy(data, offset, buffer(i), JAVA_BYTE, 0L, length)
        setLength(i, length)
        setAddress(i, to)
    }

    /** Copies the `length(i)` valid bytes of slot `i` out to a new array. */
    fun get(i: Int): ByteArray = buffer(i).asSlice(0, length(i).toLong()).toArray(JAVA_BYTE)

    companion object {
        const val DEFAULT_BUFFER_SIZE = 2048
    }
}

/**
 * Single-entry cache turning a descriptor's `(addr, port, family)` into an [InetSocketAddress] without allocating
 * while the sender does not change — on a busy socket every datagram of a batch typically comes from the same
 * peer, and `DatagramChannel.receive` allocating a fresh address per datagram is one of the costs the native rx
 * path removes. Not thread-safe; one per rx thread.
 */
class SockAddrCache {
    private var a0 = 0L; private var a1 = 0L; private var pf = 0L
    private var cached: InetSocketAddress? = null
    /** How many times a new [InetSocketAddress] had to be built (diagnostics). */
    var misses = 0L; private set

    internal fun resolve(descs: MemorySegment, base: Long): InetSocketAddress? {
        // addr[16] as two longs, then port(2) | family(1) | zero padding(5) as one long: 24 bytes, three compares
        val x0 = descs.get(JAVA_LONG, base + PacketDesc.OFF_ADDR)
        val x1 = descs.get(JAVA_LONG, base + PacketDesc.OFF_ADDR + 8)
        val x2 = descs.get(JAVA_LONG, base + PacketDesc.OFF_PORT)
        val hit = cached
        if (hit != null && x0 == a0 && x1 == a1 && x2 == pf) return hit
        val fresh = PacketDesc.readAddress(descs, base) ?: return null
        a0 = x0; a1 = x1; pf = x2; cached = fresh; misses++
        return fresh
    }
}

/**
 * Transmit-side batch: datagrams are appended back-to-back into one contiguous off-heap segment (no per-slot
 * alignment), each with its own descriptor, so a run of equal-size datagrams to one destination is laid out exactly
 * as UDP GSO wants it (see [runEnd]) while mixed runs still go out with one `sendmmsg`. Not thread-safe: one batch
 * per sending thread.
 */
class TxBatch(val capacity: Int, val bytes: Int, arena: Arena = Arena.ofAuto()) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(bytes > 0) { "bytes must be > 0" }
    }

    internal val descs: MemorySegment = arena.allocate(PacketDesc.SIZE * capacity, 8)
    /** Payload area; datagram `i` occupies `[offset(i), offset(i) + length(i))`. */
    val data: MemorySegment = arena.allocate(bytes.toLong(), 64)
    private val offsets = LongArray(capacity)
    private val dataAddress = data.address()
    private var lastTo: InetSocketAddress? = null
    private var lastBase = -1L

    /** Datagrams queued. */
    var count: Int = 0
        private set
    /** Payload bytes queued. */
    var used: Long = 0
        private set

    val isEmpty: Boolean get() = count == 0
    val isFull: Boolean get() = count == capacity
    fun hasRoom(length: Int): Boolean = count < capacity && used + length <= bytes

    /**
     * Appends `src[position, limit)` as one datagram to `to` (the buffer's position is left untouched).
     * Returns false, leaving the batch unchanged, when it does not fit — flush and retry.
     */
    fun add(src: ByteBuffer, to: InetSocketAddress): Boolean {
        val len = src.remaining()
        if (!hasRoom(len)) return false
        MemorySegment.copy(MemorySegment.ofBuffer(src), 0L, data, used, len.toLong())
        append(len, to)
        return true
    }

    /** Appends `src[offset, offset + length)` as one datagram to `to`; false (unchanged) when it does not fit. */
    fun add(src: ByteArray, to: InetSocketAddress, offset: Int = 0, length: Int = src.size - offset): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) { "bad range $offset+$length for ${src.size} bytes" }
        if (!hasRoom(length)) return false
        MemorySegment.copy(src, offset, data, JAVA_BYTE, used, length)
        append(length, to)
        return true
    }

    private fun append(len: Int, to: InetSocketAddress) {
        val base = count * PacketDesc.SIZE
        descs.set(JAVA_LONG, base + PacketDesc.OFF_BUF, dataAddress + used)
        descs.set(JAVA_INT, base + PacketDesc.OFF_LEN, len)
        descs.set(JAVA_INT, base + PacketDesc.OFF_CAP, len)
        if (to === lastTo && lastBase >= 0) {
            // same destination object as the previous datagram (a connection's `peer`): copy the 24 encoded bytes
            MemorySegment.copy(descs, lastBase + PacketDesc.OFF_ADDR, descs, base + PacketDesc.OFF_ADDR, PacketDesc.SIZE - PacketDesc.OFF_ADDR)
        } else {
            PacketDesc.writeAddress(descs, base, to)
            lastTo = to
        }
        lastBase = base
        offsets[count] = used
        used += len
        count++
    }

    private fun base(i: Int): Long {
        require(i in 0 until count) { "datagram $i out of range 0..${count - 1}" }
        return i * PacketDesc.SIZE
    }

    fun length(i: Int): Int = descs.get(JAVA_INT, base(i) + PacketDesc.OFF_LEN)
    fun offset(i: Int): Long { base(i); return offsets[i] }
    fun address(i: Int): InetSocketAddress? = PacketDesc.readAddress(descs, base(i))
    /** Copies datagram `i` out (tests / diagnostics). */
    fun get(i: Int): ByteArray = data.asSlice(offset(i), length(i).toLong()).toArray(JAVA_BYTE)

    /** Whether datagrams `i` and `j` carry the same destination (address, port, family). */
    fun sameDestination(i: Int, j: Int): Boolean {
        val a = base(i) + PacketDesc.OFF_ADDR; val b = base(j) + PacketDesc.OFF_ADDR
        return descs.get(JAVA_LONG, a) == descs.get(JAVA_LONG, b) &&
            descs.get(JAVA_LONG, a + 8) == descs.get(JAVA_LONG, b + 8) &&
            descs.get(JAVA_LONG, a + 16) == descs.get(JAVA_LONG, b + 16)
    }

    /**
     * End (exclusive) of the GSO-able run starting at `i`: consecutive datagrams to the same destination with the
     * same length as datagram `i`, optionally closed by one shorter datagram (the last GSO segment may be short),
     * at most `maxSegments` of them and `maxBytes` in total. A result of `i + 1` means "no run".
     */
    fun runEnd(i: Int, maxSegments: Int, maxBytes: Int): Int {
        val segSize = length(i)
        var total = segSize
        var j = i + 1
        while (j < count && j - i < maxSegments && sameDestination(i, j)) {
            val lj = length(j)
            if (lj > segSize || total + lj > maxBytes) break
            total += lj; j++
            if (lj < segSize) break
        }
        return j
    }

    /** Forgets every queued datagram (the memory is reused). */
    fun clear() { count = 0; used = 0; lastBase = -1 }
}

/**
 * A non-blocking UDP socket driven through `aether_udp_*`: batch send/receive over [PacketBatch]
 * buffers (`sendmmsg`/`recvmmsg` on Linux, Winsock loops on Windows — one FFI crossing per batch
 * either way), UDP GSO, and `SO_BUSY_POLL`.
 *
 * The socket itself may be used from several threads (e.g. one sender, one receiver), but each
 * [PacketBatch] must be confined to one thread at a time.
 */
class NativeUdp(bind: String = "0.0.0.0", port: Int = 0) : AutoCloseable {
    private object Handles {
        val open = NativeLib.downcall("aether_udp_open", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_SHORT))
        val close = NativeLib.downcall("aether_udp_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
        val localPort = NativeLib.downcall("aether_udp_local_port", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
        val sendBatch = NativeLib.downcall("aether_udp_send_batch", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG))
        val recvBatch = NativeLib.downcall("aether_udp_recv_batch", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT))
        val sendGso = NativeLib.downcall("aether_udp_send_gso", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_SHORT, ADDRESS))
        val busyPoll = NativeLib.downcall("aether_busy_poll", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_BOOLEAN))
    }

    /** OS descriptor (Unix) or `SOCKET` handle (Windows). */
    val fd: Long
    val localPort: Int
    private val closed = AtomicBoolean(false)

    init {
        require(port in 0..65535) { "port $port out of range" }
        if (!NativeLib.available) throw IllegalStateException("aether_native is not available", NativeLib.loadError)
        val handle = Arena.ofConfined().use { arena ->
            Handles.open.invoke(arena.allocateUtf8String(bind), port.toShort()) as Long
        }
        if (handle < 0) throw IOException("aether_udp_open($bind:$port) failed: ${NativeLib.errorString(-handle)}")
        fd = handle
        val p = Handles.localPort.invoke(fd) as Int
        if (p < 0) {
            Handles.close.invoke(fd)
            throw IOException("aether_udp_local_port failed: ${NativeLib.errorString(-p.toLong())}")
        }
        localPort = p
    }

    private fun checkOpen() = check(!closed.get()) { "socket is closed" }

    /**
     * Sends slots `[0, count)` of `batch` to their addresses. Returns how many datagrams the kernel
     * accepted; fewer than `count` means the socket would block — retry the remainder.
     */
    fun sendBatch(batch: PacketBatch, count: Int = batch.capacity): Int {
        require(count in 0..batch.capacity) { "count $count outside 0..${batch.capacity}" }
        checkOpen()
        if (count == 0) return 0
        val r = Handles.sendBatch.invoke(fd, batch.descs, count.toLong()) as Int
        if (r < 0) throw IOException("aether_udp_send_batch failed: ${NativeLib.errorString(-r.toLong())}")
        return r
    }

    /**
     * Receives up to `max` datagrams into slots `[0, max)` of `batch`, filling lengths and sender
     * addresses. `timeoutMs > 0` waits that long for the first datagram, `0` polls, `< 0` blocks.
     * Returns the number received (0 on timeout).
     *
     * The library adapts per socket: while datagrams trickle in it does one blocking receive per
     * call (one syscall per datagram, like a classic receive loop); once a backlog is detected it
     * switches the socket to non-blocking and drains it eagerly (`recvmmsg` on Linux), N datagrams
     * per N+1 syscalls. So one rx thread per socket, please — the mode is socket state.
     */
    fun recvBatch(batch: PacketBatch, timeoutMs: Int, max: Int = batch.capacity): Int {
        require(max in 0..batch.capacity) { "max $max outside 0..${batch.capacity}" }
        checkOpen()
        if (max == 0) return 0
        val r = Handles.recvBatch.invoke(fd, batch.descs, max.toLong(), timeoutMs) as Int
        if (r < 0) throw IOException("aether_udp_recv_batch failed: ${NativeLib.errorString(-r.toLong())}")
        return r
    }

    /**
     * Sends datagrams `[from, from + count)` of a [TxBatch] (one `sendmmsg` on Linux, a `WSASendTo` loop on
     * Windows — one FFI crossing either way). Returns how many the kernel accepted; fewer than `count` means the
     * socket would block — retry the remainder.
     */
    fun sendBatch(batch: TxBatch, from: Int = 0, count: Int = batch.count - from): Int {
        require(from >= 0 && count >= 0 && from + count <= batch.count) { "range $from+$count outside 0..${batch.count}" }
        checkOpen()
        if (count == 0) return 0
        val r = Handles.sendBatch.invoke(fd, batch.descs.asSlice(from * PacketDesc.SIZE), count.toLong()) as Int
        if (r < 0) throw IOException("aether_udp_send_batch failed: ${NativeLib.errorString(-r.toLong())}")
        return r
    }

    /**
     * Sends datagrams `[from, end)` of a [TxBatch] — a run as delimited by [TxBatch.runEnd] — as one GSO
     * super-datagram (`UDP_SEGMENT` on Linux; user-space segmentation elsewhere, same bytes on the wire).
     * Returns how many datagrams were sent (fewer than `end - from` only when the fallback path would block).
     */
    fun sendGso(batch: TxBatch, from: Int, end: Int): Int {
        require(from >= 0 && end > from && end <= batch.count) { "range $from..$end outside 0..${batch.count}" }
        checkOpen()
        val segSize = batch.length(from)
        val start = batch.offset(from)
        val total = (batch.offset(end - 1) + batch.length(end - 1) - start).toInt()
        require(segSize in 1..65535) { "segment size $segSize outside 1..65535" }
        // the destination descriptor is the run's own first descriptor (only its addr/port/family are read)
        val r = Handles.sendGso.invoke(fd, batch.data.asSlice(start), total.toLong(), segSize.toShort(), batch.descs.asSlice(from * PacketDesc.SIZE)) as Int
        if (r < 0) throw IOException("aether_udp_send_gso failed: ${NativeLib.errorString(-r.toLong())}")
        var sentBytes = r; var n = 0
        while (n < end - from && sentBytes >= batch.length(from + n)) { sentBytes -= batch.length(from + n); n++ }
        return n
    }

    /**
     * Sends `data[0, totalLen)` as consecutive `segSize`-byte datagrams to `to` — kernel GSO
     * (`UDP_SEGMENT`) on Linux, a sendto loop elsewhere. Returns payload bytes sent.
     */
    fun sendGso(data: MemorySegment, totalLen: Int, segSize: Int, to: InetSocketAddress): Int {
        require(data.isNative) { "data must be an off-heap segment" }
        require(totalLen in 1..data.byteSize().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) { "totalLen $totalLen outside 1..${data.byteSize()}" }
        require(segSize in 1..65535) { "segSize $segSize outside 1..65535" }
        checkOpen()
        Arena.ofConfined().use { arena ->
            val dst = arena.allocate(PacketDesc.LAYOUT)
            PacketDesc.writeAddress(dst, 0, to)
            val r = Handles.sendGso.invoke(fd, data, totalLen.toLong(), segSize.toShort(), dst) as Int
            if (r < 0) throw IOException("aether_udp_send_gso failed: ${NativeLib.errorString(-r.toLong())}")
            return r
        }
    }

    /** `SO_BUSY_POLL` on Linux (0 on success, negative errno — typically -EPERM — otherwise); no-op elsewhere. */
    fun busyPoll(on: Boolean): Int {
        checkOpen()
        return Handles.busyPoll.invoke(fd, on) as Int
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) Handles.close.invoke(fd)
    }

    override fun toString(): String = "NativeUdp(fd=$fd, localPort=$localPort${if (closed.get()) ", closed" else ""})"
}
