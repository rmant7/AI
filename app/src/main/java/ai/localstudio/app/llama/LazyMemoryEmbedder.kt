package ai.localstudio.app.llama

import ai.localstudio.memory.MemoryEmbedder
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * [reloadTrigger] is what turns [unload] into a self-healing degradation
 * rather than a permanent one until [AppContainer]'s own periodic backfill
 * loop happens to tick again: the moment a real call finds [isEnabled] true
 * but nothing loaded, this fires once, in the background, while that same
 * call still answers immediately with the same lexical-only fallback as
 * any other not-ready call. [AppContainer] wires this to the exact same
 * load path (`ensureEmbedderLoaded`) the periodic loop already uses — see
 * that function's own doc comment for how it stays limited to the file
 * already on disk, never a re-download, and never two such loads running
 * at once regardless of which of the two callers asked. The periodic loop
 * itself is left in place as a second, independent path to the same
 * recovery, in case a [reloadTrigger] call is ever missed or its own
 * attempt fails outright.
 */
class LazyMemoryEmbedder(
    private val isEnabled: () -> Boolean = { true },
    private val reloadTrigger: (onComplete: () -> Unit) -> Unit = { onComplete -> onComplete() },
) : MemoryEmbedder {

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

    /**
     * Guards [reloadTrigger] itself, not the load it kicks off — a burst of
     * calls arriving while not ready (a batch of memory writes, several
     * concurrent searches) should fire it once, not once per call. Reset by
     * [reloadTrigger]'s own completion callback regardless of outcome, and
     * again — as a fully idempotent safety net, not double-tracking — by
     * every [set], so a reload that lands through some other path (the
     * periodic loop calling [AppContainer]'s loader directly, say) still
     * leaves this ready for the next [unload].
     */
    private val reloadInFlight = AtomicBoolean(false)

    val isReady: Boolean get() = delegate != null

    /**
     * Installs [real] as the active delegate, whose [release] callback runs
     * once, inside [unload], to free whatever native resources it holds.
     *
     * If a delegate is already installed — [set] called again with no
     * intervening [unload], exactly what a completed [reloadTrigger] looks
     * like when the periodic loop's own load also lands around the same
     * time — the previous delegate's own [release] still runs here, so its
     * native resources are never leaked outliving this call. The new
     * delegate is assigned first, before that old [release] runs: a
     * throwing old [release] then still leaves [delegate] pointing at the
     * new, valid instance rather than one already freed.
     */
    suspend fun set(real: MemoryEmbedder, release: () -> Unit = {}) {
        val oldRelease = lock.withLock {
            val old = this.release
            this.delegate = real
            this.release = release
            old
        }
        reloadInFlight.set(false)
        oldRelease?.invoke()
    }

    /**
     * Frees the currently loaded model (via the [release] callback [set]
     * was given) and reverts to the same not-ready-yet degradation as
     * before [set] was ever called. A no-op if nothing is currently loaded.
     *
     * Nothing reloads it from here — the very next [embedForQuery]/
     * [embedForStorage] call does, via [reloadTrigger], while itself still
     * answering with the lexical-only fallback immediately; [AppContainer]'s
     * periodic backfill loop is a second, independent path to the same
     * reload. Either way it comes from the same on-disk file, never a
     * re-download.
     */
    suspend fun unload() = lock.withLock {
        release?.invoke()
        delegate = null
        release = null
    }

    override val modelId: String get() = delegate?.modelId ?: "pending"
    override val dimension: Int get() = delegate?.dimension ?: 0

    override suspend fun embedForQuery(query: String): FloatArray {
        var notReady = false
        val vector = lock.withLock {
            val current = delegate
            when {
                !isEnabled() -> null
                current != null -> current.embedForQuery(query)
                else -> { notReady = true; null }
            }
        }
        if (notReady) requestReload()
        return vector ?: FloatArray(0)
    }

    override suspend fun embedForStorage(texts: List<String>): List<FloatArray> {
        var notReady = false
        val vectors = lock.withLock {
            val current = delegate
            when {
                !isEnabled() -> null
                current != null -> current.embedForStorage(texts)
                else -> { notReady = true; null }
            }
        }
        if (notReady) requestReload()
        return vectors ?: emptyList()
    }

    /**
     * Fires [reloadTrigger] at most once per unload — [reloadInFlight] is
     * set here, before the trigger itself runs anything, and cleared by its
     * completion callback (or by the next [set], whichever comes first), so
     * a second call arriving while a reload is already in flight is a
     * cheap no-op rather than a second concurrent load.
     */
    private fun requestReload() {
        if (reloadInFlight.compareAndSet(false, true)) {
            reloadTrigger { reloadInFlight.set(false) }
        }
    }
}
