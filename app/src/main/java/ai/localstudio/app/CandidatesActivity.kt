package ai.localstudio.app

import ai.localstudio.app.databinding.ActivityCandidatesBinding
import ai.localstudio.app.databinding.ItemFilesHeaderBinding
import ai.localstudio.app.databinding.ItemLocalModelBinding
import ai.localstudio.app.modelinstall.CandidateFacts
import ai.localstudio.app.modelinstall.CandidatePurpose
import ai.localstudio.app.modelinstall.CandidateWork
import ai.localstudio.app.modelinstall.DiscoveredCandidate
import ai.localstudio.app.modelinstall.DiscoveryLabels
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
                container.candidateWork.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.discoveryStore.markSeen()
        // Which model is selected may have changed on another screen.
        render()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(android.view.Menu.NONE, MENU_DISCOVER, android.view.Menu.NONE, R.string.discover_menu)
        UtilityMenu.inflate(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_DISCOVER) {
            DiscoveryLauncher.start(this)
            return true
        }
        return UtilityMenu.handle(this, item.itemId) || super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** The list as last built: rebuilt only when the stored runs change, never on a progress tick (see [render]). */
    private var shownRuns: List<DiscoveryRun>? = null
    private val cards = mutableListOf<Triple<String, DiscoveredCandidate, ItemLocalModelBinding>>()

    /**
     * Progress ticks arrive several times a second during a download, and a
     * tap is a press and a release on the same view: rebuilding the cards on
     * every tick (as the first version did) swapped the button out between
     * the two, so Pause "did nothing" on a real device even when tapped
     * twice. Cards are now built once per change of the stored runs and only
     * re-bound -- text, progress, buttons -- in place after that.
     */
    private fun render(work: CandidateWork = container.candidateWork.value) {
        // Chat families first, then translation, each in Lineages.ALL order; runs from before lineages last.
        val order = ai.localstudio.model.install.Lineages.ALL.map { DiscoveryLabels.of(it) }
        val runs = container.discoveryStore.runs().sortedWith(
            compareBy<DiscoveryRun> { DiscoveryLabels.isTranslation(it.label) }
                .thenBy { order.indexOf(it.label).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE },
        )
        if (runs != shownRuns) {
            shownRuns = runs
            cards.clear()
            binding.candidatesList.removeAllViews()
            if (runs.isEmpty()) addHeader(getString(R.string.candidates_none))
            for (run in runs) {
                addHeader(sectionTitle(run))
                run.candidates.forEach { c ->
                    val card = ItemLocalModelBinding.inflate(layoutInflater, binding.candidatesList, false)
                    card.root.setOnClickListener { showDetails(run.label, c) }
                    binding.candidatesList.addView(card.root)
                    cards += Triple(run.label, c, card)
                }
            }
        }
        cards.forEach { (label, c, card) -> bind(card, label, c, work) }
    }

    private fun sectionTitle(run: DiscoveryRun): String {
        val purpose = getString(if (DiscoveryLabels.isTranslation(run.label)) R.string.candidates_purpose_translation else R.string.candidates_purpose_chat)
        val group = DiscoveryLabels.lineage(run.label)?.let { "${it.displayName} · $purpose" }
            ?: getString(if (DiscoveryLabels.isTranslation(run.label)) R.string.candidates_group_translation else R.string.candidates_group_chat)
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

    private fun bind(card: ItemLocalModelBinding, label: String, c: DiscoveredCandidate, work: CandidateWork) {
        val facts = CandidateFacts.of(c.tags)
        val name = c.repoId
        val download = work.downloads[name]
        val trial = work.trial?.takeIf { it.repoId == name }
        val queued = name in work.queued
        val usage = if (download == null) container.candidateUsage(label, c) else CandidateUsage.NONE
        val inUse = usage != CandidateUsage.NONE
        val translation = DiscoveryLabels.isTranslation(label)
        val installedBytes = if (download == null) container.candidateInstalledBytes(c) else null
        val partialBytes = if (download == null && installedBytes == null) container.candidatePartialBytes(c) else null

        card.localTitle.text = name.substringAfter('/') + if (container.discoveryStore.isNew(c)) "  · " + getString(R.string.candidate_new) else ""
        card.localSubtitle.text = subtitle(c, facts)

        val verification = c.verification
        val result = if (verification == null) {
            tierLabel(CandidateTier.UNVERIFIED)
        } else {
            buildString {
                append(tierLabel(verification.tier()))
                verification.tokensPerSecond?.let { append(String.format(Locale.ROOT, " · %.1f tok/s", it)) }
                verification.error?.let { append("\n").append(it) }
            }
        }
        val mb = { bytes: Long -> (bytes / 1_000_000).toInt() }
        card.localStatus.text = when {
            download != null -> getString(R.string.candidate_downloading, mb(download.bytesDone), mb(download.bytesTotal))
            trial != null -> trial.describe(this)
            queued -> getString(R.string.candidate_queued)
            else -> listOfNotNull(
                when (usage) {
                    CandidateUsage.SELECTED -> getString(if (translation) R.string.candidate_selected_translation else R.string.candidate_selected_chat)
                    CandidateUsage.IN_MODELS -> getString(if (translation) R.string.candidate_in_use_translation else R.string.candidate_in_use_chat)
                    CandidateUsage.NONE -> null
                },
                partialBytes?.let { getString(R.string.candidate_paused, mb(it), mb(c.sizeBytes)) },
                result,
                work.failures[name]?.let { getString(R.string.candidate_download_failed, it) },
            ).joinToString("\n")
        }

        val percent = download?.percent ?: partialBytes?.let { (it * 100 / c.sizeBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100) }
        card.localProgress.visibility = if (download != null || trial != null || partialBytes != null) View.VISIBLE else View.GONE
        card.localProgress.isIndeterminate = percent == null
        if (percent != null) card.localProgress.progress = percent

        val primary = card.localPrimaryButton
        val secondary = card.localSecondaryButton
        secondary.visibility = View.GONE
        primary.isEnabled = true
        when {
            download != null -> {
                primary.text = getString(R.string.model_pause)
                primary.setOnClickListener { container.pauseCandidateDownload(c) }
            }
            trial != null || queued -> {
                primary.text = getString(R.string.candidate_test)
                primary.isEnabled = false
                primary.setOnClickListener(null)
            }
            usage == CandidateUsage.SELECTED -> {
                primary.text = getString(if (translation) R.string.candidate_open_translation else R.string.candidate_open_chat)
                primary.setOnClickListener { openWhereUsed(translation) }
            }
            inUse -> {
                primary.text = getString(if (translation) R.string.candidate_use_translation else R.string.candidate_use_chat)
                primary.setOnClickListener { use(label, c) }
            }
            installedBytes != null && verification.tier() == CandidateTier.FUNCTIONAL -> {
                primary.text = getString(if (translation) R.string.candidate_use_translation else R.string.candidate_use_chat)
                primary.setOnClickListener { use(label, c) }
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.candidate_delete, mb(installedBytes))
                secondary.setOnClickListener { deleteInstall(c) }
            }
            installedBytes != null -> {
                primary.text = getString(if (verification == null) R.string.candidate_test else R.string.candidate_retest)
                primary.setOnClickListener { test(label, c) }
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.candidate_delete, mb(installedBytes))
                secondary.setOnClickListener { deleteInstall(c) }
            }
            partialBytes != null -> {
                primary.text = getString(R.string.model_resume)
                primary.setOnClickListener { download(label, c) }
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.candidate_delete, mb(partialBytes))
                secondary.setOnClickListener { deleteInstall(c) }
            }
            else -> {
                primary.text = getString(R.string.candidate_download, mb(c.sizeBytes))
                primary.setOnClickListener { download(label, c) }
            }
        }
    }

    private fun subtitle(c: DiscoveredCandidate, facts: CandidateFacts): String = buildString {
        append(c.repoId.substringBefore('/'))
        append(" · ").append(c.sizeBytes / 1_000_000).append(" MB")
        append(" · ").append(c.architecture)
        c.contextLength?.let { append(" · ").append(getString(R.string.candidate_context, it.toString())) }
        append(" · ").append(getString(R.string.candidate_downloads, compact(c.downloads)))
        c.createdAt?.take(10)?.let { append(" · ").append(getString(R.string.candidate_created, it)) }
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

    private fun showDetails(label: String, c: DiscoveredCandidate) {
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
        val canRetest = container.candidateInstalledBytes(c) != null && !container.candidateWork.value.isBusy(c.repoId)
        AlertDialog.Builder(this)
            .setTitle(c.repoId)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .apply { if (canRetest) setNeutralButton(R.string.candidate_retest) { _, _ -> test(label, c) } }
            .show()
    }

    private fun download(label: String, c: DiscoveredCandidate) {
        NetworkPolicy.confirmIfNeeded(this, container.settings) {
            ModelDownloadService.ensureStarted(this)
            if (!container.downloadCandidate(label, c)) Toast.makeText(this, R.string.candidate_busy, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * "Use", with where the model went said in full -- a short toast was
     * gone before it was read, and a translation model ending up under
     * Translation (not Chat) was a surprise nothing had announced.
     */
    private fun use(label: String, c: DiscoveredCandidate) {
        val seed = container.useCandidate(label, c)
        render()
        if (seed == null) {
            Toast.makeText(this, R.string.candidate_use_failed, Toast.LENGTH_LONG).show()
            return
        }
        val translation = DiscoveryLabels.isTranslation(label)
        AlertDialog.Builder(this)
            .setTitle(getString(if (translation) R.string.candidate_used_title_translation else R.string.candidate_used_title_chat, seed.title))
            .setMessage(if (translation) R.string.candidate_used_translation else R.string.candidate_used_chat)
            .setPositiveButton(if (translation) R.string.candidate_open_translation else R.string.candidate_open_chat) { _, _ -> openWhereUsed(translation) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun openWhereUsed(translation: Boolean) {
        val target = if (translation) TranslationActivity::class.java else ChatActivity::class.java
        startActivity(android.content.Intent(this, target).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun test(label: String, c: DiscoveredCandidate) {
        ModelDownloadService.ensureStarted(this)
        if (!container.testCandidate(label, c)) Toast.makeText(this, R.string.candidate_busy, Toast.LENGTH_SHORT).show()
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

    private companion object {
        const val MENU_DISCOVER = 9100
    }
}
