package ai.localstudio.app.benchmark

import android.content.Context
import android.os.PowerManager

/**
 * Thin wrapper around [PowerManager.isSustainedPerformanceModeSupported] —
 * `Window.setSustainedPerformanceMode(true)` is a no-op on unsupported
 * hardware rather than an error, so the requirement this exists for is to
 * *know* that happened (and record `sustained_mode=unsupported` rather than
 * silently reporting a SUSTAINED run indistinguishable from one where the
 * mode actually engaged).
 */
object SustainedPerformanceSupport {
    fun isSupported(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isSustainedPerformanceModeSupported
    }
}
