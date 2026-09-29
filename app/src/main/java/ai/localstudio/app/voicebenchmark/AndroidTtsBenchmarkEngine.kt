package ai.localstudio.app.voicebenchmark

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The device's own TextToSpeech — the baseline every cloning model is
 * measured against. It cannot imitate a voice, so the reference audio and
 * transcript are ignored.
 */
class AndroidTtsBenchmarkEngine(context: Context, private val outputDir: File) : VoiceBenchmarkEngine {

    private val appContext = context.applicationContext

    override val id = "android_tts"
    override val displayName = "Android TTS"
    override val supportedLanguages = setOf("ru", "en", "he")

    override suspend fun availability() = EngineAvailability.AVAILABLE

    override suspend fun synthesize(
        text: String,
        language: String,
        referenceAudio: File?,
        referenceText: String?,
    ): VoiceBenchmarkResult = withContext(Dispatchers.Main.immediate) {
        val loadStart = SystemClock.elapsedRealtime()
        val tts = initTts()
            ?: return@withContext VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.ERROR, "TextToSpeech failed to start")
        try {
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            val locale = Locale.forLanguageTag(language)
            if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                return@withContext VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.UNSUPPORTED_LANGUAGE, "No voice for $language")
            }
            tts.language = locale
            outputDir.mkdirs()
            val file = File(outputDir, "android_tts_${System.currentTimeMillis()}.wav")
            val genStart = SystemClock.elapsedRealtime()
            val done = suspendCancellableCoroutine<Boolean> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit
                    override fun onDone(utteranceId: String) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(utteranceId: String) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                if (tts.synthesizeToFile(text, Bundle(), file, "voice_benchmark") != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation { tts.stop() }
            }
            val generationMs = SystemClock.elapsedRealtime() - genStart
            val audioMs = if (done) WavFiles.durationMs(file) else null
            if (audioMs == null || audioMs <= 0) {
                file.delete()
                VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.ERROR, "TextToSpeech produced no audio")
            } else {
                VoiceBenchmarkResult.ok(id, file, generationMs, audioMs, loadMs)
            }
        } finally {
            tts.shutdown()
        }
    }

    private suspend fun initTts(): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext) { status ->
            if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) created else null)
        }
        cont.invokeOnCancellation { created?.shutdown() }
    }
}
