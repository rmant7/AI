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

    /** [minBytes]/[maxBytes] override the ±[SIZE_TOLERANCE] check for a file whose exact size is not pinned. */
    data class ModelFile(
        val name: String,
        val url: String,
        val expectedBytes: Long,
        val minBytes: Long? = null,
        val maxBytes: Long? = null,
        /** Checked once after download when set (lower-case hex). */
        val sha256: String? = null,
    )

    /**
     * Which quantization of the talker (which also holds the Code Predictor) to run. Q4_K_M is the
     * default download; Q8_0 is qwen3-tts.cpp's own main, validated path. The Code Predictor re-reads
     * its weights 15 times per audio frame and ARM dot-product kernels for Q8_0 are simpler than for
     * Q4_K, so Q8_0 may well be faster on a phone despite being bigger — which is what this option
     * is for measuring.
     */
    enum class TalkerVariant(val key: String, val label: String, val file: ModelFile) {
        Q4_K_M(
            "q4_k_m", "Q4_K_M",
            ModelFile(TALKER_FILE, "$BASE_URL/$TALKER_FILE?download=true", 628_905_056L),
        ),
        Q8_0(
            "q8_0", "Q8_0",
            // Serveurperso's Q8_0 talker: ~993 MB (MB or MiB not stated, so the range covers both), and the SHA-256
            // reported for that file. Not verifiable from the build environment, hence the hash check on the phone.
            ModelFile(
                TALKER_Q8_FILE, "$BASE_URL/$TALKER_Q8_FILE?download=true", 993_000_000L, 940_000_000L, 1_100_000_000L,
                "d54dbaf10591421fa764ed630d764efa717ae40cd959bd48c66d4eb1af226426",
            ),
        );

        companion object {
            fun fromKey(key: String?): TalkerVariant = entries.firstOrNull { it.key == key } ?: Q4_K_M
        }
    }

    private const val BASE_URL = "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main"

    const val DISPLAY_NAME = "Qwen3-TTS 0.6B Base (Q4_K_M)"
    const val TALKER_FILE = "qwen-talker-0.6b-base-Q4_K_M.gguf"
    const val TALKER_Q8_FILE = "qwen-talker-0.6b-base-Q8_0.gguf"
    private const val TOKENIZER_FILE = "qwen-tokenizer-12hz-Q4_K_M.gguf"
    const val SIZE_TOLERANCE = 0.05

    val files: List<ModelFile> = listOf(
        ModelFile(TOKENIZER_FILE, "$BASE_URL/$TOKENIZER_FILE?download=true", 254_974_752L),
        TalkerVariant.Q4_K_M.file,
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

    fun isReady(): Boolean = QwenTtsModelDescriptor.files.all { isValid(File(modelDir, it.name), it) }

    private val _q8State = MutableStateFlow<QwenModelState>(
        if (isVariantReady(QwenTtsModelDescriptor.TalkerVariant.Q8_0)) QwenModelState.Ready else QwenModelState.NotDownloaded,
    )

    /** Download state of the optional Q8_0 talker. */
    val q8State: StateFlow<QwenModelState> = _q8State
    private var q8Job: Job? = null
    private var q8Downloader: ModelDownloader? = null

    /** The base files plus this variant's talker are on disk. */
    fun isVariantReady(variant: QwenTtsModelDescriptor.TalkerVariant): Boolean = isValid(File(modelDir, variant.file.name), variant.file)

    /** Downloads the optional Q8_0 talker (the tokenizer comes with the base download). */
    fun downloadQ8() {
        val file = QwenTtsModelDescriptor.TalkerVariant.Q8_0.file
        if (q8Job?.isActive == true) return
        if (isValid(File(modelDir, file.name), file)) {
            _q8State.value = QwenModelState.Ready
            return
        }
        _q8State.value = QwenModelState.Downloading(0f)
        q8Job = scope.launch {
            val target = File(modelDir, file.name)
            try {
                val dl = ModelDownloader().also { q8Downloader = it }
                dl.download(file.url, target, File(modelDir, file.name + ".part")) { p: DownloadProgress ->
                    val fraction = if (p.bytesTotal > 0) p.fraction else p.bytesDownloaded.toFloat() / file.expectedBytes
                    _q8State.value = QwenModelState.Downloading(fraction.coerceIn(0f, 1f))
                }
                if (!isValid(target, file)) {
                    val got = target.length()
                    target.delete()
                    throw IOException("${file.name} is not a valid GGUF of the expected size (got $got bytes)")
                }
                file.sha256?.let { expected ->
                    val actual = sha256Of(target)
                    if (actual != expected) {
                        target.delete()
                        throw IOException("${file.name}: SHA-256 mismatch (got $actual)")
                    }
                }
                _q8State.value = QwenModelState.Ready
            } catch (e: Exception) {
                _q8State.value = if (e.message == "Download cancelled") QwenModelState.NotDownloaded
                else QwenModelState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                q8Downloader = null
            }
        }
    }

    fun cancelQ8Download() {
        q8Downloader?.cancel()
    }

    fun deleteQ8() {
        cancelQ8Download()
        val name = QwenTtsModelDescriptor.TalkerVariant.Q8_0.file.name
        File(modelDir, name).delete()
        File(modelDir, "$name.part").delete()
        _q8State.value = QwenModelState.NotDownloaded
    }

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
                    if (isValid(target, file)) {
                        doneBefore += target.length()
                        continue
                    }
                    val dl = ModelDownloader().also { downloader = it }
                    val base = doneBefore
                    dl.download(file.url, target, File(modelDir, file.name + ".part")) { p: DownloadProgress ->
                        _state.value = QwenModelState.Downloading(((base + p.bytesDownloaded) / total).toFloat().coerceIn(0f, 1f))
                    }
                    if (!isValid(target, file)) {
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
        cancelQ8Download()
        modelDir.listFiles()?.forEach { it.delete() }
        _state.value = QwenModelState.NotDownloaded
        _q8State.value = QwenModelState.NotDownloaded
    }

    private fun isValid(file: File, spec: QwenTtsModelDescriptor.ModelFile): Boolean {
        if (!file.isFile) return false
        val low = spec.minBytes ?: (spec.expectedBytes * (1 - QwenTtsModelDescriptor.SIZE_TOLERANCE)).toLong()
        val high = spec.maxBytes ?: (spec.expectedBytes * (1 + QwenTtsModelDescriptor.SIZE_TOLERANCE)).toLong()
        if (file.length() !in low..high) return false
        return runCatching { file.inputStream().use { s -> ByteArray(4).also { s.read(it) }.decodeToString() == "GGUF" } }
            .getOrDefault(false)
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile
        private var instance: QwenTtsModelProvider? = null

        fun get(context: Context): QwenTtsModelProvider =
            instance ?: synchronized(this) { instance ?: QwenTtsModelProvider(context).also { instance = it } }
    }
}
