package ai.localstudio.app.vosk

import android.content.Context
import java.io.File

/**
 * Locates the on-device Vosk model directory for the spike — see
 * docs/14-vosk-spike.md for exactly how to provision it (`adb push`, not a
 * download in the app). Deliberately not a real catalog/downloader like
 * [ai.localstudio.app.whisper.WhisperStore]: the spike's own scope is no
 * production download UI and no multi-model manager, just "is a model
 * there or not" — a real download path is only worth building once the
 * device test says Vosk itself is worth keeping.
 */
object VoskModelStore {

    /** Where an unzipped Vosk model directory (containing am/, conf/, graph/, ...) is expected. App-private storage — no permission needed, survives app restarts, gone on uninstall. */
    fun modelDir(context: Context): File = File(context.filesDir, "vosk-model-ru-small")

    /** A directory that exists and isn't empty is close enough for a spike — Vosk's own `Model(path)` constructor is what actually validates its contents, and fails loudly (IOException) if they're wrong. */
    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }
}
