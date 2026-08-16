package ai.localstudio.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.File

/**
 * Wraps MediaPipe Tasks Vision's [ObjectDetector] for a live camera feed.
 *
 * Ported from VirtualClone's `feature/mediapipe-integration` branch — the one
 * feature in that branch that was actually wired end to end (everything else
 * there, "Ask Image", "Audio Scribe" and so on, was a UI shell with a `TODO`
 * where the model call should be). Adapted to load the model from a
 * downloaded file instead of a bundled asset, since this app fetches models
 * on demand rather than shipping them in the APK.
 */
class ObjectDetectorHelper(private val context: Context) {

    fun interface DetectorListener {
        fun onResults(result: ObjectDetectorResult, imageWidth: Int, imageHeight: Int)
    }

    private var objectDetector: ObjectDetector? = null

    fun setup(modelFile: File, listener: DetectorListener): Result<Unit> = runCatching {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(modelFile.absolutePath)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setScoreThreshold(0.5f)
            .setMaxResults(5)
            .setResultListener { result, image -> listener.onResults(result, image.width, image.height) }
            .setErrorListener { }
            .build()

        objectDetector = ObjectDetector.createFromOptions(context, options)
    }

    /** Consumes and closes [imageProxy]; results arrive asynchronously via the listener passed to [setup]. */
    fun detectLivestream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val detector = objectDetector
        if (detector == null) {
            imageProxy.close()
            return
        }

        val frameTimeMs = android.os.SystemClock.uptimeMillis()
        val bitmap = imageProxy.toBitmap()
        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) postScale(-1f, 1f, bitmap.width.toFloat(), bitmap.height.toFloat())
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        imageProxy.close()

        val mpImage = BitmapImageBuilder(rotated).build()
        detector.detectAsync(mpImage, frameTimeMs)
    }

    fun close() {
        objectDetector?.close()
        objectDetector = null
    }
}
