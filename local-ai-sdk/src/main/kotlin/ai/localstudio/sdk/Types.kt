package ai.localstudio.sdk

/** What a model can be asked to do. */
enum class LocalCapability { TEXT, TRANSLATION, VISION }

/**
 * What a check on this device says about one capability now. STALE: it
 * was checked, but something it depended on has changed since (other
 * files, another device, another runtime build, other questions) -- not a
 * pass, and not "never checked" either.
 *
 * Capability, not performance: PASS means the model did the job -- gave the
 * right answers to every question of the check -- however long it took. A
 * 7B vision model at a few tokens per second passes VISION; speed is a
 * separate measurement, never folded into this. FAIL is the answers, not
 * the runtime: a model whose projector loads and runs but that answers an
 * image question wrongly fails, and stays failed until a check passes.
 */
enum class CheckResult { PASS, FAIL, NOT_TESTED, STALE }

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
    /**
     * How long the model may work on this request -- time spent waiting for
     * another model's turn does not count. Past it the request fails with
     * [LocalAiException.Timeout]; a native call that hangs never holds the
     * caller forever.
     */
    val timeoutMs: Long = 5 * 60_000L,
    /** The hard limit on everything, waiting included. */
    val deadlineMs: Long = 2 * timeoutMs,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(temperature >= 0.0) { "temperature must not be negative" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(deadlineMs >= timeoutMs) { "deadlineMs must not be shorter than timeoutMs" }
    }
}

/** A language by its code (ISO 639-1 where one exists, e.g. "fr", "crs") and its English name. */
data class Language(val code: String, val name: String)

data class TranslationRequest(val text: String, val source: Language, val target: Language)

/**
 * Which bytes a model is: the same [ArtifactRef] is the same model byte for
 * byte. A check on a device is about one [ArtifactRef] on that device.
 */
data class ArtifactRef(
    val repository: String,
    val revision: String,
    val mainFile: String,
    val projectorFile: String? = null,
) {
    /** Its stable string form -- [ModelCandidate.id] is this. */
    val key: String get() = listOfNotNull(repository, revision, mainFile, projectorFile).joinToString("|")
}

/** Where a model's files came from. */
enum class ModelSource {
    /** Shipped in the app's own catalog. */
    CATALOG,
    /** Found by discovery and taken in after a check on this device. */
    DISCOVERED,
    /** Added by the user by its repository. */
    CUSTOM,
    /**
     * Part of the phone, not of the app: Gemini Nano through Android's
     * AICore. No file of the caller's ([LocalModel.artifact] is null,
     * [LocalModel.sizeBytes] 0); offered only while the system says it is
     * ready on this device. Its checks are tied to the system service's
     * version, so an update of it makes them STALE.
     */
    SYSTEM,
}

/**
 * Which models a caller wants -- the catalog's filters as data, so every
 * screen (this app's, IntelliVerse's, a mini app's) means the same thing by
 * them. Every set criterion must hold; unset ones do not filter.
 */
data class ModelQuery(
    /** Offered for this capability (its files allow it). */
    val capability: LocalCapability? = null,
    /**
     * What a check on this device says now: for [capability] when one is
     * set, otherwise for any capability. setOf(PASS) = proven only.
     */
    val checkResults: Set<CheckResult>? = null,
    val sources: Set<ModelSource>? = null,
    /** Every word must appear in the id, the name or (for a candidate) the repository; case ignored. */
    val text: String = "",
) {
    fun matches(model: LocalModel): Boolean =
        offered(model.capabilities) && checked(model.verified) &&
            (sources == null || model.source in sources) &&
            words(model.id, model.displayName)

    /** A candidate's source is always DISCOVERED. */
    fun matches(candidate: ModelCandidate): Boolean =
        offered(candidate.capabilities) && checked(candidate.verified) &&
            (sources == null || ModelSource.DISCOVERED in sources) &&
            words(candidate.id, candidate.repository)

    private fun offered(capabilities: Set<LocalCapability>) = capability == null || capability in capabilities

    private fun checked(verified: Map<LocalCapability, CheckResult>): Boolean {
        val wanted = checkResults ?: return true
        fun result(c: LocalCapability) = verified[c] ?: CheckResult.NOT_TESTED
        return if (capability != null) result(capability) in wanted else LocalCapability.entries.any { result(it) in wanted }
    }

    private fun words(vararg fields: String): Boolean {
        val wanted = text.lowercase().split(' ', ',').filter { it.isNotBlank() }
        if (wanted.isEmpty()) return true
        val haystack = fields.joinToString(" ").lowercase()
        return wanted.all { it in haystack }
    }

    companion object {
        /** What a feature can rely on for [capability] on this device: a current PASS. */
        fun proven(capability: LocalCapability) = ModelQuery(capability = capability, checkResults = setOf(CheckResult.PASS))
    }
}

/**
 * An installed model -- every installed model, checked or not.
 * [capabilities]: what its installed files allow it to be asked (VISION
 * only with its projector) -- a possibility, not a promise. [verified]:
 * what a check on this device says now. Use [proven] to decide what to
 * rely on.
 */
data class LocalModel(
    val id: String,
    val displayName: String,
    val capabilities: Set<LocalCapability>,
    val verified: Map<LocalCapability, CheckResult> = emptyMap(),
    val sizeBytes: Long,
    /** Its bytes, when the install records where they came from; null for a model installed before that was kept, and for a [ModelSource.SYSTEM] one. */
    val artifact: ArtifactRef? = null,
    val source: ModelSource = ModelSource.CATALOG,
) {
    /** A current PASS on this device -- not STALE, not merely offered. */
    fun proven(capability: LocalCapability): Boolean = verified[capability] == CheckResult.PASS
}

/** A model discovery found: one model, all of its files ([sizeBytes] counts them all). */
data class ModelCandidate(
    /** [artifact]'s key: what install() and verify() take. */
    val id: String,
    val artifact: ArtifactRef,
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
    class Timeout(val limitMs: Long) : LocalAiException("no complete answer within ${limitMs / 1000} s")
    class Failed(reason: String, cause: Throwable? = null) : LocalAiException(reason, cause)
}
