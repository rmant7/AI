package ai.localstudio.app.avatar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
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
 * A [SpeechSynthesizer] — the device's TextToSpeech or the local Qwen3-TTS
 * cloned voice, chosen by [AvatarVoiceConfig] — is used only to *synthesize*
 * (to a WAV file per utterance); this class then plays that audio itself and follows
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
class AvatarTtsEngine(
    context: Context,
    config: AvatarVoiceConfig = AvatarVoiceConfig(),
    private val onEvent: (Event) -> Unit,
) {

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

    private val appContext = context.applicationContext
    private val cacheDir = appContext.cacheDir

    // Timing of the sentence pipeline: how long a sentence waited for its audio, and the silence
    // between the end of one sentence and the start of the next (the number that matters for a
    // voice slower than real time). Short lines to the app log, tag AVATAR_TTS.
    private val queuedAt = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastEndAt = 0L

    // Which voice makes the WAV; everything below is the same for all of them.
    private val synthesizer: SpeechSynthesizer = when (config.backend) {
        AvatarVoiceBackend.ANDROID -> AndroidTtsSynthesizer(context).also { it.setPreferredVoiceName(config.preferredVoiceName) }
        AvatarVoiceBackend.QWEN -> QwenTtsSynthesizer(context, config.qwenReferenceText)
    }

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

    val isReady: Boolean get() = synthesizer.isReady

    /** The text actually behind an in-flight utterance's [Event.Range]/[Event.Audio] — null once it's [Event.Done]/[Event.Failed]. */
    fun textFor(utteranceId: String): String? = utteranceText[utteranceId]

    /** Every Android TTS voice this device exposes — empty until [isReady], and always empty for the cloned voice. */
    fun availableVoices(): List<Voice> = (synthesizer as? AndroidTtsSynthesizer)?.availableVoices().orEmpty()

    /** Android TTS only: overrides the automatic pick for every call after this one — null reverts to automatic. */
    fun setManualVoice(voice: Voice?) {
        (synthesizer as? AndroidTtsSynthesizer)?.setManualVoice(voice)
    }

    fun setPreferredVoiceName(name: String?) {
        (synthesizer as? AndroidTtsSynthesizer)?.setPreferredVoiceName(name)
    }

    /**
     * Queues [text] to be synthesized and then spoken after whatever is
     * already queued. Returns the utterance id [Event]s for this call will
     * carry, or null if the engine isn't ready yet. [locale] is unused: the
     * voice decides the language.
     */
    @Suppress("UNUSED_PARAMETER")
    fun speak(text: String, locale: Locale?): String? {
        if (!isReady || text.isBlank()) return null
        val utteranceId = UUID.randomUUID().toString()
        val file = File(cacheDir, "avatar_tts_$utteranceId.wav")
        utteranceText[utteranceId] = text
        utteranceFile[utteranceId] = file
        queuedAt[utteranceId] = SystemClock.elapsedRealtime()
        synthesizer.synthesize(text, file) { ok -> onSynthesized(utteranceId, ok) }
        return utteranceId
    }

    private fun onSynthesized(utteranceId: String, ok: Boolean) {
        val text = utteranceText[utteranceId]
        val file = utteranceFile[utteranceId]
        if (!ok || text == null || file == null || !file.exists()) {
            discard(utteranceId)
            if (!ok) onEvent(Event.Failed(utteranceId))
            return
        }
        queue.put(Job(utteranceId, text, file, generation))
        startWorker()
    }

    /** Drops everything queued or playing — a new turn starting, or the user hitting Stop. */
    fun stop() {
        generation++
        synthesizer.stop()
        track?.let { runCatching { it.stop() } }
        utteranceText.clear()
        utteranceFile.values.forEach { it.delete() }
        utteranceFile.clear()
        queuedAt.clear()
        lastEndAt = 0L
    }

    fun shutdown() {
        stop()
        synthesizer.shutdown()
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
            logTiming(job, totalFrames.toDouble() / wav.sampleRate)
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
                lastEndAt = SystemClock.elapsedRealtime()
                utteranceText.remove(job.id)
                onEvent(Event.Done(job.id))
            }
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
            track = null
        }
    }

    private fun logTiming(job: Job, audioSeconds: Double) {
        val now = SystemClock.elapsedRealtime()
        val waited = queuedAt.remove(job.id)?.let { (now - it) / 1000.0 }
        val gap = lastEndAt.takeIf { it > 0 && now - it < 60_000 }?.let { (now - it) / 1000.0 }
        val line = buildString {
            append("sentence ${job.text.length} chars, audio ${"%.1f".format(audioSeconds)} s")
            waited?.let { append(", ready after ${"%.1f".format(it)} s") }
            append(if (gap != null) ", silence before it ${"%.1f".format(gap)} s" else ", first of the answer")
        }
        runCatching { ai.localstudio.app.AppContainer.get(appContext).appLog.record("AVATAR_TTS", line) }
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

    private companion object {
        const val ENVELOPE_HZ = 50
        const val TICK_MS = 16L
        const val IDLE_EXIT_SECONDS = 20L
    }
}
