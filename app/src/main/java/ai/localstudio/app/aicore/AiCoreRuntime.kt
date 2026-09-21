package ai.localstudio.app.aicore

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.TextModelHandle
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Gemini Nano via AICore, wired as a real [ModelRuntime] candidate —
 * see docs/04-runtime.md's "Gemini Nano / AICore feasibility" section
 * and [AiCorePromptClient]'s own doc comments for the architecture this
 * builds on (an IPC client to AICore's system service, not local weights
 * in this app's own process).
 *
 * Deliberately does NOT trigger [AiCorePromptClient.ensureDownloaded] from
 * [load] — a chat turn has no progress UI the way [ai.localstudio.app.AiCoreTestActivity]
 * does, and the real device report that motivated adding that screen's own
 * progress/cancel handling (a silent multi-minute wait) is exactly what
 * auto-downloading here would reproduce, just one layer further from where
 * the user could do anything about it. [load] fails fast instead, leaving
 * [ai.localstudio.core.runtime.FallbackTextRuntime] to fall through to
 * whatever cloud provider is configured next — same as any other candidate
 * that isn't ready yet.
 */
class AiCoreRuntime(
    private val log: (tag: String, message: String) -> Unit = { _, _ -> },
) : ModelRuntime {

    override val kind: RuntimeKind = RuntimeKind.AICORE

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.AICORE

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        // Real device report: nothing related to AICore ever showed up in
        // the app's own log, even when it silently failed to load — the
        // only trace was the fallback chain quietly moving on to the next
        // provider (or "no source answered" if it was the only one enabled).
        // Every branch below now logs before it returns or throws, same as
        // LlamaCppRuntime.load()'s LOCAL_LOAD lines.
        log("AICORE_LOAD", "$AICORE_MODEL_LABEL: checking status")
        val client = AiCorePromptClient()
        val status = try {
            client.status()
        } catch (e: CancellationException) {
            client.close()
            throw e
        } catch (e: Exception) {
            client.close()
            val reason = "Gemini Nano (AICore) status check failed: ${e.message} — pick a different model in Settings instead."
            log("AICORE_LOAD", "$AICORE_MODEL_LABEL: FAILED status check: ${e.javaClass.simpleName}: ${e.message}")
            throw ModelLoadException(reason, e)
        }
        if (status != FeatureStatus.AVAILABLE) {
            client.close()
            // This exact message is what a real user sees, one of three ways
            // (per this app's existing, unchanged error handling — nothing
            // new needed here): the whole error bubble when AICore is the
            // only enabled candidate (ChatActivity's onFailure), the inline
            // "⚠ ..." line FallbackTextRuntime's own attribution footer adds
            // when a later candidate answers instead, or a Compare-mode
            // source's own bubble. Says what's wrong AND names the fix that
            // doesn't require waiting on AICore at all — switching models —
            // rather than only explaining how to make AICore itself work.
            val reason = if (status == FeatureStatus.DOWNLOADABLE) {
                "Gemini Nano (AICore) isn't downloaded yet — open Settings → Advanced → " +
                    "Gemini Nano (AICore) to download it, or pick a different model in Settings until then."
            } else {
                "Gemini Nano (AICore) isn't available on this device (status=$status) — " +
                    "pick a different model in Settings instead."
            }
            log("AICORE_LOAD", "$AICORE_MODEL_LABEL: SKIPPED — $reason")
            throw ModelLoadException(reason)
        }
        log("AICORE_LOAD", "$AICORE_MODEL_LABEL: ready (status=AVAILABLE)")
        return AiCoreTextModel(model.id, binding.effectiveRequiredRamBytes, client, log)
    }

    private companion object {
        const val AICORE_MODEL_LABEL = "gemini-nano-aicore"
    }
}

private class AiCoreTextModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val client: AiCorePromptClient,
    private val log: (tag: String, message: String) -> Unit,
) : TextModelHandle {

    private val activeWorker = AtomicReference<Job?>(null)

    /**
     * One emission, not a token stream: the Prompt API surface confirmed
     * against a real published implementation (see [AiCorePromptClient.generate])
     * exposes no incremental/streaming variant, only a single suspend call
     * returning the whole answer. [ai.localstudio.core.runtime.FallbackTextRuntime]
     * and [ai.localstudio.app.ChatActivity] both already handle a candidate
     * that answers in one chunk rather than many — this is not a special case.
     */
    override fun generate(request: GenerationRequest): Flow<String> = callbackFlow {
        val start = System.currentTimeMillis()
        // ImageRef.uri is always a "data:<mime>;base64,<payload>" string here,
        // never a content:// or file path — ChatActivity.attachImage() builds
        // it that way specifically because core/openai are plain JVM modules
        // with no Android Context to resolve a real URI against; same source
        // and same one-image-per-turn assumption LlamaCppRuntime's own image
        // handling uses (see its generate()'s own comment on ImageRef.uri).
        val image = request.images.firstOrNull()?.let { ref ->
            runCatching {
                val bytes = Base64.decode(ref.uri.substringAfter(",", ""), Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        log(
            "AICORE_GENERATE",
            "$modelId: starting (prompt=${request.prompt.length} chars" +
                (if (request.images.isNotEmpty()) ", with image (decoded=${image != null})" else "") + ")",
        )
        // AiCorePromptClient.generate() has no separate system-prompt slot
        // confirmed to exist in this SDK version's generateContentRequest —
        // folded into the same prompt text instead, same as every candidate
        // here treats a missing feature: degrade, don't drop the field.
        val fullPrompt = request.systemPrompt?.let { "$it\n\n${request.prompt}" } ?: request.prompt
        val worker = CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching { client.generate(fullPrompt, image) }
            outcome.fold(
                onSuccess = { text ->
                    log("AICORE_GENERATE", "$modelId: done in ${System.currentTimeMillis() - start}ms, ${text.length} chars")
                    trySend(text)
                    close()
                },
                onFailure = { error ->
                    if (error is CancellationException) {
                        log("AICORE_GENERATE", "$modelId: cancelled after ${System.currentTimeMillis() - start}ms")
                    } else {
                        log("AICORE_GENERATE", "$modelId: FAILED after ${System.currentTimeMillis() - start}ms: ${error.message}")
                    }
                    close(error)
                },
            )
        }
        activeWorker.set(worker)
        awaitClose { worker.cancel() }
    }

    override fun requestCancel() {
        activeWorker.get()?.cancel()
    }

    override fun close() {
        activeWorker.get()?.cancel()
        runCatching { client.close() }
    }
}
