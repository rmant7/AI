package ai.localstudio.commercialmemory

import kotlinx.serialization.Serializable

/**
 * Which memory path a turn used — the whole reason [MemoryExperimentRunner]
 * and [ExperimentLogger] exist: to make it possible to measure whether the
 * commercial layer actually helps, instead of assuming it does.
 */
@Serializable
enum class ExperimentMode {
    /** No memory retrieval at all — the model sees only the ordinary conversation context. */
    MEMORY_OFF,

    /** Plain retrieval, no commercial ranking or budget beyond a flat item/character cap. */
    BASIC_MEMORY,

    /** Full private pipeline: retrieve, then [ContextRanker], then [ContextSelector]'s budget. */
    COMMERCIAL_MEMORY,
}
