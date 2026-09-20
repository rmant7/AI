package ai.localstudio.app.vosk

import ai.localstudio.app.log.AppLog
import ai.localstudio.app.models.DownloadProgress
import ai.localstudio.app.models.ModelDownloader
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * `libvosk.so` is excluded from the APK itself (see app/build.gradle.kts's
 * `packaging.jniLibs.excludes`) — real device report: bundling it for every
 * install cost ~19.5MB (arm64-v8a + x86_64, both stored uncompressed under
 * this app's minSdk 26) for a feature most installs never touch, the same
 * "download it yourself" tradeoff this app already makes for GGUF/Whisper
 * models.
 *
 * This works because `org.vosk.LibVosk` doesn't use `System.loadLibrary` —
 * decompiling the published AAR shows its static initializer calls
 * `com.sun.jna.Native.register(LibVosk.class, "vosk")` instead. JNA
 * resolves a named library by searching `jna.library.path` *before* falling
 * back to the APK's own bundled jniLibs, so a `.so` placed anywhere on disk
 * and pointed to via that system property is picked up exactly like a
 * bundled one — as long as it's set before the first `org.vosk.*` class is
 * touched anywhere in the process. [ensureReady] is the one place every
 * `Model(...)` construction site in this package calls first, for exactly
 * that reason.
 *
 * The `.so` itself is pulled from the same published
 * `com.alphacephei:vosk-android` AAR this app already depends on for its
 * Java API (Maven Central) — no separate hosting needed, and the extracted
 * bytes are identical to what would have shipped in the APK.
 */
object VoskNativeLibrary {

    private const val AAR_URL =
        "https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar"

    private val mutex = Mutex()

    @Volatile
    private var ready = false

    fun directory(context: Context): File = File(context.filesDir, "vosk-native").apply { mkdirs() }

    fun isInstalled(context: Context): Boolean = soFile(context).isFile

    private fun soFile(context: Context): File = File(directory(context), "libvosk.so")

    /**
     * Idempotent and cheap on every call after the first: [ready] short-
     * circuits once this process has already staged the file and set the
     * property, since neither needs redoing per model load, only once per
     * process — same reasoning as [ai.localstudio.app.llama.LazyMemoryEmbedder]
     * not reloading a model that's already resident.
     */
    suspend fun ensureReady(context: Context, appLog: AppLog? = null) {
        if (ready) return
        mutex.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                if (!soFile(context).isFile) {
                    appLog?.record("VOSK_NATIVE", "libvosk.so not staged, downloading")
                    val start = System.currentTimeMillis()
                    download(context)
                    appLog?.record("VOSK_NATIVE", "libvosk.so staged in ${System.currentTimeMillis() - start}ms")
                }
                System.setProperty("jna.library.path", directory(context).absolutePath)
            }
            ready = true
        }
    }

    private fun download(context: Context) {
        // Matches app/build.gradle.kts' own ndk.abiFilters — the only two
        // ABIs this app's own native code ships for, so the running process
        // is guaranteed to be one of them.
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: throw IOException("No supported ABI for libvosk.so (device reports ${Build.SUPPORTED_ABIS.joinToString()})")

        val dir = directory(context)
        val aar = File(dir, "vosk-android.aar")
        val aarPart = File(dir, "vosk-android.aar.part")
        ModelDownloader().download(url = AAR_URL, destination = aar, tempFile = aarPart) { _: DownloadProgress -> }

        try {
            ZipFile(aar).use { zip ->
                val entry = zip.getEntry("jni/$abi/libvosk.so")
                    ?: throw IOException("jni/$abi/libvosk.so missing from the downloaded AAR")
                val target = soFile(context)
                val tmp = File(dir, "${target.name}.tmp")
                zip.getInputStream(entry).use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                if (!tmp.renameTo(target)) throw IOException("Could not stage libvosk.so from the downloaded AAR")
            }
        } finally {
            aar.delete()
        }
    }
}
