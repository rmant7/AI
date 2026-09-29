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

    // Speech states — the only ones VisemeMapper's MouthShape can select.
    // round and wide are different lip shapes, not two degrees of "open", so
    // moving between them is an alpha cross-fade of full layers, nothing more.
    private enum class Speech(val asset: String) { OPEN("a"), WIDE("e"), ROUND("o") }

    // Expressions — held by toggle, never chosen by speech, and independent of
    // it: they set the base mouth/eyes/brows, speech is drawn over the mouth.
    private enum class Expression(val mouthAsset: String) { SMILE("smile"), WOW("wow"), ANGER("anger") }

    private class Layers(
        val head: Bitmap,
        val leftOpen: Bitmap,
        val leftClosed: Bitmap,
        val leftAngry: Bitmap,
        val rightOpen: Bitmap,
        val rightClosed: Bitmap,
        val rightAngry: Bitmap,
        val browsAngry: Bitmap,
        val leftWide: Bitmap,
        val rightWide: Bitmap,
        val browsRaised: Bitmap,
        val mouthNeutral: Bitmap,
        val speech: Map<Speech, Bitmap>,
        val expression: Map<Expression, Bitmap>,
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
    private val speechWeights = FloatArray(Speech.entries.size)
    private val expressionWeights = FloatArray(Expression.entries.size)

    // 0..1 = how far the closed-eye layer is faded in over the open one.
    private var leftClosed = 0f
    private var rightClosed = 0f

    private var nextBlinkAtMs = 0L
    private var blinkPhaseStartMs = 0L
    private var blinking = false

    // At most one held gesture per group (eyes / expression), so a wink and
    // an expression can be held together but two of the same kind cannot.
    private var heldEyes: AvatarGesture? = null
    private var heldExpression: AvatarGesture? = null

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
     * Holds [gesture] at its full expression until it is toggled again (or
     * another gesture of the same [AvatarGesture.group] replaces it) — so a
     * screenshot can be taken at the extreme of an expression instead of
     * racing a timer. Independent of whatever [updateState] is driving.
     * Returns whether [gesture] is held after the call.
     */
    fun toggleGesture(gesture: AvatarGesture): Boolean {
        val held = isHeld(gesture)
        when (gesture.group) {
            AvatarGesture.Group.EYES -> heldEyes = if (held) null else gesture
            AvatarGesture.Group.EXPRESSION -> heldExpression = if (held) null else gesture
        }
        return !held
    }

    fun isHeld(gesture: AvatarGesture): Boolean = heldEyes == gesture || heldExpression == gesture

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
                leftAngry = load("eyes/left_angry.png"),
                rightOpen = load("eyes/right_open.png"),
                rightClosed = load("eyes/right_closed.png"),
                rightAngry = load("eyes/right_angry.png"),
                browsAngry = load("eyebrows/angry.png"),
                leftWide = load("eyes/left_wide.png"),
                rightWide = load("eyes/right_wide.png"),
                browsRaised = load("eyebrows/raised.png"),
                mouthNeutral = load("mouth/neutral.png"),
                speech = Speech.entries.associateWith { load("mouth/${it.asset}.png") },
                expression = Expression.entries.associateWith { load("mouth/${it.mouthAsset}.png") },
            )
            post {
                layers = loaded
                rebuildBaseMatrix()
                invalidate()
            }
        }, "avatar-layers").start()
    }

    // Fit the (square) head bitmap into this view and centre it. The head layer
    // is a cut-out (transparent background, cut edges faded), so head motion
    // never exposes a photo edge and no overscan is needed. Every layer is
    // the same size as the head, so this one matrix is all any of them ever
    // needs.
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
        when (heldEyes) {
            AvatarGesture.BLINK -> { left = 1f; right = 1f }
            AvatarGesture.WINK_LEFT -> left = 1f
            AvatarGesture.WINK_RIGHT -> right = 1f
            else -> Unit
        }

        val speechShape = speechFor(targetState.mouthShape)
        val speechTarget = smoothstep(MOUTH_OPEN_MIN, MOUTH_OPEN_FULL, currentMouthOpen)
        val held = when (heldExpression) {
            AvatarGesture.SMILE -> Expression.SMILE
            AvatarGesture.SURPRISE -> Expression.WOW
            AvatarGesture.ANGER -> Expression.ANGER
            else -> null
        }
        for (e in Expression.entries) {
            val i = e.ordinal
            expressionWeights[i] += ((if (e == held) 1f else 0f) - expressionWeights[i]) * VISEME_SMOOTHING
        }
        // Speech stays visible over a held expression, just a little less
        // dominant, so the expression is still readable while talking.
        val speechScale = 1f - EXPRESSION_SPEECH_DAMPING * expressionWeights.max()
        for (v in Speech.entries) {
            val target = if (v == speechShape) speechTarget * speechScale else 0f
            speechWeights[v.ordinal] += (target - speechWeights[v.ordinal]) * VISEME_SMOOTHING
        }

        // A triangular blink pulse is closed only at its very peak; this
        // widens the closed plateau so a blink reads as shut, not as a
        // half-faded ghost. Held eyes are already at 1 and simply stay there.
        leftClosed += (min(1f, left * CLOSED_PLATEAU) - leftClosed) * EYE_SMOOTHING
        rightClosed += (min(1f, right * CLOSED_PLATEAU) - rightClosed) * EYE_SMOOTHING

        val wow = expressionWeights[Expression.WOW.ordinal]
        val anger = expressionWeights[Expression.ANGER.ordinal]
        val popPulse = wow

        // Idle micro-motion, incommensurate periods so it never visibly loops.
        // Bounded well inside the ±1–3% translation, ±2° rotation, 0.98–1.02
        // scale envelope; speech adds a slight nod, surprise a quick pop.
        val t = now / 1000.0
        val speech = currentMouthOpen.coerceIn(0f, 1f)
        headRotationDeg = (0.9 * sin(2 * PI * t / 6.3) + 0.5 * sin(2 * PI * t / 3.1 + 1.0)).toFloat()
        headShiftX = (0.012 * sin(2 * PI * t / 7.1 + 0.7)).toFloat()
        headShiftY = (0.014 * sin(2 * PI * t / 5.3) + 0.006 * speech - 0.012 * popPulse + 0.008 * anger).toFloat()
        headScale = (1.0 + 0.008 * sin(2 * PI * t / 8.9) + 0.006 * speech + 0.02 * popPulse + 0.01 * anger).toFloat()
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

        val anger = expressionWeights[Expression.ANGER.ordinal]
        val wow = expressionWeights[Expression.WOW.ordinal]
        drawLayer(canvas, l.head, 1f)
        drawLayer(canvas, l.leftOpen, 1f)
        drawLayer(canvas, l.rightOpen, 1f)
        if (anger > MIN_LAYER_ALPHA) {
            drawLayer(canvas, l.leftAngry, anger)
            drawLayer(canvas, l.rightAngry, anger)
        }
        if (wow > MIN_LAYER_ALPHA) {
            drawLayer(canvas, l.leftWide, wow)
            drawLayer(canvas, l.rightWide, wow)
        }
        // Closed eyes over open/angry ones, then brows over everything on the
        // eyes: a blink or wink under anger keeps the lowered brows.
        if (leftClosed > 0f) drawLayer(canvas, l.leftClosed, leftClosed)
        if (rightClosed > 0f) drawLayer(canvas, l.rightClosed, rightClosed)
        if (anger > MIN_LAYER_ALPHA) drawLayer(canvas, l.browsAngry, anger)
        if (wow > MIN_LAYER_ALPHA) drawLayer(canvas, l.browsRaised, wow)
        drawLayer(canvas, l.mouthNeutral, 1f)
        for (e in Expression.entries) {
            val w = expressionWeights[e.ordinal]
            if (w > MIN_LAYER_ALPHA) drawLayer(canvas, l.expression.getValue(e), w)
        }
        for (v in Speech.entries) {
            val w = speechWeights[v.ordinal]
            if (w > MIN_LAYER_ALPHA) drawLayer(canvas, l.speech.getValue(v), w)
        }
    }

    private fun drawLayer(canvas: Canvas, bitmap: Bitmap, alpha: Float) {
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        canvas.drawBitmap(bitmap, drawMatrix, paint)
    }

    // Existing MouthShape buckets -> the three non-neutral speech layers.
    // CLOSED (bilabials, punctuation) is just the neutral mouth underneath.
    // The lips of this face sit behind a moustache, so the fine distinctions
    // (teeth, sibilant, narrow, and e vs i) are not visible and share "wide".
    private fun speechFor(shape: MouthShape): Speech? = when (shape) {
        MouthShape.CLOSED -> null
        MouthShape.OPEN -> Speech.OPEN
        MouthShape.ROUND -> Speech.ROUND
        MouthShape.WIDE, MouthShape.TEETH, MouthShape.SIBILANT, MouthShape.NARROW -> Speech.WIDE
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
        const val OVERSCAN = 1f

        const val SMOOTHING = 0.25f
        const val VISEME_SMOOTHING = 0.4f
        const val EYE_SMOOTHING = 0.55f
        const val MIN_LAYER_ALPHA = 0.02f

        const val BLINK_DURATION_MS = 180L
        const val MIN_BLINK_GAP_MS = 2000L
        const val MAX_BLINK_GAP_MS = 6000L
        const val CLOSED_PLATEAU = 2.2f

        const val MOUTH_OPEN_MIN = 0.04f
        const val MOUTH_OPEN_FULL = 0.35f
        const val EXPRESSION_SPEECH_DAMPING = 0.4f
    }
}

/**
 * An expression [AvatarView.toggleGesture] holds at its maximum until it is
 * toggled off — see [ai.localstudio.app.AvatarTestActivity], the only caller
 * today. Gestures in the same [Group] replace each other; different groups
 * combine.
 */
enum class AvatarGesture(val group: Group) {
    BLINK(Group.EYES),
    WINK_LEFT(Group.EYES),
    WINK_RIGHT(Group.EYES),
    SURPRISE(Group.EXPRESSION),
    SMILE(Group.EXPRESSION),
    ANGER(Group.EXPRESSION),
    ;

    enum class Group { EYES, EXPRESSION }
}
