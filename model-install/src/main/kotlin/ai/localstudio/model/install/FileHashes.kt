package ai.localstudio.model.install

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * sha256 of local files that have no install manifest to say what they are
 * (legacy downloads), remembered by path with the size and modification
 * time they had when hashed -- the same shortcut git's index takes. A file
 * whose size or time changed is unknown again until hashed again; nothing
 * is ever assumed from a name or a size alone.
 */
class FileHashes(private val file: File) {
    @Serializable
    private data class Entry(val sizeBytes: Long, val modifiedAtEpochMs: Long, val sha256: String)

    @Serializable
    private data class Stored(val schema: Int = 1, val files: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: Stored? = null

    /** [target]'s sha256 when it was hashed as it is now; null when never, or when it changed since. */
    @Synchronized
    fun known(target: File): String? {
        val entry = read().files[target.absolutePath] ?: return null
        return entry.sha256.takeIf { target.isFile && target.length() == entry.sizeBytes && target.lastModified() == entry.modifiedAtEpochMs }
    }

    /** [target]'s sha256, read through once unless [known]; remembered for next time. */
    fun hash(target: File): String {
        known(target)?.let { return it }
        val size = target.length()
        val modified = target.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        // Changed while being read: the hash describes no one state of the file, so it is not kept.
        if (target.length() == size && target.lastModified() == modified) remember(target.absolutePath, Entry(size, modified, sha))
        return sha
    }

    @Synchronized
    private fun remember(path: String, entry: Entry) {
        val stored = read()
        write(stored.copy(files = stored.files + (path to entry)))
    }

    private fun read(): Stored = cache ?: (
        runCatching { file.takeIf { it.isFile }?.let { json.decodeFromString(Stored.serializer(), it.readText()) } }.getOrNull() ?: Stored()
        ).also { cache = it }

    private fun write(stored: Stored) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(Stored.serializer(), stored))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not write ${file.path}" }
        }
        cache = stored
    }
}
