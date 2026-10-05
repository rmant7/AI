package ai.localstudio.app.vision

import ai.localstudio.app.modelinstall.ProbeImage
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Draws a [ProbeImage] as a PNG: square, white background, the subject
 * large and centred -- nothing a vision encoder at any input resolution
 * could plausibly misread, so a wrong answer is the model's, not the
 * image's.
 */
object ProbeImageRenderer {
    const val SIZE_PX = 448

    fun png(image: ProbeImage): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val centre = SIZE_PX / 2f
            when (image) {
                is ProbeImage.Digit -> {
                    val text = image.digit.toString()
                    paint.color = Color.BLACK
                    paint.typeface = Typeface.DEFAULT_BOLD
                    paint.textSize = SIZE_PX * 0.75f
                    paint.textAlign = Paint.Align.CENTER
                    val bounds = Rect()
                    paint.getTextBounds(text, 0, text.length, bounds)
                    canvas.drawText(text, centre, centre - bounds.exactCenterY(), paint)
                }
                is ProbeImage.Disc -> {
                    paint.color = Color.BLACK or image.rgb
                    canvas.drawCircle(centre, centre, SIZE_PX * 0.35f, paint)
                }
            }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** The form [ai.localstudio.core.model.ImageRef.uri] takes for an image handed to a local model. */
    fun dataUri(image: ProbeImage): String = "data:image/png;base64," + Base64.encodeToString(png(image), Base64.NO_WRAP)
}
