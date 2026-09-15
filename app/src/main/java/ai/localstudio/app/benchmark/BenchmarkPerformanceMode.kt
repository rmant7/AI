package ai.localstudio.app.benchmark

/**
 * Three ways to pace a long benchmark run, for comparing whether Android's
 * own Sustained Performance Mode actually pays off on a long inference
 * series — the point this whole mode framework exists for: does avoiding
 * heavy thermal throttling (via [android.view.Window.setSustainedPerformanceMode])
 * produce a *better total* than running flat-out and letting the SoC
 * throttle itself.
 *
 * Deliberately excluded: this app never calls
 * [android.os.PowerManager.isPowerSaveMode] or anything that would enable
 * Battery Saver / Extreme Battery Saver programmatically, in any mode —
 * that is an explicit requirement, not an oversight. Only [SUSTAINED]
 * touches an OS performance API at all, and only the one built for exactly
 * this ([android.view.Window.setSustainedPerformanceMode]).
 */
enum class BenchmarkPerformanceMode {
    /** No pacing, no OS performance mode — run engine after engine back to back, exactly as this benchmark always has. The baseline the other two are compared against. */
    MAXIMUM,

    /** Same as [MAXIMUM] except `Window.setSustainedPerformanceMode(true)` is set for the duration of the run wherever [SustainedPerformanceSupport.isSupported] reports the device can honor it — see requirement #6: on an unsupported device this still runs, it just logs `sustained_mode=unsupported` instead of silently claiming the mode was active. */
    SUSTAINED,

    /** Same pacing as [MAXIMUM] between files within one engine, but pauses via [ThermalGuard.waitUntilSafe] plus a fixed floor between engines specifically to let the device recover — the only one of the three modes that intentionally changes wall-clock behavior to protect thermal state, which is why it is not used for the Maximum vs. Sustained comparison itself (requirement #5). */
    COOL_DOWN,
}
