package ai.localstudio.app.aicore

import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** [FeatureStatus.AVAILABLE]/[FeatureStatus.DOWNLOADABLE]/[FeatureStatus.UNAVAILABLE] (and whatever else this SDK version adds — see its own doc comment for why callers should not assume this is exhaustive). */
    suspend fun status(): FeatureStatus = model.checkStatus()

    /**
     * Suspends until Gemini Nano itself has finished downloading onto this
     * device via AICore — this is *not* the ~10-20MB client SDK, it is the
     * shared model weights AICore keeps once, system-wide (see
     * docs/04-runtime.md's own note on why that's structurally different
     * from a per-app GGUF download like [ai.localstudio.app.vosk.VoskNativeLibrary]'s).
     * [onProgress] reports (downloadedBytes, totalBytes) — totalBytes is 0
     * until [DownloadCallback.onDownloadStarted] fires.
     */
    suspend fun ensureDownloaded(onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }) {
        var totalBytes = 0L
        suspendCancellableCoroutine<Unit> { cont ->
            model.downloadFeature(
                object : DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) {
                        totalBytes = bytesToDownload
                        onProgress(0L, totalBytes)
                    }

                    override fun onDownloadProgress(totalBytesDownloaded: Long) {
                        onProgress(totalBytesDownloaded, totalBytes)
                    }

                    override fun onDownloadCompleted() {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onDownloadFailed(e: GenAiException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                },
            )
        }
    }

    /** Runs [prompt] against Gemini Nano and returns its plain-text answer. Only meaningful once [status] reports [FeatureStatus.AVAILABLE]. */
    suspend fun generate(prompt: String): String =
        model.generateContent(generateContentRequest(TextPart(prompt))).text

    /** Releases whatever native/IPC resources the client holds. Safe to call even if [model] was never actually used. */
    fun close() {
        runCatching { model.close() }
    }
}
