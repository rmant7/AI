package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.DeviceVerification
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A test that is running right now, on disk. A model can take the whole
 * process down natively (an abort inside llama.cpp, the OOM killer) where no
 * Kotlin code runs to record anything; this file outliving the process is
 * how the next launch knows a test was cut short, and by what.
 */
@Serializable
data class RunningTrial(
    val label: String,
    val repoId: String,
    val deviceProfile: String,
    val runtimeId: String,
    val startedAtEpochMs: Long,
    /** Whether the runtime had reported the weights loaded before the process ended. */
    val loaded: Boolean = false,
    /** The exact files under test ([DiscoveredCandidate.identity]); null in a marker written before it was kept. */
    val identity: String? = null,
)

/** Why the previous process ended, as the system reported it -- only the exits worth attributing. */
data class ProcessDeath(val atEpochMs: Long, val reason: String, val detail: String?)

class CandidateTrialMarker(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun write(trial: RunningTrial) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(RunningTrial.serializer(), trial))
    }

    fun read(): RunningTrial? = runCatching {
        file.takeIf { it.isFile }?.let { json.decodeFromString(RunningTrial.serializer(), it.readText()) }
    }.getOrNull()

    fun clear() {
        file.delete()
    }

    companion object {
        /**
         * What an interrupted test [marker] proves, given how the previous
         * process ended. Only a crash or a low-memory kill after the test
         * started is evidence about the model; anything else (no notable
         * exit, the person swiping the app away, an exit older than the
         * test) says nothing, and nothing is recorded.
         */
        fun verdict(marker: RunningTrial, death: ProcessDeath?): DeviceVerification? {
            if (death == null || death.atEpochMs < marker.startedAtEpochMs) return null
            val stage = if (marker.loaded) "during generation" else "while loading"
            return DeviceVerification(
                deviceProfile = marker.deviceProfile,
                runtimeId = marker.runtimeId,
                loaded = marker.loaded,
                inferenceOk = false,
                error = "the app was killed $stage: ${death.reason}" + (death.detail?.let { " -- $it" } ?: ""),
                verifiedAtEpochMs = death.atEpochMs,
                checkVersion = DeviceVerification.CURRENT_CHECK,
            )
        }
    }
}
