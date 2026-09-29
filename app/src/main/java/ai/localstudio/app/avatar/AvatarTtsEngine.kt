package ai.localstudio.app.avatar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Speaks text for [AvatarSpeechController] and reports what is being said,
 * as a plain [Event] stream via [onEvent].
 *
 * `android.speech.tts.TextToSpeech` is used only to *synthesize* (to a WAV
 * file per utterance); this class then plays that audio itself and follows
 * the playback position, emitting [Event.Audio] (the samples about to be
 * heard) and [Event.Range] (the letter estimated to be spoken right now —
 * see [SpeechAlignment]). Relying on the engine's own `onRangeStart` /
 * `onAudioAvailable` callbacks was the reason the mouth barely correlated
 * with the voice: engines report word-level ranges at best and often no
 * audio at all, and neither is tied to what is actually audible. Driving
 * the events from the played samples makes the mouth follow the real sound.
 *
 * Deliberately separate from [ai.localstudio.app.TranslationActivity]'s own
 * `TextToSpeech` instance for its one-shot "play this translation" button —
 * sharing one would mean either interrupting the other's queue.
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

    private class Job(val id: String, val text: String, val file: File, val generation: Int)

    private class Wav(val sampleRate: Int, val channels: Int, val pcm: ByteArray)

    private val cacheDir = context.applicationContext.cacheDir

    @Volatile
    private var ready = false

    // Callbacks report only the utteranceId, not the text — this is what lets
    // Range events resolve back to "which text was that a range of" without
    // changing this class's own public API every time a new utterance starts.
    private val utteranceText = ConcurrentHashMap<String, String>()
    private val utteranceFile = ConcurrentHashMap<String, File>()

    // Bumped by stop(): queued/playing work from an older generation is dropped.
    @Volatile
    private var generation = 0
    private val queue = LinkedBlockingQueue<Job>()
    private val workerLock = Any()
    private var worker: Thread? = null

    @Volatile
    private var track: AudioTrack? = null

    private val tts: TextToSpeech = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }.also { engine ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                val text = utteranceText[utteranceId]
                val file = utteranceFile[utteranceId]
                if (text == null || file == null || !file.exists()) {
                    discard(utteranceId)
                    return
                }
                queue.put(Job(utteranceId, text, file, generation))
                startWorker()
            }

            // The single-arg overload is the abstract one every
            // UtteranceProgressListener must implement; the platform always
            // calls the two-arg overload below when it has an error code
            // (every real TTS engine), so this one is effectively unused —
            // still required to compile.
            override fun onError(utteranceId: String) = Unit

            override fun onError(utteranceId: String, errorCode: Int) {
                discard(utteranceId)
                onEvent(Event.Failed(utteranceId))
            }
        })
    }

    val isReady: Boolean get() = ready

    // Set only from AvatarTestActivity's voice picker — every other caller
    // (the real chat pipeline, via AvatarSpeechController) leaves this null
    // and gets the remembered voice or preferMaleVoice()'s own automatic pick.
    @Volatile
    private var manualVoice: Voice? = null

    // A remembered voice, by name: resolved when speaking, since the engine's
    // voice list is empty until it has finished its own async init.
    @Volatile
    private var preferredVoiceName: String? = null

    /** The text actually behind an in-flight utterance's [Event.Range]/[Event.Audio] — null once it's [Event.Done]/[Event.Failed]. */
    fun textFor(utteranceId: String): String? = utteranceText[utteranceId]

    /** Every voice this device's TTS engine(s) currently expose — empty until [isReady]. */
    fun availableVoices(): List<Voice> = tts.voices?.toList().orEmpty()

    /** Overrides the automatic pick for every call after this one — null reverts to automatic. */
    fun setManualVoice(voice: Voice?) {
        manualVoice = voice
    }

    fun setPreferredVoiceName(name: String?) {
        preferredVoiceName = name
    }

    /**
     * Queues [text] to be synthesized and then spoken after whatever is
     * already queued. Returns the utterance id [Event]s for this call will
     * carry, or null if the engine isn't ready yet or no usable [locale] was
     * found.
     */
    fun speak(text: String, locale: Locale?): String? {
        if (!ready || text.isBlank()) return null
        if (!applyVoice(locale)) return null
        val utteranceId = UUID.randomUUID().toString()
        val file = File(cacheDir, "avatar_tts_$utteranceId.wav")
        utteranceText[utteranceId] = text
        utteranceFile[utteranceId] = file
        val result = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            discard(utteranceId)
            return null
        }
        return utteranceId
    }

    /** Drops everything queued or playing — a new turn starting, or the user hitting Stop. */
    fun stop() {
        generation++
        tts.stop()
        track?.let { runCatching { it.stop() } }
        utteranceText.clear()
        utteranceFile.values.forEach { it.delete() }
        utteranceFile.clear()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun discard(utteranceId: String) {
        utteranceText.remove(utteranceId)
        utteranceFile.remove(utteranceId)?.delete()
    }

    private fun startWorker() {
        synchronized(workerLock) {
            if (worker != null) return
            worker = Thread({ runWorker() }, "avatar-tts-player").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun runWorker() {
        while (true) {
            val job = queue.poll(IDLE_EXIT_SECONDS, TimeUnit.SECONDS)
            if (job == null) {
                synchronized(workerLock) {
                    if (queue.isEmpty()) {
                        worker = null
                        return
                    }
                }
                continue
            }
            if (job.generation != generation) {
                job.file.delete()
                continue
            }
            try {
                play(job)
            } catch (e: Exception) {
                onEvent(Event.Failed(job.id))
            } finally {
                job.file.delete()
                utteranceFile.remove(job.id)
            }
        }
    }

    private fun play(job: Job) {
        val wav = readWav(job.file)
        if (wav == null || wav.pcm.size < 2) {
            utteranceText.remove(job.id)
            onEvent(Event.Failed(job.id))
            return
        }
        val bytesPerFrame = 2 * wav.channels
        val totalFrames = wav.pcm.size / bytesPerFrame
        val stepFrames = maxOf(1, wav.sampleRate / ENVELOPE_HZ)
        val envelope = FloatArray(totalFrames / stepFrames + 1) { i ->
            val from = minOf(wav.pcm.size, i * stepFrames * bytesPerFrame)
            val to = minOf(wav.pcm.size, from + stepFrames * bytesPerFrame)
            PcmEnvelopeAnalyzer.rms(wav.pcm.copyOfRange(from, to))
        }
        val timeline = SpeechAlignment.build(job.text, envelope, stepFrames.toFloat() / wav.sampleRate)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(wav.sampleRate)
                    .setChannelMask(if (wav.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(wav.pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = audioTrack
        try {
            audioTrack.write(wav.pcm, 0, wav.pcm.size)
            onEvent(Event.Started(job.id))
            audioTrack.play()

            var lastLetter = -1
            while (job.generation == generation) {
                val position = audioTrack.playbackHeadPosition
                if (position >= totalFrames) break
                timeline.letterAt(position.toFloat() / wav.sampleRate)?.let { letter ->
                    if (letter != lastLetter) {
                        lastLetter = letter
                        onEvent(Event.Range(job.id, letter, letter + 1))
                    }
                }
                // The samples about to be heard, not everything the engine has
                // synthesized so far — that is what ties the mouth to the sound.
                val from = minOf(wav.pcm.size, position * bytesPerFrame)
                val to = minOf(wav.pcm.size, from + 2 * stepFrames * bytesPerFrame)
                onEvent(Event.Audio(job.id, wav.pcm.copyOfRange(from, to)))
                Thread.sleep(TICK_MS)
            }
            if (job.generation == generation) {
                utteranceText.remove(job.id)
                onEvent(Event.Done(job.id))
            }
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
            track = null
        }
    }

    private fun readWav(file: File): Wav? {
        val b = file.readBytes()
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        var channels = 1
        var sampleRate = 22050
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val declared = u32(pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= b.size) {
                channels = u16(body + 2).coerceIn(1, 2)
                sampleRate = u32(body + 4).toInt()
                if (u16(body + 14) != 16) return null
            } else if (id == "data") {
                // Some engines stream the file and leave the data size as 0 or
                // 0xFFFFFFFF — trust what is actually there.
                val available = (b.size - body).toLong()
                val size = if (declared <= 0L || declared > available) available else declared
                val aligned = (size - size % (2 * channels)).toInt()
                return Wav(sampleRate, channels, b.copyOfRange(body, body + aligned))
            }
            pos = body + declared.toInt().coerceAtLeast(0) + (declared.toInt() and 1)
        }
        return null
    }

    // Picks the voice for the next utterance: an explicit choice from the
    // test screen, else the remembered one, else an automatic male-leaning
    // pick. Returns false only if a requested [locale] has no voice at all.
    private fun applyVoice(locale: Locale?): Boolean {
        val manual = manualVoice
        if (manual != null) {
            tts.voice = manual
            tts.setPitch(1.0f)
            return true
        }
        val remembered = preferredVoiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (remembered != null) {
            tts.voice = remembered
            tts.setPitch(1.0f)
            return true
        }
        if (locale != null && tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return false
        if (locale != null) tts.language = locale
        preferMaleVoice()
        return true
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
        const val ENVELOPE_HZ = 50
        const val TICK_MS = 16L
        const val IDLE_EXIT_SECONDS = 20L
    }
}
