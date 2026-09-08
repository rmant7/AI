package ai.localstudio.app.history

import ai.localstudio.app.Message
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredMessage(
    val role: String,
    val body: String,
    val details: String? = null,
    val isError: Boolean = false,
    /** Defaults to 0 so conversations saved before timestamps existed still decode; the UI hides a zero. */
    val timestamp: Long = 0L,
    /** Null for every conversation saved before image attachments existed. */
    val imageDataUri: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    /** Derived from the first thing the user said — recomputed on every save. */
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
    /**
     * A name the user typed, which wins over [title] and survives every
     * later message. Nullable with a default so conversations saved before
     * renaming existed still decode.
     */
    val customTitle: String? = null,
) {
    /** What to show in the history list: the user's own name if there is one. */
    val displayTitle: String get() = customTitle?.takeIf { it.isNotBlank() } ?: title
}

/**
 * Persists conversations to disk so closing the app — or the process simply
 * being killed in the background, which on Android is routine — does not
 * erase a chat the user is in the middle of.
 *
 * One file per conversation under `filesDir/conversations/`, named by id.
 * There is no database because the access pattern is trivial: list all
 * (there will be dozens at most, not thousands) and load/save one at a time.
 */
class ChatHistoryStore(context: Context) {

    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun save(conversation: Conversation) {
        if (conversation.messages.isEmpty()) return
        runCatching {
            file(conversation.id).writeText(json.encodeToString(Conversation.serializer(), conversation))
        }
    }

    fun load(id: String): Conversation? = runCatching {
        file(id).takeIf { it.isFile }?.let { json.decodeFromString(Conversation.serializer(), it.readText()) }
    }.getOrNull()

    /** Newest first — that is the order a history list should show them in. */
    fun list(): List<Conversation> = dir.listFiles { f -> f.extension == "json" }
        ?.mapNotNull { f -> runCatching { json.decodeFromString(Conversation.serializer(), f.readText()) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        .orEmpty()

    fun delete(id: String) {
        file(id).delete()
    }

    /**
     * Renames one conversation, leaving its messages alone.
     *
     * Read-modify-write rather than taking a whole [Conversation] from the
     * caller: the history screen only ever holds the list it rendered, and
     * writing that back would undo any message the chat screen persisted in
     * between. A blank name clears the custom title and lets the derived one
     * take over again.
     */
    fun rename(id: String, title: String) {
        val existing = load(id) ?: return
        runCatching {
            file(id).writeText(
                json.encodeToString(
                    Conversation.serializer(),
                    existing.copy(customTitle = title.trim().ifBlank { null }),
                ),
            )
        }
    }

    private fun file(id: String) = File(dir, "$id.json")

    companion object {
        fun titleFor(messages: List<StoredMessage>): String =
            messages.firstOrNull { it.role == "Вы" }?.body?.take(60)?.trim().orEmpty()
                .ifBlank { "Новый чат" }
    }
}

fun Message.toStored() = StoredMessage(role, body, details, isError, timestamp, imageDataUri)
fun StoredMessage.toMessage() = Message(role, body, details, isError, timestamp, imageDataUri)
