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
    /** Null until a real device has tried to load and use this exact file -- see [CandidateTier]. */
    val verification: ai.localstudio.model.install.DeviceVerification? = null,
)

/** One label's ("chat"/"translation") sweep -- what [DiscoveryStore] keeps. */
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
private data class DiscoveryFile(val runs: List<DiscoveryRun> = emptyList(), val lastSeenAtEpochMs: Long = 0L)

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
    fun record(run: DiscoveryRun) {
        val current = read()
        val known = current.runs.flatMap { it.candidates }
            .mapNotNull { c -> c.verification?.let { Triple(c.repoId, c.commit, c.filePath) to it } }
            .toMap()
        val carried = run.copy(
            candidates = run.candidates.map { c ->
                if (c.verification != null) c else c.copy(verification = known[Triple(c.repoId, c.commit, c.filePath)])
            },
        )
        write(current.copy(runs = current.runs.filterNot { it.label == run.label } + carried))
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
    fun recordVerification(label: String, repoId: String, verification: ai.localstudio.model.install.DeviceVerification): Boolean {
        val current = read()
        val run = current.runs.firstOrNull { it.label == label } ?: return false
        if (run.candidates.none { it.repoId == repoId }) return false
        val updatedRun = run.copy(candidates = run.candidates.map { if (it.repoId == repoId) it.copy(verification = verification) else it })
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
        )
    }
}
