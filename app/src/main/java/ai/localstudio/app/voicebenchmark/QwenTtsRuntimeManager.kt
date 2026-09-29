package ai.localstudio.app.voicebenchmark

import ai.localstudio.app.llama.LlamaBridge
import ai.localstudio.qwen3tts.Qwen3TtsNative
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** [log] is whatever the native runtime reported before it failed. */
class QwenTtsException(message: String, val log: String = "") : Exception(message)

/**
 * The one owner of the native Qwen3-TTS model: at most one context exists at
 * any time, and every native call runs under one lock, so the model is never
 * loaded twice and no two generations overlap. Deliberately separate from the
 * app's LLM RuntimeManager — the two are not integrated yet.
 *
 * Native calls cannot be interrupted, so they run detached from the caller's
 * cancellation: cancelling the caller flips the native cancel flag (which
 * stops generation between audio chunks) and then waits for the native call to
 * actually return before rethrowing — nothing is left running in native code.
 */
object QwenTtsRuntimeManager {

    class LoadInfo(
        val loadMs: Long,
        val alreadyLoaded: Boolean,
        val availRamMbBefore: Long,
        val pssMbBefore: Long,
        val pssMbAfter: Long,
        val threads: Int,
        val log: String,
    )

    /** [cached]: the speaker/reference analysis was reused (from memory or from disk) rather than redone. */
    class PrepInfo(
        val prepMs: Long,
        val cached: Boolean,
        val referenceSeconds: Double,
        val usedSeconds: Double,
        val log: String,
    )

    class SynthInfo(val generationMs: Long, val firstAudioMs: Long?, val log: String)

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var handle = 0L
    private var preparedKey: String? = null
    private var promptFile: File? = null
    private var loadedThreads = 0

    /** Diagnostics: CPU threads for the model (null = the app's default), applied on the next load. */
    @Volatile
    var threadsOverride: Int? = null

    /** Diagnostics: streaming chunk length / vocoder left context in ms (default 3 s / 0.5 s: measured about 2x faster than upstream's 1 s / 2 s). */
    @Volatile
    var streamingChunkMs: Int = 3000
    @Volatile
    var streamingLeftMs: Int = 500

    /** Diagnostics: use only the first N seconds of the reference recording for the voice prompt (null = all of it; default 6 s like the upstream benchmarks — a long reference makes every step slower). */
    @Volatile
    var referenceMaxSeconds: Double? = 6.0

    /**
     * Default thread count for Qwen: every core within ~70% of the fastest core's clock (prime + big
     * cores). [LlamaBridge.defaultThreads] counts only the single top-clocked core, which on a
     * 1+3+4 SoC (e.g. Snapdragon 865) means one thread — measured ~20-200x slower than realtime.
     */
    fun defaultThreads(): Int {
        val total = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until total).mapNotNull { core ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        if (freqs.size < total) return LlamaBridge.defaultThreads()
        val top = freqs.max()
        return freqs.count { it * 10 >= top * 7 }.coerceIn(1, 8)
    }

    /** Free RAM needed before loading: ~0.9 GB of weights plus graph buffers and the vocoder. */
    private const val MIN_AVAILABLE_MB = 1400L

