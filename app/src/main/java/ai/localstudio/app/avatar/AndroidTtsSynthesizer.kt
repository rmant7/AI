package ai.localstudio.app.avatar

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The device's `TextToSpeech`, used only to *synthesize* to a file. Also owns
 * the Android-specific voice choice: an explicit voice from the test screen,
 * else the remembered one, else an automatic male-leaning pick.
 */
internal class AndroidTtsSynthesizer(context: Context) : SpeechSynthesizer {

    @Volatile
    private var ready = false

    // The utterance id is the file's name: unique per request, and all the
    // listener callbacks report.
    private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }.also { engine ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.invoke(true)
            }

            // The single-arg overload is the abstract one every
            // UtteranceProgressListener must implement; the platform always
            // calls the two-arg overload below when it has an error code
            // (every real TTS engine), so this one is effectively unused —
            // still required to compile.
            override fun onError(utteranceId: String) = Unit

            override fun onError(utteranceId: String, errorCode: Int) {
                pending.remove(utteranceId)?.invoke(false)
            }
        })
    }

    override val isReady: Boolean get() = ready

    @Volatile
    private var manualVoice: Voice? = null

    // A remembered voice, by name: resolved when speaking, since the engine's
    // voice list is empty until it has finished its own async init.
    @Volatile
    private var preferredVoiceName: String? = null

    fun availableVoices(): List<Voice> = tts.voices?.toList().orEmpty()

    fun setManualVoice(voice: Voice?) {
        manualVoice = voice
    }

    fun setPreferredVoiceName(name: String?) {
        preferredVoiceName = name
    }

    override fun synthesize(text: String, file: File, onResult: (Boolean) -> Unit) {
        if (!ready) {
            onResult(false)
            return
        }
        applyVoice()
        pending[file.name] = onResult
        if (tts.synthesizeToFile(text, Bundle(), file, file.name) != TextToSpeech.SUCCESS) {
            pending.remove(file.name)?.invoke(false)
        }
    }

    override fun stop() {
        tts.stop()
        pending.clear()
    }

    override fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun applyVoice() {
        val manual = manualVoice
        if (manual != null) {
            tts.voice = manual
            tts.setPitch(1.0f)
            return
        }
        val remembered = preferredVoiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (remembered != null) {
            tts.voice = remembered
            tts.setPitch(1.0f)
            return
        }
        preferMaleVoice()
    }

    // The avatar depicts a specific (male) person, so this engine's voice
    // should match — TextToSpeech has no gender field on Voice, so this is
    // two best-effort layers rather than one reliable API: (1) some engines
    // do put "male"/"female" in a voice's own name (careful: "female"
    // contains "male" as a substring, so the exclusion below isn't
    // optional); (2) a lower pitch, which works on every engine/voice
    // regardless of (1) ever matching, as the actual fallback that makes
    // this reliable rather than a guess.
    private fun preferMaleVoice() {
        val activeLocale = tts.voice?.locale ?: tts.language
        val maleVoice = activeLocale?.let { locale ->
            tts.voices?.firstOrNull { voice ->
                voice.locale.language == locale.language &&
                    voice.name.contains("male", ignoreCase = true) &&
                    !voice.name.contains("female", ignoreCase = true)
            }
        }
        if (maleVoice != null) tts.voice = maleVoice
        tts.setPitch(MALE_PITCH)
    }

    private companion object {
        const val MALE_PITCH = 0.85f
    }
}
