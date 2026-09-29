package ai.localstudio.app.avatar

import ai.localstudio.app.voicebenchmark.QwenTtsException
import ai.localstudio.app.voicebenchmark.QwenTtsModelProvider
import ai.localstudio.app.voicebenchmark.QwenTtsRuntimeManager
import ai.localstudio.app.voicebenchmark.ReferenceVoiceRecorder
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import ai.localstudio.app.AppContainer
import ai.localstudio.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The user's cloned voice: local Qwen3-TTS 0.6B Base, cloned from the
 * reference recording and transcript made on the Voice Benchmark screen.
 * Sentences are synthesized one at a time, in order, on one worker — the
 * model itself allows only one generation at a time — while the sentence
 * before is already playing, so speech starts after the first sentence rather
 * than after the whole answer.
 *
 * The model stays loaded between sentences (that load is the expensive part)
 * and is freed on [shutdown]. Whether it can run at all is decided before
 * this class is chosen — see [AvatarVoiceAvailability].
 */
internal class QwenTtsSynthesizer(context: Context, private val referenceText: String) : SpeechSynthesizer {

    private class Request(val text: String, val file: File, val generation: Int, val onResult: (Boolean) -> Unit)

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Request>(Channel.UNLIMITED)

    @Volatile
    private var generation = 0

    init {
        scope.launch { for (request in requests) process(request) }
    }

    override val isReady: Boolean get() = true

    override fun synthesize(text: String, file: File, onResult: (Boolean) -> Unit) {
        note("sentence queued (${text.length} chars)")
        requests.trySend(Request(text, file, generation, onResult))
    }

    override fun stop() {
        generation++
        // Stops the sentence being generated, between audio chunks; the model
        // stays loaded for the next answer.
        QwenTtsRuntimeManager.requestCancel()
    }

    override fun shutdown() {
        stop()
        requests.close()
        scope.cancel()
        QwenTtsRuntimeManager.releaseAsync()
    }

    private suspend fun process(request: Request) {
        if (request.generation != generation) return
        val started = SystemClock.elapsedRealtime()
        fun since() = "%.1f s".format((SystemClock.elapsedRealtime() - started) / 1000.0)
        note("sentence started (${request.text.length} chars)")
        val ok = try {
            val reference = ReferenceVoiceRecorder.referenceFile(app)
            val load = QwenTtsRuntimeManager.ensureLoaded(app, QwenTtsModelProvider.get(app))
            note("model ${if (load.alreadyLoaded) "already loaded" else "loaded"}, threads=${load.threads}, after ${since()}")
            QwenTtsRuntimeManager.prepareVoice(app, reference, referenceText)
            note("voice ready after ${since()}")
            QwenTtsRuntimeManager.synthesize(request.text, languageIdFor(request.text), request.file)
            note("sentence synthesized in ${since()}")
            true
        } catch (e: CancellationException) {
            // Either this worker is being shut down (rethrown here) or just
            // the current sentence was stopped (carry on with the queue).
            currentCoroutineContext().ensureActive()
            note("sentence cancelled after ${since()}")
            false
        } catch (e: Exception) {
            // Not only QwenTtsException: any failure must be visible, or "Play" just does nothing.
            Log.w(TAG, "Qwen3-TTS failed", e)
            val message = e.message ?: e.javaClass.simpleName
            note("FAILED after ${since()}: $message")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(app, app.getString(R.string.avatar_qwen_failed, message), Toast.LENGTH_LONG).show()
            }
            false
        }
        if (request.generation == generation) request.onResult(ok)
    }

    private fun note(message: String) {
        runCatching { AppContainer.get(app).appLog.record("AVATAR_QWEN", message) }
    }

    // Codec language ids from qwen3-tts.cpp: Cyrillic text is Russian, anything else English.
    private fun languageIdFor(text: String): Int {
        val cyrillic = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (cyrillic >= latin) RUSSIAN else ENGLISH
    }

    private companion object {
        const val TAG = "QwenTtsSynthesizer"
        const val RUSSIAN = 2069
        const val ENGLISH = 2050
    }
}

/** Why the cloned voice cannot be used right now, or null if it can. */
object AvatarVoiceAvailability {

    /**
     * The voice configuration to actually use: the cloned voice if it is
     * selected *and* usable, otherwise Android TTS — an unusable cloned voice
     * (model not downloaded, no reference recording, ...) never leaves the
     * avatar silent.
     */
    fun config(context: Context, settings: ai.localstudio.app.Settings): AvatarVoiceConfig {
        val wanted = AvatarVoiceBackend.fromKey(settings.avatarVoiceBackend)
        val usable = wanted == AvatarVoiceBackend.QWEN && qwenProblem(context, settings.voiceReferenceText) == null
        return AvatarVoiceConfig(
            backend = if (usable) AvatarVoiceBackend.QWEN else AvatarVoiceBackend.ANDROID,
            preferredVoiceName = settings.avatarVoiceName,
            qwenReferenceText = settings.voiceReferenceText,
        )
    }

    fun qwenProblem(context: Context, referenceText: String): Int? = when {
        !ai.localstudio.qwen3tts.Qwen3TtsNative.isLoaded -> ai.localstudio.app.R.string.avatar_qwen_problem_native
        ai.localstudio.whisper.CpuVariant.current == ai.localstudio.whisper.CpuVariant.BASELINE ->
            ai.localstudio.app.R.string.avatar_qwen_problem_cpu
        !QwenTtsModelProvider.get(context).isReady() -> ai.localstudio.app.R.string.avatar_qwen_problem_model
        !ReferenceVoiceRecorder.referenceFile(context).let { it.exists() && it.length() > 44 } ->
            ai.localstudio.app.R.string.avatar_qwen_problem_reference
        referenceText.isBlank() -> ai.localstudio.app.R.string.avatar_qwen_problem_transcript
        else -> null
    }
}
