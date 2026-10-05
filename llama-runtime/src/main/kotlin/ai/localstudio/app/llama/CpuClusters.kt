package ai.localstudio.app.llama

/**
 * How many threads llama.cpp should use, from each core's max frequency:
 * every core within [PERFORMANCE_RATIO] of the fastest one. ggml splits each
 * matmul evenly across its threads, so an efficiency core holds every other
 * thread up -- those are left out; but a SoC's mid cluster is not one of
 * them. Counting only the cores at the very top frequency gave a Tensor G5
 * (1 x 3.78 GHz + 5 x 3.05 GHz + 2 x 2.25 GHz) a single thread for every
 * local model.
 *
 * At 0.78: Tensor G5 -> 6 (3.05/3.78 = 0.81 in, 2.25/3.78 = 0.60 out);
 * Snapdragon 8 Gen 1 (1 x 3.0 + 3 x 2.5 + 4 x 1.8) -> 4; a 4 x 2.4 + 4 x 1.8
 * big.LITTLE -> 4 (0.75 out); Snapdragon 8 Elite (2 x 4.32 + 6 x 3.53) -> 8.
 *
 * Null when the frequencies are unusable (none, or not one per core) or all
 * equal -- nothing to tell apart; the caller falls back to its flat guess.
 */
object CpuClusters {
    const val PERFORMANCE_RATIO = 0.78

    fun performanceCoreCount(maxFreqs: List<Long>, totalCores: Int): Int? {
        if (maxFreqs.size < totalCores || maxFreqs.isEmpty()) return null
        val top = maxFreqs.max()
        if (top <= 0 || maxFreqs.all { it == top }) return null
        return maxFreqs.count { it >= top * PERFORMANCE_RATIO }.coerceIn(1, totalCores)
    }
}
