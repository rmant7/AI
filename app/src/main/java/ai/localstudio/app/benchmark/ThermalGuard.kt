package ai.localstudio.app.benchmark

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * Real device report: a benchmark run's own model warm-up took 791 seconds
 * (13 minutes) for a 1.5s clip, and every file transcribed after that point
 * failed with the same `MediaCodec$CodecException` — while the exact same
 * operations had succeeded minutes earlier in the same run. Nothing in the
 * code changed between those two points; the device's own thermal state
 * did, after 30+ minutes of continuous, back-to-back multi-GB model loads
 * and inference with no rest between engines.
 *
 * There is no code fix for real silicon throttling — [waitUntilSafe] does
 * not make the device faster. What it does is stop
 * [BenchmarkOrchestrator] from immediately starting the *next* multi-GB
 * model load while the device is already in a state where that load and
 * everything after it is near-certain to fail or time out, wasting
 * several more minutes for zero usable data. `PowerManager`'s thermal
 * status API is API 29+; below that this is a silent no-op — this app's
 * minSdk (26) still needs to run without it.
 */
class ThermalGuard(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** Suspends, polling every [POLL_INTERVAL_MS], for as long as the device reports [PowerManager.THERMAL_STATUS_SEVERE] or worse. [onWaiting] is called once per poll while waiting, and once more when the wait ends, so the caller can surface this on screen/in its own log — a silent multi-minute pause here would just be a smaller version of the exact problem this exists to fix. */
    suspend fun waitUntilSafe(onWaiting: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = powerManager ?: return
        var waited = false
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            waited = true
            onWaiting("Device thermal status ${describeStatus(pm.currentThermalStatus)} — pausing until it cools down…")
            delay(POLL_INTERVAL_MS)
        }
        if (waited) onWaiting("Thermal status recovered — resuming.")
    }

    private fun describeStatus(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> status.toString()
    }

    /** Read-only, non-blocking snapshot for per-file logging (see [ai.localstudio.core.benchmark.BenchmarkRunMetrics.thermalStatus]) — unlike [waitUntilSafe], never pauses or acts on the value, just reports it. API 29+; null below that or if the service is unavailable. */
    fun currentStatusLabel(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = powerManager ?: return null
        return describeStatus(pm.currentThermalStatus)
    }

    /**
     * [PowerManager.getThermalHeadroom]'s normalized forecast (not a raw
     * Celsius reading — Android exposes no public raw-temperature API to
     * apps) for the current moment ([forecastSeconds] = 0). API 30+; the
     * platform call itself can throw when it has no data yet, which this
     * treats the same as "unavailable" rather than crashing a benchmark run
     * over a missing metric.
     */
    fun currentHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val pm = powerManager ?: return null
        return runCatching { pm.getThermalHeadroom(0) }.getOrNull()?.takeUnless { it.isNaN() }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 15_000L
    }
}
