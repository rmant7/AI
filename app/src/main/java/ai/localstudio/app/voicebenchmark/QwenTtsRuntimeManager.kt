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

class QwenTtsException(message: String) : Exception(message)

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
    )

    class PrepInfo(val prepMs: Long, val cached: Boolean)

    class SynthInfo(val generationMs: Long, val firstAudioMs: Long?)

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handle = 0L
    private var preparedKey: String? = null
    private var promptFile: File? = null

    /** Free RAM needed before loading: ~0.9 GB of weights plus graph buffers and the vocoder. */
    private const val MIN_AVAILABLE_MB = 1400L

    suspend fun ensureLoaded(context: Context, provider: QwenTtsModelProvider): LoadInfo = lock.withLock {
        val app = context.applicationContext
        if (handle != 0L) return@withLock LoadInfo(0, true, availMb(app), pssMb(), pssMb())
        if (!Qwen3TtsNative.isLoaded) throw QwenTtsException("Native Qwen3-TTS library could not be loaded")

        val avail = availMb(app)
        if (avail < MIN_AVAILABLE_MB) {
            throw QwenTtsException("Not enough free RAM to load Qwen3-TTS: need about $MIN_AVAILABLE_MB MB, $avail MB available")
        }
        val pssBefore = pssMb()
        val started = SystemClock.elapsedRealtime()
        val newHandle = runNative(null) { Qwen3TtsNative.create(LlamaBridge.defaultThreads()) }
        if (newHandle == 0L) throw QwenTtsException("Could not create the Qwen3-TTS context")
        val ok = runNative(null) {
            Qwen3TtsNative.loadModels(newHandle, provider.modelDir.absolutePath, QwenTtsModelDescriptor.TALKER_FILE)
        }
        if (!ok) {
            val error = Qwen3TtsNative.lastError(newHandle)
            Qwen3TtsNative.destroy(newHandle)
            throw QwenTtsException("Model load failed: $error")
        }
        handle = newHandle
        LoadInfo(SystemClock.elapsedRealtime() - started, false, avail, pssBefore, pssMb())
    }

    /** Prepares (or reuses) the voice prompt for this exact reference recording + transcript. */
    suspend fun prepareVoice(context: Context, referenceWav: File, referenceText: String): PrepInfo = lock.withLock {
        check(handle != 0L) { "model not loaded" }
        val key = "${referenceWav.absolutePath}:${referenceWav.length()}:${referenceWav.lastModified()}:${referenceText.hashCode()}"
        val prompt = File(File(context.applicationContext.filesDir, "qwen3tts").also { it.mkdirs() }, "voice_prompt.json")
        if (key == preparedKey && prompt.exists()) return@withLock PrepInfo(0, true)

        val started = SystemClock.elapsedRealtime()
        val ok = runNative(null) { Qwen3TtsNative.prepareVoice(handle, referenceWav.absolutePath, referenceText, prompt.absolutePath) }
        if (!ok) throw QwenTtsException("Voice preparation failed: ${Qwen3TtsNative.lastError(handle)}")
        preparedKey = key
        promptFile = prompt
        PrepInfo(SystemClock.elapsedRealtime() - started, false)
    }

    suspend fun synthesize(text: String, languageId: Int, output: File): SynthInfo = lock.withLock {
        check(handle != 0L) { "model not loaded" }
        val prompt = promptFile ?: throw QwenTtsException("Voice not prepared")
        val h = handle
        val started = SystemClock.elapsedRealtime()
        val status = runNative(h) {
            Qwen3TtsNative.synthesize(h, prompt.absolutePath, text, languageId, MAX_AUDIO_TOKENS, output.absolutePath)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        when (status) {
            Qwen3TtsNative.STATUS_OK -> SynthInfo(elapsed, Qwen3TtsNative.firstChunkMs(h).takeIf { it >= 0 })
            Qwen3TtsNative.STATUS_CANCELLED -> throw CancellationException("Qwen3-TTS generation cancelled")
            else -> throw QwenTtsException("Generation failed: ${Qwen3TtsNative.lastError(h)}")
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

    /** For callers with no coroutine of their own (an Activity being destroyed). */
    fun releaseAsync() {
        scope.launch { release() }
    }

    private const val MAX_AUDIO_TOKENS = 1024

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
