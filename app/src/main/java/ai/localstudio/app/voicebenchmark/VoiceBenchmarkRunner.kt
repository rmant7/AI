package ai.localstudio.app.voicebenchmark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs [VoiceBenchmarkEngine]s one at a time — sequentially and under one
 * lock, so no two heavy models are ever loaded at once and every timing is
 * measured on an otherwise idle device. Cancelling the calling coroutine
 * cancels the engine in progress.
 */
class VoiceBenchmarkRunner(val engines: List<VoiceBenchmarkEngine>) {

    /** The one input every engine in a run receives. */
    data class Request(
        val text: String,
        val language: String,
        val referenceAudio: File?,
        val referenceText: String?,
    )

    private val lock = Mutex()

    /** [onStarted] just before an engine begins, [onFinished] with its result; both on the calling coroutine's context. */
    suspend fun runAll(
        engineIds: List<String>,
        request: Request,
        onStarted: (String) -> Unit,
        onFinished: (VoiceBenchmarkResult) -> Unit,
    ) {
        for (id in engineIds) {
            onStarted(id)
            onFinished(runOne(id, request))
        }
    }

    suspend fun runOne(engineId: String, request: Request): VoiceBenchmarkResult {
        val engine = engines.firstOrNull { it.id == engineId }
            ?: return VoiceBenchmarkResult.failed(engineId, VoiceBenchmarkStatus.ERROR, "Unknown engine")
        return lock.withLock {
            withContext(Dispatchers.Default) {
                try {
                    when {
                        request.language !in engine.supportedLanguages ->
                            VoiceBenchmarkResult.failed(engine.id, VoiceBenchmarkStatus.UNSUPPORTED_LANGUAGE)
                        engine.availability() == EngineAvailability.NOT_INSTALLED ->
                            VoiceBenchmarkResult.failed(engine.id, VoiceBenchmarkStatus.NOT_INSTALLED)
                        else -> engine.synthesize(request.text, request.language, request.referenceAudio, request.referenceText)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    VoiceBenchmarkResult.failed(engine.id, VoiceBenchmarkStatus.ERROR, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}
