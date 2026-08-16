package ai.localstudio.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps content out from under the system bars.
 *
 * From `targetSdk 35` on, the window is laid out edge to edge whether the app
 * asks for it or not, so anything at the top slides under the clock and
 * anything at the bottom under the navigation gesture bar. Padding the root by
 * the measured insets is the fix; hard-coded margins are not, because the bar
 * heights differ per device and change when the keyboard appears.
 */
fun View.applySystemBarInsets(applyImeInset: Boolean = false) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = if (applyImeInset) {
            windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        } else {
            0
        }
        view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime))
        // Consumed: nested views must not subtract the same inset again.
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(this)
}