    suspend fun ensureLoaded(context: Context, provider: QwenTtsModelProvider): LoadInfo = lock.withLock {
        val app = context.applicationContext
        val wantedThreads = threadsOverride ?: defaultThreads()
        // A different thread count needs a fresh context.
        if (handle != 0L && loadedThreads != wantedThreads) {
            val old = handle
            handle = 0L
            preparedKey = null
            runNative(null) { Qwen3TtsNative.destroy(old) }
        }
        if (handle != 0L) return@withLock LoadInfo(0, true, availMb(app), pssMb(), pssMb(), loadedThreads, "")
        if (!Qwen3TtsNative.isLoaded) throw QwenTtsException("Native Qwen3-TTS library could not be loaded")

        val avail = availMb(app)
        if (avail < MIN_AVAILABLE_MB) {
            throw QwenTtsException("Not enough free RAM to load Qwen3-TTS: need about $MIN_AVAILABLE_MB MB, $avail MB available")
        }
        val pssBefore = pssMb()
        val started = SystemClock.elapsedRealtime()
        val newHandle = runNative(null) { Qwen3TtsNative.create(wantedThreads) }
        if (newHandle == 0L) throw QwenTtsException("Could not create the Qwen3-TTS context")
        val ok = runNative(null) {
            Qwen3TtsNative.loadModels(newHandle, provider.modelDir.absolutePath, QwenTtsModelDescriptor.TALKER_FILE)
        }
        val log = Qwen3TtsNative.takeLog(newHandle)
        if (!ok) {
            val error = Qwen3TtsNative.lastError(newHandle)
            Qwen3TtsNative.destroy(newHandle)
            throw QwenTtsException("Model load failed: $error", log)
        }
        handle = newHandle
        loadedThreads = wantedThreads
        LoadInfo(SystemClock.elapsedRealtime() - started, false, avail, pssBefore, pssMb(), wantedThreads, log)
    }

    /**
     * Prepares the voice prompt for this exact reference recording + transcript
     * (+ trim setting) — or reuses it. The prompt is a file next to a small key
     * file, so it survives the model being unloaded and the app being
     * restarted: the reference is analysed once per voice, not once per run.
     */
    suspend fun prepareVoice(context: Context, referenceWav: File, referenceText: String): PrepInfo = lock.withLock {
        check(handle != 0L) { "model not loaded" }
        val dir = File(context.applicationContext.filesDir, "qwen3tts").also { it.mkdirs() }
        val prompt = File(dir, "voice_prompt.json")
        val keyFile = File(dir, "voice_prompt.key")
        val maxSeconds = referenceMaxSeconds
        val fullSeconds = (WavFiles.durationMs(referenceWav) ?: 0L) / 1000.0
        val usedSeconds = if (maxSeconds != null) minOf(maxSeconds, fullSeconds) else fullSeconds

        val key = "${referenceWav.absolutePath}:${referenceWav.length()}:${referenceWav.lastModified()}:" +
            "${referenceText.hashCode()}:${maxSeconds ?: 0.0}:pause-trim-v2"
        val onDisk = keyFile.takeIf { it.exists() }?.readText()
        if (prompt.exists() && (key == preparedKey || key == onDisk)) {
            preparedKey = key
            promptFile = prompt
            return@withLock PrepInfo(0, true, fullSeconds, usedSeconds, "")
        }

        // The transcript must describe exactly the audio that is used: audio cut at 6 s with the text of the
        // whole 30 s recording makes the model lose track of where the reference ends, and it then never
        // emits its end token. So the text is cut at the same relative point, at a word boundary.
        var source = referenceWav
        var promptText = referenceText
        if (maxSeconds != null && fullSeconds > maxSeconds) {
            val (file, keptSeconds) = WavFiles.trimAtPause(referenceWav, File(dir, "reference_trimmed.wav"), maxSeconds)
            source = file
            promptText = trimTextToFraction(referenceText, keptSeconds / fullSeconds)
        }
        val started = SystemClock.elapsedRealtime()
        val ok = runNative(null) { Qwen3TtsNative.prepareVoice(handle, source.absolutePath, promptText, prompt.absolutePath) }
        val log = Qwen3TtsNative.takeLog(handle)
        if (!ok) throw QwenTtsException("Voice preparation failed: ${Qwen3TtsNative.lastError(handle)}", log)
        keyFile.writeText(key)
        preparedKey = key
        promptFile = prompt
        PrepInfo(SystemClock.elapsedRealtime() - started, false, fullSeconds, usedSeconds, log)
    }

    /** The first [fraction] of [text] by length, ended at the nearest word boundary at or before that point. */
    internal fun trimTextToFraction(text: String, fraction: Double): String {
        val trimmed = text.trim()
        if (fraction >= 1.0) return trimmed
        val target = (trimmed.length * fraction).toInt().coerceIn(1, trimmed.length)
        if (target >= trimmed.length) return trimmed
        val boundary = trimmed.lastIndexOf(' ', target)
        val end = if (boundary > 0) boundary else target
        return trimmed.substring(0, end).trim().trimEnd(',', ';', ':', '-', '—')
    }

