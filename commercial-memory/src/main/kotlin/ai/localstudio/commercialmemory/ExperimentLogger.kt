package ai.localstudio.commercialmemory

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where [ExperimentRecord]s go. An interface so a real Android app can plug
 * in its own persistent sink (see this module's README on why that adapter
 * belongs in the app, not here) without this module needing to know
 * anything about it.
 */
interface ExperimentLogger {
    fun log(record: ExperimentRecord)
}

/** Keeps every record in memory — for tests, and short-lived processes that read them back the same run. */
class InMemoryExperimentLogger : ExperimentLogger {
    private val lock = Any()
    private val records = mutableListOf<ExperimentRecord>()

    override fun log(record: ExperimentRecord) {
        synchronized(lock) { records += record }
    }

    fun all(): List<ExperimentRecord> = synchronized(lock) { records.toList() }
}

/**
 * Appends one JSON object per line to [file] — the standard shape for
 * feeding this into any offline analysis later (a notebook, a spreadsheet
 * import, `jq`) without this module needing to know what that analysis
 * will look like. Each call opens, appends, and closes the file rather than
 * holding it open: an experiment logger runs for the lifetime of the
 * process, and a held-open file handle across an app's whole session risks
 * losing buffered lines if the process is killed, which is routine on
 * Android.
 */
class JsonlExperimentLogger(private val file: File) : ExperimentLogger {
    private val lock = Any()
    private val json = Json { encodeDefaults = true }

    override fun log(record: ExperimentRecord) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(ExperimentRecord.serializer(), record) + "\n")
        }
    }
}
