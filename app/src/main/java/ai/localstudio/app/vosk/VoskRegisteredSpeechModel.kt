package ai.localstudio.app.vosk

import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.speech.AsrEngineType
import ai.localstudio.core.speech.Language
import ai.localstudio.core.speech.RegisteredSpeechModel
import ai.localstudio.core.speech.SpeechModelCapabilities
import ai.localstudio.core.speech.SpeechModelInfo
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model

/**
 * [RegisteredSpeechModel] for one installed [VoskModelSeed] — lazily loads
 * the [org.vosk.Model] on first [handle] call and reuses it afterward
 * (same reuse-don't-reload convention as
 * [ai.localstudio.app.whisper.WhisperCppMicSession.ensureLoaded]). If
 * [seed] isn't actually installed, [Model]'s own constructor throws
 * (`IOException`) — which the router already treats as "this specialist
 * is unavailable, try the next candidate" (see
 * [ai.localstudio.core.speech.DefaultStreamingSpeechRouter]'s own
 * fallback handling), so there is no separate "is it installed" check
 * needed here.
 */
class VoskRegisteredSpeechModel(
    private val context: Context,
    private val seed: VoskModelSeed,
    languages: Set<Language>,
    priority: Int = 100,
) : RegisteredSpeechModel {

    override val info = SpeechModelInfo(
        id = seed.id,
        displayName = seed.title,
        capabilities = SpeechModelCapabilities(
            languages = languages,
            supportsStreaming = true,
            supportsFileTranscription = false,
            supportsLanguageAutoDetection = false,
            supportsCodeSwitching = false,
            engine = AsrEngineType.VOSK,
        ),
        priority = priority,
    )

    @Volatile
    private var loaded: VoskSpeechModel? = null
    private val loadMutex = Mutex()

    override suspend fun handle(): SpeechModelHandle {
        loaded?.let { return it }
        return loadMutex.withLock {
            loaded?.let { return@withLock it }
            VoskNativeLibrary.ensureReady(context)
            withContext(Dispatchers.IO) {
                val dir = VoskModelStore.modelDir(context, seed)
                val model = Model(dir.absolutePath)
                val sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                VoskSpeechModel(seed.id, model, sizeBytes).also { loaded = it }
            }
        }
    }

    val isLoaded: Boolean get() = loaded != null

    /** Frees the loaded model under memory pressure — same convention as [ai.localstudio.app.whisper.WhisperRegisteredSpeechModel.release] (see its own doc comment on why this is non-suspend and unguarded by [loadMutex]). */
    fun release() {
        loaded?.close()
        loaded = null
    }
}
