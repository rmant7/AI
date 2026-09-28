package ai.localstudio.app.avatar

import ai.localstudio.app.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Phase 2 face — the user's own photo ([R.drawable.avatar_face]), animated by
 * warping a mesh of points laid over it (via [Canvas.drawBitmapMesh]) instead
 * of drawing cartoon shapes on top of it. There's no face-landmark detection
 * here: the mouth/eye regions below are a fixed guess at where those are in
 * *this specific* photo, expressed as fractions of its width/height — if the
 * photo is ever swapped for a different one, those constants need re-eyeballing
 * too. The warp itself stays purely local (smoothstep falloff around each
 * region) so the rest of the face never moves.
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

    // decodeResource, not a Bitmap.createBitmap round-trip — this is a fixed
    // photo baked into the APK, never generated or replaced at runtime.
    private val faceBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.avatar_face)
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    // Rebuilt in onSizeChanged: the mesh's rest position (bitmap
    // center-cropped to this view's current bounds), before any per-frame
    // mouth/eye offset is added.
    private var baseVerts = FloatArray(0)
    private var warpVerts = FloatArray(0)
    private var drawWidthPx = 0f
    private var drawHeightPx = 0f

    private var targetState = AvatarState()
    private var currentMouthOpen = 0f
    private var currentMouthShape = MouthShape.CLOSED

    // -1..1: positive closes the eye (a blink), negative widens it (e.g.
    // [AvatarGesture.SURPRISE]) — see applyWarp's own comment on why the
    // same warp direction handles both.
    private var currentLeftEyeAmount = 0f
    private var currentRightEyeAmount = 0f

    private var nextBlinkAtMs = 0L
    private var blinkPhaseStartMs = 0L
    private var blinking = false

    private var gesture: AvatarGesture? = null
    private var gestureStartMs = 0L

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
     * currently driving (speech keeps animating the mouth underneath a
     * [AvatarGesture.SURPRISE], for instance) — see [AvatarTestActivity] for
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
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBaseMesh(w, h)
    }

    // Center-crop the photo into the view's bounds (it's rarely the same
    // aspect ratio as the AvatarView, e.g. a square photo in a short wide
    // strip), then lay an evenly spaced grid of rest positions over it —
    // this is the mesh applyWarp() perturbs per frame, not the bitmap's own
    // pixels.
    private fun rebuildBaseMesh(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val scale = maxOf(w.toFloat() / faceBitmap.width, h.toFloat() / faceBitmap.height)
        drawWidthPx = faceBitmap.width * scale
        drawHeightPx = faceBitmap.height * scale
        val left = (w - drawWidthPx) / 2f
        val top = (h - drawHeightPx) / 2f

        val verts = FloatArray((MESH_COLS + 1) * (MESH_ROWS + 1) * 2)
        var vi = 0
        for (row in 0..MESH_ROWS) {
            val v = row / MESH_ROWS.toFloat()
            for (col in 0..MESH_COLS) {
                val u = col / MESH_COLS.toFloat()
                verts[vi] = left + u * drawWidthPx
                verts[vi + 1] = top + v * drawHeightPx
                vi += 2
            }
        }
        baseVerts = verts
        warpVerts = verts.copyOf()
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
        val autoBlink = if (blinking) {
            val elapsed = now - blinkPhaseStartMs
            if (elapsed >= BLINK_DURATION_MS) {
                blinking = false
                nextBlinkAtMs = now + nextBlinkDelayMs()
                0f
            } else {
                trianglePulse(elapsed, BLINK_DURATION_MS)
            }
        } else {
            0f
        }

        var leftEye = autoBlink
        var rightEye = autoBlink
        val activeGesture = gesture
        if (activeGesture != null) {
            val elapsed = now - gestureStartMs
            if (elapsed >= activeGesture.durationMs) {
                gesture = null
            } else {
                when (activeGesture) {
                    AvatarGesture.BLINK -> {
                        leftEye = trianglePulse(elapsed, activeGesture.durationMs)
                        rightEye = leftEye
                    }
                    AvatarGesture.WINK_LEFT -> leftEye = trianglePulse(elapsed, activeGesture.durationMs)
                    AvatarGesture.WINK_RIGHT -> rightEye = trianglePulse(elapsed, activeGesture.durationMs)
                    AvatarGesture.SURPRISE -> {
                        // Negative = widened, not closed — see applyWarp.
                        val pulse = trianglePulse(elapsed, activeGesture.durationMs)
                        leftEye = -pulse
                        rightEye = -pulse
                        currentMouthShape = MouthShape.ROUND
                        currentMouthOpen = pulse
                    }
                }
            }
        }
        currentLeftEyeAmount = leftEye
        currentRightEyeAmount = rightEye
        invalidate()
    }

    // A triangular pulse (0 -> 1 -> 0), not a step — an instant open/closed
    // eyelid reads as a glitch, not a blink or a wink. Shared by the idle
    // auto-blink timer and every timed [AvatarGesture].
    private fun trianglePulse(elapsedMs: Long, durationMs: Long): Float {
        val phase = elapsedMs.toFloat() / durationMs
        return 1f - kotlin.math.abs(phase - 0.5f) * 2f
    }

    private fun nextBlinkDelayMs(): Long = Random.nextLong(MIN_BLINK_GAP_MS, MAX_BLINK_GAP_MS)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseVerts.isEmpty()) return
        applyWarp()
        canvas.drawBitmapMesh(faceBitmap, MESH_COLS, MESH_ROWS, warpVerts, 0, null, 0, meshPaint)
    }

    private fun applyWarp() {
        val openAmount = currentMouthOpen.coerceIn(0f, 1f)
        // Rough, not measured — see this class's own doc comment: real lips
        // move mostly via the jaw (the "below center" half of the mouth
        // region), the upper lip barely at all, which is why these two
        // fractions aren't symmetric.
        val (openScale, widthScale) = when (currentMouthShape) {
            MouthShape.CLOSED -> 0f to 0f
            MouthShape.OPEN -> 1f to 0f
            MouthShape.ROUND -> 0.8f to -0.4f
            MouthShape.WIDE -> 0.5f to 0.5f
            MouthShape.TEETH -> 0.4f to 0.2f
            MouthShape.SIBILANT -> 0.3f to -0.2f
            MouthShape.NARROW -> 0.5f to -0.15f
        }
        val mouthOpenPx = openAmount * openScale * MAX_MOUTH_OPEN_FRACTION * drawHeightPx
        val mouthWidthPx = openAmount * widthScale * MAX_MOUTH_WIDTH_FRACTION * drawWidthPx
        // Same displacement, opposite direction depending on sign — a
        // negative amount (only ever from AvatarGesture.SURPRISE) pushes the
        // lids apart instead of together, which is why this isn't just
        // `.coerceIn(0f, 1f)` the way a plain blink amount would be. Widening
        // reads clearly at a slightly larger fraction than a blink's own —
        // see MAX_EYE_WIDE_FRACTION's own comment.
        val leftEyePx = eyeAmplitudePx(currentLeftEyeAmount)
        val rightEyePx = eyeAmplitudePx(currentRightEyeAmount)

        var vi = 0
        for (row in 0..MESH_ROWS) {
            val v = row / MESH_ROWS.toFloat()
            for (col in 0..MESH_COLS) {
                val u = col / MESH_COLS.toFloat()
                var dx = 0f
                var dy = 0f

                val mouthInfluence = ellipseFalloff(u - MOUTH_CX, v - MOUTH_CY, MOUTH_RX, MOUTH_RY)
                if (mouthInfluence > 0f) {
                    val jawSign = if (v >= MOUTH_CY) 1f else -0.4f
                    dy += mouthInfluence * mouthOpenPx * jawSign
                    val cornerSign = if (u >= MOUTH_CX) 1f else -1f
                    dx += mouthInfluence * mouthWidthPx * cornerSign
                }

                // Left/right regions are spatially disjoint, so a vertex only
                // ever gets influence from (at most) one of them — no need to
                // pick a max, both contributions can just be added.
                val lidSign = if (v < EYE_CY) 1f else -1f
                val leftEyeInfluence = ellipseFalloff(u - LEFT_EYE_CX, v - EYE_CY, EYE_RX, EYE_RY)
                if (leftEyeInfluence > 0f) dy += leftEyeInfluence * leftEyePx * lidSign
                val rightEyeInfluence = ellipseFalloff(u - RIGHT_EYE_CX, v - EYE_CY, EYE_RX, EYE_RY)
                if (rightEyeInfluence > 0f) dy += rightEyeInfluence * rightEyePx * lidSign

                warpVerts[vi] = baseVerts[vi] + dx
                warpVerts[vi + 1] = baseVerts[vi + 1] + dy
                vi += 2
            }
        }
    }

    private fun eyeAmplitudePx(amount: Float): Float {
        val clamped = amount.coerceIn(-1f, 1f)
        val fraction = if (clamped >= 0f) MAX_BLINK_FRACTION else MAX_EYE_WIDE_FRACTION
        return clamped * fraction * drawHeightPx
    }

    // Smoothstep falloff on normalized elliptical distance: 1 at the region's
    // center, 0 at/beyond its radius, with no hard edge in between — a linear
    // falloff instead would show as a visible crease where influence hits 0.
    private fun ellipseFalloff(du: Float, dv: Float, rx: Float, ry: Float): Float {
        val nx = du / rx
        val ny = dv / ry
        val d = kotlin.math.sqrt(nx * nx + ny * ny)
        val t = (1f - d).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        const val SMOOTHING = 0.25f
        const val BLINK_DURATION_MS = 180L
        const val MIN_BLINK_GAP_MS = 2000L
        const val MAX_BLINK_GAP_MS = 6000L

        const val MESH_COLS = 16
        const val MESH_ROWS = 16

        // Fractions of avatar_face.jpg's own width/height (0..1) — eyeballed
        // against that specific photo, not derived from any detector.
        const val MOUTH_CX = 0.51f
        const val MOUTH_CY = 0.77f
        const val MOUTH_RX = 0.17f
        const val MOUTH_RY = 0.11f
        const val LEFT_EYE_CX = 0.39f
        const val RIGHT_EYE_CX = 0.66f
        const val EYE_CY = 0.51f
        const val EYE_RX = 0.075f
        const val EYE_RY = 0.05f

        // How far a fully-open mouth/fully-closed eye is allowed to displace
        // the mesh, as a fraction of the drawn photo's own size — kept small
        // on purpose: this is a real photo, not a caricature, and a few
        // percent of displacement already reads clearly at 200dp height.
        const val MAX_MOUTH_OPEN_FRACTION = 0.09f
        const val MAX_MOUTH_WIDTH_FRACTION = 0.05f
        const val MAX_BLINK_FRACTION = 0.028f
        // Larger than MAX_BLINK_FRACTION on purpose — eyes widening open is
        // a subtler visual change than eyes shutting, so it needs more room
        // to read as "surprised" rather than just a slightly bigger blink.
        const val MAX_EYE_WIDE_FRACTION = 0.045f
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
}
