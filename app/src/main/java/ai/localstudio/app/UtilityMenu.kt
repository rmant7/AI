package ai.localstudio.app

import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity

/**
 * The same "jump to another utility screen" menu on every secondary
 * screen (Models, Files, Settings, History, Log, Transcribe, Benchmark) —
 * not just [ChatActivity]. Real device report: reaching Benchmark from Log
 * meant navigating back to Chat first, just to switch between two
 * screens neither of which is chat, and re-triggering whatever Chat's own
 * `onCreate`/`onResume` touches along the way (its own periodic embedder
 * reload showed up mid-benchmark-run in that same report). Each of these
 * Activities calls [inflate] from its own `onCreateOptionsMenu` and
 * [handle] from `onOptionsItemSelected`, so navigating between any two of
 * them is one tap, not a round trip through Chat.
 *
 * Deliberately not [ChatActivity] itself — that screen's own menu already
 * carries every one of these entries plus chat-specific ones (Memory,
 * Share, Clear), and mixing this object into it would just mean the same
 * item added twice.
 */
object UtilityMenu {
    private data class Entry(val id: Int, val titleRes: Int, val activityClass: Class<out AppCompatActivity>)

    // IDs start well above anything a target screen might ever add for its
    // own toolbar — none of these Activities has any options menu of its
    // own today, but this keeps a future one from colliding by accident.
    private val ENTRIES = listOf(
        Entry(9001, R.string.menu_models, ModelsActivity::class.java),
        Entry(9002, R.string.menu_files, FilesActivity::class.java),
        Entry(9003, R.string.menu_settings, SettingsActivity::class.java),
        Entry(9004, R.string.menu_history, HistoryActivity::class.java),
        Entry(9005, R.string.menu_log, LogActivity::class.java),
        Entry(9006, R.string.menu_transcribe, TranscribeActivity::class.java),
        Entry(9007, R.string.menu_benchmark, BenchmarkActivity::class.java),
    )

    /** Adds every entry except the one for [activity]'s own screen — no point offering "go to where you already are." */
    fun inflate(activity: AppCompatActivity, menu: Menu) {
        ENTRIES.filter { it.activityClass != activity::class.java }
            .forEach { entry -> menu.add(0, entry.id, 0, entry.titleRes) }
    }

    /** Returns true (and navigates) if [itemId] is one of this menu's own entries; false otherwise, so callers can fall through to their own item handling. */
    fun handle(activity: AppCompatActivity, itemId: Int): Boolean {
        val entry = ENTRIES.firstOrNull { it.id == itemId } ?: return false
        activity.startActivity(Intent(activity, entry.activityClass))
        return true
    }
}
