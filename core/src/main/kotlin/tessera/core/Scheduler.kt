package tessera.core

/**
 * Multipath scheduler: earliest-completion-first across paths using PathEstimator.expectedCompletionUs.
 * Repair symbols go to a path other than the source packet's, so a single-path blackout never stalls decoding.
 */
class Scheduler(private val paths: MutableMap<PathId, PathEstimator> = HashMap()) {
    fun add(est: PathEstimator) { paths[est.path] = est }
    fun remove(p: PathId) { paths.remove(p) }
    fun pick(bytes: Int, exclude: PathId? = null): PathId? =
        paths.values.filter { it.path != exclude }.minByOrNull { it.expectedCompletionUs(bytes) }?.path
    fun repairPathFor(sourcePath: PathId): PathId = pick(Wire.MAX_DATAGRAM, exclude = sourcePath) ?: sourcePath
}
