package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.ModelDiscovery
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DiscoveredCandidate(
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long,
    val architecture: String,
    val contextLength: Long?,
    val notes: List<String>,
    val commit: String,
    val downloads: Long,
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

    @Synchronized
    fun record(run: DiscoveryRun) {
        val current = read()
        write(current.copy(runs = current.runs.filterNot { it.label == run.label } + run))
    }

    /** Whether any run finished after the last time [markSeen] was called. */
    fun hasUnseen(): Boolean = read().let { f -> f.runs.any { it.finishedAtEpochMs > f.lastSeenAtEpochMs } }

    @Synchronized
    fun markSeen() {
        write(read().copy(lastSeenAtEpochMs = System.currentTimeMillis()))
    }

    companion object {
        fun candidateOf(outcome: ModelDiscovery.Outcome.Candidate) = DiscoveredCandidate(
            repoId = outcome.repo.id,
            fileName = outcome.file.name,
            sizeBytes = outcome.file.sizeBytes,
            architecture = outcome.architecture,
            contextLength = outcome.contextLength,
            notes = outcome.notes,
            commit = outcome.commit,
            downloads = outcome.repo.downloads,
        )
    }
}
