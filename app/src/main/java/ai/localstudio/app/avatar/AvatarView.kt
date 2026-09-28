package ai.localstudio.app.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Phase 1 placeholder face — plain [Canvas] shapes (a circle head, two eye
 * ellipses, one mouth ellipse), not artwork. The point of this skeleton is
 * proving the whole pipeline (LLM stream → sentence → TTS → mouth shape/
 * openness → a face that visibly reacts) works end to end before spending
 * anything on real character art; see the `avatar` branch's own scope notes
 * for why bitmap sprites and mesh-deformed mouths are deliberately later
 * phases, not this one.
 *
 * Owns two loops, both independent of whatever [AvatarSpeechController]
 * feeds it: a per-frame smoothing loop (so mouth-open jumps from
 * [AvatarTtsEngine] callbacks read as motion, not a flicker between two
 * fixed states) and an idle blink timer (so the face still looks alive with
 * nothing being spoken at all). Both start in [onAttachedToWindow] and stop
 * in [onDetachedFromWindow] — this view must never keep animating (or
 * holding a callback the framework can't collect) after it's off-screen.
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var targetState = AvatarState()
    private var currentMouthOpen = 0f
    private var currentBlink = 0f
    private var currentMouthShape = MouthShape.CLOSED

    private var nextBlinkAtMs = 0L
    private var blinkPhaseStartMs = 0L
    private var blinking = false

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FACE_COLOR }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EYE_COLOR }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MOUTH_COLOR }
    private val mouthRect = RectF()

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        nextBlinkAtMs = System.currentTimeMillis() + nextBlinkDelayMs()
        postOnAnimation(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frameCallback)
    }

    private fun step() {
        // Exponential smoothing toward the target — a fixed-fraction catch-up
        // per frame, not a fixed-duration tween: it reacts immediately to a
        // new target (no ramp-up lag chasing fast speech) while still never
        // jumping instantly (see this class's own doc comment on why the
        // raw callback values alone would read as a flicker).
        currentMouthOpen += (targetState.mouthOpen - currentMouthOpen) * SMOOTHING
        currentMouthShape = targetState.mouthShape

        val now = System.currentTimeMillis()
        if (!blinking && now >= nextBlinkAtMs) {
            blinking = true
            blinkPhaseStartMs = now
        }
        currentBlink = if (blinking) {
            val elapsed = now - blinkPhaseStartMs
            if (elapsed >= BLINK_DURATION_MS) {
                blinking = false
                nextBlinkAtMs = now + nextBlinkDelayMs()
                0f
            } else {
                // A triangular pulse (0 -> 1 -> 0), not a step — an instant
                // closed-then-open eyelid reads as a glitch, not a blink.
                val phase = elapsed.toFloat() / BLINK_DURATION_MS
                1f - kotlin.math.abs(phase - 0.5f) * 2f
            }
        } else {
            0f
        }
        invalidate()
    }

    private fun nextBlinkDelayMs(): Long = Random.nextLong(MIN_BLINK_GAP_MS, MAX_BLINK_GAP_MS)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.85f
        canvas.drawCircle(cx, cy, radius, facePaint)

        val eyeOffsetX = radius * 0.4f
        val eyeY = cy - radius * 0.25f
        val eyeRadiusX = radius * 0.14f
        // 1f (open) down to a sliver — never fully 0, a fully flat oval
        // draws as an invisible line rather than a closed eye.
        val eyeRadiusY = radius * 0.14f * (1f - currentBlink * 0.9f)
        canvas.drawOval(cx - eyeOffsetX - eyeRadiusX, eyeY - eyeRadiusY, cx - eyeOffsetX + eyeRadiusX, eyeY + eyeRadiusY, eyePaint)
        canvas.drawOval(cx + eyeOffsetX - eyeRadiusX, eyeY - eyeRadiusY, cx + eyeOffsetX + eyeRadiusX, eyeY + eyeRadiusY, eyePaint)

        drawMouth(canvas, cx, cy + radius * 0.35f, radius)
    }

    private fun drawMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val baseWidth = radius * 0.36f
        val baseHeight = radius * 0.06f
        val openAmount = currentMouthOpen.coerceIn(0f, 1f)
        // Width/height multipliers are a rough per-shape look, not measured
        // against anything — see MouthShape's own doc comment.
        val (widthScale, heightScale) = when (currentMouthShape) {
            MouthShape.CLOSED -> 1f to 1f
            MouthShape.OPEN -> 1f to (1f + openAmount * 5f)
            MouthShape.ROUND -> 0.55f to (1f + openAmount * 4f)
            MouthShape.WIDE -> 1.4f to (1f + openAmount * 2.5f)
            MouthShape.TEETH -> 1.1f to (1f + openAmount * 1.5f)
            MouthShape.SIBILANT -> 0.7f to (1f + openAmount * 1.8f)
            MouthShape.NARROW -> 0.85f to (1f + openAmount * 2f)
        }
        val halfWidth = baseWidth * widthScale
        val halfHeight = baseHeight * heightScale
        mouthRect.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        canvas.drawOval(mouthRect, mouthPaint)
    }

    private companion object {
        const val SMOOTHING = 0.25f
        const val BLINK_DURATION_MS = 180L
        const val MIN_BLINK_GAP_MS = 2000L
        const val MAX_BLINK_GAP_MS = 6000L
        // Not `const` — Color.parseColor() is a real function call (an ARGB
        // int computed from the hex string at class-init time), not a
        // compile-time literal, which is exactly what `const val` requires.
        val FACE_COLOR = Color.parseColor("#F2C9A0")
        val EYE_COLOR = Color.parseColor("#2B2B2B")
        val MOUTH_COLOR = Color.parseColor("#8A3B3B")
    }
}
