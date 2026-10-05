package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.ModelDiscovery
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DiscoveredCandidate(
    val repoId: String,
    /** Display only -- the last path segment. Installing uses [filePath], never this. */
    val fileName: String,
    /** The file's full path inside the repository -- some repos keep quants in a subdirectory, so this is what actually resolves; [fileName] alone would miss it. */
    val filePath: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val architecture: String,
    val contextLength: Long?,
    val notes: List<String>,
    val commit: String,
    val downloads: Long,
    /** The repository's Hugging Face tags as the search returned them -- see [CandidateFacts]. Empty for a run stored before they were kept. */
    val tags: List<String> = emptyList(),
    /** When the repository was created on the Hub (ISO 8601), if the search said. */
    val createdAt: String? = null,
    /** When a sweep on this phone first found this repository -- kept across sweeps; see [DiscoveryStore.isNew]. */
    val firstSeenAtEpochMs: Long = 0L,
    /** The vision projector that is part of this model (same repository and commit); null for a text-only model. */
    val projector: ai.localstudio.model.install.ProjectorFile? = null,
    /** Null until a real device has tried to load and use this exact file -- see [CandidateTier]. */
    val verification: ai.localstudio.model.install.DeviceVerification? = null,
) {
    /** The exact bytes a verification is about: repository, commit, file -- and the projector, when there is one. */
    val identity: String get() = listOfNotNull(repoId, commit, filePath, projector?.file?.path).joinToString("|")

    /** What installing it downloads: the main file plus its projector. */
    val totalBytes: Long get() = artifact().totalBytes

    /** This candidate as one model: its main file and, when it has one, its projector. */
    fun artifact(): ai.localstudio.model.install.ModelArtifact = ai.localstudio.model.install.ModelArtifact(
        main = ai.localstudio.model.install.ModelFile(repoId, commit, filePath, sizeBytes, sha256),
        projector = projector,
    )
}

/** One lineage's part of a sweep, by its label (see [DiscoveryLabels]) -- what [DiscoveryStore] keeps. */
@Serializable
data class DiscoveryRun(
    val label: String,
    val finishedAtEpochMs: Long,
    val checked: Int,
    val candidates: List<DiscoveredCandidate>,
    /** Set when the whole sweep failed outright (the search request itself); [checked]/[candidates] are then 0/empty, not partial. */
    val failure: String? = null,
)

@Serializable
private data class DiscoveryFile(
    val runs: List<DiscoveryRun> = emptyList(),
    val lastSeenAtEpochMs: Long = 0L,
    /** When the current (or last) sweep started, and the one before it -- what "new since the last search" is measured against. */
    val sweepStartedAtEpochMs: Long = 0L,
    val previousSweepStartedAtEpochMs: Long = 0L,
)

/**
 * The last discovery sweep's results, one per label, surviving the app
 * closing and reopening -- a real device report: examining 15 repositories
 * is up to three sequential HTTP round trips each, so one sweep can run
 * close to two minutes, and by the time it finished the person had already
 * left the Models screen (or the app). A dialog tied to that one screen's
 * lifecycleScope is gone the moment the screen or process goes away, with
 * nothing left to show for the work afterward -- this is what makes the
 * result outlive that.
 *
 * [hasUnseen] / [markSeen] track whether a finished run has actually been
 * shown yet, so the Models screen can pop it open the next time it resumes
 * (once), not every single time there happens to be an old result on disk.
 */
class DiscoveryStore(context: Context, baseDir: File = context.filesDir) {

