package ai.localstudio.app.avatar

import java.io.File

/** Which voice the avatar speaks with. */
enum class AvatarVoiceBackend(val key: String) {
    /** The device's own TextToSpeech. */
    ANDROID("android"),

    /** The user's cloned voice, from the local Qwen3-TTS 0.6B Base model. */
    QWEN("qwen"),
    ;

    companion object {
        fun fromKey(key: String?): AvatarVoiceBackend = entries.firstOrNull { it.key == key } ?: ANDROID
    }
}

/** Everything a backend needs to know beyond the text. */
data class AvatarVoiceConfig(
    val backend: AvatarVoiceBackend = AvatarVoiceBackend.ANDROID,
    /** Android TTS only: the remembered voice, by name. */
    val preferredVoiceName: String? = null,
    /** Qwen only: the transcript of the reference recording being cloned. */
    val qwenReferenceText: String = "",
)

/**
 * Turns text into a WAV file — the only thing that differs between voices.
 * Everything after it (playing the file, following the playback position,
 * aligning letters to the sound, driving the mouth) is shared, in
 * [AvatarTtsEngine].
 */
internal interface SpeechSynthesizer {
    val isReady: Boolean

    /**
     * Writes [text] to [file] as a WAV and reports the outcome through
     * [onResult] (true = the file is complete), exactly once unless [stop] or
     * [shutdown] dropped the request first. Returns immediately; requests are
     * synthesized in the order they were made.
     */
    fun synthesize(text: String, file: File, onResult: (Boolean) -> Unit)

    /** Drops everything queued and stops whatever is being synthesized. */
    fun stop()

    fun shutdown()
}
