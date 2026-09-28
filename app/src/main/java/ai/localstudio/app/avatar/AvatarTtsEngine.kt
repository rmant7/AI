package ai.localstudio.app.avatar

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/**
 * A thin wrapper around `android.speech.tts.TextToSpeech`, dedicated to
 * [AvatarSpeechController] — [ai.localstudio.app.TranslationActivity] has
 * its own, separate `TextToSpeech` instance for its one-shot "play this
 * translation" button; sharing a single engine between a queued, continuous
 * speech feed and an independent one-off tap would mean either one
 * interrupting the other's utterance queue.
 *
 * Reports every callback [UtteranceProgressListener] offers as of this
 * app's `minSdk` (26) — `onRangeStart` (added API 26) for which character
 * range is being spoken right now, and `onAudioAvailable` (added API 24)
 * for the synthesized PCM itself — as one [Event] stream via [onEvent],
 * rather than exposing the listener or the underlying engine directly.
 * Neither callback is guaranteed by every installed TTS engine; a caller
 * that only ever sees [Event.Started]/[Event.Done] for a given utterance
 * still gets a spoken answer, just with a cruder mouth animation — see
 * [AvatarSpeechController]'s own fallback for that case.
 */
class AvatarTtsEngine(context: Context, private val onEvent: (Event) -> Unit) {

    sealed interface Event {
        data class Started(val utteranceId: String) : Event
        /** [start]/[end] index into whatever text [speak] was given for [utteranceId] — see [VisemeMapper.shapeForRange]. */
        data class Range(val utteranceId: String, val start: Int, val end: Int) : Event
        data class Audio(val utteranceId: String, val pcm: ByteArray) : Event
        data class Done(val utteranceId: String) : Event
        data class Failed(val utteranceId: String) : Event
    }

    @Volatile
    private var ready = false

    // TextToSpeech's own callbacks report only the utteranceId, not the
    // text — this is what lets Range events resolve back to "which text was
    // that a range of" without changing AvatarTtsEngine's own public API
    // every time a new utterance starts.
    private val utteranceText = mutableMapOf<String, String>()

    private val tts: TextToSpeech = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }.also { engine ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = onEvent(Event.Started(utteranceId))

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) =
                onEvent(Event.Range(utteranceId, start, end))

            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) =
                onEvent(Event.Audio(utteranceId, audio))

            override fun onDone(utteranceId: String) {
                utteranceText.remove(utteranceId)
                onEvent(Event.Done(utteranceId))
            }

            // The single-arg overload is the abstract one every
            // UtteranceProgressListener must implement; the platform always
            // calls the two-arg overload below when it has an error code
            // (every real TTS engine), so this one is effectively unused —
            // still required to compile.
            override fun onError(utteranceId: String) = Unit

            override fun onError(utteranceId: String, errorCode: Int) {
                utteranceText.remove(utteranceId)
                onEvent(Event.Failed(utteranceId))
            }
        })
    }

    val isReady: Boolean get() = ready

    // Set only from AvatarTestActivity's voice picker — every other caller
    // (the real chat pipeline, via AvatarSpeechController) leaves this null
    // and gets preferMaleVoice()'s own automatic pick instead.
    @Volatile
    private var manualVoice: Voice? = null

    /** The text actually behind an in-flight utterance's [Event.Range]/[Event.Audio] — null once it's [Event.Done]/[Event.Failed]. */
    fun textFor(utteranceId: String): String? = utteranceText[utteranceId]

    /** Every voice this device's TTS engine(s) currently expose — empty until [isReady]. */
    fun availableVoices(): List<Voice> = tts.voices?.toList().orEmpty()

    /** Overrides [preferMaleVoice]'s own pick for every call after this one — null reverts to automatic. */
    fun setManualVoice(voice: Voice?) {
        manualVoice = voice
    }

    /**
     * Queues [text] to speak once whatever is already queued finishes
     * (`QUEUE_ADD`, not `QUEUE_FLUSH` — see this class's own doc comment on
     * why a continuous feed needs queuing, unlike a single "play" tap).
     * Returns the utterance id [Event]s for this call will carry, or null
     * if the engine isn't ready yet or no usable [locale] was found.
     */
    fun speak(text: String, locale: Locale?): String? {
        if (!ready || text.isBlank()) return null
        val voice = manualVoice
        if (voice != null) {
            tts.voice = voice
        } else {
            if (locale != null && tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return null
            if (locale != null) tts.language = locale
            preferMaleVoice()
        }
        val utteranceId = UUID.randomUUID().toString()
        utteranceText[utteranceId] = text
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            utteranceText.remove(utteranceId)
            return null
        }
        return utteranceId
    }

    /** Drops everything queued — a new turn starting, or the user hitting Stop. */
    fun stop() {
        tts.stop()
        utteranceText.clear()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        utteranceText.clear()
    }

    // The avatar depicts a specific (male) person, so this engine's voice
    // should match — TextToSpeech has no gender field on Voice, so this is
    // two best-effort layers rather than one reliable API: (1) some engines
    // do put "male"/"female" in a voice's own name (careful: "female"
    // contains "male" as a substring, so the exclusion below isn't
    // optional), picked per call since setting `language` above resets the
    // engine back to that language's default voice; (2) a lower pitch,
    // which works on every engine/voice regardless of (1) ever matching, as
    // the actual fallback that makes this reliable rather than a guess.
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
