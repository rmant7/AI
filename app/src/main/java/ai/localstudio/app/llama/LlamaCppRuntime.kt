package ai.localstudio.app.llama

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * On-device inference. The same [ModelRuntime] contract as the remote runtime,
 * which is what lets the router, pipelines, context engine and memory stay
 * untouched: only the registration changes.
 */
class LlamaCppRuntime(
    private val contextTokens: Int = LlamaBridge.DEFAULT_CONTEXT_TOKENS,
    private val threads: Int = LlamaBridge.defaultThreads(),
) : ModelRuntime {

    override val kind: RuntimeKind = RuntimeKind.LLAMA_CPP

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.LLAMA_CPP &&
            LlamaBridge.isAvailable &&
            File(binding.artifact).isFile

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        if (!LlamaBridge.isAvailable) {
            throw ModelLoadException("The llama.cpp library is not available for this device's ABI")
        }
        val file = File(binding.artifact)
        if (!file.isFile) {
            throw ModelLoadException("Model file is missing: ${binding.artifact}")
        }

        val bridge = LlamaBridge()
        val handle = bridge.nativeLoad(file.absolutePath, contextTokens, threads)
        if (handle == 0L) {
            throw ModelLoadException("llama.cpp could not load ${file.name}")
        }
        return LlamaTextModel(model.id, binding.effectiveRequiredRamBytes, bridge, handle)
    }
}

private class LlamaTextModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val bridge: LlamaBridge,
    private val handle: Long,
) : TextModelHandle {

    override fun generate(request: GenerationRequest): Flow<String> = callbackFlow {
        val sink = object : LlamaBridge.TokenSink {
            override fun onToken(text: String) {
                trySend(text)
            }
        }

        val worker = CoroutineScope(Dispatchers.IO).launch {
            val produced = bridge.nativeGenerate(
                handle = handle,
                systemPrompt = request.systemPrompt,
                userPrompt = request.prompt,
                maxTokens = request.maxTokens,
                temperature = request.temperature.toFloat(),
                callback = sink,
            )
            if (produced < 0) {
                close(IllegalStateException("Generation failed with code $produced"))
            } else {
                close()
            }
        }

        awaitClose {
            // Native generation blocks in C++; cancelling the coroutine alone
            // would leave it running to completion on a background thread.
            bridge.nativeCancel(handle)
            worker.cancel()
        }
        // Tokens arrive faster than a RecyclerView can render them; an unbounded
        // buffer keeps the generating thread from stalling on the UI.
    }.buffer(Channel.UNLIMITED)

    override fun requestCancel() {
        bridge.nativeCancel(handle)
    }

    override fun close() {
        bridge.nativeFree(handle)
    }
}
