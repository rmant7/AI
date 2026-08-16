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
 */
class RuntimeManager(
    private val budgetBytes: Long,
    private val runtimes: Map<RuntimeKind, ModelRuntime>,
    private val clock: () -> Long = System::nanoTime,
) {
    private class Entry(
        val loaded: LoadedModel,
        val runtime: RuntimeKind,
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
        block: suspend (LoadedModel) -> T,
    ): T {
        val loaded = acquire(model, binding)
        try {
            return block(loaded)
        } finally {
            release(model.id)
        }
    }

    suspend fun acquire(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel = mutex.withLock {
        resident[model.id]?.let { entry ->
            entry.refCount++
            entry.lastUsedAt = clock()
            return entry.loaded
        }

        val runtime = runtimes[binding.runtime]
            ?: throw ModelLoadException("No runtime registered for ${binding.runtime.id}")
        if (!runtime.canRun(model, binding)) {
            throw ModelLoadException("Runtime ${binding.runtime.id} cannot run ${model.id}")
        }
        val requiredBytes = binding.effectiveRequiredRamBytes
        if (requiredBytes > budgetBytes) {
            throw InsufficientMemoryException(requiredBytes, budgetBytes, residentBytes)
        }

        evictUntilFits(requiredBytes)

        val loaded = runtime.load(model, binding)
        resident[model.id] = Entry(loaded, binding.runtime, refCount = 1, lastUsedAt = clock())
        return loaded
    }

    suspend fun release(modelId: String) = mutex.withLock {
        val entry = resident[modelId] ?: return@withLock
        if (entry.refCount > 0) entry.refCount--
        entry.lastUsedAt = clock()
    }

    /** Frees memory on demand — e.g. on `onTrimMemory` from Android. */
    suspend fun evictIdle() = mutex.withLock {
        resident.values
            .filter { it.refCount == 0 }
            .map { it.loaded.modelId }
            .forEach { unload(it) }
    }

    suspend fun unloadAll() = mutex.withLock {
        resident.keys.toList().forEach { unload(it) }
    }

    private fun evictUntilFits(requiredBytes: Long) {
        while (residentBytes + requiredBytes > budgetBytes) {
            val victim = resident.values
                .filter { it.refCount == 0 }
                .minByOrNull { it.lastUsedAt }
                ?: throw InsufficientMemoryException(requiredBytes, budgetBytes, residentBytes)
            unload(victim.loaded.modelId)
        }
    }

    private fun unload(modelId: String) {
        resident.remove(modelId)?.loaded?.close()
    }
}
