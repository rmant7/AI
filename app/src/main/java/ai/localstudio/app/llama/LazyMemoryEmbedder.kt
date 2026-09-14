package ai.localstudio.app.llama

import ai.localstudio.memory.MemoryEmbedder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fronts a [MemoryEmbedder] that finishes loading asynchronously, after
 * [ai.localstudio.memory.FileMemoryStore] itself already exists — see
 * [ai.localstudio.app.AppContainer]'s own wiring. A native GGUF load is a
 * real, blocking few hundred milliseconds to a few seconds; [AppContainer]'s
 * `memory` property is built during the very first [AppContainer.get] call,
 * on whatever thread that happens to be (an Activity's onCreate, in
 * practice) — paying that cost synchronously there is not acceptable.
 *
 * Every call before [set] (or after [unload]) degrades exactly the way
 * [ai.localstudio.memory.SemanticRetrieval] already treats "no embedder
 * configured" — an empty query vector falls back to lexical-only search —
 * so nothing downstream needs to know whether the real model is currently
 * resident. [modelId]/[dimension] report harmless placeholders no real
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

    @Volatile
    private var release: (() -> Unit)? = null

    /**
     * Serializes every read of [delegate] (a real embed call) against every
     * write to it ([set]/[unload]) — [LlamaBridge.nativeOpMutex] alone does
     * not cover this: it only protects one *already-fetched* native
     * context's own calls against each other, not "a call already holds a
     * reference to the old delegate when [unload] frees it out from under
     * that reference." A call that read [delegate] a moment before [unload]
     * nulls it is still holding a perfectly valid [MemoryEmbedder] — this
     * lock is what stops [unload] from freeing that same instance's native
     * handle until any such in-flight call has actually finished with it.
     */
    private val lock = Mutex()

    val isReady: Boolean get() = delegate != null

    /** [release] runs once, inside [unload], to free whatever native resources [real] holds. */
    suspend fun set(real: MemoryEmbedder, release: () -> Unit = {}) = lock.withLock {
        this.delegate = real
        this.release = release
    }

    /**
     * Frees the currently loaded model (via the [release] callback [set]
     * was given) and reverts to the same not-ready-yet degradation as
     * before [set] was ever called — [AppContainer] reloads it the next
     * time memory actually needs it, from the same on-disk file, no
     * re-download. A no-op if nothing is currently loaded.
     */
    suspend fun unload() = lock.withLock {
        release?.invoke()
        delegate = null
        release = null
    }

    override val modelId: String get() = delegate?.modelId ?: "pending"
    override val dimension: Int get() = delegate?.dimension ?: 0

    override suspend fun embedForQuery(query: String): FloatArray = lock.withLock {
        if (isEnabled()) delegate?.embedForQuery(query) ?: FloatArray(0) else FloatArray(0)
    }

    override suspend fun embedForStorage(texts: List<String>): List<FloatArray> = lock.withLock {
        if (isEnabled()) delegate?.embedForStorage(texts) ?: emptyList() else emptyList()
    }
}
