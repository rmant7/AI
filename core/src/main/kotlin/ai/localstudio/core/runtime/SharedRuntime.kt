package ai.localstudio.core.runtime

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Routes every load of [inner] through one process-wide [manager], whoever
 * asks for it: a single-candidate orchestrator, a [FallbackTextRuntime] chain,
 * a Compare-mode source, translation. [load] returns a weightless handle; the
 * real model is acquired from [manager] for the duration of each [generate]
 * and released right after, so it stays resident between turns but is
 * evictable the moment something else needs the RAM.
 *
 * Without this, each of those callers held its own copy — a chain caches
 * whatever it loaded in its own map, invisible to any manager's budget — so
 * the same multi-GB GGUF could sit in RAM twice, or a previous model could
 * stay resident while a new one loaded next to it.
 */
class SharedRuntime(
    private val inner: ModelRuntime,
    private val manager: RuntimeManager,
    private val variant: Any? = null,
) : ModelRuntime {
    override val kind: RuntimeKind get() = inner.kind

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean = inner.canRun(model, binding)

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel =
        SharedTextHandle(model, binding, inner, manager, variant)
}

private class SharedTextHandle(
    private val model: ModelDescriptor,
    private val binding: RuntimeBinding,
    private val inner: ModelRuntime,
    private val manager: RuntimeManager,
    private val variant: Any?,
) : TextModelHandle {
    override val modelId: String = model.id

    // The weights are accounted for in [manager], not in whichever manager or
    // chain holds this handle.
    override val ramBytes: Long = 0

    @Volatile
    private var active: TextModelHandle? = null

    override fun generate(request: GenerationRequest): Flow<String> = flow {
        manager.withModel(model, binding, inner, variant) { loaded ->
            val handle = loaded as? TextModelHandle
                ?: throw ModelLoadException("${model.id} did not load as a text model")
            active = handle
            try {
                emitAll(handle.generate(request))
            } finally {
                active = null
            }
        }
    }

    override fun requestCancel() {
        active?.requestCancel()
    }

    override fun close() = Unit
}
