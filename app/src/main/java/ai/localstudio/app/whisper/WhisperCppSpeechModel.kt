package ai.localstudio.app.whisper

import ai.localstudio.core.audio.AudioSource
import ai.localstudio.core.audio.PcmBuffer
import ai.localstudio.core.audio.UtteranceAccumulator
import ai.localstudio.core.audio.UtteranceConfig
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.StreamingSpeechSession
import ai.localstudio.whisper.WhisperBridge
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * whisper.cpp registered as a first-class [ModelRuntime] for
 * [RuntimeKind.WHISPER_CPP] — the model catalog already declares
 * `whisper_cpp` bindings (see `registry/catalog.example.json`), but until
 * this, nothing implemented the kind: [ai.localstudio.core.runtime.RuntimeManager.acquire]
 * would throw `"No runtime registered for whisper_cpp"` for any pipeline
 * that tried. See docs/13-asr-pipeline-migration.md.
 *
 * Mirrors [ai.localstudio.app.llama.LlamaCppRuntime]'s shape: the blocking
 * native load runs on a detached worker so cancelling the caller during a
 * slow load doesn't leave [load] itself unresponsive, only abandons the
 * worker (which frees what it produced once it finishes).
 */
class WhisperCppRuntime(
    /** Application context, held for the process lifetime — used only to resolve `content://` URIs (see [WhisperCppSpeechModel.audioSourceFor]). */
    private val context: Context,
    private val threads: Int = WhisperBridge.defaultThreads(),
    private val log: (tag: String, message: String) -> Unit = { _, _ -> },
) : ModelRuntime {

    override val kind: RuntimeKind = RuntimeKind.WHISPER_CPP

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.WHISPER_CPP && WhisperBridge.isAvailable && File(binding.artifact).isFile

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        if (!WhisperBridge.isAvailable) {
            throw ModelLoadException("The whisper.cpp library is not available for this device's ABI")
        }
        val file = File(binding.artifact)
        if (!file.isFile) {
            throw ModelLoadException("Model file is missing: ${binding.artifact}")
        }

        val bridge = WhisperBridge()
        val loadStart = System.currentTimeMillis()
        log("WHISPER_LOAD", "${file.name}: starting")

        var producedHandle = 0L
        val result = CompletableDeferred<Unit>()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            WhisperBridge.nativeOpMutex.withLock {
                producedHandle = runCatching { bridge.nativeLoad(file.absolutePath) }.getOrDefault(0L)
            }
            result.complete(Unit)
        }
        val handle = try {
            result.await()
            producedHandle
        } catch (e: CancellationException) {
            log("WHISPER_LOAD", "${file.name}: abandoned after ${System.currentTimeMillis() - loadStart}ms, still loading in the background")
            worker.invokeOnCompletion {
                if (producedHandle != 0L) bridge.nativeFree(producedHandle)
            }
            throw e
        }
        val loadMs = System.currentTimeMillis() - loadStart
        if (handle == 0L) {
            log("WHISPER_LOAD", "${file.name}: FAILED after ${loadMs}ms")
            throw ModelLoadException("whisper.cpp could not load ${file.name}")
        }
        log("WHISPER_LOAD", "${file.name}: ready in ${loadMs}ms")

        return WhisperCppSpeechModel(model.id, binding.effectiveRequiredRamBytes, bridge, handle, threads, context, log)
    }
}

/** Whisper's own analysis window (and what it pads shorter input up to), in samples at [SAMPLE_RATE]. */
private const val WINDOW_SECONDS = 30
private const val SAMPLE_RATE = 16_000
private const val WINDOW_SAMPLES = WINDOW_SECONDS * SAMPLE_RATE

/**
 * Raw decoder output ahead of inference, in [MediaCodecAudioSource]'s
 * ~1-second chunks — bounded so an hour-long file's decoder cannot race
 * arbitrarily far ahead of a slower inference pass; [AudioSource.stream]'s
 * producer suspends on [Channel.send] once this fills, which is the actual
 * backpressure mechanism between decode and inference.
 */
private const val DECODE_QUEUE_CAPACITY = 8

