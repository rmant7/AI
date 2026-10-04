package ai.localstudio.model

import kotlinx.serialization.Serializable

/**
 * What a model can do — an open string id, not an enum.
 *
 * A catalog may name a capability this build has never heard of (a newer
 * remote catalog, say). That must not break parsing: the model is kept, its
 * capability simply has no typed facet and no runtime here to execute it.
 * The built-in ids live in [Capabilities].
 */
@Serializable
@JvmInline
value class CapabilityId(val id: String) {
    init {
        require(id.isNotBlank()) { "CapabilityId must not be blank" }
    }

    override fun toString(): String = id
}

/**
 * An inference engine that can execute a model (llama.cpp, whisper.cpp,
 * sherpa-onnx, ...) — a string for the same reason as [CapabilityId]: a
 * catalog may reference a runtime this build doesn't ship, and that is a
 * "runtime missing" condition to report, not a parse failure. Built-ins in
 * [Runtimes].
 */
@Serializable
@JvmInline
value class RuntimeId(val id: String) {
    init {
        require(id.isNotBlank()) { "RuntimeId must not be blank" }
    }

    override fun toString(): String = id
}

/**
 * What one file of a variant is *for*: the main weights, a vision encoder,
 * a vocoder, a tokenizer, ONNX external data... Replaces the per-type special
 * fields (an "mmproj file name" on one seed type, nothing equivalent on the
 * others) with one mechanism every model type uses. Built-ins in [ArtifactRoles].
 */
@Serializable
@JvmInline
value class ArtifactRole(val id: String) {
    init {
        require(id.isNotBlank()) { "ArtifactRole must not be blank" }
    }

    override fun toString(): String = id
}

/**
 * The broadest grouping: a model line across sizes and releases ("gemma-3"),
 * above [ModelId] ("gemma-3-4b-it"), which is above [VariantId]
 * ("gemma-3-4b-it@q4_k_m"). Used for update detection and comparison across
 * sizes — not for picking a quantization, which is a variant-level choice.
 */
@Serializable
@JvmInline
value class ModelFamilyId(val id: String) {
    init {
        require(id.isNotBlank()) { "ModelFamilyId must not be blank" }
    }

    override fun toString(): String = id
}

/** One logical model — its capabilities and languages, independent of quantization. */
@Serializable
@JvmInline
value class ModelId(val id: String) {
    init {
        require(id.isNotBlank()) { "ModelId must not be blank" }
    }

    override fun toString(): String = id
}

/** One installable form of a model: a specific quantization / file set. Unique across a whole catalog. */
@Serializable
@JvmInline
value class VariantId(val id: String) {
    init {
        require(id.isNotBlank()) { "VariantId must not be blank" }
    }

    override fun toString(): String = id
}

/** The capabilities this build knows by name. Anything else is still a valid [CapabilityId]. */
object Capabilities {
    val TEXT_GENERATION = CapabilityId("text.generation")
    val REASONING = CapabilityId("text.reasoning")
    val CODING = CapabilityId("text.coding")
    val TRANSLATION = CapabilityId("text.translation")
    val RERANKING = CapabilityId("text.rerank")
    val SPEECH_TO_TEXT = CapabilityId("audio.stt")
    val DIARIZATION = CapabilityId("audio.diarization")
    val TEXT_TO_SPEECH = CapabilityId("audio.tts")
    val VOICE_CLONING = CapabilityId("audio.voice_cloning")
    val VISION = CapabilityId("vision.understanding")
    val OCR = CapabilityId("vision.ocr")
    val VIDEO_UNDERSTANDING = CapabilityId("video.understanding")
    val TEXT_EMBEDDING = CapabilityId("embedding.text")
    val IMAGE_EMBEDDING = CapabilityId("embedding.image")

    val BUILT_IN: Set<CapabilityId> = setOf(
        TEXT_GENERATION, REASONING, CODING, TRANSLATION, RERANKING,
        SPEECH_TO_TEXT, DIARIZATION, TEXT_TO_SPEECH, VOICE_CLONING,
        VISION, OCR, VIDEO_UNDERSTANDING, TEXT_EMBEDDING, IMAGE_EMBEDDING,
    )
}

/** Runtime ids this codebase already uses or plans to. A catalog may name others. */
object Runtimes {
    val LLAMA_CPP = RuntimeId("llama_cpp")
    val WHISPER_CPP = RuntimeId("whisper_cpp")
    val VOSK = RuntimeId("vosk")
    val ONNX = RuntimeId("onnx")
    val SHERPA_ONNX = RuntimeId("sherpa_onnx")
    val AICORE = RuntimeId("aicore")
    val MEDIAPIPE = RuntimeId("mediapipe")
}

/** Artifact roles with an agreed meaning. A catalog may name others. */
object ArtifactRoles {
    val WEIGHTS = ArtifactRole("weights")

    /** A separate vision (or other modality) encoder — llama.cpp's "mmproj" is one. */
    val PROJECTOR = ArtifactRole("projector")
    val VOCODER = ArtifactRole("vocoder")
    val TOKENIZER = ArtifactRole("tokenizer")
    val CONFIG = ArtifactRole("config")

    /** ONNX external-data shards: must sit next to the model file under their exact names. */
    val ONNX_DATA = ArtifactRole("onnx_data")

    /** A compressed bundle unpacked on install (Vosk models, sherpa-onnx voice packs). */
    val ARCHIVE = ArtifactRole("archive")
    val RUNTIME_ASSET = ArtifactRole("runtime_asset")
}
