package ai.localstudio.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `content` is a [JsonElement], not a plain string, because the OpenAI-
 * compatible vision format needs an array of parts (`[{type:"text",...},
 * {type:"image_url",...}]`) instead of a bare string the moment an image is
 * attached — [JsonPrimitive] for the plain-text case, [JsonArray] for vision.
 * A response is always read back as plain text in this app, and
 * [JsonElement] deserializes either shape without extra work.
 */
@Serializable
data class ChatMessage(val role: String, val content: JsonElement)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stop: List<String>? = null,
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class StreamChoice(
    val delta: Delta = Delta(),
    val index: Int = 0,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatStreamChunk(val choices: List<StreamChoice> = emptyList())

@Serializable
data class ChatChoice(val message: ChatMessage? = null, val index: Int = 0)

@Serializable
data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
data class EmbeddingRequest(val model: String, val input: List<String>)

@Serializable
data class EmbeddingData(val embedding: List<Float> = emptyList(), val index: Int = 0)

@Serializable
data class EmbeddingResponse(val data: List<EmbeddingData> = emptyList())

@Serializable
data class TranscriptionSegmentDto(
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = "",
)

@Serializable
data class TranscriptionResponse(
    val text: String = "",
    val language: String? = null,
    val segments: List<TranscriptionSegmentDto> = emptyList(),
)

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(val message: String? = null, val type: String? = null)

/**
 * `encodeDefaults` is on deliberately: `stream` defaults to true here but to
 * *false* on every server, so omitting it because it matches our default turns
 * a streaming request into a blocking one.
 */
internal val openAiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
