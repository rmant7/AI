package ai.localstudio.core.registry

import ai.localstudio.core.capability.Capability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An inference backend. A model is a file; a runtime is what executes it.
 * The same model may be executable by several runtimes with different
 * memory and acceleration characteristics.
 */
@Serializable
enum class RuntimeKind(val id: String) {
    @SerialName("llama_cpp")
    LLAMA_CPP("llama_cpp"),

    @SerialName("mediapipe")
    MEDIAPIPE("mediapipe"),

    @SerialName("mlc")
    MLC("mlc"),

    @SerialName("onnx_runtime")
    ONNX_RUNTIME("onnx_runtime"),

    @SerialName("whisper_cpp")
    WHISPER_CPP("whisper_cpp"),

    @SerialName("sherpa_onnx")
    SHERPA_ONNX("sherpa_onnx"),

    /** OpenAI-compatible endpoint (Ollama, llama-server) — development mode only. */
    @SerialName("remote_openai")
    REMOTE_OPENAI("remote_openai");

    companion object {
        private val byId = entries.associateBy(RuntimeKind::id)

        fun fromId(id: String): RuntimeKind =
            byId[id] ?: throw IllegalArgumentException("Unknown runtime: $id")
    }
}

/**
 * One concrete way to execute a model: an artifact plus the resources it needs.
 *
 * [referenceTokensPerSecond] is measured on a reference device and scaled by
 * [DeviceProfile.performanceIndex] when scoring, so speed stays comparable
 * across devices without benchmarking every model everywhere.
 */
@Serializable
data class RuntimeBinding(
    val runtime: RuntimeKind,
    val artifact: String,
    val fileSizeBytes: Long,
    val requiredRamBytes: Long,
    val requiresGpu: Boolean = false,
    val requiresNpu: Boolean = false,
    val minAndroidApi: Int = 0,
    val referenceTokensPerSecond: Double? = null,
) {
    init {
        require(fileSizeBytes > 0) { "fileSizeBytes must be positive for $artifact" }
        require(requiredRamBytes > 0) { "requiredRamBytes must be positive for $artifact" }
    }
}

/**
 * Quality signals used for ranking. Values are normalised to 0..100,
 * higher is better — including [asrAccuracy], which is stored as
 * `100 - WER%` so that every field points the same way.
 */
@Serializable
data class Benchmarks(
    val reasoning: Double? = null,
    val coding: Double? = null,
    val vision: Double? = null,
    val asrAccuracy: Double? = null,
    val general: Double? = null,
) {
    /** Quality signal for [capability], falling back to [general] when unmeasured. */
    fun scoreFor(capability: Capability): Double? = when (capability) {
        Capability.REASONING -> reasoning ?: general
        Capability.CODING -> coding ?: general
        Capability.VISION,
        Capability.OCR,
        Capability.IMAGE_UNDERSTANDING,
        Capability.VIDEO_UNDERSTANDING,
        -> vision ?: general

        Capability.SPEECH_TO_TEXT,
        Capability.SPEAKER_DIARIZATION,
        -> asrAccuracy ?: general

        else -> general
    }
}

/**
 * A model as the application knows it: an id, what it can do, and how it can
 * be run. Nothing here is specific to a vendor or to an inference engine.
 */
@Serializable
data class ModelDescriptor(
    val id: String,
    val family: String,
    val version: String,
    val parameterCount: Long,
    val quantization: String? = null,
    val contextLength: Int = 0,
    val languages: Set<String> = emptySet(),
    val capabilities: Set<Capability>,
    val license: String? = null,
    val sourceUrl: String? = null,
    val bindings: List<RuntimeBinding>,
    val benchmarks: Benchmarks = Benchmarks(),
) {
    init {
        require(id.isNotBlank()) { "Model id must not be blank" }
        require(capabilities.isNotEmpty()) { "Model $id declares no capabilities" }
        require(bindings.isNotEmpty()) { "Model $id declares no runtime bindings" }
    }

    fun supports(capability: Capability): Boolean = capability in capabilities
}

/** A catalog of models — the on-disk form of the registry. */
@Serializable
data class ModelCatalog(
    val updatedAt: String? = null,
    val models: List<ModelDescriptor>,
)
