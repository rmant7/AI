package ai.localstudio.app.llama

/**
 * Candidate embedding models for [ai.localstudio.memory.MemorySemanticIndex] —
 * **experimental, deliberately not wired into anything.** None of these are
 * referenced by [ai.localstudio.app.models.LocalModels], the model-download
 * UI, or [ai.localstudio.app.AppContainer]. See
 * `Mobile_mem0/SEMANTIC_RETRIEVAL_DESIGN.md`'s "Choosing the concrete model"
 * section for why: picking one needs verifying it actually exists as listed,
 * behaves correctly through [LlamaCppMemoryEmbedder], and uses the right
 * [pooling] — none of which this file, written without device or network
 * access, could do. This exists only to give manual, on-device verification
 * (see [ai.localstudio.app.LlamaNativeTest]'s embedding-model sibling test)
 * something concrete to load, one candidate at a time.
 *
 * [fileName] is deliberately left unset here rather than guessed — confirm
 * the exact file name from the repo's own listing before downloading; a
 * repo commonly hosts several quantizations under different names.
 */
data class EmbeddingModelSpec(
    val id: String,
    /** Display name for [ExperimentalEmbeddingsActivity] — never shown anywhere in the production model catalog. */
    val title: String,
    val repoId: String,
    val quantLabel: String,
    val dimension: Int,
    val pooling: EmbeddingPooling,
    val queryPrefix: String?,
    val passagePrefix: String?,
)

object ExperimentalEmbeddingModels {

    /**
     * First candidate to test the JNI path with: small enough for a fast
     * first device test, per the e5 family's own documented convention of
     * `"query: "`/`"passage: "` prefixes on an asymmetric encoder trained
     * for mean pooling.
     */
    val E5_SMALL = EmbeddingModelSpec(
        id = "multilingual-e5-small-iq4xs",
        title = "Multilingual E5 Small",
        repoId = "cstr/multilingual-e5-small-GGUF",
        quantLabel = "IQ4_XS",
        dimension = 384,
        pooling = EmbeddingPooling.MEAN,
        queryPrefix = "query: ",
        passagePrefix = "passage: ",
    )

    /** Second candidate: same family, larger — for a real quality comparison against [E5_SMALL] once both load correctly. */
    val E5_BASE = EmbeddingModelSpec(
        id = "multilingual-e5-base-q4km",
        title = "Multilingual E5 Base",
        repoId = "groonga/multilingual-e5-base-Q4_K_M-GGUF",
        quantLabel = "Q4_K_M",
        dimension = 768,
        pooling = EmbeddingPooling.MEAN,
        queryPrefix = "query: ",
        passagePrefix = "passage: ",
    )

    /** Every candidate, for [ExperimentalEmbeddingsActivity] to list. */
    val ALL = listOf(E5_SMALL, E5_BASE)
}
