package ai.localstudio.app

import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity

/**
 * The same "jump to another utility screen" menu on every screen of the
 * app (Models, Settings, History, Log, Transcribe, Benchmark, Memory, API
 * keys, ...) — not just
 * [ChatActivity]. Real device report: reaching Benchmark from Log meant
 * navigating back to Chat first, just to switch between two screens
 * neither of which is chat, and re-triggering whatever Chat's own
 * `onCreate`/`onResume` touches along the way (its own periodic embedder
 * reload showed up mid-benchmark-run in that same report). Each of these
 * Activities calls [inflate] from its own `onCreateOptionsMenu` and
 * [handle] from `onOptionsItemSelected`, so navigating between any two of
 * them is one tap, not a round trip through Chat.
 *
 * [ChatActivity] uses this too, alongside its own chat-specific items
 * (Memory, Files, Share, Clear, and a History item of its own — see
 * [inflate]'s `skip` parameter). [FilesActivity] is deliberately NOT in
 * [ENTRIES]: real user feedback — it lists a conversation's own attached
 * documents (see its own doc comment), so offering it from Models, Log or
 * any other screen with no conversation in view read as a general file
 * manager it isn't. [ChatActivity] adds it directly, the same way it
 * already added Memory/Share/Clear. Chat history is left out for the same
 * reason — it is chat's own, and [ChatActivity] already adds its own
 * History item (which needs a result back, see [inflate]).
 *
 * [MORE_ENTRIES] (Discovered candidates, Phrasebook, Benchmark) fold into one "More" submenu
 * instead of two more flat rows — both are occasional, task-specific
 * screens (a fixed phrase list; a one-off perf measurement), unlike the
 * six above them a user actually switches between while working. Still
 * driven by the exact same [inflate]/[handle] pair: a submenu item posts
 * the same `onOptionsItemSelected` id as a flat one, so [handle] doesn't
 * need to know which of the two produced it.
 */
object UtilityMenu {
    private data class Entry(val id: Int, val titleRes: Int, val activityClass: Class<out AppCompatActivity>)

    // IDs start well above anything a target screen might ever add for its
    // own toolbar — none of these Activities has any options menu of its
    // own today, but this keeps a future one from colliding by accident.
    //
    // Chat itself is an entry here too, first in the list — real device
    // report: every *other* screen could jump straight to any other, but
    // none of them could jump back to the actual ongoing conversation
    // without the system Back button, since [inflate] already excludes
    // whichever screen is showing it, and Chat had never been in this list
    // at all to be excluded from. [inflate]'s own `activity::class.java`
    // check still keeps it out of [ChatActivity]'s own menu the same way
    // every other entry stays out of its own screen's menu.
    private val ENTRIES = listOf(
        Entry(9000, R.string.menu_chat, ChatActivity::class.java),
        Entry(9001, R.string.menu_models, ModelsActivity::class.java),
        Entry(9003, R.string.menu_settings, SettingsActivity::class.java),
        Entry(9006, R.string.menu_transcribe, TranscribeActivity::class.java),
        Entry(9008, R.string.menu_translation, TranslationActivity::class.java),
    )

    /** Folded into the "More" submenu — see this object's own doc comment. */
    private val MORE_ENTRIES = listOf(
        Entry(9010, R.string.candidates_menu, CandidatesActivity::class.java),
        Entry(9009, R.string.menu_phrasebook, PhrasebookActivity::class.java),
        Entry(9007, R.string.menu_benchmark, BenchmarkActivity::class.java),
    )

    // Explicit menu order, not insertion order: a screen's own items (added
    // with the default order 0, before or after [inflate]) always come
    // first, then these, and Log is always the very last row. Insertion
    // order alone let a screen break that -- Models added its discovery
    // items after [inflate] and Log ended up in the middle.
    private const val ORDER_SHARED = 100
    private const val ORDER_MORE = 900
    private const val ORDER_LOG = 1000

    // Log stays its own last row, not folded into "More" — an error log is
    // exactly what someone reaches for right after something went wrong,
    // not something to go hunting for inside a submenu. Added after
    // everything else in [inflate] so it stays last regardless of how
    // [ENTRIES]/[MORE_ENTRIES] themselves are ordered or resized.
    private val LOG_ENTRY = Entry(9005, R.string.menu_log, LogActivity::class.java)

    private val ALL_ENTRIES = ENTRIES + MORE_ENTRIES + LOG_ENTRY

    /**
     * Adds every entry except the one for [activity]'s own screen (no point
     * offering "go to where you already are") and any in [skip] — for
     * [ChatActivity]'s History item specifically, which needs a result back
     * (which conversation got picked, or deleted) that a plain
     * [handle]-driven `startActivity` has no way to deliver. [ChatActivity]
     * adds its own History item instead, so it stays in the same menu
     * without the entry this function would otherwise add colliding with it.
     */
    fun inflate(activity: AppCompatActivity, menu: Menu, skip: Set<Class<out AppCompatActivity>> = emptySet()) {
        ENTRIES.filter { it.activityClass != activity::class.java && it.activityClass !in skip }
            .forEachIndexed { i, entry -> menu.add(0, entry.id, ORDER_SHARED + i, entry.titleRes) }

        val more = MORE_ENTRIES.filter { it.activityClass != activity::class.java && it.activityClass !in skip }
        if (more.isNotEmpty()) {
            val subMenu = menu.addSubMenu(0, Menu.NONE, ORDER_MORE, R.string.menu_more)
            more.forEach { entry -> subMenu.add(0, entry.id, 0, entry.titleRes) }
        }

        if (LOG_ENTRY.activityClass != activity::class.java && LOG_ENTRY.activityClass !in skip) {
            menu.add(0, LOG_ENTRY.id, ORDER_LOG, LOG_ENTRY.titleRes)
        }
    }

    /**
     * Returns true (and navigates) if [itemId] is one of this menu's own
     * entries; false otherwise, so callers can fall through to their own
     * item handling.
     *
     * `CLEAR_TOP or SINGLE_TOP`, not a plain `startActivity` — real device
     * report: ping-ponging between two of these screens (e.g. Benchmark ->
     * Log -> Benchmark) kept creating a brand new instance of the target
     * screen on every hop instead of returning to the one already on the
     * back stack. Beyond piling up dead Activity instances, a fresh
     * [BenchmarkActivity] instance's own views start from their XML
     * defaults (its mode picker showing "Maximum") with no idea a benchmark
     * was already running in a different mode in the background — which
     * read as the run having silently changed mode and led to an
     * accidental Stop. `CLEAR_TOP` pops back to an existing instance
     * already in the stack instead of stacking a new one on top; `SINGLE_TOP`
     * is what keeps that existing instance from being torn down and
     * recreated in the process — together they make "go to X" actually mean
     * "return to X" whenever X is already open, for every one of these
     * screens uniformly, not just this one.
     */
    fun handle(activity: AppCompatActivity, itemId: Int): Boolean {
        val entry = ALL_ENTRIES.firstOrNull { it.id == itemId } ?: return false
        val intent = Intent(activity, entry.activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        activity.startActivity(intent)
        return true
    }
}
