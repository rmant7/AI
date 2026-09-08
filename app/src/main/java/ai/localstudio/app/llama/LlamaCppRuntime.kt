package ai.localstudio.app.llama

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes every blocking native llama.cpp call across the whole app —
 * every [LlamaCppRuntime.load] and every [LlamaTextModel.generate], any
 * model, any instance. Both wrap a single-shot JNI call with no
 * cancellation hook worth relying on for [load] (see its own doc comment):
 * abandoning the coroutine that's waiting on one leaves the native call
 * itself running to completion regardless, on its own thread, and nothing
 * stopped a completely unrelated *new* load or generate from starting right
 * alongside it — both then compete for the same CPU cores and RAM. Confirmed
 * on a real device: an abandoned load of one model and a plain generate on
 * a *different*, already-loaded model, running at the same time, both took
 * several times longer than either alone should. This mutex is what makes
 * "abandon and try something else" mean "queued after," not "in parallel
 * with," whatever's still finishing in the background.
 */
private val nativeOpMutex = Mutex()

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

        // nativeLoad() is a single blocking JNI call — llama_model_load_from_file()
        // and llama_init_from_model() have no cancellation hook of their own,
        // unlike generate()'s per-chunk checks. Called directly inside this
        // suspend function, a Stop tap or a timeout during a slow load (a
        // 12B model has taken over 10 minutes on this device) would have no
        // effect until the call finally returned — a coroutine cancellation
        // can only be observed at a suspension point, and there wasn't one.
        // Running it on a detached worker (its own SupervisorJob, not a
        // child of the caller) means load() itself still responds to
        // cancellation immediately, while the worker keeps running to
        // completion in the background and frees whatever it produced if
        // nobody is waiting for it anymore, instead of leaking a model.
        // Plain var, not @Volatile: CompletableDeferred's completion (awaited
        // below, and observed via invokeOnCompletion in the cancelled path)
        // already establishes happens-before, so both readers always see the
        // write the worker made just before completing.
        var producedHandle = 0L
        val result = CompletableDeferred<Unit>()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            nativeOpMutex.withLock {
                producedHandle = runCatching { bridge.nativeLoad(file.absolutePath, contextTokens, threads) }.getOrDefault(0L)
            }
            result.complete(Unit)
        }
        val handle = try {
            result.await()
            producedHandle
        } catch (e: CancellationException) {
            log("LOCAL_LOAD", "${file.name}: abandoned after ${System.currentTimeMillis() - loadStart}ms, still loading in the background")
            worker.invokeOnCompletion {
                if (producedHandle != 0L) bridge.nativeFree(producedHandle)
            }
            throw e
        }
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

    // nativeCancel()/Job.cancel() only ask a blocking native call to stop at
    // its next checkpoint — they cannot interrupt it, so "the Job was
    // cancelled" does not mean "the native call already returned". close()
    // waits on this before freeing the context (see close() below) so a
    // model eviction can never free memory a still-running llama_decode()
    // call is using out from under it.
    private val activeWorker = AtomicReference<Job?>(null)

    /** So the chat-template line lands in the log once per model, not once per turn. */
    private val templateLogged = AtomicBoolean(false)

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
                    // Once per model, and here rather than at load time:
                    // asked before the first turn it can only report "not
                    // attempted yet", which is precisely the part worth
                    // knowing — whether this model's own turn markers were
                    // really used, or a fallback scaffold stood in for them.
                    if (templateLogged.compareAndSet(false, true)) {
                        runCatching { bridge.nativeChatTemplateInfo(handle) }
                            .onSuccess { log("LOCAL_GENERATE", "$modelId: chat template $it") }
                    }
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
            val produced = nativeOpMutex.withLock {
                bridge.nativeGenerate(
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
            }
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
        activeWorker.set(worker)

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
        // Blocks (bounded now that prompt processing is chunked and checks
        // cancellation between batches, see llama_jni.cpp — plus however
        // long nativeOpMutex makes this worker wait its turn behind some
        // other model's load or generate, if one happens to be running)
        // until any in-flight native call has genuinely returned — freeing
        // the context while a background thread is still inside
        // llama_decode() using it is a use-after-free, not a graceful stop.
        runBlocking { activeWorker.get()?.join() }
        bridge.nativeFree(handle)
    }
}
