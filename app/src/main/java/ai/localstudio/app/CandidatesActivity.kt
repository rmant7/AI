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
import ai.localstudio.model.install.CheckStatus
import ai.localstudio.model.install.VerifiedCapability
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
        val verification = c.verification
        // What a model is offered for comes from what passed on this phone, not from which search found it.
        val usage = USABLE.associateWith { if (download == null) container.candidateUsage(it, c) else CandidateUsage.NONE }
        val passed = verification?.passed.orEmpty().filter { it in USABLE }
        val actions = USABLE.filter { it in passed || usage.getValue(it) != CandidateUsage.NONE }
        val testable = download == null && container.candidateTestable(c)
        val installedBytes = if (download == null) container.candidateInstalledBytes(c) else null
        val partialBytes = if (download == null && installedBytes == null && !testable) container.candidatePartialBytes(c) else null

        card.localTitle.text = name.substringAfter('/') + if (container.discoveryStore.isNew(c)) "  · " + getString(R.string.candidate_new) else ""
        card.localSubtitle.text = subtitle(c, facts)

        val mb = { bytes: Long -> (bytes / 1_000_000).toInt() }
        card.localStatus.text = when {
            download != null -> getString(R.string.candidate_downloading, mb(download.bytesDone), mb(download.bytesTotal))
            trial != null -> trial.describe(this)
            queued -> getString(R.string.candidate_queued)
            else -> listOfNotNull(
                *USABLE.mapNotNull { cap ->
                    when (usage.getValue(cap)) {
                        CandidateUsage.SELECTED -> getString(if (cap == VerifiedCapability.TRANSLATION) R.string.candidate_selected_translation else R.string.candidate_selected_chat)
                        CandidateUsage.IN_MODELS -> getString(if (cap == VerifiedCapability.TRANSLATION) R.string.candidate_in_use_translation else R.string.candidate_in_use_chat)
                        CandidateUsage.NONE -> null
                    }
                }.toTypedArray(),
                partialBytes?.let { getString(R.string.candidate_paused, mb(it), mb(c.totalBytes)) },
                checksSummary(verification),
                work.failures[name]?.let { getString(R.string.candidate_download_failed, it) },
            ).joinToString("\n")
        }

        val percent = download?.percent ?: partialBytes?.let { (it * 100 / c.totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100) }
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
            actions.isNotEmpty() -> {
                bindAction(primary, actions[0], usage.getValue(actions[0]), c)
                when {
                    actions.size > 1 -> {
                        secondary.visibility = View.VISIBLE
                        bindAction(secondary, actions[1], usage.getValue(actions[1]), c)
                    }
                    installedBytes != null -> {
                        secondary.visibility = View.VISIBLE
                        secondary.text = getString(R.string.candidate_delete, mb(installedBytes))
                        secondary.setOnClickListener { deleteInstall(c) }
                    }
                }
            }
            testable -> {
                primary.text = getString(if (verification == null) R.string.candidate_test else R.string.candidate_retest)
                primary.setOnClickListener { test(label, c) }
                if (installedBytes != null) {
                    secondary.visibility = View.VISIBLE
                    secondary.text = getString(R.string.candidate_delete, mb(installedBytes))
                    secondary.setOnClickListener { deleteInstall(c) }
                }
            }
            partialBytes != null -> {
                primary.text = getString(R.string.model_resume)
                primary.setOnClickListener { download(label, c) }
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.candidate_delete, mb(partialBytes))
                secondary.setOnClickListener { deleteInstall(c) }
            }
            else -> {
                primary.text = getString(R.string.candidate_download, mb(c.totalBytes))
                primary.setOnClickListener { download(label, c) }
            }
        }
    }

    /** "Open chat" when the model already answers there, otherwise "Use in chat" / "Use for translation". */
    private fun bindAction(button: android.widget.Button, capability: String, usage: CandidateUsage, c: DiscoveredCandidate) {
        val translation = capability == VerifiedCapability.TRANSLATION
        if (usage == CandidateUsage.SELECTED) {
            button.text = getString(if (translation) R.string.candidate_open_translation else R.string.candidate_open_chat)
            button.setOnClickListener { openWhereUsed(translation) }
        } else {
            button.text = getString(if (translation) R.string.candidate_use_translation else R.string.candidate_use_chat)
            button.setOnClickListener { use(capability, c) }
        }
    }

    /** One line per checked capability ("Chat: works · Translation: did not pass") plus why, speed, or a call to test again. */
    private fun checksSummary(v: ai.localstudio.model.install.DeviceVerification?): String {
        if (v == null) return tierLabel(CandidateTier.UNVERIFIED)
        if (v.checkVersion < ai.localstudio.model.install.DeviceVerification.CURRENT_CHECK) {
            return getString(if (v.loaded) R.string.candidate_old_check else R.string.candidate_tier_unverified) +
                (v.error?.let { "\n$it" } ?: "")
        }
        if (!v.loaded) return tierLabel(CandidateTier.UNVERIFIED) + (v.error?.let { "\n$it" } ?: "")
        val lines = USABLE.map { cap ->
            val what = getString(if (cap == VerifiedCapability.TRANSLATION) R.string.candidate_cap_translation else R.string.candidate_cap_text)
            val status = getString(
                when (v.status(cap)) {
                    CheckStatus.PASS -> R.string.candidate_check_pass
                    CheckStatus.FAIL -> R.string.candidate_check_fail
                    CheckStatus.NOT_TESTED -> R.string.candidate_check_not_tested
                },
            )
            "$what: $status"
        }
        return buildString {
            append(lines.joinToString(" · "))
            v.tokensPerSecond?.let { append(String.format(Locale.ROOT, " · %.1f tok/s", it)) }
            v.checks.filterValues { it.status == CheckStatus.FAIL }.forEach { (cap, check) ->
                append("\n").append(cap).append(": ").append(check.detail?.take(160) ?: "")
            }
        }
    }

    private fun subtitle(c: DiscoveredCandidate, facts: CandidateFacts): String = buildString {
        append(c.repoId.substringBefore('/'))
        append(" · ").append(c.totalBytes / 1_000_000).append(" MB")
        append(" · ").append(c.architecture)
        c.projector?.let { append(" · ").append(getString(R.string.candidate_vision, it.type)) }
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
            c.projector?.let { p -> append("\n").append(getString(R.string.candidate_projector, p.file.path, (p.file.sizeBytes / 1_000_000).toInt(), p.type)) }
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
            c.verification?.checks?.forEach { (cap, check) ->
                append("\n\n• ").append(cap).append(": ").append(check.status.name)
                check.detail?.let { append(" — ").append(it) }
                check.sample?.let { append("\n  ").append(getString(R.string.candidate_check_said, it)) }
            }
            if (c.tags.isNotEmpty()) append("\n\n").append(getString(R.string.candidate_all_tags, c.tags.joinToString(", ")))
        }
        val busy = container.candidateWork.value.isBusy(c.repoId)
        val canRetest = container.candidateTestable(c) && !busy
        val installedBytes = container.candidateInstalledBytes(c)
        AlertDialog.Builder(this)
            .setTitle(c.repoId)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .apply { if (canRetest) setNeutralButton(R.string.candidate_retest) { _, _ -> test(label, c) } }
            .apply {
                if (installedBytes != null && !busy) {
                    setNegativeButton(getString(R.string.candidate_delete, (installedBytes / 1_000_000).toInt())) { _, _ -> deleteInstall(c) }
                }
            }
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
    private fun use(capability: String, c: DiscoveredCandidate) {
        val seed = container.useCandidate(capability, c)
        render()
        if (seed == null) {
            Toast.makeText(this, R.string.candidate_use_failed, Toast.LENGTH_LONG).show()
            return
        }
        val translation = capability == VerifiedCapability.TRANSLATION
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

        /** The capabilities a model can be put to use for from here; vision joins once it is checked. */
        val USABLE = listOf(VerifiedCapability.TEXT, VerifiedCapability.TRANSLATION)
    }
}
