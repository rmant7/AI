package ai.localstudio.app.voicebenchmark

import ai.localstudio.app.models.DownloadProgress
import ai.localstudio.app.models.ModelDownloader
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Where the Qwen3-TTS 0.6B Base GGUFs come from and what they should look
 * like. Both files are the Q4_K_M quantizations that the reference Android
 * project (Danmoreng/qwen3-tts-android) downloads from the
 * Serveurperso/Qwen3-TTS-GGUF repository; the byte sizes are the ones that
 * project pins. They were not re-verified against Hugging Face from the build
 * environment, so [QwenTtsModelProvider] only requires a GGUF header and a
 * size within [SIZE_TOLERANCE] of them, instead of an exact match that would
 * throw away a completed 880 MB download over a re-upload.
 *
 * Never bundled in the APK: downloaded on request into app-private storage.
 */
object QwenTtsModelDescriptor {

    data class ModelFile(val name: String, val url: String, val expectedBytes: Long)

    private const val BASE_URL = "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main"

    const val DISPLAY_NAME = "Qwen3-TTS 0.6B Base (Q4_K_M)"
    const val TALKER_FILE = "qwen-talker-0.6b-base-Q4_K_M.gguf"
    private const val TOKENIZER_FILE = "qwen-tokenizer-12hz-Q4_K_M.gguf"
    const val SIZE_TOLERANCE = 0.05

    val files: List<ModelFile> = listOf(
        ModelFile(TOKENIZER_FILE, "$BASE_URL/$TOKENIZER_FILE?download=true", 254_974_752L),
        ModelFile(TALKER_FILE, "$BASE_URL/$TALKER_FILE?download=true", 628_905_056L),
    )

    val totalExpectedBytes: Long get() = files.sumOf { it.expectedBytes }
}

sealed interface QwenModelState {
    data object NotDownloaded : QwenModelState
    data class Downloading(val fraction: Float) : QwenModelState
    data object Ready : QwenModelState
    data class Failed(val message: String) : QwenModelState
}

/**
 * Downloads and tracks the Qwen3-TTS model files. Reuses the app's own
 * [ModelDownloader] (redirects, resume, backoff) rather than a second download
 * system; the download runs in an application-scoped coroutine so leaving the
 * screen does not stop it.
 */
class QwenTtsModelProvider private constructor(context: Context) {

    val modelDir: File = File(context.applicationContext.filesDir, "qwen3tts/models").also { it.mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var downloader: ModelDownloader? = null

    private val _state = MutableStateFlow<QwenModelState>(if (isReady()) QwenModelState.Ready else QwenModelState.NotDownloaded)
    val state: StateFlow<QwenModelState> = _state

    fun isReady(): Boolean = QwenTtsModelDescriptor.files.all { isValid(File(modelDir, it.name), it.expectedBytes) }

    fun download() {
        if (job?.isActive == true || isReady()) {
            if (isReady()) _state.value = QwenModelState.Ready
            return
        }
        _state.value = QwenModelState.Downloading(0f)
        job = scope.launch {
            val files = QwenTtsModelDescriptor.files
            val total = QwenTtsModelDescriptor.totalExpectedBytes.toDouble()
            var doneBefore = 0L
            try {
                for (file in files) {
                    val target = File(modelDir, file.name)
                    if (isValid(target, file.expectedBytes)) {
                        doneBefore += target.length()
                        continue
                    }
                    val dl = ModelDownloader().also { downloader = it }
                    val base = doneBefore
                    dl.download(file.url, target, File(modelDir, file.name + ".part")) { p: DownloadProgress ->
                        _state.value = QwenModelState.Downloading(((base + p.bytesDownloaded) / total).toFloat().coerceIn(0f, 1f))
                    }
                    if (!isValid(target, file.expectedBytes)) {
                        target.delete()
                        throw IOException("${file.name} is not a valid GGUF of the expected size (got ${target.length()} bytes)")
                    }
                    doneBefore += target.length()
                }
                _state.value = QwenModelState.Ready
            } catch (e: Exception) {
                _state.value = if (e.message == "Download cancelled") QwenModelState.NotDownloaded
                else QwenModelState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                downloader = null
            }
        }
    }

    fun cancelDownload() {
        downloader?.cancel()
    }

    /** Deletes the model files (and any partial download), freeing about 900 MB. */
    fun delete() {
        cancelDownload()
        modelDir.listFiles()?.forEach { it.delete() }
        _state.value = QwenModelState.NotDownloaded
    }

    private fun isValid(file: File, expectedBytes: Long): Boolean {
        if (!file.isFile) return false
        val low = (expectedBytes * (1 - QwenTtsModelDescriptor.SIZE_TOLERANCE)).toLong()
        val high = (expectedBytes * (1 + QwenTtsModelDescriptor.SIZE_TOLERANCE)).toLong()
        if (file.length() !in low..high) return false
        return runCatching { file.inputStream().use { s -> ByteArray(4).also { s.read(it) }.decodeToString() == "GGUF" } }
            .getOrDefault(false)
    }

    companion object {
        @Volatile
        private var instance: QwenTtsModelProvider? = null

        fun get(context: Context): QwenTtsModelProvider =
            instance ?: synchronized(this) { instance ?: QwenTtsModelProvider(context).also { instance = it } }
    }
}
