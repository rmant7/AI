package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.DeviceVerification
import kotlin.coroutines.cancellation.CancellationException

/**
 * One functional check of a discovered candidate on this device: a prompt
 * whose correct answer is known in advance, so "the model works" is judged
 * from what it actually said, never from it merely producing text.
 */
data class FunctionalProbe(val prompt: String, val expectAnyOf: List<String>) {
    fun passes(answer: String): Boolean = expectAnyOf.any { answer.contains(it, ignoreCase = true) }

    val title: String get() = prompt.lineSequence().first()

    companion object {
        /** Per discovery label; anything unknown gets the chat probes. */
        fun forLabel(label: String): List<FunctionalProbe> = when (label) {
            "translation" -> listOf(
                FunctionalProbe("Translate into French. Reply with the translation only.\n\nGood morning, my friend.", listOf("bonjour")),
            )
            else -> listOf(
                FunctionalProbe("What is the capital of France? Answer with one word.", listOf("Paris")),
                FunctionalProbe("What is 7 + 5? Answer with the number only.", listOf("12", "twelve")),
            )
        }
    }
}

/** What [CandidateTrial.run] needs from the real runtime: load (once) and answer one prompt. */
fun interface TrialRuntime {
    /**
     * Answers [prompt], streaming text to [onChunk]. Calls [onLoaded] once
     * the weights are actually loaded -- the only evidence of a load the
     * trial accepts besides text itself; a throw before either means the
     * model never loaded.
     */
    suspend fun answer(prompt: String, onLoaded: () -> Unit, onChunk: (String) -> Unit)
}

/**
 * Runs [FunctionalProbe]s through a [TrialRuntime] and records what was
 * observed -- nothing more. inferenceOk is true only when every probe's
 * answer contains what it was expected to: an answer that is fluent but
 * wrong is LOADABLE, not FUNCTIONAL.
 */
class CandidateTrial(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        deviceProfile: String,
        runtimeId: String,
        probes: List<FunctionalProbe>,
        runtime: TrialRuntime,
    ): DeviceVerification {
        require(probes.isNotEmpty()) { "a trial needs at least one probe" }
        val answers = mutableListOf<String>()
        var intervals = 0
        var generatingMs = 0L
        var loaded = false

        fun verdict(inferenceOk: Boolean, error: String?) = DeviceVerification(
            deviceProfile = deviceProfile,
            runtimeId = runtimeId,
            loaded = loaded,
            inferenceOk = inferenceOk,
            sampleOutput = answers.joinToString(" | ") { it.trim().replace('\n', ' ') }.take(SAMPLE_CHARS).ifBlank { null },
            tokensPerSecond = if (intervals > 0 && generatingMs > 0) intervals * 1000.0 / generatingMs else null,
            error = error,
            verifiedAtEpochMs = clock(),
        )

        for (probe in probes) {
            val answer = StringBuilder()
            var firstAt = 0L
            var lastAt = 0L
            var chunks = 0
            try {
                runtime.answer(
                    probe.prompt,
                    onLoaded = { loaded = true },
                    onChunk = { chunk ->
                        val now = clock()
                        if (chunks == 0) firstAt = now
                        lastAt = now
                        chunks++
                        loaded = true
                        answer.append(chunk)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (answer.isNotEmpty()) answers += answer.toString()
                val stage = if (loaded) "generation" else "load"
                return verdict(inferenceOk = false, error = "$stage failed: ${describe(t)}")
            }
            // Between the first and the last chunk of each answer: excludes the
            // load and the prompt evaluation, which say nothing about how fast
            // this device generates with this model.
            if (chunks >= 2) {
                intervals += chunks - 1
                generatingMs += lastAt - firstAt
            }
            val text = answer.toString()
            answers += text
            if (text.isBlank()) return verdict(inferenceOk = false, error = "empty answer to: ${probe.title}")
            if (!probe.passes(text)) {
                return verdict(inferenceOk = false, error = "wrong answer to: ${probe.title} (expected ${probe.expectAnyOf.joinToString(" or ")})")
            }
        }
        return verdict(inferenceOk = true, error = null)
    }

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message.orEmpty()}".trimEnd(' ', ':')

    companion object {
        const val SAMPLE_CHARS = 300
    }
}

/** Where the one running test is: loading the weights, then asking question [probe] of [probes]. */
data class CandidateTrialState(
    val repoId: String,
    val phase: Phase,
    /** 1-based, while [phase] is [Phase.ANSWERING]. */
    val probe: Int = 0,
    val probes: Int = 0,
) {
    enum class Phase { LOADING, ANSWERING }
}

data class CandidateDownload(val bytesDone: Long, val bytesTotal: Long) {
    val percent: Int? get() = if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100) else null
}

/**
 * Everything candidate-related in flight, keyed by repository id: downloads
 * run side by side like any model's; tests run one at a time (each loads a
 * whole model into RAM), the rest wait in [queued].
 */
data class CandidateWork(
    val downloads: Map<String, CandidateDownload> = emptyMap(),
    val trial: CandidateTrialState? = null,
    val queued: List<String> = emptyList(),
    /** The last download failure per repository, until it is retried. */
    val failures: Map<String, String> = emptyMap(),
) {
    val isIdle: Boolean get() = downloads.isEmpty() && trial == null && queued.isEmpty()

    /** Downloading, waiting for a test or being tested -- not a moment to delete its files or start it again. */
    fun isBusy(repoId: String): Boolean = repoId in downloads || repoId in queued || trial?.repoId == repoId
}