    private val file = File(baseDir, "discovery_results.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: DiscoveryFile? = null

    private fun read(): DiscoveryFile = cache ?: (runCatching {
        file.takeIf { it.isFile }?.let { json.decodeFromString(DiscoveryFile.serializer(), it.readText()) }
    }.getOrNull() ?: DiscoveryFile()).also { cache = it }

    private fun write(value: DiscoveryFile) {
        cache = value
        // filesDir itself always exists on a real device; a scratch baseDir
        // (tests) does not until something creates it -- a first write with
        // no parent directory failed silently inside this runCatching, with
        // the in-memory cache already updated, so only a *fresh* instance
        // (a real cold start, or exactly what this method's own test checks)
        // showed the loss.
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(DiscoveryFile.serializer(), value))
        }
    }

    fun runs(): List<DiscoveryRun> = read().runs

    /**
     * Replaces [run]'s label. A candidate found again at the same commit and
     * path keeps the verification it already had: that is evidence about
     * those exact bytes on this device, and a new sweep re-finding them
     * changes nothing about it. A different commit or file starts over.
     */
    @Synchronized
    fun record(run: DiscoveryRun, nowMs: Long = System.currentTimeMillis()) {
        val current = read()
        val known = current.runs.flatMap { it.candidates }
            .mapNotNull { c -> c.verification?.let { c.identity to it } }
            .toMap()
        // Found before, under any label or commit: keeps the time it was first found (a run stored before this was kept: that run's own time).
        val firstSeen = current.runs.flatMap { r -> r.candidates.map { it.repoId to (it.firstSeenAtEpochMs.takeIf { t -> t > 0 } ?: r.finishedAtEpochMs) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.min() }
        val carried = run.copy(
            candidates = run.candidates.map { c ->
                c.copy(
                    verification = c.verification ?: known[c.identity],
                    firstSeenAtEpochMs = c.firstSeenAtEpochMs.takeIf { it > 0 } ?: firstSeen[c.repoId] ?: nowMs,
                )
            },
        )
        write(current.copy(runs = current.runs.filterNot { it.label == run.label } + carried))
    }

    /** Marks a new sweep's start: candidates first found from here on are [isNew]. */
    @Synchronized
    fun beginSweep(nowMs: Long = System.currentTimeMillis()) {
        val current = read()
        val previous = current.sweepStartedAtEpochMs.takeIf { it > 0 } ?: current.runs.maxOfOrNull { it.finishedAtEpochMs } ?: 0L
        write(current.copy(sweepStartedAtEpochMs = nowMs, previousSweepStartedAtEpochMs = previous))
    }

    /** After a complete sweep: drops runs whose label it no longer produces (a family removed, or the pre-lineage "chat"/"translation"). */
    @Synchronized
    fun retainLabels(labels: Set<String>) {
        val current = read()
        write(current.copy(runs = current.runs.filter { it.label in labels }))
    }

    /** When the last sweep started (or, for runs stored before that was kept, finished); 0 when there never was one. */
    fun lastSweepAtEpochMs(): Long = read().let { f -> f.sweepStartedAtEpochMs.takeIf { it > 0 } ?: f.runs.maxOfOrNull { it.finishedAtEpochMs } ?: 0L }

    /** What happened since [markSeen] was last called -- what the "new models" notice tells the person. Pairs are (label, candidate). */
    data class News(
        val newCandidates: List<Pair<String, DiscoveredCandidate>>,
        val tested: List<Pair<String, DiscoveredCandidate>>,
        val sweepFinished: Boolean,
    )

    fun news(): News = read().let { f ->
        val all = f.runs.flatMap { run -> run.candidates.map { run.label to it } }
        News(
            newCandidates = all.filter { (_, c) -> isNew(c) && c.firstSeenAtEpochMs > f.lastSeenAtEpochMs },
            tested = all.filter { (_, c) -> (c.verification?.verifiedAtEpochMs ?: 0L) > f.lastSeenAtEpochMs },
            sweepFinished = f.runs.any { it.finishedAtEpochMs > f.lastSeenAtEpochMs },
        )
    }

    /** First found by the latest sweep, when there was an earlier one to compare with -- on the very first sweep nothing is "new". */
    fun isNew(candidate: DiscoveredCandidate): Boolean = read().let { f ->
        f.previousSweepStartedAtEpochMs > 0 && candidate.firstSeenAtEpochMs >= f.sweepStartedAtEpochMs
    }

    /** Whether any run finished, or any candidate was verified, after the last time [markSeen] was called. */
    fun hasUnseen(): Boolean = read().let { f ->
        f.runs.any { run ->
            run.finishedAtEpochMs > f.lastSeenAtEpochMs ||
                run.candidates.any { (it.verification?.verifiedAtEpochMs ?: 0L) > f.lastSeenAtEpochMs }
        }
    }

    @Synchronized
    fun markSeen() {
        write(read().copy(lastSeenAtEpochMs = System.currentTimeMillis()))
    }

    /**
     * Attaches [verification] to the one candidate [repoId] in [label]'s run
     * -- the only way a stored candidate's tier ever changes, and only to
     * whatever this call was actually given. False (nothing written) when
     * that run or that candidate is no longer there, e.g. a newer sweep for
     * the same label already replaced it; the caller decides what that's
     * worth, this method does not guess.
     */
    @Synchronized
    fun recordVerification(
        label: String,
        repoId: String,
        verification: ai.localstudio.model.install.DeviceVerification,
        /** When given, the verification lands only on the candidate with this exact [DiscoveredCandidate.identity]: a sweep since may have moved the repository to other bytes. */
        identity: String? = null,
    ): Boolean {
        val current = read()
        val run = current.runs.firstOrNull { it.label == label } ?: return false
        fun matches(c: DiscoveredCandidate) = c.repoId == repoId && (identity == null || c.identity == identity)
        if (run.candidates.none(::matches)) return false
        val updatedRun = run.copy(candidates = run.candidates.map { if (matches(it)) it.copy(verification = verification) else it })
        write(current.copy(runs = current.runs.map { if (it.label == label) updatedRun else it }))
        return true
    }

    companion object {
        fun candidateOf(outcome: ModelDiscovery.Outcome.Candidate) = DiscoveredCandidate(
            repoId = outcome.repo.id,
            fileName = outcome.file.name,
            filePath = outcome.file.path,
            sizeBytes = outcome.file.sizeBytes,
            sha256 = outcome.file.lfsSha256,
            architecture = outcome.architecture,
            contextLength = outcome.contextLength,
            notes = outcome.notes,
            commit = outcome.commit,
            downloads = outcome.repo.downloads,
            tags = outcome.repo.tags,
            createdAt = outcome.repo.createdAt,
            projector = outcome.artifact().projector,
        )
    }
}
