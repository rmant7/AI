package ai.localstudio.app.aicore

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
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
     * `GenerativeModel.download()` reports progress as a `Flow<DownloadStatus>`,
     * not a callback like the Summarization API's own `downloadFeature()` —
     * this just drains it to completion; `DownloadStatus`'s own fields
     * aren't read here; that would need its own verification, unlike the
     * calls made from this class, whose names came back confirmed against
     * this SDK's real compiler errors, not just search snippets.
     */
    suspend fun ensureDownloaded() {
        model.download().collect { /* draining for its terminal signal only */ }
    }

    /** Runs [prompt] against Gemini Nano and returns its plain-text answer. Only meaningful once [status] reports [FeatureStatus.AVAILABLE]. */
    suspend fun generate(prompt: String): String =
        model.generateContent(generateContentRequest { text(prompt) }).text

    /** Releases whatever native/IPC resources the client holds. Safe to call even if [model] was never actually used. */
    fun close() {
        runCatching { model.close() }
    }
}
