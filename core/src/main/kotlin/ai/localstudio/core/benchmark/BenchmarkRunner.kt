package ai.localstudio.core.benchmark

import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.util.describeForUser
import java.util.Locale

/**
 * Runs every registered [TranscriptionEngine] against every selected file
 * and returns the raw measurements — see [BenchmarkReport] for the full,
 * reproducible shape this feeds into (device/app metadata is attached by
 * the caller, not here: this class has no Android dependency, deliberately,
 * the same reasoning the rest of `core` already follows).
 *
 * **One engine fully resident at a time, on purpose.** Each engine is
 * loaded, warmed up (see [TranscriptionEngine.load]/
 * [TranscriptionEngineSession.warmUp]), run against every file, and
 * released *before the next engine's own [TranscriptionEngine.load] call
 * starts* — never two engines' sessions held open at once. Comparing
 * several on-device model sizes (e.g. every installed Whisper size, from
 * Tiny to Large) is exactly this benchmark's own point; holding all of them
 * resident simultaneously would mean several hundred MB to multiple GB of
 * native weights (plus each one's own inference buffers) loaded at the same
 * time, on a phone already running everything else the user has open. This
 * is what real memory pressure during a multi-model run looks like — this
 * ordering is what avoids it, not just a nice-to-have.
 *
 * Per-file results across engines still land in the same order every file
 * was scanned in ([BenchmarkFileResult] per file, holding one
 * [BenchmarkRunMetrics] per engine) — callers do not see or need to care
 * that engines were processed one at a time internally.
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
        /**
         * A human-readable line for every state transition (load starting,
         * warm-up starting, each file starting) — not just [onProgress]'s
         * counter, which only advances once a whole (file, engine) pair
         * finishes. A real device report is why this exists: a run showed
         * "0 / 35" for minutes with nothing else on screen or in the app's
         * own log, with no way to tell "still loading a 3GB model" from
         * "stuck" from "one very long file is still transcribing." The
         * caller decides what to do with each line (log it, show it) —
         * this module stays free of any Android dependency either way.
         */
        onStatus: (String) -> Unit = {},
        /**
         * Used to warm up every engine instead of one of [files] — content
         * doesn't matter for warm-up (see [TranscriptionEngineSession.warmUp]'s
         * own doc comment: it's priming native buffers/thread pools, not
         * exercising the model against real speech), so a short, fixed
         * sample is strictly better than picking one of the files actually
         * being measured: bounded cost regardless of what's in [files], and
         * comparable warm-up cost across separate runs instead of depending
         * on whichever file happened to be shortest in a given folder. Null
         * (the default) falls back to the shortest-known-duration file in
         * [files] — this module has no Android dependency to source a
         * bundled asset from itself, so the caller provides one when it can.
         */
        warmupSample: BenchmarkAudioFile? = null,
    ): BenchmarkRunOutput {
        val startedAt = clock()
        val engineSummaries = mutableListOf<BenchmarkEngineSummary>()
        // Keyed by identity, not content — two engines can legitimately
        // share every field (same backend, same model, different instance)
        // and still need separate result lists.
        val perFileMetrics = LinkedHashMap<BenchmarkAudioFile, MutableList<BenchmarkRunMetrics>>()
        files.forEach { perFileMetrics[it] = mutableListOf() }

        var completed = 0
        val total = files.size * engines.size

        // Prefer the caller's own fixed sample (see warmupSample's own doc
        // comment); falling back to the shortest-known-duration file in
        // files avoids the same mistake a real device report already
        // surfaced once: with no fixed sample and a folder scanned in
        // filesystem order, files.firstOrNull() landed on a long Zoom
        // recording, and warm-up ran a multi-minute full transcription
        // before any real measurement even started.
        val warmSample = warmupSample ?: files.minByOrNull { it.durationMs ?: Long.MAX_VALUE }

        for (engine in engines) {
            onStatus("Loading ${engine.displayName} (${engine.modelId})…")
            val loadStart = clock()
            val session = try {
                engine.load()
            } catch (e: Exception) {
                onStatus("${engine.displayName}: load failed — ${e.describeForUser()}")
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
                    loadErrorMessage = e.describeForUser(),
                )
                files.forEach { file ->
                    perFileMetrics.getValue(file) += BenchmarkRunMetrics(
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
                        errorMessage = "engine failed to load: ${e.describeForUser()}",
                    )
                    completed++
                    onProgress(completed, total)
                }
                continue
            }
            val modelLoadMs = clock() - loadStart
            onStatus("${engine.displayName}: loaded in ${modelLoadMs}ms")

            var warmInferenceMs: Long? = null
            var warmUpFailed = false
            if (warmSample == null) {
                warmUpFailed = true
            } else {
                onStatus("${engine.displayName}: warming up on ${warmSample.fileName}…")
                val warmStart = clock()
                try {
                    session.warmUp(AudioRef(uri = warmSample.uri, durationMs = warmSample.durationMs, sampleRate = warmSample.sampleRateHz))
                    warmInferenceMs = clock() - warmStart
                    onStatus("${engine.displayName}: warm-up done in ${warmInferenceMs}ms")
                } catch (e: Exception) {
                    warmUpFailed = true
                    onStatus("${engine.displayName}: warm-up failed — ${e.describeForUser()}")
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

            try {
                files.forEachIndexed { index, file ->
                    onStatus("${engine.displayName}: ${file.fileName} (${index + 1}/${files.size})…")
                    val metrics = runOneTimed(engine, session, file, forcedLanguage, memorySamplerMb)
                    perFileMetrics.getValue(file) += metrics
                    onStatus(
                        "${engine.displayName}: ${file.fileName} — ${metrics.status}" +
                            (metrics.rtf?.let { " (RTF ${"%.2f".format(Locale.ROOT, it)})" } ?: ""),
                    )
                    completed++
                    onProgress(completed, total)
                }
            } finally {
                // Always released before the next engine's own load() call —
                // see this class's own doc comment on why that ordering is
                // the actual point, not just cleanup. `finally` so a file
                // loop that throws (it shouldn't — runOneTimed catches its
                // own exceptions — but a bug here must not leave this
                // engine's model resident through the rest of the run) still
                // frees it.
                session.release()
            }
        }

        val fileResults = files.map { file -> BenchmarkFileResult(file, perFileMetrics.getValue(file)) }

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
                memoryMb = memorySamplerMb?.invoke(),
                status = BenchmarkStatus.ERROR,
                errorMessage = e.describeForUser(),
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