    /** Forgets the prepared voice (memory and disk), so the next run analyses the reference again. */
    suspend fun clearVoiceCache(context: Context) = lock.withLock {
        preparedKey = null
        promptFile = null
        File(context.applicationContext.filesDir, "qwen3tts").listFiles { f -> f.name.startsWith("voice_prompt") }?.forEach { it.delete() }
    }

    suspend fun synthesize(text: String, languageId: Int, output: File): SynthInfo = lock.withLock {
        check(handle != 0L) { "model not loaded" }
        val prompt = promptFile ?: throw QwenTtsException("Voice not prepared")
        val h = handle
        val started = SystemClock.elapsedRealtime()
        val status = runNative(h) {
            Qwen3TtsNative.setStreaming(streamingChunkMs, streamingLeftMs)
            Qwen3TtsNative.synthesize(h, prompt.absolutePath, text, languageId, maxFramesFor(text), output.absolutePath)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        val log = Qwen3TtsNative.takeLog(h)
        when (status) {
            Qwen3TtsNative.STATUS_OK -> SynthInfo(elapsed, Qwen3TtsNative.firstChunkMs(h).takeIf { it >= 0 }, log)
            Qwen3TtsNative.STATUS_CANCELLED -> throw CancellationException("Qwen3-TTS generation cancelled")
            else -> throw QwenTtsException("Generation failed: ${Qwen3TtsNative.lastError(h)}", log)
        }
    }

    /** Frees the model and voice prompt; safe to call at any time, including after a cancel. */
    suspend fun release() = lock.withLock {
        if (handle != 0L) {
            val h = handle
            handle = 0L
            preparedKey = null
            runNative(null) { Qwen3TtsNative.destroy(h) }
        }
    }

    /** What the native call in progress has printed so far (null if no model is loaded). */
    fun liveLog(): String? {
        val h = handle
        return if (h == 0L) null else Qwen3TtsNative.peekLive(h)
    }

    /** Audio milliseconds delivered so far and elapsed milliseconds of the generation in progress (null if idle). */
    fun generationProgress(): Pair<Long, Long>? {
        val h = handle
        if (h == 0L) return null
        val p = Qwen3TtsNative.progress(h)
        return if (p.size == 3 && p[2] > 0) p[1] to p[2] else null
    }

    /**
     * Asks a generation in progress to stop between audio chunks. Safe from any
     * thread; does nothing if no model is loaded. The model stays loaded.
     */
    fun requestCancel() {
        val h = handle
        if (h != 0L) Qwen3TtsNative.cancel(h)
    }

    /** For callers with no coroutine of their own (an Activity being destroyed). */
    fun releaseAsync() {
        scope.launch { release() }
    }

    private const val MAX_AUDIO_TOKENS = 1024

    // Speech is ~12.5 codec frames a second; even slow speech is under ~2.5 frames per character. A model that
    // never emits its end token (seen on a Pixel: 25 characters, an hour and still generating) is cut off
    // at a length the text can plausibly need instead of at 1024 frames (82 s of audio).
    private fun maxFramesFor(text: String): Int = (text.length * 3 + 24).coerceIn(48, MAX_AUDIO_TOKENS)

    private suspend fun <T> runNative(cancelHandle: Long?, block: () -> T): T = coroutineScope {
        val call = async(Dispatchers.IO + NonCancellable) { block() }
        try {
            call.await()
        } catch (e: CancellationException) {
            if (cancelHandle != null && cancelHandle != 0L) Qwen3TtsNative.cancel(cancelHandle)
            // The native call cannot be interrupted: wait for it to return so
            // nothing keeps running in native code after Cancel.
            withContext(NonCancellable) { runCatching { call.await() } }
            throw e
        }
    }

    private fun availMb(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun pssMb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024L
    }
}
