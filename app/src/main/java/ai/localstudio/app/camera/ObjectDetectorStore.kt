package ai.localstudio.app.camera

import ai.localstudio.app.models.ModelDownloader
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one MediaPipe Tasks Vision model this screen needs. Downloaded on
 * demand — like every other model in this app — rather than bundled into
 * the APK, so the base install stays small.
 */
object ObjectDetectorModel {
    const val URL = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite"
    const val APPROX_SIZE_BYTES = 5_600_000L
}

class ObjectDetectorStore(context: Context) {

    private val directory = File(context.filesDir, "vision").apply { mkdirs() }
    val modelFile: File = File(directory, "efficientdet_lite0.tflite")
    val partFile: File = File(directory, "efficientdet_lite0.tflite.part")

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() > MIN_PLAUSIBLE_SIZE

    fun delete() {
        modelFile.delete()
        partFile.delete()
    }

    suspend fun download(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        ModelDownloader().download(ObjectDetectorModel.URL, modelFile, partFile) { progress ->
            onProgress(progress.bytesDownloaded, progress.bytesTotal)
        }
    }

    private companion object {
        const val MIN_PLAUSIBLE_SIZE = 1L * 1024 * 1024
    }
}
