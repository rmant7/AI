package ai.localstudio.app.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max

/** Draws bounding boxes over the camera preview. Ported from VirtualClone's Compose `OverlayView`. */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var result: ObjectDetectorResult? = null
    private var imageWidth = 1
    private var imageHeight = 1

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 180
    }

    fun update(result: ObjectDetectorResult, imageWidth: Int, imageHeight: Int) {
        this.result = result
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)
        postInvalidate()
    }

    fun clear() {
        result = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val detections = result?.detections() ?: return
        val scaleFactor = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)

        for (detection in detections) {
            val box = detection.boundingBox()
            val rect = RectF(
                box.left * scaleFactor,
                box.top * scaleFactor,
                box.right * scaleFactor,
                box.bottom * scaleFactor,
            )
            canvas.drawRect(rect, boxPaint)

            val category = detection.categories().firstOrNull() ?: continue
            val label = "${category.categoryName()} ${(category.score() * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            canvas.drawRect(rect.left, rect.top - 50f, rect.left + textWidth + 16f, rect.top, textBackgroundPaint)
            canvas.drawText(label, rect.left + 8f, rect.top - 12f, textPaint)
        }
    }
}
