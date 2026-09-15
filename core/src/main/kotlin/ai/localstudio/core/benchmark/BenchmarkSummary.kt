package ai.localstudio.core.benchmark

/** One row of the "Backend | Model | Files | Avg RTF | Median RTF | Avg time | Peak RAM | Errors" summary table. */
data class BenchmarkSummaryRow(
    val backendId: String,
    val displayName: String,
    val modelId: String,
    val fileCount: Int,
    val successCount: Int,
    val errorCount: Int,
    val avgRtf: Double?,
    val medianRtf: Double?,
    val avgProcessingMs: Double?,
    val totalProcessingMs: Long,
    val peakMemoryMb: Long?,
)

/**
 * Median, not just average, per the explicit requirement: a single slow
 * outlier (one large file, one thermal-throttled run) skews an average far
 * more than it skews a median, and this exists specifically so that one
 * bad file doesn't quietly misrepresent a backend that is normally fine.
 */
object BenchmarkSummary {
    fun summarize(report: BenchmarkReport): List<BenchmarkSummaryRow> {
        val byBackend = report.files.flatMap { it.results }.groupBy { it.backendId }
        return report.engines.map { engine ->
            val results = byBackend[engine.backendId].orEmpty()
            val success = results.filter { it.status == BenchmarkStatus.SUCCESS }
            val rtfs = success.mapNotNull { it.rtf }.sorted()
            BenchmarkSummaryRow(
                backendId = engine.backendId,
                displayName = engine.displayName,
                modelId = engine.modelId,
                fileCount = results.size,
                successCount = success.size,
                errorCount = results.size - success.size,
                avgRtf = rtfs.takeIf { it.isNotEmpty() }?.average(),
                medianRtf = median(rtfs),
                avgProcessingMs = success.map { it.processingMs.toDouble() }.takeIf { it.isNotEmpty() }?.average(),
                totalProcessingMs = results.sumOf { it.processingMs },
                peakMemoryMb = results.mapNotNull { it.memoryMb }.maxOrNull(),
            )
        }
    }

    private fun median(sorted: List<Double>): Double? {
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    /**
     * [orderedRtfs] must already be in execution order (first file
     * transcribed first), not grouped/sorted by backend or file the way
     * [report]'s own structure is — see [BenchmarkPerformanceTrend]'s own
     * doc comment for why this needs to be execution order specifically.
     * [BenchmarkPerformanceTrend.degradationPercent] is null whenever the
     * first RTF is null or non-positive, never a divide-by-zero or
     * divide-by-negative result.
     */
    fun computeTrend(orderedRtfs: List<Double>): BenchmarkPerformanceTrend {
        val first = orderedRtfs.firstOrNull()
        val last = orderedRtfs.lastOrNull()
        val average = orderedRtfs.takeIf { it.isNotEmpty() }?.average()
        val degradation = if (first != null && first > 0 && last != null) ((last - first) / first) * 100.0 else null
        return BenchmarkPerformanceTrend(
            firstRtf = first,
            averageRtf = average,
            lastRtf = last,
            degradationPercent = degradation,
        )
    }
}
