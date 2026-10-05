package ai.localstudio.sdk

/** What a model can be asked to do. */
enum class LocalCapability { TEXT, TRANSLATION, VISION }

/** What a check on this device observed for one capability. */
enum class CheckResult { PASS, FAIL, NOT_TESTED }

/** An image handed to a model: the encoded file (PNG, JPEG, ...), not a path or a URI. */
class LocalImage(bytes: ByteArray, val mimeType: String) {
    private val content = bytes.copyOf()

    val bytes: ByteArray get() = content.copyOf()

    override fun equals(other: Any?): Boolean = other is LocalImage && other.mimeType == mimeType && other.content.contentEquals(content)

    override fun hashCode(): Int = 31 * content.contentHashCode() + mimeType.hashCode()

    override fun toString(): String = "LocalImage($mimeType, ${content.size} bytes)"
}

/** Text, and any number of images in the order the model should see them. */
data class LocalAiInput(
    val text: String,
    val images: List<LocalImage> = emptyList(),
    val systemPrompt: String? = null,
) {
    /** What a model needs to take this input: VISION as soon as one image is attached. */
    val requiredCapabilities: Set<LocalCapability>
        get() = if (images.isEmpty()) setOf(LocalCapability.TEXT) else setOf(LocalCapability.TEXT, LocalCapability.VISION)
}

data class GenerationOptions(
    val maxTokens: Int = 1024,
    /** 0 = deterministic. */
    val temperature: Double = 0.7,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(temperature >= 0.0) { "temperature must not be negative" }
    }
}

/** A language by its code (ISO 639-1 where one exists, e.g. "fr", "crs") and its English name. */
data class Language(val code: String, val name: String)

data class TranslationRequest(val text: String, val source: Language, val target: Language)

/**
 * An installed model. [capabilities]: what its installed files allow
 * (VISION only with its projector). [verified]: what a check on this device
 * observed -- absent or NOT_TESTED until one ran.
 */
data class LocalModel(
    val id: String,
    val displayName: String,
    val capabilities: Set<LocalCapability>,
    val verified: Map<LocalCapability, CheckResult> = emptyMap(),
    val sizeBytes: Long,
) {
    fun proven(capability: LocalCapability): Boolean = verified[capability] == CheckResult.PASS
}

/** A model discovery found: one model, all of its files ([sizeBytes] counts them all). */
data class ModelCandidate(
    val id: String,
    val repository: String,
    val sizeBytes: Long,
    /** What its files allow -- VISION only when it comes with a usable projector. */
    val capabilities: Set<LocalCapability>,
    val installed: Boolean,
    val verified: Map<LocalCapability, CheckResult> = emptyMap(),
    val notes: List<String> = emptyList(),
)

sealed interface InstallProgress {
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : InstallProgress
    data object Checking : InstallProgress
    data class Done(val verified: Map<LocalCapability, CheckResult>) : InstallProgress
    data class Failed(val reason: String) : InstallProgress
}

/** Why a request could not be served -- a caller can tell these apart without parsing messages. */
sealed class LocalAiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoModel(val capability: LocalCapability) : LocalAiException("no installed model can do ${capability.name.lowercase()}")
    class UnknownModel(val modelId: String) : LocalAiException("no installed model \"$modelId\"")
    class ImageNotSeen(val reason: String) : LocalAiException("the image was not seen: $reason")
    class NotEnoughMemory(val neededBytes: Long, val availableBytes: Long) :
        LocalAiException("not enough memory: needs ~${neededBytes / 1_000_000} MB, ~${availableBytes / 1_000_000} MB available")
    class Failed(reason: String, cause: Throwable? = null) : LocalAiException(reason, cause)
}