internal class WhisperCppSpeechModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val bridge: WhisperBridge,
    private val handle: Long,
    private val threads: Int,
    private val context: Context,
    private val log: (tag: String, message: String) -> Unit,
) : SpeechModelHandle {

    /**
     * Set by [requestCancel], checked between windows/utterances. The native
     * abort flag ([WhisperBridge.nativeCancel]) only interrupts whatever
     * `whisper_full` call is running *right now* — and is reset by the native
     * side at the start of the next call (see whisper_jni.cpp), so a batch
     * transcription with several windows left would otherwise resume on the
     * very next one. This flag is what actually stops the loop.
     */
    private val cancelRequested = AtomicBoolean(false)

    override suspend fun transcribe(audio: AudioRef, language: String?): Transcript =
        transcribeInternal(audio, language) {}

    /**
     * Same as [transcribe], but also invokes [onSegment] as soon as
     * whisper.cpp finalizes each segment — before the rest of the file has
     * even finished decoding, let alone the whole transcription. Not part of
     * [SpeechModelHandle] itself: no other engine can promise this exact
     * timing, and an interface method would commit every implementation to
     * it. Exists mainly so the vertical-slice acceptance test (see
     * docs/13-asr-pipeline-migration.md) has something concrete to observe.
     */
    suspend fun transcribeStreaming(
        audio: AudioRef,
        language: String? = null,
        onSegment: (TranscriptSegment) -> Unit,
    ): Transcript = transcribeInternal(audio, language, onSegment)

    private suspend fun transcribeInternal(
        audio: AudioRef,
        language: String?,
        onSegment: (TranscriptSegment) -> Unit,
    ): Transcript {
        cancelRequested.set(false)
        val lang = language ?: "auto"
        val source = audioSourceFor(audio)
        val channel = Channel<ShortArray>(DECODE_QUEUE_CAPACITY)

        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val producer = producerScope.launch {
            // A plain `launch` under a SupervisorJob still crashes the
            // process on an uncaught exception — SupervisorJob only stops it
            // from cancelling siblings, it doesn't swallow the exception
            // itself. source.stream() throws routinely for anything
            // MediaCodecAudioSource couldn't decode (no audio track,
            // unsupported format, a genuinely corrupt file) — every one of
            // those would otherwise crash the whole app instead of just
            // failing this transcription. channel.close(cause) is what
            // turns that into an ordinary exception the consumer's
            // `for (chunk in channel)` loop below throws, exactly as if
            // source.stream() had been called in-line without a channel
            // between them at all.
            try {
                source.stream { chunk -> channel.send(chunk) }
                channel.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.close(e)
            }
        }

        try {
            // Explicit, not inherited from the caller: a caller on
            // Dispatchers.Main (a UI screen driving this straight off a
            // button click, e.g. TranscribeActivity) must not have whisper's
            // blocking native calls run in-line on the main thread just
            // because nothing here forced them elsewhere.
            return withContext(Dispatchers.Default) {
                val segments = mutableListOf<TranscriptSegment>()
                val window = PcmBuffer()
                var offsetSamples = 0L

                suspend fun runWindow(samples: ShortArray) {
                    if (samples.isEmpty()) return
                    val floats = FloatArray(samples.size) { samples[it] / 32768f }
                    val offsetMs = offsetSamples * 1000 / SAMPLE_RATE
                    val sink = WhisperBridge.SegmentSink { text, startMs, endMs ->
                        if (text.isNotBlank()) {
                            val segment = TranscriptSegment(text = text, startMs = offsetMs + startMs, endMs = offsetMs + endMs)
                            segments += segment
                            onSegment(segment)
                        }
                    }
                    WhisperBridge.nativeOpMutex.withLock {
                        bridge.nativeTranscribe(handle, floats, threads, lang, sink)
                    }
                    offsetSamples += samples.size
                }

                for (chunk in channel) {
                    if (cancelRequested.get()) break
                    window.append(chunk)
                    while (window.size >= WINDOW_SAMPLES) {
                        runWindow(window.take(WINDOW_SAMPLES))
                        if (cancelRequested.get()) break
                    }
                }
                if (!cancelRequested.get()) runWindow(window.takeAll())

                Transcript(
                    text = segments.joinToString(" ") { it.text }.trim(),
                    language = lang.takeIf { it != "auto" },
                    segments = segments,
                )
            }
        } finally {
            producer.cancel()
        }
    }

    /**
     * A sliding-window shim, not true incremental streaming: the whole
     * in-progress utterance is re-transcribed on every refresh, same policy
     * as the mic path docs/12-audio.md documents (and the same reasoning —
     * it's what lets the model revise earlier words once it hears the end of
     * a sentence). Accepted for phase 1 per docs/13-asr-pipeline-migration.md;
     * a real incremental engine would replace this method's body without
     * [SpeechModelHandle] or any caller changing.
     */
    override fun startStreaming(language: String?): StreamingSpeechSession {
        val lang = language ?: "auto"
        val config = UtteranceConfig(sampleRate = SAMPLE_RATE)
        val accumulator = UtteranceAccumulator(config)
        val lock = Any()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val channel = Channel<TranscriptSegment>(Channel.UNLIMITED)
        val closed = AtomicBoolean(false)
        var utteranceStartMs = 0L

        suspend fun infer(samples: FloatArray): String {
            if (samples.isEmpty()) return ""
            return WhisperBridge.nativeOpMutex.withLock { bridge.nativeTranscribe(handle, samples, threads, lang, null) }
        }

        fun emit(text: String, sampleCount: Int) {
            if (text.isBlank()) return
            val endMs = utteranceStartMs + (sampleCount * 1000L / SAMPLE_RATE)
            channel.trySend(TranscriptSegment(text = text, startMs = utteranceStartMs, endMs = endMs))
        }

        suspend fun finalizeUtterance() {
            val finished = synchronized(lock) { accumulator.takeUtterance() }
            if (finished.isNotEmpty()) emit(infer(finished), finished.size)
            utteranceStartMs += finished.size * 1000L / SAMPLE_RATE
        }

        val refreshJob = scope.launch {
            while (isActive) {
                delay(config.refreshMs)
                val snapshot = synchronized(lock) {
                    if (accumulator.hasEnoughToTranscribe) accumulator.snapshot() else null
                } ?: continue
                emit(infer(snapshot), snapshot.size)
            }
        }

        return object : StreamingSpeechSession {
            override val segments: Flow<TranscriptSegment> = channel.receiveAsFlow()

            override fun acceptAudio(pcm: ShortArray) {
                val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
                val shouldFinalize = synchronized(lock) {
                    accumulator.append(floats)
                    accumulator.shouldFinalize
                }
                if (shouldFinalize) scope.launch { finalizeUtterance() }
            }

            override fun finish() {
                if (!closed.compareAndSet(false, true)) return
                scope.launch {
                    refreshJob.cancel()
                    finalizeUtterance()
                    channel.close()
                }
            }

            override fun cancel() {
                if (!closed.compareAndSet(false, true)) return
                bridge.nativeCancel(handle)
                channel.close()
                scope.cancel()
            }
        }
    }

    /**
     * Interrupts the current [transcribe]/[transcribeStreaming] call: flips
     * the native abort flag (stops whatever window is decoding right now)
     * and [cancelRequested] (stops the next one from starting). Does not
     * reach into a session started via [startStreaming] — that has its own
     * [StreamingSpeechSession.cancel].
     */
    override fun requestCancel() {
        cancelRequested.set(true)
        bridge.nativeCancel(handle)
    }

    /**
     * Safe with respect to [transcribe]/[transcribeStreaming]: those run the
     * native call synchronously within the awaited suspend chain, so by the
     * time [ai.localstudio.core.runtime.RuntimeManager.release] can bring
     * this model's refCount to 0 (i.e., every `withModel { }` block that
     * called them has already returned), no native call from this instance
     * is still in flight.
     *
     * **Not** safe with respect to a session from [startStreaming]: it
     * returns immediately and keeps making native calls from a background
     * coroutine for as long as the caller keeps feeding it audio — well
     * after whatever `withModel { }` block *created* the session has
     * returned and released its refCount. [RuntimeManager] has no concept of
     * a long-lived handle outliving the call that acquired it, so a live mic
     * session must not be wired up against an evictable model until that's
     * addressed (acquire a ref-count-holding handle for the session's own
     * lifetime, release it from [StreamingSpeechSession.finish]/[cancel]) —
     * tracked as a Phase 3 TODO in docs/13-asr-pipeline-migration.md. Phase
     * 1's transcribe()-only vertical slice never exercises this path.
     */
    override fun close() {
        bridge.nativeFree(handle)
    }

    private fun audioSourceFor(audio: AudioRef): AudioSource {
        val parsed = runCatching { URI.create(audio.uri) }.getOrNull()
        return when (parsed?.scheme) {
            // A SAF/file-picker URI (TranscribeActivity) — needs a Context to
            // resolve through ContentResolver, unlike a plain file path or URL.
            "content" -> MediaCodecAudioSource.forUri(context, Uri.parse(audio.uri))
            // Same convention as OpenAiRuntime.RemoteSpeechModel.audioFile():
            // a file:// URI is unwrapped to a bare path.
            "file" -> MediaCodecAudioSource.forPathOrUrl(parsed.path ?: audio.uri)
            // A plain path, or an http(s) URL MediaExtractor can fetch progressively.
            else -> MediaCodecAudioSource.forPathOrUrl(audio.uri)
        }
    }
}
