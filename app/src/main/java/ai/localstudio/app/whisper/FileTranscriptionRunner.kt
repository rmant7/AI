package ai.localstudio.app.whisper

import ai.localstudio.app.vosk.VoskFileTranscriber
import ai.localstudio.app.vosk.VoskModelSeed
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.util.describeForUser
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class TranscriptionStatus { PENDING, RUNNING, DONE, ERROR, CANCELLED }

data class TranscriptionResult(
    val uri: Uri,
    val name: String,
    val status: TranscriptionStatus = TranscriptionStatus.PENDING,
    val text: String = "",
    val error: String? = null,
    val savedAs: String? = null,
)

/**
 * Owns [ai.localstudio.app.TranscribeActivity]'s batch file transcription
 * for the whole app rather than for a screen — the same reasoning
 * [ai.localstudio.app.models.ModelDownloads] already follows for GGUF/
 * whisper downloads (see that class's own doc comment).
 *
 * A real device report is why this exists: TranscribeActivity used to hold
 * the file list and per-file status as a plain instance field, so any
 * Activity recreation — which memory pressure can trigger on completely
 * ordinary navigation to another screen, not just backgrounding the whole
 * app — silently wiped an in-progress transcription with no error, no
 * saved partial file, nothing. The actual work now runs in an application-
 * scoped coroutine and survives that; the Activity only observes [results]/
 * [running]. What it still cannot survive on its own is the OS killing the
 * whole *process* while backgrounded — [onTranscriptionStarted] is the seam
 * for a foreground service (see [FileTranscriptionService]) to prevent
 * that, the same way [ai.localstudio.app.models.ModelDownloads.onDownloadStarted]
 * already does for downloads.
 *
 * [startWhisper]/[startVosk] — two entry points, not one taking an engine
 * tag, because each engine's own transcriber has a genuinely different
 * per-segment callback shape (Whisper reports one new [TranscriptSegment]
 * at a time; Vosk's own [VoskFileTranscriber.transcribe] reports the *full*
 * text accumulated so far on every call — see that class's own doc
 * comment). [whisperTranscriber]/[voskTranscriber] are two separate,
 * always-resident slots rather than one shared "whichever is active" — the
 * same split [ai.localstudio.app.AppContainer.whisperFileTranscriber] and
 * [ai.localstudio.app.AppContainer.whisperMicSession] already keep for
 * Whisper, so a file transcription running in the background never
 * `cancel()`s an unrelated live-mic session just because they would
 * otherwise share one loaded model.
 */
