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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device inference. The same [ModelRuntime] contract as the remote runtime,
 * which is what lets the router, pipelines, context engine and memory stay
 * untouched: only the registration changes.
 */
class LlamaCppRuntime(
    private val contextTokens: Int = LlamaBridge.DEFAULT_CONTEXT_TOKENS,
    private val threads: Int = LlamaBridge.defaultThreads(),
    /**
     * Every stage worth timing gets a line here — load, first token, done or
     * cancelled — because a hang with no crash and no exception (the native
     * call is simply slow, or genuinely stuck) previously left nothing to
     * look at afterward beyond "no response after 180s". Defaults to a no-op
     * so tests and any other caller don't need a real [AppLog][ai.localstudio.app.log.AppLog].
     */
    private val log: (tag: String, message: String) -> Unit = { _, _ -> },
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
        val loadStart = System.currentTimeMillis()
        log("LOCAL_LOAD", "${file.name}: starting (ctx=$contextTokens, threads=$threads)")
        val handle = bridge.nativeLoad(file.absolutePath, contextTokens, threads)
        val loadMs = System.currentTimeMillis() - loadStart
        if (handle == 0L) {
            log("LOCAL_LOAD", "${file.name}: FAILED after ${loadMs}ms")
            throw ModelLoadException("llama.cpp could not load ${file.name}")
        }
        log("LOCAL_LOAD", "${file.name}: ready in ${loadMs}ms")
        return LlamaTextModel(model.id, binding.effectiveRequiredRamBytes, bridge, handle, log)
    }
}

private class LlamaTextModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val bridge: LlamaBridge,
    private val handle: Long,
    private val log: (tag: String, message: String) -> Unit,
) : TextModelHandle {

    override fun generate(request: GenerationRequest): Flow<String> = callbackFlow {
        val start = System.currentTimeMillis()
        var tokenCount = 0
        var firstTokenLogged = false
        val completed = AtomicBoolean(false)
        log("LOCAL_GENERATE", "$modelId: starting (prompt=${request.prompt.length} chars, maxTokens=${request.maxTokens})")

        val sink = object : LlamaBridge.TokenSink {
            override fun onToken(text: String) {
                if (!firstTokenLogged) {
                    firstTokenLogged = true
                    log("LOCAL_GENERATE", "$modelId: first token after ${System.currentTimeMillis() - start}ms")
                }
                tokenCount++
                trySend(text)
            }
        }

        val worker = CoroutineScope(Dispatchers.IO).launch {
            // The user asked for the model, not for a background chore: tell
            // Android this thread matters before the native pool inherits it.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
            val produced = bridge.nativeGenerate(
                handle = handle,
                systemPrompt = request.systemPrompt,
                userPrompt = request.prompt,
                maxTokens = request.maxTokens,
                temperature = request.temperature.toFloat(),
                topP = request.topP.toFloat(),
                topK = request.topK,
                repeatPenalty = request.repeatPenalty.toFloat(),
                callback = sink,
            )
            completed.set(true)
            val elapsedMs = System.currentTimeMillis() - start
            if (produced < 0) {
                log("LOCAL_GENERATE", "$modelId: FAILED code=$produced after ${elapsedMs}ms, $tokenCount tokens")
                close(IllegalStateException("Generation failed with code $produced"))
            } else {
                log("LOCAL_GENERATE", "$modelId: done in ${elapsedMs}ms, $produced tokens")
                close()
            }
        }

        awaitClose {
            // Native generation blocks in C++; cancelling the coroutine alone
            // would leave it running to completion on a background thread.
            if (!completed.get()) {
                log(
                    "LOCAL_GENERATE",
                    "$modelId: cancelled/timed out after ${System.currentTimeMillis() - start}ms, $tokenCount tokens so far",
                )
            }
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
