package ai.localstudio.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Time an operation has spent queued on a [DeviceMemoryGatedRuntime] gate —
 * installed by [withOperationTimeout], reported by the gate itself, so that
 * waiting behind another source's turn doesn't eat this operation's own
 * timeout budget.
 */
class GateWaitClock internal constructor(
    private val nanoTime: () -> Long,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GateWaitClock>

    private val completedWaitNanos = AtomicLong(0)
    private val waitingSince = AtomicLong(NOT_WAITING)

    fun beginWait() {
        waitingSince.set(nanoTime())
    }

    fun endWait() {
        val since = waitingSince.getAndSet(NOT_WAITING)
        if (since != NOT_WAITING) completedWaitNanos.addAndGet(nanoTime() - since)
    }

    /** Total time queued so far, including a wait still in progress. */
    fun waitedNanos(now: Long): Long {
        val since = waitingSince.get()
        return completedWaitNanos.get() + if (since != NOT_WAITING) now - since else 0L
    }
}

/**
 * Thrown by [withOperationTimeout]: [deadlineHit] tells a hard upper bound
 * (queueing included) apart from the operation's own budget running out.
 */
class OperationTimeoutException(val limitMs: Long, val deadlineHit: Boolean) :
    Exception(if (deadlineHit) "deadline of ${limitMs}ms reached" else "no answer within ${limitMs}ms of active work")

/**
 * Like `withTimeout(timeoutMs)`, except that time [block] spends queued on a
 * [DeviceMemoryGatedRuntime] gate (behind AICore or another local model's
 * turn) doesn't count against [timeoutMs] — real device report: a local
 * translation's 120 s budget covered waiting behind an AICore call that
 * itself took ~80 s, plus a ~10 s model load and ~9 s T5 encode, before the
 * local model had generated anything. [deadlineMs] is the hard upper bound
 * on everything, queueing included.
 *
 * A caller's own cancellation (Stop) still propagates as a plain
 * [CancellationException]; only this function's own limits surface as
 * [OperationTimeoutException].
 */
suspend fun <T> withOperationTimeout(
    timeoutMs: Long,
    deadlineMs: Long,
    nanoTime: () -> Long = System::nanoTime,
    tickMs: Long = WATCHDOG_TICK_MS,
    block: suspend () -> T,
): T = coroutineScope {
    val clock = GateWaitClock(nanoTime)
    val start = nanoTime()
    val timedOut = AtomicReference<OperationTimeoutException?>(null)
    val work = async(clock) { block() }
    val watchdog = launch {
        while (true) {
            delay(tickMs)
            val now = nanoTime()
            val elapsedMs = (now - start) / NANOS_PER_MS
            val activeMs = elapsedMs - clock.waitedNanos(now) / NANOS_PER_MS
            val hit = when {
                activeMs > timeoutMs -> OperationTimeoutException(timeoutMs, deadlineHit = false)
                elapsedMs > deadlineMs -> OperationTimeoutException(deadlineMs, deadlineHit = true)
                else -> null
            }
            if (hit != null) {
                timedOut.set(hit)
                work.cancel()
                break
            }
        }
    }
    try {
        work.await()
    } catch (e: CancellationException) {
        throw timedOut.get() ?: e
    } finally {
        watchdog.cancel()
    }
}

private const val NOT_WAITING = Long.MIN_VALUE
private const val WATCHDOG_TICK_MS = 250L
private const val NANOS_PER_MS = 1_000_000L
