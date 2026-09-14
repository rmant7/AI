package ai.localstudio.app.llama

/**
 * Embedding models for [ai.localstudio.memory.MemorySemanticIndex] — one
 * production model ([E5_BASE]) and one still-experimental candidate
 * ([E5_SMALL], which does not currently load at all — see its own doc
 * comment). Not referenced by [ai.localstudio.app.models.LocalModels] or its
 * download UI either way: those are the *chat*-model catalog, a different
 * concept this app's Models screen exposes for a different kind of model —
 * see `Mobile_mem0/SEMANTIC_RETRIEVAL_DESIGN.md`'s "Choosing the concrete
 * model" section for that distinction, and for the on-device verification
 * (dimension/cosine sanity check) plus the standalone Mobile_mem0 benchmark
 * (`benchmark/e5_base_benchmark.ipynb`) that promoted [E5_BASE] out of
 * "download it yourself first" and into [ai.localstudio.app.AppContainer]'s
 * own automatic download-and-load path.
 *
 * A future candidate still belongs here first, unwired, exactly the way
 * [E5_BASE] itself started: verified manually via
 * [ai.localstudio.app.ExperimentalEmbeddingsActivity] (dimension, cosine
 * sanity check) before anything in [ai.localstudio.app.AppContainer] is
 * changed to load it automatically.
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
     * Still experimental, and currently broken: on-device verification via
     * [ai.localstudio.app.ExperimentalEmbeddingsActivity] failed to load
     * this exact file with `llama_model_load: error loading model: bert
     * model needs to define token type count` — a metadata field missing
     * from this specific GGUF conversion, not something this app's own JNI
     * code can work around. Never auto-loaded by
     * [ai.localstudio.app.AppContainer] for that reason; kept here (rather
     * than deleted) as a record of what was tried, and because the
     * Experimental screen still needs something to show alongside [E5_BASE].
     * A different Small conversion could replace this entry once verified —
     * this one specifically should not be retried as-is.
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

    /**
     * The app's production semantic-memory embedding model — the only one
     * [ai.localstudio.app.AppContainer] downloads and loads automatically.
     * Verified twice: on-device via
     * [ai.localstudio.app.ExperimentalEmbeddingsActivity] (dimension 768,
     * cosine(similar)=0.897 > cosine(dissimilar)=0.754), and again by the
     * standalone Mobile_mem0 retrieval benchmark
     * (`benchmark/e5_base_benchmark.ipynb`), which found it beat lexical
     * retrieval overall (Recall@1 0.848 vs 0.500) and in every measured
     * category except `identifier`, where it tied rather than lost.
     */
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
