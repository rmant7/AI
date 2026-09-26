package ai.localstudio.core.runtime

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes [inner]'s real memory-heavy work — whatever [TextModelHandle.generate]
 * actually does, not [load] itself — against every other runtime sharing the
 * same [gate].
 *
 * Built for one real device failure, not a hypothetical: Gemini Nano/AICore's
 * weights live in AICore's own system service, entirely outside [RuntimeManager]'s
 * RAM budget (see `AiCoreRuntime`'s own doc comment on why it has nothing here
 * to plan for). A Compare-mode batch fires every source concurrently, so a
 * local llama.cpp candidate's own budget check — real free RAM, read at that
 * instant — can pass clean, and still lose the race: AICore's concurrent
 * memory ramp-up during its own `generate()` isn't in that number, and the two
 * together exceeded what was actually free. Device log: a local load started
 * (budget check passed, free RAM: 9796 MB) alongside an AICore `generate()`
 * that finished normally 9 seconds later — the local load never logged
 * "ready" at all, and the next launch opened on a fresh-boot `PROCESS_EXIT`
 * OOM record.
 *
 * Wrapping both candidates' runtimes in one shared [gate] means at most one
 * of them holds real weights/inference memory at a time — every other
 * candidate (a network call) has no device memory footprint to protect
 * against and is never wrapped in this.
 */
class DeviceMemoryGatedRuntime(
    private val inner: ModelRuntime,
    private val gate: Mutex,
) : ModelRuntime {
    override val kind: RuntimeKind get() = inner.kind

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean = inner.canRun(model, binding)

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        val loaded = inner.load(model, binding)
        val handle = loaded as? TextModelHandle
            ?: throw ModelLoadException("${model.id} did not load as a text model — DeviceMemoryGatedRuntime only supports text runtimes")
        return GatedTextHandle(handle, gate)
    }
}

private class GatedTextHandle(
    private val inner: TextModelHandle,
    private val gate: Mutex,
) : TextModelHandle {
    override val modelId: String get() = inner.modelId
    override val ramBytes: Long get() = inner.ramBytes

    override fun generate(request: GenerationRequest): Flow<String> = flow {
        gate.withLock {
            emitAll(inner.generate(request))
        }
    }

    override fun requestCancel() = inner.requestCancel()

    override fun close() = inner.close()
}
