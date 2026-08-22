package aether.transport

import java.nio.ByteBuffer

/** Preallocated direct buffers for the tx path. Thread-safe; grows (and counts it) only if exhausted. */
class BufferPool(count: Int, val size: Int) {
    private val free = ArrayDeque<ByteBuffer>(count)
    private val cap = count
    @Volatile var overflowAllocations = 0L; private set

    init { repeat(count) { free.addLast(ByteBuffer.allocateDirect(size)) } }

    @Synchronized fun acquire(): ByteBuffer =
        free.removeLastOrNull()?.also { it.clear() } ?: ByteBuffer.allocateDirect(size).also { overflowAllocations++ }

    @Synchronized fun release(b: ByteBuffer) { if (free.size < cap) free.addLast(b) }
}
