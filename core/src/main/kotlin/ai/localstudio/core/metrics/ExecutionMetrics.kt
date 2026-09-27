package ai.localstudio.core.metrics

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.errors.AIError

/**
 * One request's own execution stats, structured rather than a log-line
 * string built ad hoc at each call site — the way this app's own
 * `LOCAL_GENERATE`/`RAM_MEASURE`/generation-error log lines are today, each
 * with its own hand-assembled format. This is additive: no existing log
 * line changes to produce one of these — a caller can build one alongside
 * the string it already logs, or derive a new line from it, without either
 * one needing to change.
 *
 * Every field beyond [provider]/[model]/[startEpochMs]/[durationMs] is
 * nullable/defaulted: what's actually knowable differs by execution path —
 * [timeToFirstTokenMs]/[inputTokens]/[outputTokens] apply to both cloud and
 * local generation, while [loadTimeMs]/[peakRamBytes]/[tokensPerSecond] are
 * meaningful only for a local runtime (a cloud call has no model load step
 * and no local RAM footprint of its own to report).
 */
data class ExecutionMetrics(
    val provider: String,
    val model: String,
    val capability: Capability? = null,
    val startEpochMs: Long,
    val durationMs: Long,
    val timeToFirstTokenMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val retries: Int = 0,
    val fallbacks: Int = 0,
    val error: AIError? = null,
    /** Local-only: how long the model took to load before this call could even start generating. */
    val loadTimeMs: Long? = null,
    /** Local-only: the load-plus-first-generation RAM peak (see [ai.localstudio.core.registry.RamMeasurement]). */
    val peakRamBytes: Long? = null,
    /** Local-only: generation throughput for this call. */
    val tokensPerSecond: Double? = null,
) {
    val succeeded: Boolean get() = error == null
}

fun interface ExecutionMetricsListener {
    fun onExecution(metrics: ExecutionMetrics)
}

/**
 * A trivial multi-listener broadcast, not a queue or a store — this app
 * already has [ai.localstudio.app.AppLog] for anything that needs to
 * persist or be read back later; this exists only so more than one
 * interested party (a future in-app metrics screen, and this app's own
 * `AppLog` line for a call site that opts in) can observe the same
 * [ExecutionMetrics] without one registering as a wrapper around the other.
 * Thread-safe: [post] and [addListener]/[removeListener] all touch the same
 * listener list, expected to be called from whatever thread each execution
 * path naturally finishes on.
 */
object ExecutionMetricsBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ExecutionMetricsListener>()

    fun addListener(listener: ExecutionMetricsListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ExecutionMetricsListener) {
        listeners.remove(listener)
    }

    /** A listener throwing must not stop the others from seeing this [metrics], or break the caller that posted it. */
    fun post(metrics: ExecutionMetrics) {
        for (listener in listeners) {
            runCatching { listener.onExecution(metrics) }
        }
    }
}
