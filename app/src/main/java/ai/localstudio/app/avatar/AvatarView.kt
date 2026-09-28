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
    private var currentBlink = 0f
    private var currentMouthShape = MouthShape.CLOSED

    private var nextBlinkAtMs = 0L
    private var blinkPhaseStartMs = 0L
    private var blinking = false

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
        val eyeClosePx = currentBlink.coerceIn(0f, 1f) * MAX_BLINK_FRACTION * drawHeightPx

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

                val eyeInfluence = maxOf(
                    ellipseFalloff(u - LEFT_EYE_CX, v - EYE_CY, EYE_RX, EYE_RY),
                    ellipseFalloff(u - RIGHT_EYE_CX, v - EYE_CY, EYE_RX, EYE_RY),
                )
                if (eyeInfluence > 0f) {
                    // Upper eyelid pulled down, lower eyelid pulled up — the
                    // eye visually pinches shut instead of the whole region
                    // just sliding, which read as the eye "melting".
                    val lidSign = if (v < EYE_CY) 1f else -1f
                    dy += eyeInfluence * eyeClosePx * lidSign
                }

                warpVerts[vi] = baseVerts[vi] + dx
                warpVerts[vi + 1] = baseVerts[vi + 1] + dy
                vi += 2
            }
        }
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
    }
}