class FileTranscriptionRunner(
    private val whisperTranscriber: WhisperFileTranscriber,
    private val voskTranscriber: VoskFileTranscriber,
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onTranscriptionStarted: () -> Unit = {},
) {

    private val _results = MutableStateFlow<List<TranscriptionResult>>(emptyList())
    val results: StateFlow<List<TranscriptionResult>> = _results

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private var job: Job? = null

    /** Replaces the file list — refused while a run is in progress, same guard [startWhisper]/[startVosk] themselves use. */
    fun setSource(items: List<TranscriptionResult>) {
        if (job?.isActive == true) return
        _results.value = items
    }

    fun startWhisper(seed: WhisperModelSeed) = start { uri, name -> runOneWhisper(uri, name, seed) }

    fun startVosk(seed: VoskModelSeed) = start { uri, name -> runOneVosk(uri, name, seed) }

    private fun start(runOne: suspend (Uri, String) -> Unit) {
        if (_results.value.isEmpty() || job?.isActive == true) return
        onTranscriptionStarted()
        _running.value = true
        job = scope.launch {
            try {
                for (result in _results.value) {
                    if (result.status == TranscriptionStatus.DONE) continue
                    updateResult(result.uri) { it.copy(status = TranscriptionStatus.RUNNING, text = "") }
                    runOne(result.uri, result.name)
                }
            } finally {
                _running.value = false
            }
        }
    }

    /**
     * Both transcribers' own `requestCancel` are safe no-ops on an idle
     * engine (Whisper's on a null loaded handle, Vosk's on a null mic job)
     * — calling both unconditionally means [stop] doesn't need to track
     * which engine [startWhisper]/[startVosk] last used.
     */
    fun stop() {
        job?.cancel()
        whisperTranscriber.requestCancel()
        voskTranscriber.requestCancel()
    }

    private suspend fun runOneWhisper(uri: Uri, name: String, seed: WhisperModelSeed) {
        try {
            val transcript = whisperTranscriber.transcribe(uri, seed, language = null) { segment: TranscriptSegment ->
                // Fires as each segment is finalized, before the rest of the
                // file has decoded. Safe to write from whichever dispatcher
                // this callback lands on (see WhisperFileTranscriber's own
                // threading) — StateFlow.value is a plain atomic reference
                // write, no main-thread hop needed the way mutating a View
                // directly used to require.
                updateResult(uri) { it.copy(text = (it.text + " " + segment.text).trim()) }
            }
            finishRun(uri, name, transcript.text)
        } catch (e: CancellationException) {
            cancelRun(uri, name)
            throw e
        } catch (e: Exception) {
            failRun(uri, e)
        }
    }

    private suspend fun runOneVosk(uri: Uri, name: String, seed: VoskModelSeed) {
        try {
            // Unlike Whisper's per-segment callback, this reports the full
            // text accumulated so far on every call — see
            // VoskFileTranscriber.transcribe's own doc comment — so the
            // result is replaced outright, not appended to.
            val transcript = voskTranscriber.transcribe(uri, seed) { text ->
                updateResult(uri) { it.copy(text = text) }
            }
            finishRun(uri, name, transcript.text)
        } catch (e: CancellationException) {
            cancelRun(uri, name)
            throw e
        } catch (e: Exception) {
            failRun(uri, e)
        }
    }

    private fun finishRun(uri: Uri, name: String, text: String) {
        val finalText = text.ifBlank { textOf(uri) }
        val savedName = save(name, finalText)
        updateResult(uri) { it.copy(text = finalText, status = TranscriptionStatus.DONE, savedAs = savedName) }
    }

    /**
     * Whatever text already arrived via the per-segment callback before Stop
     * was pressed is real transcript, not garbage — discarding it meant a
     * long file stopped partway through showed neither text nor a saved
     * file, even after minutes of real work.
     */
    private fun cancelRun(uri: Uri, name: String) {
        val text = textOf(uri)
        val savedName = if (text.isNotBlank()) save(name, text) else null
        updateResult(uri) { it.copy(status = TranscriptionStatus.CANCELLED, savedAs = savedName ?: it.savedAs) }
    }

    private fun failRun(uri: Uri, e: Exception) {
        updateResult(uri) { it.copy(status = TranscriptionStatus.ERROR, error = e.describeForUser()) }
    }

    private fun textOf(uri: Uri): String = _results.value.firstOrNull { it.uri == uri }?.text.orEmpty()

    private fun updateResult(uri: Uri, transform: (TranscriptionResult) -> TranscriptionResult) {
        _results.value = _results.value.map { if (it.uri == uri) transform(it) else it }
    }

    private fun save(sourceName: String, text: String): String {
        val dir = File(context.filesDir, "transcripts").apply { mkdirs() }
        val safeName = sourceName.substringBeforeLast('.').ifBlank { "transcript" }
        val out = File(dir, "$safeName.txt")
        out.writeText(text)
        // Reported directly: transcribing a folder of hundreds of files and
        // then having to Share each one individually out of the app's own
        // private storage isn't usable — every result needs to land
        // somewhere reachable on its own, with no per-file action needed.
        // MediaStore.Downloads is the scoped-storage-era way to do that
        // without WRITE_EXTERNAL_STORAGE or any permission prompt: the app
        // can always create files there under its own name. A collision
        // (re-transcribing the same source) gets auto-renamed by MediaStore
        // itself, not overwritten.
        copyToDownloads(out.name, text)
        return out.name
    }

    private fun copyToDownloads(name: String, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Transcripts/")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }
}
