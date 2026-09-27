package ai.localstudio.core.resources

import java.util.concurrent.ConcurrentHashMap

/**
 * Every engine this app's own memory decisions currently reason about,
 * separately and inconsistently, in application code
 * ([ai.localstudio.app.AppContainer]): the local LLM (via
 * [ai.localstudio.core.runtime.RuntimeManager], which already tracks its own
 * resident bytes precisely), the E5 embedder, Whisper/Vosk ASR, and AICore
 * (whose real footprint this app cannot measure at all — see
 * [ResourceBudget]'s own doc comment).
 */
enum class ResourceKind { LOCAL_LLM, EMBEDDING, WHISPER, VOSK, AICORE_RESERVE }

/**
 * A cross-engine RAM ledger for exactly one problem the OS's own free-RAM
 * reading cannot solve on its own: two engines starting to ramp up memory
 * at nearly the same instant, where at least one of them (AICore's own
 * system-service process) is invisible to this app's own free-RAM reading
 * entirely. Real device crash this exists to stop
 * ([ai.localstudio.app.AppContainer.makeRoomForAicore]'s own doc comment):
 * a local llama.cpp load's own budget check read free RAM, saw enough,
 * and started — at the same moment AICore's `generate()` was independently
 * ramping up its own memory nobody in this process could see coming. Both
 * together exceeded what was actually free; the process was killed.
 *
 * This does not replace [ai.localstudio.core.runtime.RuntimeManager]'s own
 * precise, measured accounting of the local LLM's resident bytes — a
 * [reserve] here for [ResourceKind.LOCAL_LLM] is redundant with that (both
 * already show up in a fresh free-RAM reading once the model is actually
 * loaded) and is not this ledger's point. What it is for: a caller about to
 * start something whose real cost either isn't measurable at all
 * (AICore) or hasn't shown up in the OS's own free-RAM number *yet*
 * (a load that just started, mid-ramp-up) can [reserve] its expected
 * footprint first, so a second caller's [canRun] a moment later — checked
 * against a free-RAM reading that still doesn't reflect the first caller's
 * in-flight cost — sees it anyway, and [release] once the real cost is
 * either measured (folds into the next free-RAM reading) or the engine
 * shut back down.
 *
 * Thread-safe: [reserve]/[release]/[canRun] all read/write the same shared
 * map and are meant to be called from whichever coroutine happens to be
 * starting or stopping each engine, with no coordination between callers
 * beyond this class itself.
 */
class ResourceBudget {
    private val reserved = ConcurrentHashMap<ResourceKind, Long>()

    /** Marks [kind] as currently costing [bytes] — replaces any previous reservation for the same kind. */
    fun reserve(kind: ResourceKind, bytes: Long) {
        reserved[kind] = bytes
    }

    fun release(kind: ResourceKind) {
        reserved.remove(kind)
    }

    fun reservedBytes(kind: ResourceKind): Long = reserved[kind] ?: 0L

    val totalReservedBytes: Long get() = reserved.values.sum()

    /**
     * Whether [kind] can start now, needing [requiredBytes], given
     * [freeBytesNow] (a fresh OS-level free-RAM reading). Every *other*
     * kind's own reservation is subtracted first — the whole point of this
     * ledger is accounting for a cost [freeBytesNow] does not yet reflect —
     * but [kind]'s own existing reservation (if this is a re-check of the
     * same engine that already reserved) is not: it isn't a second,
     * additional cost on top of itself.
     */
    fun canRun(kind: ResourceKind, requiredBytes: Long, freeBytesNow: Long): Boolean {
        val reservedByOthers = reserved.entries.filter { it.key != kind }.sumOf { it.value }
        return requiredBytes <= freeBytesNow - reservedByOthers
    }
}

/** What [decideAicoreRoom] found — see its own doc comment. */
enum class AicoreRoomDecision {
    /** Nothing local is resident; AICore has the field to itself regardless of free RAM. */
    NO_LOCAL_MODEL,

    /** Free RAM already clears the reserve; keep whatever local model(s) are resident. */
    SUFFICIENT_HEADROOM,

    /** Free RAM is under the reserve; evict idle local model(s) before AICore generates. */
    EVICT_IDLE,
}

/**
 * The pure decision [ai.localstudio.app.AppContainer.makeRoomForAicore]
 * makes before letting AICore generate — extracted so it's tested without an
 * Android [android.content.Context] or a real [ai.localstudio.core.runtime.RuntimeManager],
 * and so the same decision is available to [ResourceBudget]-aware callers
 * elsewhere. [residentBytes] is [ai.localstudio.core.runtime.RuntimeManager.residentBytes];
 * [reserveBytes] is a deliberately conservative safety margin (not a real
 * measurement of AICore's own footprint — that's exactly what can't be
 * measured), tuned from real device logs.
 */
fun decideAicoreRoom(freeBytesNow: Long, residentBytes: Long, reserveBytes: Long): AicoreRoomDecision = when {
    residentBytes <= 0L -> AicoreRoomDecision.NO_LOCAL_MODEL
    freeBytesNow >= reserveBytes -> AicoreRoomDecision.SUFFICIENT_HEADROOM
    else -> AicoreRoomDecision.EVICT_IDLE
}
