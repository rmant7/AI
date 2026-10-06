package ai.localstudio.app.aicore

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.ImageNotSeenException
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
    /** Every status AICore reports, so callers can remember whether this device supports it at all. */
    private val onStatus: (Int) -> Unit = {},
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
        // Client construction used to sit outside this try/catch, on the
        // (unverified) assumption it was cheap and couldn't fail — every
        // *other* AiCorePromptClient interaction in this file is already
        // wrapped. Real device report: on a Samsung phone with no AICore
        // support at all, translation failed outright with no per-card
        // error the user could point at, exactly the shape an uncaught
        // throw here — never converted to a ModelLoadException, so never
        // hidden by hideOnFailure, and (in a Compare-mode batch built from
        // several plain `async {}` children) capable of cancelling every
        // sibling source along with it — would produce. Catching Throwable,
        // not just Exception, here specifically: unlike the generic
        // per-source catch blocks in ChatActivity/TranslationActivity,
        // AICore is the one candidate whose own construction is known to
        // reach into a system service that may simply not exist on a given
        // device, and there's nothing more to do here than report that as
        // this candidate's own failure.
        var client: AiCorePromptClient? = null
        val status = try {
            client = AiCorePromptClient()
            client.status()
        } catch (e: CancellationException) {
            client?.close()
            throw e
        } catch (e: Throwable) {
            client?.close()
            val reason = "Gemini Nano (AICore) status check failed: ${e.message} — pick a different model in Settings instead."
            log("AICORE_LOAD", "$AICORE_MODEL_LABEL: FAILED status check: ${e.javaClass.simpleName}: ${e.message}")
            throw ModelLoadException(reason, e)
        }
        // Non-null past this point: the try block above only reaches here
        // (rather than throwing out of the function) once client has been
        // assigned and its own status() call has already succeeded.
        val readyClient = client!!
        onStatus(status)
        if (status != FeatureStatus.AVAILABLE) {
            readyClient.close()
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
        return AiCoreTextModel(model.id, binding.effectiveRequiredRamBytes, readyClient, log)
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
        // with no Android Context to resolve a real URI against.
        // The Prompt API takes one image per request (see AiCorePromptClient.generate).
        // A second image, or one that does not decode, used to be dropped
        // silently and the answer read as if Nano had seen everything; it
        // is now the same refusal any model that cannot see gives.
        if (request.images.size > 1) {
            log("AICORE_GENERATE", "$modelId: REFUSED — ${request.images.size} images, Gemini Nano takes one per request")
            throw ImageNotSeenException("Gemini Nano (AICore) takes one image per request, got ${request.images.size}")
        }
        val image = request.images.firstOrNull()?.let { ref ->
            runCatching {
                val bytes = Base64.decode(ref.uri.substringAfter(",", ""), Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull() ?: run {
                log("AICORE_GENERATE", "$modelId: REFUSED — the image did not decode")
                throw ImageNotSeenException("the image could not be decoded for Gemini Nano (AICore)")
            }
        }
        log(
            "AICORE_GENERATE",
            "$modelId: starting (prompt=${request.prompt.length} chars" +
                (if (image != null) ", with an image ${image.width}x${image.height}" else "") + ")",
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
                    // Real device report: repeatedly, on a long chat prompt
                    // (Compare mode's full assembled context, several
                    // thousand characters), the SDK call "succeeded" with a
                    // blank candidate — no exception, nothing to fall back
                    // from, just an empty bubble under "Ответ от: Gemini
                    // Nano (AICore)" that reads as a real, deliberately
                    // silent answer. Most likely cause: this candidate's
                    // context window isn't sized for Gemini Nano's actual
                    // on-device limit at all right now (AppContainer routes
                    // any non-llama.cpp candidate, AICore included, through
                    // CLOUD_CONTEXT_WINDOW_TOKENS — 32,000, sized for a real
                    // network API — rather than something scoped to what the
                    // Prompt API can actually take), but whatever the exact
                    // cause, a blank result is never a real answer worth
                    // showing as one. Treated as a failure here is what lets
                    // FallbackTextRuntime move on to the next candidate
                    // instead of silently stopping at nothing.
                    if (text.isBlank()) {
                        val reason = "Gemini Nano (AICore) returned an empty answer " +
                            "(likely: this prompt is longer than it can actually handle) — " +
                            "pick a different model in Settings instead."
                        log("AICORE_GENERATE", "$modelId: FAILED — blank response")
                        close(ModelLoadException(reason))
                    } else {
                        trySend(text)
                        close()
                    }
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
