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
}
