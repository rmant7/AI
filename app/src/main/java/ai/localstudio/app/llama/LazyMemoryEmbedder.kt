package ai.localstudio.app.llama

import ai.localstudio.memory.MemoryEmbedder

/**
 * Fronts a [MemoryEmbedder] that finishes loading asynchronously, after
 * [ai.localstudio.memory.FileMemoryStore] itself already exists — see
 * [ai.localstudio.app.AppContainer]'s own wiring. A native GGUF load is a
 * real, blocking few hundred milliseconds to a few seconds; [AppContainer]'s
 * `memory` property is built during the very first [AppContainer.get] call,
 * on whatever thread that happens to be (an Activity's onCreate, in
 * practice) — paying that cost synchronously there is not acceptable.
 *
 * Every call before [set] degrades exactly the way
 * [ai.localstudio.memory.SemanticRetrieval] already treats "no embedder
 * configured" — an empty query vector falls back to lexical-only search —
 * so nothing downstream needs to know whether the real model has finished
 * loading yet. [modelId]/[dimension] report harmless placeholders no real
 * vector is ever upserted under: [AppContainer] only calls
 * `memory.embedPending()` after confirming [isReady], never before, so
 * [embedForStorage]'s own "not ready" branch (an empty list, shorter than
 * the input) is never actually reachable from real use — it exists only so
 * this class satisfies [MemoryEmbedder]'s contract without a partial-init
 * flag leaking into that interface.
 *
 * [isEnabled] is the same degradation, triggered by a person instead of a
 * load still in progress — the Memory screen's own "Semantic retrieval"
 * switch. Read fresh on every call rather than cached, the same way
 * [ai.localstudio.app.Settings]'s other flags are read live elsewhere in
 * this app: flipping the switch takes effect on the very next call, with
 * no restart, no [set]/reload, and no separate sync step for [AppContainer]
 * to remember. [isReady] still reports whether the real model is loaded
 * (a *download-and-load* question, the Models screen's own concern),
 * independent of whether it is currently allowed to answer.
 */
class LazyMemoryEmbedder(private val isEnabled: () -> Boolean = { true }) : MemoryEmbedder {

    @Volatile
    private var delegate: MemoryEmbedder? = null

    val isReady: Boolean get() = delegate != null

    fun set(real: MemoryEmbedder) {
        delegate = real
    }

    override val modelId: String get() = delegate?.modelId ?: "pending"
    override val dimension: Int get() = delegate?.dimension ?: 0

    override suspend fun embedForQuery(query: String): FloatArray =
        if (isEnabled()) delegate?.embedForQuery(query) ?: FloatArray(0) else FloatArray(0)

    override suspend fun embedForStorage(texts: List<String>): List<FloatArray> =
        if (isEnabled()) delegate?.embedForStorage(texts) ?: emptyList() else emptyList()
}
