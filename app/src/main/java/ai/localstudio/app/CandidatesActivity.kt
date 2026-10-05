package ai.localstudio.app

import ai.localstudio.app.databinding.ActivityCandidatesBinding
import ai.localstudio.app.databinding.ItemFilesHeaderBinding
import ai.localstudio.app.databinding.ItemLocalModelBinding
import ai.localstudio.app.modelinstall.CandidateFacts
import ai.localstudio.app.modelinstall.CandidateFilter
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
        filter = loadFilter()
        binding.candidatesSearch.setText(filter.text)
        binding.candidatesSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                // Typing re-filters a beat after the last key, not on every key.
                binding.candidatesSearch.removeCallbacks(applySearch)
                binding.candidatesSearch.postDelayed(applySearch, SEARCH_DELAY_MS)
            }
        })
        buildFilterChips()

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

    /** The list as last built: rebuilt only when the stored runs or the filter change, never on a progress tick (see [render]). */
    private var shownRuns: Pair<List<DiscoveryRun>, CandidateFilter>? = null

    private var filter = CandidateFilter()

    private val applySearch = Runnable { setFilter(filter.copy(text = binding.candidatesSearch.text.toString().trim())) }
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
        if (runs to filter != shownRuns) {
            shownRuns = runs to filter
            cards.clear()
            binding.candidatesList.removeAllViews()
            if (runs.isEmpty()) addHeader(getString(R.string.candidates_none))
            var total = 0
            var shown = 0
            for (run in runs) {
                total += run.candidates.size
                val matching = filter.sorted(run.candidates.filter { c -> matches(run.label, c) }) { it }
                shown += matching.size
                // A filtered-out section is left out whole, unless its search itself failed (worth seeing either way).
                if (matching.isEmpty() && !filter.isDefault && run.failure == null) continue
                addHeader(sectionTitle(run, if (filter.isDefault) null else matching.size))
                matching.forEach { c ->
                    val card = ItemLocalModelBinding.inflate(layoutInflater, binding.candidatesList, false)
                    card.root.setOnClickListener { showDetails(run.label, c) }
                    card.root.setOnLongClickListener {
                        copyDetails(run.label, c)
                        true
                    }
                    binding.candidatesList.addView(card.root)
                    cards += Triple(run.label, c, card)
                }
            }
            binding.candidatesCount.text = getString(R.string.candidates_shown, shown, total)
            binding.candidatesCount.visibility = if (runs.isEmpty()) View.GONE else View.VISIBLE
        }
        cards.forEach { (label, c, card) -> bind(card, label, c, work) }
    }

    private fun matches(label: String, c: DiscoveredCandidate): Boolean {
        val now = container.verificationContext(c)
        val v = c.verification
        return filter.matches(label, c, passesNow = v?.passed(now)?.toSet().orEmpty(), testedNow = v?.isValidFor(now) == true)
    }

    private fun setFilter(new: CandidateFilter) {
        if (new == filter) return
        filter = new
        saveFilter(new)
        buildFilterChips()
        render()
    }

    /** One chip per filter, showing its current value; a tap offers the choices. */
    private fun buildFilterChips() {
        val group = binding.candidatesFilters
        group.removeAllViews()
        fun chip(text: String, onClick: () -> Unit) {
            group.addView(
                com.google.android.material.chip.Chip(this).apply {
                    this.text = text
                    setOnClickListener { onClick() }
                },
            )
        }
        val purposes = listOf(
            CandidateFilter.Purpose.ANY to R.string.candidates_filter_all,
            CandidateFilter.Purpose.CHAT to R.string.candidates_filter_chat,
            CandidateFilter.Purpose.TRANSLATION to R.string.candidates_filter_translation,
            CandidateFilter.Purpose.VISION to R.string.candidates_filter_vision,
        ).map { (value, res) -> value to getString(res) }
        val sizes = listOf<Pair<Long?, String>>(null to getString(R.string.candidates_filter_any)) +
            SIZE_LIMITS_GB.map { gb -> gb * 1_000_000_000L to "≤ $gb GB" }
        val downloads = listOf(0L to getString(R.string.candidates_filter_any)) +
            DOWNLOAD_MINIMUMS.map { n -> n to "≥ ${compact(n)}" }
        val contexts = listOf(0L to getString(R.string.candidates_filter_any)) +
            CONTEXT_MINIMUMS.map { n -> n to "≥ " + if (n >= 1_048_576) "${n / 1_048_576}M" else "${n / 1024}K" }
        val statuses = listOf(
            CandidateFilter.Status.ANY to R.string.candidates_filter_all,
            CandidateFilter.Status.NOT_TESTED to R.string.candidates_filter_not_tested,
            CandidateFilter.Status.WORKS to R.string.candidates_filter_works,
            CandidateFilter.Status.FAILED to R.string.candidates_filter_failed,
        ).map { (value, res) -> value to getString(res) }
        val sorts = listOf(
            CandidateFilter.Sort.SEARCH to R.string.candidates_sort_search,
            CandidateFilter.Sort.DOWNLOADS to R.string.candidates_sort_downloads,
            CandidateFilter.Sort.NEWEST to R.string.candidates_sort_newest,
            CandidateFilter.Sort.SMALLEST to R.string.candidates_sort_smallest,
        ).map { (value, res) -> value to getString(res) }

        fun <T> choose(title: String, options: List<Pair<T, String>>, current: T, apply: (T) -> CandidateFilter) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == current }) { dialog, which ->
                    dialog.dismiss()
                    setFilter(apply(options[which].first))
                }
                .show()
        }
        fun <T> label(options: List<Pair<T, String>>, current: T) = options.firstOrNull { it.first == current }?.second ?: current.toString()

        // "For": what the search was for, then every purpose the found repositories' own tags declare,
        // with how many say so -- only those actually present, so no choice leads to an empty list.
        val found = container.discoveryStore.runs().flatMap { it.candidates }
        val tagCounts = CandidatePurpose.entries
            .map { p -> p to found.count { p in CandidateFacts.of(it.tags).purposes } }
            .filter { it.second > 0 }
        val forOptions: List<Pair<Pair<CandidateFilter.Purpose, CandidatePurpose?>, String>> =
            purposes.map { (p, text) -> (p to null) to text } +
                tagCounts.map { (p, n) -> (CandidateFilter.Purpose.ANY to p) to "${purposeLabel(p)} ($n)" }
        val currentFor = filter.purpose to filter.tagged
        chip(getString(R.string.candidates_filter_purpose, label(forOptions, currentFor))) {
            choose(getString(R.string.candidates_filter_purpose, ""), forOptions, currentFor) { (p, tag) -> filter.copy(purpose = p, tagged = tag) }
        }
        chip(getString(R.string.candidates_filter_size, label(sizes, filter.maxBytes))) {
            choose(getString(R.string.candidates_filter_size, ""), sizes, filter.maxBytes) { filter.copy(maxBytes = it) }
        }
        chip(getString(R.string.candidates_filter_downloads, label(downloads, filter.minDownloads))) {
            choose(getString(R.string.candidates_filter_downloads, ""), downloads, filter.minDownloads) { filter.copy(minDownloads = it) }
        }
        chip(getString(R.string.candidates_filter_context, label(contexts, filter.minContextTokens))) {
            choose(getString(R.string.candidates_filter_context, ""), contexts, filter.minContextTokens) { filter.copy(minContextTokens = it) }
        }
        chip(getString(R.string.candidates_filter_status, label(statuses, filter.status))) {
            choose(getString(R.string.candidates_filter_status, ""), statuses, filter.status) { filter.copy(status = it) }
        }
        chip(getString(R.string.candidates_filter_sort, label(sorts, filter.sort))) {
            choose(getString(R.string.candidates_filter_sort, ""), sorts, filter.sort) { filter.copy(sort = it) }
        }
        if (!filter.isDefault) {
            chip(getString(R.string.candidates_filter_reset)) {
                binding.candidatesSearch.removeCallbacks(applySearch)
                binding.candidatesSearch.setText("")
                setFilter(CandidateFilter())
            }
        }
    }

    /** Remembered on this phone only, a convenience: a filter that cannot be read back is the default one. */
    private fun loadFilter(): CandidateFilter = runCatching {
        val prefs = getSharedPreferences(FILTER_PREFS, MODE_PRIVATE)
        CandidateFilter(
            text = prefs.getString("text", "").orEmpty(),
            purpose = CandidateFilter.Purpose.valueOf(prefs.getString("purpose", null) ?: CandidateFilter.Purpose.ANY.name),
            tagged = prefs.getString("tagged", null)?.let { CandidatePurpose.valueOf(it) },
            maxBytes = prefs.getLong("maxBytes", -1L).takeIf { it > 0 },
            minDownloads = prefs.getLong("minDownloads", 0L),
            minContextTokens = prefs.getLong("minContextTokens", 0L),
            status = CandidateFilter.Status.valueOf(prefs.getString("status", null) ?: CandidateFilter.Status.ANY.name),
            sort = CandidateFilter.Sort.valueOf(prefs.getString("sort", null) ?: CandidateFilter.Sort.SEARCH.name),
        )
    }.getOrDefault(CandidateFilter())

    private fun saveFilter(f: CandidateFilter) {
        runCatching {
            getSharedPreferences(FILTER_PREFS, MODE_PRIVATE).edit()
                .putString("text", f.text)
                .putString("purpose", f.purpose.name)
                .putString("tagged", f.tagged?.name)
                .putLong("maxBytes", f.maxBytes ?: -1L)
                .putLong("minDownloads", f.minDownloads)
                .putLong("minContextTokens", f.minContextTokens)
                .putString("status", f.status.name)
                .putString("sort", f.sort.name)
                .apply()
        }
    }

    private fun sectionTitle(run: DiscoveryRun, matching: Int? = null): String {
        val purpose = getString(if (DiscoveryLabels.isTranslation(run.label)) R.string.candidates_purpose_translation else R.string.candidates_purpose_chat)
        val group = DiscoveryLabels.lineage(run.label)?.let { "${it.displayName} · $purpose" }
            ?: getString(if (DiscoveryLabels.isTranslation(run.label)) R.string.candidates_group_translation else R.string.candidates_group_chat)
        val count = if (run.failure != null) {
            getString(R.string.candidates_section_failed, run.failure)
        } else {
            getString(R.string.candidates_section_count, run.candidates.size, run.checked)
        }
        val shown = matching?.let { " · " + getString(R.string.candidates_shown, it, run.candidates.size) }.orEmpty()
        return "$group\n$count$shown"
    }

    private fun addHeader(text: String) {
        val header = ItemFilesHeaderBinding.inflate(layoutInflater, binding.candidatesList, false)
        header.root.text = text
        binding.candidatesList.addView(header.root)
    }

    private fun bind(card: ItemLocalModelBinding, label: String, c: DiscoveredCandidate, work: CandidateWork) {
        val facts = CandidateFacts.of(c.tags)
        val name = c.repoId
        val key = c.identity
        val now = container.verificationContext(c)
        val download = work.downloads[key]
        val trial = work.trial?.takeIf { it.key == key }
        val queued = key in work.queued
        val verification = c.verification
        // What a model is offered for comes from what passed on this phone, not from which search found it.
        val usage = USABLE.associateWith { if (download == null) container.candidateUsage(it, c) else CandidateUsage.NONE }
        val passed = verification?.passed(now).orEmpty().filter { it in USABLE }
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
                checksSummary(verification, now),
                work.failures[key]?.let { getString(R.string.candidate_download_failed, it) },
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
                    // In use already (its files moved into the chat's model): testing again is still a tap away,
                    // not hidden in the details dialog -- a real report could not find it.
                    testable -> {
                        secondary.visibility = View.VISIBLE
                        secondary.text = getString(R.string.candidate_retest)
                        secondary.setOnClickListener { test(label, c) }
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
    private fun checksSummary(v: ai.localstudio.model.install.DeviceVerification?, now: ai.localstudio.model.install.VerificationContext): String {
        if (v == null) return tierLabel(CandidateTier.UNVERIFIED)
        if (!v.loaded) return tierLabel(CandidateTier.UNVERIFIED) + (v.error?.let { "\n$it" } ?: "")
        // Still shown, as what was observed then: STALE is a result to refresh, not one to forget.
        val stale = v.staleReason(now)
        val lines = (USABLE + listOf(VerifiedCapability.VISION).filter { it in v.checks }).map { cap ->
            val what = getString(
                when (cap) {
                    VerifiedCapability.TRANSLATION -> R.string.candidate_cap_translation
                    VerifiedCapability.VISION -> R.string.candidate_cap_vision
                    else -> R.string.candidate_cap_text
                },
            )
            val status = when (v.status(cap, now)) {
                CheckStatus.PASS -> getString(R.string.candidate_check_pass)
                CheckStatus.FAIL -> getString(R.string.candidate_check_fail)
                CheckStatus.NOT_TESTED -> getString(R.string.candidate_check_not_tested)
                CheckStatus.STALE -> getString(R.string.candidate_check_stale, checkWord(v.recorded(cap)))
            }
            "$what: $status"
        }
        return buildString {
            append(lines.joinToString(" · "))
            stale?.let { append("\n").append(getString(R.string.candidate_stale_reason, it)) }
            v.tokensPerSecond?.let { append(String.format(Locale.ROOT, " · %.1f tok/s", it)) }
            v.checks.filterValues { it.status == CheckStatus.FAIL }.forEach { (cap, check) ->
                append("\n").append(cap).append(": ").append(check.detail?.take(160) ?: "")
            }
        }
    }

    private fun checkWord(status: CheckStatus): String = getString(
        when (status) {
            CheckStatus.PASS -> R.string.candidate_check_pass
            CheckStatus.FAIL -> R.string.candidate_check_fail
            else -> R.string.candidate_check_not_tested
        },
    )

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
            // "Images" by its tags is only half the story: whether this phone can use them depends on the projector that came with it.
            val purposes = facts.purposes.joinToString(", ") {
                if (it == CandidatePurpose.VISION && c.projector == null) getString(R.string.purpose_vision_blind) else purposeLabel(it)
            }
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

    /**
     * Everything known about [c] as plain text: what it is (repository, file,
     * size, architecture, what its tags say it is for), where it came from,
     * and what this phone observed -- what "Copy" hands to the clipboard, so
     * a person can paste it into any AI chat and ask whether it fits.
     */
    private fun detailsText(label: String, c: DiscoveredCandidate): String {
        val dash = "—"
        return buildString {
            append("https://huggingface.co/").append(c.repoId).append(" · ").append(label).append("\n")
            append(subtitle(c, CandidateFacts.of(c.tags))).append("\n\n")
            append(
                getString(
                    R.string.candidate_details,
                    c.repoId, c.filePath, (c.sizeBytes / 1_000_000).toInt(), c.architecture,
                    c.contextLength?.toString() ?: dash, c.commit.take(12), tierLabel(c.verification.tier(container.verificationContext(c))),
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
            container.verificationContext(c).let { now -> c.verification?.staleReason(now)?.let { append("\n\n").append(getString(R.string.candidate_stale_reason, it)) } }
        }
    }

    private fun copyDetails(label: String, c: DiscoveredCandidate) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(c.repoId, detailsText(label, c)))
        Toast.makeText(this, R.string.candidate_copied, Toast.LENGTH_LONG).show()
    }

    private fun showDetails(label: String, c: DiscoveredCandidate) {
        val details = detailsText(label, c)
        val busy = container.candidateWork.value.isBusy(c.identity)
        val canRetest = container.candidateTestable(c) && !busy
        val installedBytes = container.candidateInstalledBytes(c)
        AlertDialog.Builder(this)
            .setTitle(c.repoId)
            .setMessage(details)
            .setPositiveButton(R.string.candidate_copy) { _, _ -> copyDetails(label, c) }
            .apply { if (canRetest) setNeutralButton(R.string.candidate_retest) { _, _ -> test(label, c) } }
            .apply {
                if (installedBytes != null && !busy) {
                    setNegativeButton(getString(R.string.candidate_delete, (installedBytes / 1_000_000).toInt())) { _, _ -> deleteInstall(c) }
                }
            }
            .show()
            // Selectable, so any part of it can be copied too, not only all of it.
            .findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
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
        const val FILTER_PREFS = "candidate_filter"
        const val SEARCH_DELAY_MS = 300L
        val SIZE_LIMITS_GB = listOf(2L, 4L, 6L, 8L)
        val DOWNLOAD_MINIMUMS = listOf(1_000L, 10_000L, 100_000L)

        /** 4K .. 1M: the range models on the Hub actually declare (some 1M+). */
        val CONTEXT_MINIMUMS = listOf(4_096L, 8_192L, 16_384L, 32_768L, 65_536L, 131_072L, 262_144L, 524_288L, 1_048_576L)

        /** The capabilities a model can be put to use for from here; vision joins once it is checked. */
        val USABLE = listOf(VerifiedCapability.TEXT, VerifiedCapability.TRANSLATION)
    }
}
