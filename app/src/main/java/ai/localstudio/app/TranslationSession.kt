package ai.localstudio.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Translation screen's last batch of results, owned by [AppContainer]
 * rather than by [TranslationActivity]. Real device report: going to another
 * screen and back lost every result — they existed only as views inside the
 * Activity, and the translation itself ran in its lifecycleScope, so leaving
 * mid-translation cancelled it as well.
 *
 * The batch runs in [scope] (process-wide) and every change is written to
 * SharedPreferences, so results survive the Activity being destroyed and the
 * process being killed. A result still pending when the process died is
 * dropped on reload — nothing is going to finish it.
 */
class TranslationSession(context: Context) {

    /** [text] null = still waiting for this source. */
    data class Result(val label: String, val text: String?, val hidden: Boolean = false)

    private val prefs = context.getSharedPreferences("translation-session", Context.MODE_PRIVATE)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _results = MutableStateFlow(load())
    val results: StateFlow<List<Result>> = _results

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    @Volatile
    var job: Job? = null

    private var batch = 0

    /** Returns this batch's id for [end] — a cancelled earlier batch finishing late must not clear [busy]. */
    fun begin(labels: List<String>): Int {
        _results.value = labels.map { Result(it, null) }
        _busy.value = true
        save()
        return ++batch
    }

    fun finish(index: Int, text: String) = change(index) { it.copy(text = text) }

    /** For a source that shouldn't show an error at all — see [AppContainer.CompareSource.hideOnFailure]. */
    fun hide(index: Int) = change(index) { it.copy(hidden = true) }

    fun end(batchId: Int) {
        if (batchId == batch) _busy.value = false
    }

    /**
     * Stops waiting for this batch's still-pending sources instead of
     * riding out whichever one is slowest. Real device report: a source
     * that takes 30+ seconds (a local model queued behind another load, a
     * rate-limited cloud call) left every other, already-finished result
     * sitting on screen but blocked starting a *new* translation until that
     * one either answered or hit [ai.localstudio.app.TranslationActivity]'s
     * own timeout — the button is disabled for the whole batch, not
     * per-source, and there was no way to give up on one turn early.
     * Cancelling [job] propagates into `runCompare`'s own per-candidate
     * coroutines and reaches [end] through the `finally` block that always
     * calls it, so [busy] clears the normal way. Whatever already has an
     * answer is untouched; whatever is still `null` gets [placeholderText]
     * instead of hanging on "…" forever with no way to tell it apart from
     * still being in progress.
     */
    fun cancel(placeholderText: String) {
        job?.cancel()
        _results.update { list -> list.map { if (it.text == null) it.copy(text = placeholderText) else it } }
        save()
    }

    private fun change(index: Int, transform: (Result) -> Result) {
        _results.update { list -> list.mapIndexed { i, r -> if (i == index) transform(r) else r } }
        save()
    }

    private fun save() {
        val array = JSONArray()
        _results.value.filter { !it.hidden }.forEach { r ->
            array.put(JSONObject().put("label", r.label).apply { if (r.text != null) put("text", r.text) })
        }
        prefs.edit().putString(KEY_RESULTS, array.toString()).apply()
    }

    private fun load(): List<Result> = runCatching {
        val array = JSONArray(prefs.getString(KEY_RESULTS, null) ?: return emptyList())
        (0 until array.length()).map { array.getJSONObject(it) }
            .filter { it.has("text") }
            .map { Result(it.getString("label"), it.getString("text")) }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_RESULTS = "results"
    }
}
