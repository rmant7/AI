package ai.localstudio.commercialmemory

import kotlinx.serialization.Serializable

/**
 * One turn's worth of measurement — enough to later ask "did COMMERCIAL_MEMORY
 * actually select better context than BASIC_MEMORY, and was it worth the
 * extra latency?" without having collected that answer yet. This is
 * Stage 3's entire contribution to Stage 4: instrumentation, not a verdict.
 * [responseQualityScore] is deliberately optional and never computed
 * automatically anywhere in this module — nothing here predicts or learns
 * response quality; a caller who has one from elsewhere (a rating, an eval)
 * may attach it.
 */
@Serializable
data class ExperimentRecord(
    val experimentId: String,
    val timestampEpochMs: Long,
    val mode: ExperimentMode,
    val queryLength: Int,
    val candidateCount: Int,
    val selectedCount: Int,
    val selectedCharacters: Int,
    val latencyMs: Long,
    val responseQualityScore: Double? = null,
    /**
     * Of [candidateCount], how many were found *only* by semantic search —
     * no lexical match at all. Zero for as long as no embedder is
     * configured, or every retrieval happens to share vocabulary with what
     * it finds; the number that actually answers whether semantic retrieval
     * is contributing anything a lexical-only search wouldn't have.
     */
    val semanticOnlyCandidateCount: Int = 0,
)
