package ai.localstudio.core.benchmark

import ai.localstudio.core.model.AudioRef

/**
 * Runs every registered [TranscriptionEngine] against every selected file,
 * in the same order for every engine, and returns the raw measurements —
 * see [BenchmarkReport] for the full, reproducible shape this feeds into
 * (device/app metadata is attached by the caller, not here: this class has
 * no Android dependency, deliberately, the same reasoning the rest of
 * `core` already follows).
 *
 * **Ordering, on purpose**: every engine is loaded and warmed up (see
 * [TranscriptionEngine.load]/[TranscriptionEngineSession.warmUp]) *before*
 * any file is transcribed by *any* engine — not interleaved — so a slow
 * load for engine B never gets charged against engine A's own numbers, and
 * so [onProgress] reflects genuine per-file work throughout the file loop
 * rather than including load time.
 *
 * **A failed load or warm-up does not abort the run**: that engine's rows
 * simply report [BenchmarkStatus.ERROR] for every file rather than
 * silently vanishing from a report someone will read weeks later trying to
 * understand why a backend is "missing."
 */
class BenchmarkRunner(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        engines: List<TranscriptionEngine>,
        files: List<BenchmarkAudioFile>,
        forcedLanguage: String? = null,
        /**
         * Reads a memory figure (native heap allocated, in MB) right after
         * each transcribe call — Android-specific (`Debug.getNativeHeapAllocatedSize()`),
         * so it comes in as a callback rather than this module depending on
         * android.os.Debug directly. A snapshot taken right after the call,
         * not a true continuously-sampled peak *during* inference — see
         * docs/16-stt-benchmark.md for why that's the honest scope of what
         * this reports. Null (the default) means memory just isn't recorded.
         */
        memorySamplerMb: (() -> Long?)? = null,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BenchmarkRunOutput {
        val startedAt = clock()
        val engineSummaries = mutableListOf<BenchmarkEngineSummary>()
        val sessions = LinkedHashMap<TranscriptionEngine, TranscriptionEngineSession?>()

        for (engine in engines) {
            val loadStart = clock()
            val session = try {
                engine.load()
            } catch (e: Exception) {
                engineSummaries += BenchmarkEngineSummary(
                    backendId = engine.backendId,
                    displayName = engine.displayName,
                    backendVersion = engine.backendVersion,
                    modelId = engine.modelId,
                    precision = engine.precision,
                    modelLoadMs = clock() - loadStart,
                    warmInferenceMs = null,
                    warmUpFailed = true,
                    loadFailed = true,
                    loadErrorMessage = e.message ?: e.toString(),
                )
                sessions[engine] = null
                continue
            }
            val modelLoadMs = clock() - loadStart

            var warmInferenceMs: Long? = null
            var warmUpFailed = false
            val warmSample = files.firstOrNull()
            if (warmSample == null) {
                warmUpFailed = true
            } else {
                val warmStart = clock()
                try {
                    session.warmUp(AudioRef(uri = warmSample.uri, durationMs = warmSample.durationMs, sampleRate = warmSample.sampleRateHz))
                    warmInferenceMs = clock() - warmStart
                } catch (e: Exception) {
                    warmUpFailed = true
                }
            }

            engineSummaries += BenchmarkEngineSummary(
                backendId = engine.backendId,
                displayName = engine.displayName,
                backendVersion = engine.backendVersion,
                modelId = engine.modelId,
                precision = engine.precision,
                modelLoadMs = modelLoadMs,
                warmInferenceMs = warmInferenceMs,
                warmUpFailed = warmUpFailed,
                loadFailed = false,
            )
            sessions[engine] = session
        }

        val fileResults = mutableListOf<BenchmarkFileResult>()
        var completed = 0
        val total = files.size * engines.size

        for (file in files) {
            val perEngine = mutableListOf<BenchmarkRunMetrics>()
            for (engine in engines) {
                val session = sessions[engine]
                perEngine += if (session == null) {
                    BenchmarkRunMetrics(
                        backendId = engine.backendId,
                        backendVersion = engine.backendVersion,
                        modelId = engine.modelId,
                        precision = engine.precision,
                        threads = null,
                        forcedLanguage = forcedLanguage,
                        detectedLanguage = null,
                        processingMs = 0,
                        rtf = null,
                        memoryMb = null,
                        status = BenchmarkStatus.ERROR,
                        errorMessage = "engine failed to load",
                    )
                } else {
                    runOneTimed(engine, session, file, forcedLanguage, memorySamplerMb)
                }
                completed++
                onProgress(completed, total)
            }
            fileResults += BenchmarkFileResult(file, perEngine)
        }

        sessions.values.forEach { it?.release() }

        return BenchmarkRunOutput(
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = clock(),
            engines = engineSummaries,
            files = fileResults,
        )
    }

    private suspend fun runOneTimed(
        engine: TranscriptionEngine,
        session: TranscriptionEngineSession,
        file: BenchmarkAudioFile,
        forcedLanguage: String?,
        memorySamplerMb: (() -> Long?)?,
    ): BenchmarkRunMetrics {
        val start = clock()
        return try {
            val transcript = session.transcribe(AudioRef(uri = file.uri, durationMs = file.durationMs, sampleRate = file.sampleRateHz))
            val elapsed = clock() - start
            val rtf = file.durationMs?.takeIf { it > 0 }?.let { elapsed.toDouble() / it }
            BenchmarkRunMetrics(
                backendId = engine.backendId,
                backendVersion = engine.backendVersion,
                modelId = engine.modelId,
                precision = engine.precision,
                threads = session.threads,
                forcedLanguage = forcedLanguage,
                detectedLanguage = transcript.language,
                processingMs = elapsed,
                rtf = rtf,
                memoryMb = memorySamplerMb?.invoke(),
                status = BenchmarkStatus.SUCCESS,
                transcriptText = transcript.text,
            )
        } catch (e: Exception) {
            val elapsed = clock() - start
            BenchmarkRunMetrics(
                backendId = engine.backendId,
                backendVersion = engine.backendVersion,
                modelId = engine.modelId,
                precision = engine.precision,
                threads = session.threads,
                forcedLanguage = forcedLanguage,
                detectedLanguage = null,
                processingMs = elapsed,
                rtf = null,
                memoryMb = null,
                status = BenchmarkStatus.ERROR,
                errorMessage = e.message ?: e.toString(),
            )
        }
    }
}

/** [BenchmarkRunner.run]'s raw output — the caller (Android-side) attaches [ai.localstudio.core.benchmark.BenchmarkDeviceInfo]/app version to build the full [BenchmarkReport], since this module has no Android dependency to source those from itself. */
data class BenchmarkRunOutput(
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val engines: List<BenchmarkEngineSummary>,
    val files: List<BenchmarkFileResult>,
)
