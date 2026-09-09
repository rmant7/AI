package ai.localstudio.app.attach

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AttachedDocument(
    val id: String,
    val name: String,
    // Kept, not just a count: InMemoryMemoryProvider holds nothing across a
    // process restart, so this is what lets a document's content come back
    // after the app is killed and relaunched instead of leaving this screen
    // listing files whose content silently isn't searchable anymore.
    val chunks: List<String>,
    val addedAt: Long,
    /**
     * The chat this was attached in. Defaults to [UNKNOWN_CONVERSATION] so a
     * document saved before this field existed still decodes — it shows up
     * grouped under "other/legacy" in the Files screen rather than crashing
     * on load or being silently dropped.
     */
    val conversationId: String = UNKNOWN_CONVERSATION,
) {
    val chunkCount: Int get() = chunks.size

    companion object {
        const val UNKNOWN_CONVERSATION = ""
    }
}

@Serializable
private data class DocumentList(val documents: List<AttachedDocument> = emptyList())

/**
 * Answers "what did I attach, and how do I get rid of it" — a question this
 * app previously had no screen for, even though [ai.localstudio.app.AppContainer]
 * silently accepted uploads into memory the whole time.
 */
class DocumentStore(context: Context) {

    private val file = File(context.filesDir, "attachments.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<AttachedDocument> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString(DocumentList.serializer(), file.readText()).documents
            .sortedByDescending { it.addedAt }
    }.getOrElse { emptyList() }

    fun add(document: AttachedDocument) {
        save(list() + document)
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    private fun save(documents: List<AttachedDocument>) {
        file.writeText(json.encodeToString(DocumentList.serializer(), DocumentList(documents)))
    }
}
