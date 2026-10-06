package ai.localstudio.core.runtime

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Memory a loaded model takes on after its load -- a vision projector
 * loaded on the first image turn is the case this exists for. Admitted
 * against the same budget as loads, by the same manager, so "what is
 * resident" always means weights + projector + whatever else was reserved,
 * never weights alone with a part on the side no budget saw.
 */
interface ModelMemory {
    /**
     * Reserves [bytes] more for the resident [modelId]: evicts idle models
     * (never [modelId] itself) until it fits, or throws
     * [InsufficientMemoryException] and changes nothing. [what] names the
     * part, for the log.
     */
    suspend fun reserve(modelId: String, bytes: Long, what: String)

    /** Gives back a reservation whose part did not load after all. */
    suspend fun unreserve(modelId: String, bytes: Long)
}

data class ResidentModel(
    val modelId: String,
    val runtime: RuntimeKind,
    /** Everything this model holds: its load plus what it [ModelMemory.reserve]d since. */
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
 *
 * [beforeAdmission] runs before every new load's budget is read — for a
 * runtime whose own loads can outlive a cancelled caller (a blocking native
 * load on a detached worker), so a model still physically loading or being
 * freed in the background is never mistaken for free memory by the next
 * admission check. It gets the load's required bytes, so a caller freeing
 * memory of its own (models outside this manager) can tell whether that
 * would let the load fit at all.
 *
 * [requiredBytesFor] is what a load is admitted against, asked fresh on every
 * acquisition — by default the binding's own estimate, but a caller with
 * real measurements (see [ai.localstudio.core.registry.RamMeasurement])
 * supplies them here, so a measurement taken a minute ago applies to the
 * very next load instead of waiting for whoever built the binding to
 * rebuild it.
 */
class RuntimeManager(
    private val budgetBytes: () -> Long,
    private val runtimes: Map<RuntimeKind, ModelRuntime>,
    private val clock: () -> Long = System::nanoTime,
    private val strictBudget: Boolean = true,
    private val exclusive: Boolean = false,
    private val log: (String) -> Unit = {},
    private val beforeAdmission: suspend (requiredBytes: Long) -> Unit = {},
    private val requiredBytesFor: (binding: RuntimeBinding, variant: Any?) -> Long = { binding, _ -> binding.effectiveRequiredRamBytes },
) : ModelMemory {
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
        /** Reserved after the load (see [ModelMemory]); gone with the model when it unloads. */
        var extraBytes: Long = 0L,
    ) {
        val bytes: Long get() = loaded.ramBytes + extraBytes
    }

    private val mutex = Mutex()
    private val resident = LinkedHashMap<String, Entry>()

    /** Sum of every entry's refCount, readable without [mutex] — see [hasModelInUse]. */
    private val activeRefs = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Whether any model is acquired right now (loading, generating). Lock-free,
     * so a background task can check it cheaply before starting work of its
     * own that would compete for the same memory.
     */
    val hasModelInUse: Boolean get() = activeRefs.get() > 0

    val residentBytes: Long
        get() = resident.values.sumOf { it.bytes }

    fun residentModels(): List<ResidentModel> = resident.values.map {
        ResidentModel(it.loaded.modelId, it.runtime, it.bytes, it.refCount, it.lastUsedAt)
    }

    override suspend fun reserve(modelId: String, bytes: Long, what: String) = mutex.withLock {
        require(bytes >= 0) { "bytes must not be negative" }
        val entry = resident[modelId] ?: throw IllegalStateException("$modelId is not loaded; nothing to reserve $what for")
        val budget = budgetBytes()
        while (residentBytes + bytes > budget) {
            val victim = resident.values
                .filter { it.refCount == 0 && it !== entry }
                .minByOrNull { it.lastUsedAt }
                ?: run {
                    log("$modelId: $what refused -- needs ${bytes / MB}MB more, ${residentBytes / MB}MB resident of a ${budget / MB}MB budget")
                    throw InsufficientMemoryException(bytes, budget - residentBytes, residentBytes)
                }
            unload(victim.loaded.modelId)
        }
        entry.extraBytes += bytes
        log("$modelId: $what admitted (+${bytes / MB}MB, ${entry.bytes / MB}MB in all)")
    }

    override suspend fun unreserve(modelId: String, bytes: Long) = mutex.withLock {
        resident[modelId]?.let { it.extraBytes = (it.extraBytes - bytes).coerceAtLeast(0L) }
        Unit
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

    /** Bumped on every [release]: what an acquisition waiting for a model to come free watches. */
    private val releases = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * [runtime] overrides the one registered for [binding]'s kind — for a
     * caller that owns its own runtime instance (settings baked into it) but
     * still wants residency tracked here. [variant] distinguishes loads of the
     * same model that are not interchangeable (a different context size): the
     * resident copy is reused only for the same variant. An idle copy of
     * another variant is unloaded and reloaded; one still in use is never
     * handed out for another variant (the caller would believe it has a
     * context size it does not) -- the acquisition waits until it is
     * released, then reloads. A caller must therefore not ask for another
     * variant of a model it is itself still holding: that wait never ends.
     */
    suspend fun acquire(
        model: ModelDescriptor,
        binding: RuntimeBinding,
        runtime: ModelRuntime? = null,
        variant: Any? = null,
    ): LoadedModel {
        while (true) {
            val seen = mutex.withLock {
                val entry = resident[model.id]
                if (entry == null || entry.variant == variant || entry.refCount == 0) {
                    return acquireLocked(model, binding, runtime, variant)
                }
                log("${model.id}: waiting -- the resident copy (${entry.variant}) is in use, this needs $variant")
                releases.value
            }
            releases.first { it != seen }
        }
    }

    /** [acquire] with [mutex] held, once no other variant of [model] is in use. */
    private suspend fun acquireLocked(
        model: ModelDescriptor,
        binding: RuntimeBinding,
        runtime: ModelRuntime?,
        variant: Any?,
    ): LoadedModel {
        resident[model.id]?.let { entry ->
            if (entry.variant == variant) {
                entry.refCount++
                activeRefs.incrementAndGet()
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
        val requiredBytes = requiredBytesFor(binding, variant)
        beforeAdmission(requiredBytes)
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
        activeRefs.incrementAndGet()
        return loaded
    }

    suspend fun release(modelId: String) {
        mutex.withLock {
            val entry = resident[modelId] ?: return@withLock
            if (entry.refCount > 0) {
                entry.refCount--
                activeRefs.decrementAndGet()
            }
            entry.lastUsedAt = clock()
        }
        releases.update { it + 1 }
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
            activeRefs.addAndGet(-entry.refCount)
            log("$modelId: evicted (${entry.bytes / MB}MB)")
            entry.loaded.close()
        }
    }

    private companion object {
        const val MB = 1_000_000L
    }
}
