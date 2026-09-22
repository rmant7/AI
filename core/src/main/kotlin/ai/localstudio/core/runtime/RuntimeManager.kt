package ai.localstudio.core.runtime

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ResidentModel(
    val modelId: String,
    val runtime: RuntimeKind,
    val ramBytes: Long,
    val refCount: Int,
    val lastUsedAt: Long,
)

/**
 * Decides what stays in memory.
 *
 * On a phone, Whisper + an LLM + a VLM compete for the same RAM, so models are
 * acquired for the duration of a call and evicted by least-recent-use when the
 * budget is exceeded. When the budget allows it they simply stay resident and
 * nothing is unloaded — the same code covers both the 8 GB and the 16 GB case.
 *
 * Models in use ([ResidentModel.refCount] > 0) are never evicted; if the budget
 * cannot be met without touching them, the load fails loudly with
 * [InsufficientMemoryException] rather than thrashing.
 *
 * [budgetBytes] is read once per [acquire], so a caller can hand in a live
 * figure (free RAM right now) instead of a number fixed at construction.
 *
 * With [strictBudget] off, a model whose estimate alone exceeds the budget is
 * still attempted once every idle model has been evicted — the estimate is a
 * heuristic, and for a model the user explicitly chose, a real load (and a
 * real failure, if it comes to that) beats a refusal based on a guess. Models
 * in use still block it: two multi-GB models generating at once is exactly
 * the thrashing this class exists to prevent.
 *
 * With [exclusive] on, loading a model first evicts every idle one: for
 * memory-mapped LLM weights the free-RAM reading already counts a resident
 * model's pages as reclaimable cache, so no budget arithmetic can tell
 * whether two of them really fit side by side — and when they don't, both
 * thrash the page cache instead of failing.
 */
class RuntimeManager(
    private val budgetBytes: () -> Long,
    private val runtimes: Map<RuntimeKind, ModelRuntime>,
    private val clock: () -> Long = System::nanoTime,
    private val strictBudget: Boolean = true,
    private val exclusive: Boolean = false,
    private val log: (String) -> Unit = {},
) {
    constructor(
        budgetBytes: Long,
        runtimes: Map<RuntimeKind, ModelRuntime>,
        clock: () -> Long = System::nanoTime,
    ) : this({ budgetBytes }, runtimes, clock)

    private class Entry(
        val loaded: LoadedModel,
        val runtime: RuntimeKind,
        val variant: Any?,
        var refCount: Int,
        var lastUsedAt: Long,
    )

    private val mutex = Mutex()
    private val resident = LinkedHashMap<String, Entry>()

    val residentBytes: Long
        get() = resident.values.sumOf { it.loaded.ramBytes }

    fun residentModels(): List<ResidentModel> = resident.values.map {
        ResidentModel(it.loaded.modelId, it.runtime, it.loaded.ramBytes, it.refCount, it.lastUsedAt)
    }

    /**
     * Runs [block] with the model loaded, releasing it afterwards. The model
     * stays resident after release and is reused by the next acquisition until
     * memory pressure evicts it.
     */
    suspend fun <T> withModel(
        model: ModelDescriptor,
        binding: RuntimeBinding,
        runtime: ModelRuntime? = null,
        variant: Any? = null,
        block: suspend (LoadedModel) -> T,
    ): T {
        val loaded = acquire(model, binding, runtime, variant)
        try {
            return block(loaded)
        } finally {
            release(model.id)
        }
    }

    /**
     * [runtime] overrides the one registered for [binding]'s kind — for a
     * caller that owns its own runtime instance (settings baked into it) but
     * still wants residency tracked here. [variant] distinguishes loads of the
     * same model that are not interchangeable (a different context size): an
     * idle resident copy of another variant is unloaded and reloaded rather
     * than reused; one still in use is reused as-is.
     */
    suspend fun acquire(
        model: ModelDescriptor,
        binding: RuntimeBinding,
        runtime: ModelRuntime? = null,
        variant: Any? = null,
    ): LoadedModel = mutex.withLock {
        resident[model.id]?.let { entry ->
            if (entry.variant == variant || entry.refCount > 0) {
                entry.refCount++
                entry.lastUsedAt = clock()
                return entry.loaded
            }
            log("${model.id}: reloading — resident copy is ${entry.variant}, need $variant")
            unload(model.id)
        }

        val chosen = runtime
            ?: runtimes[binding.runtime]
            ?: throw ModelLoadException("No runtime registered for ${binding.runtime.id}")
        if (!chosen.canRun(model, binding)) {
            throw ModelLoadException("Runtime ${binding.runtime.id} cannot run ${model.id}")
        }
        val requiredBytes = binding.effectiveRequiredRamBytes
        val budget = budgetBytes()
        if (exclusive) evictAllIdle()
        if (requiredBytes > budget) {
            if (strictBudget) throw InsufficientMemoryException(requiredBytes, budget, residentBytes)
            log(
                "${model.id}: estimate ${requiredBytes / MB}MB over budget ${budget / MB}MB — " +
                    "evicting every idle model and attempting anyway",
            )
            evictAllIdle()
            if (resident.isNotEmpty()) throw InsufficientMemoryException(requiredBytes, budget, residentBytes)
        } else {
            evictUntilFits(requiredBytes, budget)
        }

        val loaded = chosen.load(model, binding)
        resident[model.id] = Entry(loaded, binding.runtime, variant, refCount = 1, lastUsedAt = clock())
        return loaded
    }

    suspend fun release(modelId: String) = mutex.withLock {
        val entry = resident[modelId] ?: return@withLock
        if (entry.refCount > 0) entry.refCount--
        entry.lastUsedAt = clock()
    }

    /** Frees memory on demand — e.g. on `onTrimMemory` from Android. */
    suspend fun evictIdle() = mutex.withLock { evictAllIdle() }

    suspend fun unloadAll() = mutex.withLock {
        resident.keys.toList().forEach { unload(it) }
    }

    private fun evictAllIdle() {
        resident.values
            .filter { it.refCount == 0 }
            .map { it.loaded.modelId }
            .forEach { unload(it) }
    }

    private fun evictUntilFits(requiredBytes: Long, budget: Long) {
        while (residentBytes + requiredBytes > budget) {
            val victim = resident.values
                .filter { it.refCount == 0 }
                .minByOrNull { it.lastUsedAt }
                ?: throw InsufficientMemoryException(requiredBytes, budget, residentBytes)
            unload(victim.loaded.modelId)
        }
    }

    private fun unload(modelId: String) {
        resident.remove(modelId)?.let { entry ->
            log("$modelId: evicted (${entry.loaded.ramBytes / MB}MB)")
            entry.loaded.close()
        }
    }

    private companion object {
        const val MB = 1_000_000L
    }
}
