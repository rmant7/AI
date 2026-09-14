package ai.localstudio.app.whisper

import androidx.documentfile.provider.DocumentFile

/**
 * Recognizes and enumerates audio/video files that Android's MediaExtractor
 * can typically demux. Ported from `rmant7/claude-code`'s
 * `claude/android-whisper-transcription-3tuggu` branch
 * (`MediaFileUtils.kt`) — see docs/13-asr-pipeline-migration.md.
 */
object MediaFileUtils {

    private val MEDIA_EXTENSIONS = setOf(
        // audio containers
        "mp3", "wav", "wave", "m4a", "aac", "flac", "ogg", "oga", "opus", "amr", "3ga",
        // video containers (only the audio track is used)
        "mp4", "m4v", "mov", "3gp", "3g2", "webm", "mkv", "ts",
    )

    fun isMediaFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in MEDIA_EXTENSIONS
    }

    /** Recursively walks a SAF directory tree and returns every file with a recognized media extension. */
    fun listMediaFilesRecursively(root: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()

        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                when {
                    child.isDirectory -> walk(child)
                    child.isFile && isMediaFile(name) -> result.add(child)
                }
            }
        }

        walk(root)
        return result
    }
}
