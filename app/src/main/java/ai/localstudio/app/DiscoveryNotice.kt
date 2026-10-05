package ai.localstudio.app

import ai.localstudio.app.modelinstall.DiscoveredCandidate
import ai.localstudio.app.modelinstall.DiscoveryLabels
import ai.localstudio.model.install.CandidateTier
import ai.localstudio.model.install.tier
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Tells the person what discovery found -- new models from a sweep (the
 * weekly one included), and how models finished their test on this phone --
 * once, in a dialog that says what each model is for, how big it is and
 * when it was published, with the way to the list. Never a recommendation:
 * new models are stated as not yet tested.
 */
object DiscoveryNotice {

    /** Shows the notice whenever discovery and candidate tests go idle with something unseen, while [activity] is started. */
    fun watch(activity: AppCompatActivity) {
        val container = AppContainer.get(activity)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(container.discoveryRunning, container.candidateWork) { running, work -> !running && work.trial == null && work.queued.isEmpty() }
                    .distinctUntilChanged()
                    .collect { idle -> if (idle) showIfUnseen(activity) }
            }
        }
    }

    fun showIfUnseen(activity: AppCompatActivity) {
        val store = AppContainer.get(activity).discoveryStore
        if (!store.hasUnseen()) return
        val news = store.news()
        store.markSeen()
        if (news.newCandidates.isEmpty() && news.tested.isEmpty()) {
            if (news.sweepFinished) Toast.makeText(activity, R.string.notice_nothing_new, Toast.LENGTH_SHORT).show()
            return
        }
        val text = buildString {
            if (news.newCandidates.isNotEmpty()) {
                append(activity.getString(R.string.notice_new_header, news.newCandidates.size))
                news.newCandidates.forEach { (label, c) -> append("\n• ").append(describe(activity, label, c)) }
                append("\n\n").append(activity.getString(R.string.notice_new_untested))
            }
            if (news.tested.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(activity.getString(R.string.notice_tested_header))
                news.tested.forEach { (_, c) -> append("\n• ").append(testedLine(activity, c)) }
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(if (news.newCandidates.isNotEmpty()) R.string.notice_title_new else R.string.notice_title_tested)
            .setMessage(text)
            .setPositiveButton(R.string.notice_open_list) { _, _ ->
                activity.startActivity(
                    Intent(activity, CandidatesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            .setNegativeButton(R.string.notice_later, null)
            .show()
    }

    private fun describe(activity: AppCompatActivity, label: String, c: DiscoveredCandidate): String {
        val purpose = activity.getString(if (DiscoveryLabels.isTranslation(label)) R.string.notice_for_translation else R.string.notice_for_chat)
        val family = DiscoveryLabels.lineage(label)?.displayName
        val size = String.format(Locale.ROOT, "%.1f GB", c.sizeBytes / 1e9)
        return listOfNotNull(
            c.repoId.substringAfter('/'),
            family,
            purpose,
            size,
            c.createdAt?.take(10)?.let { activity.getString(R.string.candidate_created, it) },
        ).joinToString(" · ")
    }

    private fun testedLine(activity: AppCompatActivity, c: DiscoveredCandidate): String {
        val v = c.verification ?: return c.repoId
        val name = c.repoId.substringAfter('/')
        return when (v.tier()) {
            CandidateTier.FUNCTIONAL -> activity.getString(
                R.string.notice_tested_ok,
                name,
                v.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "?",
            )
            else -> activity.getString(R.string.notice_tested_failed, name, v.error?.take(120) ?: "")
        }
    }
}
