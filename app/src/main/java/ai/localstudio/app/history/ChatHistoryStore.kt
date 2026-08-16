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
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
)

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

    private fun file(id: String) = File(dir, "$id.json")

    companion object {
        fun titleFor(messages: List<StoredMessage>): String =
            messages.firstOrNull { it.role == "Вы" }?.body?.take(60)?.trim().orEmpty()
                .ifBlank { "Новый чат" }
    }
}

fun Message.toStored() = StoredMessage(role, body, details, isError)
fun StoredMessage.toMessage() = Message(role, body, details, isError)
