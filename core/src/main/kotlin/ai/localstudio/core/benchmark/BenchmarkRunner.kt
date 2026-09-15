package ai.localstudio.core.benchmark

import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.util.describeForUser
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.Locale

/** Generous ceiling for warm-up on a ~1.5s clip — real device report: 13 minutes (791s) for exactly this, with the device thermally throttled. Should never legitimately take more than a few seconds; this is a backstop, not a target. */
private const val WARMUP_TIMEOUT_MS = 90_000L

/** Floor for a file's own transcribe timeout, for a file with no known duration. */
private const val MIN_TRANSCRIBE_TIMEOUT_MS = 120_000L

/** How many times a file's own duration a transcribe call may run before it's presumed hung rather than genuinely slow — generous even for a thermally-throttled device running far below its normal speed. */
private const val TRANSCRIBE_TIMEOUT_RTF_CEILING = 10L

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
        /** Device-wide free RAM (MB) sampled right after each transcribe call — see [BenchmarkRunMetrics.freeRamMb]'s own doc comment. Null (the default) means it isn't recorded. */
        freeRamMbSampler: (() -> Long?)? = null,
        /** [android.os.PowerManager.currentThermalStatus]'s name, sampled right after each transcribe call — see [BenchmarkRunMetrics.thermalStatus]'s own doc comment. Null (the default) means it isn't recorded. */
        thermalStatusSampler: (() -> String?)? = null,
        /** [android.os.PowerManager.getThermalHeadroom] sampled right after each transcribe call — see [BenchmarkRunMetrics.thermalHeadroom]'s own doc comment. Null (the default) means it isn't recorded. */
        thermalHeadroomSampler: (() -> Float?)? = null,
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
        /**
         * Fires right after *each individual file* finishes — including a
         * file whose engine failed to load at all, which reports the same
         * load-failure [BenchmarkRunMetrics] immediately rather than
         * waiting for every other file in [files] to get the same
         * treatment. A real device report is why this exists at file
         * granularity, not just once per engine: a single engine's own file
         * loop can itself run for many minutes on a large model, and
         * waiting even for that felt like results were "batched" —
         * per-file is the finest granularity this benchmark actually
         * measures at, so it is also the finest granularity worth
         * persisting at. The caller can save each file's result the moment
         * it lands instead of waiting for anything still to come.
         */
        onFileComplete: (BenchmarkEngineSummary, BenchmarkAudioFile, BenchmarkRunMetrics) -> Unit = { _, _, _ -> },
        /**
         * Awaited right before *each* engine's own [TranscriptionEngine.load]
         * call — the seam for a caller to pace the run against real device
         * conditions this module has no way to see itself (thermal state,
         * memory pressure). A real device report is why this exists: after
         * 30+ minutes of continuous, back-to-back multi-GB model loads with
         * no rest between engines, warm-up on a 1.5s clip took 13 minutes,
         * and every file transcribed afterward failed — while the exact
         * same operations had succeeded minutes earlier in the same run.
         * Nothing in this module can detect or fix real silicon throttling;
         * a caller that can (e.g. Android's own thermal status API) gets the
         * chance to pause here instead of burning through more models
         * that are near-certain to fail or time out regardless.
         */
        beforeEngine: suspend () -> Unit = {},
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
            beforeEngine()
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
                    val metrics = BenchmarkRunMetrics(
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
                    perFileMetrics.getValue(file) += metrics
                    completed++
                    onProgress(completed, total)
                    onFileComplete(engineSummaries.last(), file, metrics)
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
                    withTimeout(WARMUP_TIMEOUT_MS) {
                        session.warmUp(AudioRef(uri = warmSample.uri, durationMs = warmSample.durationMs, sampleRate = warmSample.sampleRateHz))
                    }
                    warmInferenceMs = clock() - warmStart
                    onStatus("${engine.displayName}: warm-up done in ${warmInferenceMs}ms")
                } catch (e: TimeoutCancellationException) {
                    warmUpFailed = true
                    warmInferenceMs = clock() - warmStart
                    onStatus("${engine.displayName}: warm-up timed out after ${WARMUP_TIMEOUT_MS}ms")
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
                    val metrics = runOneTimed(
                        engine, session, file, forcedLanguage,
                        memorySamplerMb, freeRamMbSampler, thermalStatusSampler, thermalHeadroomSampler,
                    )
                    perFileMetrics.getValue(file) += metrics
                    onStatus(
                        "${engine.displayName}: ${file.fileName} — ${metrics.status}" +
                            (metrics.rtf?.let { " (RTF ${"%.2f".format(Locale.ROOT, it)})" } ?: "") +
                            (metrics.errorMessage?.let { " — $it" } ?: ""),
                    )
                    completed++
                    onProgress(completed, total)
                    onFileComplete(engineSummaries.last(), file, metrics)
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

    /**
     * `withTimeout` here is a bookkeeping backstop, not true cancellation:
     * whisper.cpp's own native calls are not interruptible mid-call (the
     * same cooperative-cancellation limitation [BenchmarkOrchestrator]'s
     * own `cancel()` already documents), so a timed-out call's underlying
     * native work — and the global native mutex it holds — keeps running
     * until it actually finishes on its own, whatever that takes. What
     * this buys: [BenchmarkRunner] itself never blocks past [timeoutMs]
     * on any single call, correctly reports [BenchmarkStatus.TIMEOUT]
     * instead of silently hanging, and can move its own bookkeeping
     * forward — even though the *next* call may still have to wait its
     * turn for the same still-held mutex.
     */
    private suspend fun runOneTimed(
        engine: TranscriptionEngine,
        session: TranscriptionEngineSession,
        file: BenchmarkAudioFile,
        forcedLanguage: String?,
        memorySamplerMb: (() -> Long?)?,
        freeRamMbSampler: (() -> Long?)?,
        thermalStatusSampler: (() -> String?)?,
        thermalHeadroomSampler: (() -> Float?)?,
    ): BenchmarkRunMetrics {
        val start = clock()
        val timeoutMs = maxOf(MIN_TRANSCRIBE_TIMEOUT_MS, (file.durationMs ?: 0L) * TRANSCRIBE_TIMEOUT_RTF_CEILING)
        return try {
            val transcript = withTimeout(timeoutMs) {
                session.transcribe(AudioRef(uri = file.uri, durationMs = file.durationMs, sampleRate = file.sampleRateHz))
            }
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
                freeRamMb = freeRamMbSampler?.invoke(),
                thermalStatus = thermalStatusSampler?.invoke(),
                thermalHeadroom = thermalHeadroomSampler?.invoke(),
            )
        } catch (e: TimeoutCancellationException) {
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
                status = BenchmarkStatus.TIMEOUT,
                errorMessage = "no result after ${timeoutMs}ms",
                freeRamMb = freeRamMbSampler?.invoke(),
                thermalStatus = thermalStatusSampler?.invoke(),
                thermalHeadroom = thermalHeadroomSampler?.invoke(),
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
                freeRamMb = freeRamMbSampler?.invoke(),
                thermalStatus = thermalStatusSampler?.invoke(),
                thermalHeadroom = thermalHeadroomSampler?.invoke(),
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
