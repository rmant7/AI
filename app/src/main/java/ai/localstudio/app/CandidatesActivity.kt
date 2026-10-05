package ai.localstudio.app

import ai.localstudio.app.databinding.ActivityCandidatesBinding
import ai.localstudio.app.databinding.ItemFilesHeaderBinding
import ai.localstudio.app.databinding.ItemLocalModelBinding
import ai.localstudio.app.modelinstall.CandidateFacts
import ai.localstudio.app.modelinstall.CandidatePurpose
import ai.localstudio.app.modelinstall.CandidateTrialState
import ai.localstudio.app.modelinstall.DiscoveredCandidate
import ai.localstudio.app.modelinstall.DiscoveryRun
import ai.localstudio.app.models.ModelDownloadService
import ai.localstudio.model.install.CandidateTier
import ai.localstudio.model.install.tier
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The last discovery sweep's candidates, grouped by what the Hub search was
 * for, each with what its own tags say it is for and what this device has
 * actually observed running it. A candidate is UNVERIFIED -- not a
 * recommendation -- until Download & Test here loaded it and got the
 * expected answers. A running test shows its progress on its own card.
 */
class CandidatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCandidatesBinding
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCandidatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        container = AppContainer.get(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Every change of the running test re-renders: its progress,
                // and -- when it turns null -- the verification it just stored.
                container.candidateTrialStatus.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.discoveryStore.markSeen()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render(trial: CandidateTrialState? = container.candidateTrialStatus.value) {
        val list = binding.candidatesList
        list.removeAllViews()
        val runs = container.discoveryStore.runs().sortedBy { if (it.label == "chat") 0 else 1 }
        if (runs.isEmpty()) {
            addHeader(getString(R.string.candidates_none))
            return
        }
        for (run in runs) {
            addHeader(sectionTitle(run))
            run.candidates.forEach { addCard(run.label, it, trial) }
        }
    }

    private fun sectionTitle(run: DiscoveryRun): String {
        val group = getString(if (run.label == "translation") R.string.candidates_group_translation else R.string.candidates_group_chat)
        val count = if (run.failure != null) {
            getString(R.string.candidates_section_failed, run.failure)
        } else {
            getString(R.string.candidates_section_count, run.candidates.size, run.checked)
        }
        return "$group\n$count"
    }

    private fun addHeader(text: String) {
        val header = ItemFilesHeaderBinding.inflate(layoutInflater, binding.candidatesList, false)
        header.root.text = text
        binding.candidatesList.addView(header.root)
    }

    private fun addCard(label: String, c: DiscoveredCandidate, trial: CandidateTrialState?) {
        val card = ItemLocalModelBinding.inflate(layoutInflater, binding.candidatesList, false)
        val facts = CandidateFacts.of(c.tags)
        val testing = trial?.repoId == c.repoId
        val anyTesting = trial != null
        val installedBytes = container.candidateInstalledBytes(c)

        card.localTitle.text = c.repoId.substringAfter('/')
        card.localSubtitle.text = subtitle(c, facts)

        val verification = c.verification
        card.localStatus.text = when {
            testing -> trial!!.describe(this)
            verification == null -> tierLabel(CandidateTier.UNVERIFIED)
            else -> buildString {
                append(tierLabel(verification.tier()))
                verification.tokensPerSecond?.let { append(String.format(Locale.ROOT, " · %.1f tok/s", it)) }
                verification.error?.let { append("\n").append(it) }
            }
        }

        card.localProgress.visibility = if (testing) View.VISIBLE else View.GONE
        if (testing) {
            val percent = trial!!.percent
            card.localProgress.isIndeterminate = percent == null
            if (percent != null) card.localProgress.progress = percent
        }

        card.localPrimaryButton.text = getString(if (verification == null) R.string.candidate_test else R.string.candidate_retest)
        card.localPrimaryButton.isEnabled = !anyTesting
        card.localPrimaryButton.setOnClickListener { testCandidate(label, c) }

        if (installedBytes != null) {
            card.localSecondaryButton.visibility = View.VISIBLE
            card.localSecondaryButton.text = getString(R.string.candidate_delete, (installedBytes / 1_000_000).toInt())
            card.localSecondaryButton.isEnabled = !anyTesting
            card.localSecondaryButton.setOnClickListener { deleteInstall(c) }
        } else {
            card.localSecondaryButton.visibility = View.GONE
        }

        card.root.setOnClickListener { showDetails(c) }
        binding.candidatesList.addView(card.root)
    }

    private fun subtitle(c: DiscoveredCandidate, facts: CandidateFacts): String = buildString {
        append(c.repoId.substringBefore('/'))
        append(" · ").append(c.sizeBytes / 1_000_000).append(" MB")
        append(" · ").append(c.architecture)
        c.contextLength?.let { append(" · ").append(getString(R.string.candidate_context, it.toString())) }
        append(" · ").append(getString(R.string.candidate_downloads, compact(c.downloads)))
        append("\n")
        if (c.tags.isEmpty()) {
            append(getString(R.string.candidate_no_tags))
        } else {
            val purposes = facts.purposes.joinToString(", ") { purposeLabel(it) }
            append(getString(R.string.candidate_purpose, purposes.ifEmpty { getString(R.string.candidate_purpose_none) }))
            if (facts.baseModels.isNotEmpty()) append("\n").append(getString(R.string.candidate_base_model, facts.baseModels.joinToString(", ")))
            val extras = listOfNotNull(
                facts.languages.takeIf { it.isNotEmpty() }?.let { getString(R.string.candidate_languages, it.joinToString(", ")) },
                facts.license?.let { getString(R.string.candidate_license, it) },
            )
            if (extras.isNotEmpty()) append("\n").append(extras.joinToString(" · "))
        }
    }

    private fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1e6)
        n >= 1_000 -> String.format(Locale.ROOT, "%.0fK", n / 1e3)
        else -> n.toString()
    }

    private fun purposeLabel(p: CandidatePurpose): String = getString(
        when (p) {
            CandidatePurpose.CHAT -> R.string.purpose_chat
            CandidatePurpose.CODE -> R.string.purpose_code
            CandidatePurpose.MATH -> R.string.purpose_math
            CandidatePurpose.REASONING -> R.string.purpose_reasoning
            CandidatePurpose.TRANSLATION -> R.string.purpose_translation
            CandidatePurpose.TOOLS -> R.string.purpose_tools
            CandidatePurpose.ROLEPLAY -> R.string.purpose_roleplay
            CandidatePurpose.UNCENSORED -> R.string.purpose_uncensored
            CandidatePurpose.VISION -> R.string.purpose_vision
            CandidatePurpose.MEDICAL -> R.string.purpose_medical
        },
    )

    private fun tierLabel(tier: CandidateTier): String = getString(
        when (tier) {
            CandidateTier.UNVERIFIED -> R.string.candidate_tier_unverified
            CandidateTier.LOADABLE -> R.string.candidate_tier_loadable
            CandidateTier.FUNCTIONAL -> R.string.candidate_tier_functional
        },
    )

    private fun showDetails(c: DiscoveredCandidate) {
        val dash = "—"
        val details = buildString {
            append(
                getString(
                    R.string.candidate_details,
                    c.repoId, c.filePath, (c.sizeBytes / 1_000_000).toInt(), c.architecture,
                    c.contextLength?.toString() ?: dash, c.commit.take(12), tierLabel(c.verification.tier()),
                ),
            )
            c.notes.forEach { append("\n• ").append(it) }
            c.verification?.let { v ->
                append("\n\n")
                append(
                    getString(
                        R.string.candidate_verification,
                        v.deviceProfile,
                        v.loaded.toString(),
                        v.inferenceOk.toString(),
                        v.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f tok/s", it) } ?: dash,
                        v.sampleOutput ?: dash,
                        v.error ?: dash,
                    ),
                )
            }
            if (c.tags.isNotEmpty()) append("\n\n").append(getString(R.string.candidate_all_tags, c.tags.joinToString(", ")))
        }
        AlertDialog.Builder(this)
            .setTitle(c.repoId)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun testCandidate(label: String, c: DiscoveredCandidate) {
        NetworkPolicy.confirmIfNeeded(this, container.settings) {
            ModelDownloadService.ensureStarted(this)
            val started = container.startCandidateTrial(label, c)
            Toast.makeText(
                this,
                if (started) getString(R.string.candidate_test_started, c.repoId) else getString(R.string.candidate_test_busy),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun deleteInstall(c: DiscoveredCandidate) {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { container.deleteCandidateInstall(c) }
            Toast.makeText(
                this@CandidatesActivity,
                if (deleted) R.string.candidate_deleted else R.string.candidate_delete_failed,
                Toast.LENGTH_SHORT,
            ).show()
            render()
        }
    }
}
