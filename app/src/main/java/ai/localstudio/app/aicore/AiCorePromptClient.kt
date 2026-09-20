package ai.localstudio.app.aicore

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.collect

/**
 * Thin wrapper around ML Kit's GenAI Prompt API (ships AICore/Gemini Nano's
 * on-device model, not any native code of this app's own — see
 * docs/04-runtime.md's "Gemini Nano / AICore feasibility" section for the
 * findings that led here). Exists purely to smoke-test the two blockers
 * that section flagged as unverifiable without a real device: whether the
 * Prompt API is actually reachable at all on this build (still beta as of
 * writing, no longer the alpha it launched as, but access could still be
 * gated in ways only a real call reveals), and — the one that actually
 * matters for this app, where Russian is one of two primary languages —
 * whether Gemini Nano's own output on a Russian prompt is any good.
 *
 * [status]/[generate] can throw whatever the SDK itself throws
 * ([GenAiException], `IllegalStateException` if AICore isn't present at
 * all) — deliberately not caught here. A caller finding out AICore is
 * simply broken on this device is exactly the "unverifiable without a real
 * device" question this class exists to answer; swallowing that here would
 * just move the mystery one layer up instead of resolving it.
 */
class AiCorePromptClient {

    private val model: GenerativeModel by lazy { Generation.getClient() }

    /**
     * `checkStatus()` itself returns a plain `Int` (an `@FeatureStatus`-annotated
     * constant, not a distinct enum type) — compare the result against
     * [FeatureStatus.AVAILABLE]/[FeatureStatus.DOWNLOADABLE]/[FeatureStatus.UNAVAILABLE]
     * (and whatever else this SDK version adds — this app has no way to
     * assume that list is exhaustive).
     */
    suspend fun status(): Int = model.checkStatus()

    /**
     * Suspends until Gemini Nano itself has finished downloading onto this
     * device via AICore — this is *not* the ~10-20MB client SDK, it is the
     * shared model weights AICore keeps once, system-wide (see
     * docs/04-runtime.md's own note on why that's structurally different
     * from a per-app GGUF download like [ai.localstudio.app.vosk.VoskNativeLibrary]'s).
     *
     * Real device report: a Pixel already having Gemini Nano resident for
     * Google's own first-party features (Recorder, Screenshots, ...) does
     * *not* mean this app's own `GenerativeModel.checkStatus()` reports
     * `AVAILABLE` — it reported `DOWNLOADABLE` and the resulting download
     * ran for several real minutes, not an instant per-app activation.
     * Whatever AICore is fetching here, it's a genuine network transfer on
     * that device, cancellable or not on AICore's own side is unverified —
     * [onStatus] exists so a caller can show real progress instead of a
     * silent multi-minute wait, which is what surfaced this in the first
     * place.
     */
    suspend fun ensureDownloaded(onStatus: (DownloadStatus) -> Unit = {}) {
        model.download().collect { status -> onStatus(status) }
    }

    /**
     * Runs [prompt] (optionally with [image] attached) against Gemini Nano
     * and returns its plain-text answer. Only meaningful once [status]
     * reports [FeatureStatus.AVAILABLE].
     *
     * `generateContentRequest(TextPart(...))` needs the trailing config
     * lambda to resolve at all here — confirmed against a real, published
     * implementation using this exact SDK version, not just a search
     * snippet, after a positional-args-only call and a lambda-only
     * `{ text(...) }` call each failed to compile in turn. Left empty:
     * this call has no generation parameters worth overriding for a smoke
     * test. Response text comes from the first candidate, not a `.text`
     * convenience property on the response itself — same source.
     *
     * [image], when present, is passed as a leading [ImagePart] alongside
     * [prompt]'s [TextPart] — confirmed against several real, published
     * implementations on this exact API (`android/androidify`'s
     * `GeminiNanoGenerationDataSource.kt`, `google-ai-edge/gallery`'s
     * `AICoreModelHelper.kt`, `sceneview/sceneview`'s `AskEngine.kt`), all
     * of which also note the Prompt API accepts only a single image per
     * request — same one-image-per-turn constraint
     * [ai.localstudio.app.llama.LlamaCppRuntime] already has for its own
     * mmproj vision path (see its own `request.images.firstOrNull()`).
     */
    suspend fun generate(prompt: String, image: Bitmap? = null): String {
        val request = if (image != null) {
            generateContentRequest(ImagePart(image), TextPart(prompt)) {}
        } else {
            generateContentRequest(TextPart(prompt)) {}
        }
        return model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
    }

    /** Releases whatever native/IPC resources the client holds. Safe to call even if [model] was never actually used. */
    fun close() {
        runCatching { model.close() }
    }
}
