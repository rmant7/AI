package ai.localstudio.app.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Layered 2D avatar: a clean head plus independent eye and mouth layers, all
 * full-size PNGs in the source photo's own pixel coordinates (see
 * `scripts/avatar/generate_layers.py`, which builds them from
 * `avatar_face.jpg`). Because every layer shares one coordinate system,
 * a single [Matrix] registers all of them — head motion is just that matrix
 * changing, never a per-layer offset, so eyes and mouth cannot drift off the
 * face and no pixel is ever resampled through a deformed mesh.
 *
 * Draw order: head, eyes (open, then the closed version faded in over it),
 * neutral mouth, then whichever viseme mouths currently have weight. Blinks
 * and mouth changes are alpha cross-fades between full-size layers, not
 * geometry changes.
 *
 * Owns two loops, both independent of whatever [AvatarSpeechController]
 * feeds it: a per-frame loop (smoothing, blink timer, idle micro-motion) and
 * the asynchronous layer decode. The frame loop starts in
 * [onAttachedToWindow] and stops in [onDetachedFromWindow] — this view must
 * never keep animating after it's off-screen.
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class Viseme(val asset: String) {
        A("a"), E("e"), I("i"), O("o"), U("u"), SMILE("smile"),
    }

    private class Layers(
        val head: Bitmap,
        val leftOpen: Bitmap,
        val leftClosed: Bitmap,
        val rightOpen: Bitmap,
        val rightClosed: Bitmap,
        val mouthNeutral: Bitmap,
        val mouths: Map<Viseme, Bitmap>,
    )

    // Decoded off the main thread (a dozen 640x640 PNGs); nothing is drawn
    // until it lands.
    @Volatile
    private var layers: Layers? = null
    private var loadStarted = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val pivot = FloatArray(2)
    private var drawnSizePx = 0f

    private var targetState = AvatarState()
    private var currentMouthOpen = 0f
    private val visemeWeights = FloatArray(Viseme.entries.size)

    // 0..1 = how far the closed-eye layer is faded in over the open one.
    private var leftClosed = 0f
    private var rightClosed = 0f

    private var nextBlinkAtMs = 0L
    private var blinkPhaseStartMs = 0L
    private var blinking = false

    private var gesture: AvatarGesture? = null
    private var gestureStartMs = 0L

    // Head motion, applied identically to every layer — see onDraw.
    private var headRotationDeg = 0f
    private var headScale = 1f
    private var headShiftX = 0f
    private var headShiftY = 0f

    private val frameCallback = object : Runnable {
        override fun run() {
            step()
            if (isAttachedToWindow) postOnAnimation(this)
        }
    }

    /** Called by [AvatarSpeechController] whenever the target expression changes — takes effect gradually, over the next few frames, not instantly. */
    fun updateState(state: AvatarState) {
        targetState = state
    }

    /**
     * One-shot, timed expression, independent of whatever [updateState] is
     * currently driving — see [ai.localstudio.app.AvatarTestActivity] for
     * where this is triggered from. Replaces any gesture already playing
     * rather than queuing, so mashing a button restarts it instead of piling
     * up.
     */
    fun playGesture(gesture: AvatarGesture) {
        this.gesture = gesture
        gestureStartMs = System.currentTimeMillis()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        nextBlinkAtMs = System.currentTimeMillis() + nextBlinkDelayMs()
        postOnAnimation(frameCallback)
        if (!loadStarted) {
            loadStarted = true
            loadLayersAsync()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBaseMatrix()
    }

    private fun loadLayersAsync() {
        val assets = context.applicationContext.assets
        Thread({
            fun load(path: String): Bitmap =
                checkNotNull(assets.open("$ASSET_DIR/$path").use { BitmapFactory.decodeStream(it) }) { "missing avatar layer $path" }
            val loaded = Layers(
                head = load("avatar_head.png"),
                leftOpen = load("eyes/left_open.png"),
                leftClosed = load("eyes/left_closed.png"),
                rightOpen = load("eyes/right_open.png"),
                rightClosed = load("eyes/right_closed.png"),
                mouthNeutral = load("mouth/neutral.png"),
                mouths = Viseme.entries.associateWith { load("mouth/${it.asset}.png") },
            )
            post {
                layers = loaded
                rebuildBaseMatrix()
                invalidate()
            }
        }, "avatar-layers").start()
    }

    // Fit the (square) head bitmap into this view and centre it, with a small
    // overscan so the ±2% head motion below never exposes the photo's own
    // top/bottom edge. Every layer is the same size as the head, so this one
    // matrix is all any of them ever needs.
    private fun rebuildBaseMatrix() {
        val head = layers?.head ?: return
        if (width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / head.width, height.toFloat() / head.height) * OVERSCAN
        val dx = (width - head.width * scale) / 2f
        val dy = (height - head.height * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        drawnSizePx = head.width * scale
        // The head turns about the neck, not the middle of the picture.
        pivot[0] = head.width * 0.5f
        pivot[1] = head.height * 0.88f
        baseMatrix.mapPoints(pivot)
    }

    private fun step() {
        val now = System.currentTimeMillis()
        // Exponential smoothing toward the target — a fixed-fraction catch-up
        // per frame, not a fixed-duration tween: it reacts immediately to a
        // new target (no ramp-up lag chasing fast speech) while still never
        // jumping instantly.
        currentMouthOpen += (targetState.mouthOpen - currentMouthOpen) * SMOOTHING

        if (!blinking && now >= nextBlinkAtMs) {
            blinking = true
            blinkPhaseStartMs = now
        }
        var blinkPulse = 0f
        if (blinking) {
            val elapsed = now - blinkPhaseStartMs
            if (elapsed >= BLINK_DURATION_MS) {
                blinking = false
                nextBlinkAtMs = now + nextBlinkDelayMs()
            } else {
                blinkPulse = trianglePulse(elapsed, BLINK_DURATION_MS)
            }
        }
        var left = blinkPulse
        var right = blinkPulse

        var viseme = visemeFor(targetState.mouthShape, currentMouthOpen)
        var visemeTarget = smoothstep(MOUTH_OPEN_MIN, MOUTH_OPEN_FULL, currentMouthOpen)
        var popPulse = 0f

        val active = gesture
        if (active != null) {
            val elapsed = now - gestureStartMs
            if (elapsed >= active.durationMs) {
                gesture = null
            } else {
                val pulse = trianglePulse(elapsed, active.durationMs)
                when (active) {
                    AvatarGesture.BLINK -> {
                        left = pulse
                        right = pulse
                    }
                    AvatarGesture.WINK_LEFT -> left = pulse
                    AvatarGesture.WINK_RIGHT -> right = pulse
                    AvatarGesture.SURPRISE -> {
                        viseme = Viseme.O
                        visemeTarget = min(1f, pulse * 1.6f)
                        popPulse = pulse
                    }
                    AvatarGesture.SMILE -> {
                        viseme = Viseme.SMILE
                        visemeTarget = min(1f, pulse * 2f)
                    }
                }
            }
        }

        // A triangular pulse is closed only at its very peak; this widens the
        // closed plateau so a blink reads as shut, not as a half-faded ghost.
        leftClosed = min(1f, left * CLOSED_PLATEAU)
        rightClosed = min(1f, right * CLOSED_PLATEAU)

        for (v in Viseme.entries) {
            val target = if (v == viseme) visemeTarget else 0f
            visemeWeights[v.ordinal] += (target - visemeWeights[v.ordinal]) * VISEME_SMOOTHING
        }

        // Idle micro-motion, incommensurate periods so it never visibly loops.
        // Bounded well inside the ±1–3% translation, ±2° rotation, 0.98–1.02
        // scale envelope; speech adds a slight nod, surprise a quick pop.
        val t = now / 1000.0
        val speech = currentMouthOpen.coerceIn(0f, 1f)
        headRotationDeg = (0.9 * sin(2 * PI * t / 6.3) + 0.5 * sin(2 * PI * t / 3.1 + 1.0)).toFloat()
        headShiftX = (0.012 * sin(2 * PI * t / 7.1 + 0.7)).toFloat()
        headShiftY = (0.014 * sin(2 * PI * t / 5.3) + 0.006 * speech - 0.012 * popPulse).toFloat()
        headScale = (1.0 + 0.008 * sin(2 * PI * t / 8.9) + 0.006 * speech + 0.02 * popPulse).toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layers ?: return

        // One matrix for every layer: fit/centre, then rotation, scale and
        // translation about the neck pivot.
        drawMatrix.set(baseMatrix)
        drawMatrix.postRotate(headRotationDeg, pivot[0], pivot[1])
        drawMatrix.postScale(headScale, headScale, pivot[0], pivot[1])
        drawMatrix.postTranslate(headShiftX * drawnSizePx, headShiftY * drawnSizePx)

        drawLayer(canvas, l.head, 1f)
        drawLayer(canvas, l.leftOpen, 1f)
        drawLayer(canvas, l.rightOpen, 1f)
        if (leftClosed > 0f) drawLayer(canvas, l.leftClosed, leftClosed)
        if (rightClosed > 0f) drawLayer(canvas, l.rightClosed, rightClosed)
        drawLayer(canvas, l.mouthNeutral, 1f)
        for (v in Viseme.entries) {
            val w = visemeWeights[v.ordinal]
            if (w > MIN_LAYER_ALPHA) drawLayer(canvas, l.mouths.getValue(v), w)
        }
    }

    private fun drawLayer(canvas: Canvas, bitmap: Bitmap, alpha: Float) {
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        canvas.drawBitmap(bitmap, drawMatrix, paint)
    }

    // Existing MouthShape buckets -> the six mouth PNGs. CLOSED (bilabials,
    // punctuation) is just the neutral mouth. The two-way splits (ROUND ->
    // o/u, WIDE -> e/i) use loudness: a louder syllable opens wider.
    private fun visemeFor(shape: MouthShape, open: Float): Viseme? = when (shape) {
        MouthShape.CLOSED -> null
        MouthShape.OPEN -> Viseme.A
        MouthShape.ROUND -> if (open >= WIDE_OPEN_THRESHOLD) Viseme.O else Viseme.U
        MouthShape.WIDE -> if (open >= WIDE_OPEN_THRESHOLD) Viseme.E else Viseme.I
        MouthShape.TEETH, MouthShape.SIBILANT, MouthShape.NARROW -> Viseme.I
    }

    // A triangular pulse (0 -> 1 -> 0), not a step — an instant open/closed
    // eyelid reads as a glitch, not a blink or a wink. Shared by the idle
    // auto-blink timer and every timed [AvatarGesture].
    private fun trianglePulse(elapsedMs: Long, durationMs: Long): Float {
        val phase = elapsedMs.toFloat() / durationMs
        return 1f - abs(phase - 0.5f) * 2f
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun nextBlinkDelayMs(): Long = Random.nextLong(MIN_BLINK_GAP_MS, MAX_BLINK_GAP_MS)

    private companion object {
        const val ASSET_DIR = "avatar"
        const val OVERSCAN = 1.04f

        const val SMOOTHING = 0.25f
        const val VISEME_SMOOTHING = 0.4f
        const val MIN_LAYER_ALPHA = 0.02f

        const val BLINK_DURATION_MS = 180L
        const val MIN_BLINK_GAP_MS = 2000L
        const val MAX_BLINK_GAP_MS = 6000L
        const val CLOSED_PLATEAU = 2.2f

        const val MOUTH_OPEN_MIN = 0.04f
        const val MOUTH_OPEN_FULL = 0.35f
        const val WIDE_OPEN_THRESHOLD = 0.4f
    }
}

/**
 * A short, timed expression [AvatarView.playGesture] plays on top of
 * whatever [AvatarState] speech is currently driving — see
 * [ai.localstudio.app.AvatarTestActivity], the only caller today.
 */
enum class AvatarGesture(val durationMs: Long) {
    BLINK(220L),
    WINK_LEFT(650L),
    WINK_RIGHT(650L),
    SURPRISE(900L),
    SMILE(1400L),
}
