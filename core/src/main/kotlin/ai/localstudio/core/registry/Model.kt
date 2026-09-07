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

    /**
     * Google's LiteRT-LM, via the Google Tensor SDK — the only runtime that
     * can hand generation to a Pixel's TPU/NPU instead of the CPU. A
     * `.litertlm` artifact, not a GGUF, with its own native library
     * entirely separate from [LLAMA_CPP].
     */
    @SerialName("litert")
    LITERT("litert"),

    @SerialName("sherpa_onnx")
    SHERPA_ONNX("sherpa_onnx"),

    /** OpenAI-compatible endpoint (Ollama, llama-server) — development mode only. */
    @SerialName("remote_openai")
    REMOTE_OPENAI("remote_openai"),

    /**
     * In-process stub: no weights, no network. Exists so the app runs — and can
     * be demonstrated — before any real runtime is installed, and so tests can
     * exercise the full stack without one.
     */
    @SerialName("stub")
    STUB("stub"),

    /**
     * Not a real backend of its own — wraps an ordered list of other runtimes
     * (see [ai.localstudio.core.runtime.FallbackTextRuntime]) and tries them
     * in order within one turn, so a local model that throws or produces
     * nothing falls through to a configured cloud provider instead of the
     * turn failing outright.
     */
    @SerialName("fallback_chain")
    FALLBACK_CHAIN("fallback_chain");

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
    /** Peak RAM measured on a device. Null until someone has actually measured it. */
    val requiredRamBytes: Long? = null,
    val requiresGpu: Boolean = false,
    val requiresNpu: Boolean = false,
    val minAndroidApi: Int = 0,
    val referenceTokensPerSecond: Double? = null,
) {
    init {
        require(fileSizeBytes > 0) { "fileSizeBytes must be positive for $artifact" }
        require(requiredRamBytes == null || requiredRamBytes > 0) {
            "requiredRamBytes must be positive for $artifact"
        }
    }

    /**
     * RAM to plan for. A catalog entry resolved from a model hub knows the file
     * size and nothing else, so an unmeasured binding falls back to an estimate.
     *
     * The estimate is intentionally crude and intentionally high: weights are
     * the bulk of the footprint but not all of it — the KV cache, activations
     * and the loader's own copies sit on top. It exists so an unmeasured model
     * is planned for pessimistically rather than optimistically, and the first
     * real measurement on a device should replace it.
     */
    val effectiveRequiredRamBytes: Long
        get() = requiredRamBytes ?: (fileSizeBytes * ESTIMATED_RAM_NUMERATOR / ESTIMATED_RAM_DENOMINATOR)

    /** True when the RAM figure is a guess rather than a measurement — worth showing in the UI. */
    val isRamEstimated: Boolean get() = requiredRamBytes == null

    private companion object {
        const val ESTIMATED_RAM_NUMERATOR = 13L
        const val ESTIMATED_RAM_DENOMINATOR = 10L
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
