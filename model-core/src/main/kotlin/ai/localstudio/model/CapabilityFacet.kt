package ai.localstudio.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.reflect.KClass

/**
 * Capability-specific metadata: which languages a translation model covers,
 * an embedding model's dimensions and pooling, a TTS voice's sample rate.
 *
 * An open interface, not a sealed hierarchy: a new capability brings its own
 * facet type and registers it in [CapabilityFacets], nothing else changes.
 * A capability this build has no facet type for is kept verbatim as a
 * [JsonCapabilityFacet], so a newer catalog round-trips through an older
 * build without losing anything.
 */
interface CapabilityFacet {
    /**
     * Artifact roles a variant must carry for this capability to be offered
     * at all — vision on a llama.cpp model needs a [ArtifactRoles.PROJECTOR].
     * A variant without them simply doesn't offer the capability.
     */
    val requiresRoles: Set<ArtifactRole>
}

/** A known capability with nothing capability-specific to say beyond [requiresRoles]. */
@Serializable
data class GenericFacet(
    override val requiresRoles: Set<ArtifactRole> = emptySet(),
) : CapabilityFacet

/**
 * Direction matters: [sourceLanguages] is what a model reads, [targetLanguages]
 * what it can write. A model that understands Hebrew but can't produce it
 * must not be picked for "translate into Hebrew".
 *
 * [promptFormat] names how the runtime has to frame the request — "chat" for
 * an instruction-tuned decoder, "madlad_tag" for MADLAD-400's `<2xx> text` —
 * replacing the per-seed `isT5EncoderDecoder` flag.
 */
@Serializable
data class TranslationFacet(
    val sourceLanguages: LanguageSet,
    val targetLanguages: LanguageSet,
    val promptFormat: String? = null,
    override val requiresRoles: Set<ArtifactRole> = emptySet(),
) : CapabilityFacet

@Serializable
data class SpeechToTextFacet(
    val languages: LanguageSet,
    val streaming: Boolean = false,
    val timestamps: Boolean = false,
    override val requiresRoles: Set<ArtifactRole> = emptySet(),
) : CapabilityFacet

@Serializable
enum class ReferenceAudio {
    @SerialName("none")
    NONE,

    @SerialName("optional")
    OPTIONAL,

    @SerialName("required")
    REQUIRED,
}

@Serializable
data class TtsSpeaker(val id: String, val name: String? = null)

@Serializable
data class TtsFacet(
    val locales: LanguageSet,
    val sampleRateHz: Int,
    val speakers: List<TtsSpeaker> = emptyList(),
    val referenceAudio: ReferenceAudio = ReferenceAudio.NONE,
    val voiceCloning: Boolean = false,
    override val requiresRoles: Set<ArtifactRole> = emptySet(),
) : CapabilityFacet

/**
 * Everything that decides whether two vectors are comparable — see
 * [EmbeddingSpace]. [pooling] and [similarity] are strings ("mean"/"cls"/
 * "last", "cosine"/"dot"/"l2") rather than enums so a new value in a newer
 * catalog doesn't make the whole model unreadable.
 */
@Serializable
data class EmbeddingFacet(
    val dimensions: Int,
    val pooling: String,
    val normalized: Boolean,
    val queryPrefix: String? = null,
    val documentPrefix: String? = null,
    val similarity: String = "cosine",
    override val requiresRoles: Set<ArtifactRole> = emptySet(),
) : CapabilityFacet

/**
 * A facet kept as raw JSON: either a capability this build has no facet type
 * for, or a known one whose facet failed to decode ([decodeError] set — the
 * catalog validator rejects the model in that case, see [CatalogValidator]).
 * [requiresRoles] is still read from the raw object when present, so role
 * gating works for capabilities this build doesn't otherwise understand.
 */
data class JsonCapabilityFacet(
    val raw: JsonObject,
    val decodeError: String? = null,
) : CapabilityFacet {
    override val requiresRoles: Set<ArtifactRole> =
        (raw["requiresRoles"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }
            ?.map(::ArtifactRole)
            ?.toSet()
            .orEmpty()
}

/**
 * Which facet type each capability uses. Built-in capabilities without a
 * dedicated type get [GenericFacet]; anything not listed at all is unknown
 * and decodes to [JsonCapabilityFacet].
 */
object CapabilityFacets {
    private class Entry(val type: KClass<out CapabilityFacet>, val serializer: KSerializer<out CapabilityFacet>)

    private val typed: Map<CapabilityId, Entry> = mapOf(
        Capabilities.TRANSLATION to Entry(TranslationFacet::class, TranslationFacet.serializer()),
        Capabilities.SPEECH_TO_TEXT to Entry(SpeechToTextFacet::class, SpeechToTextFacet.serializer()),
        Capabilities.TEXT_TO_SPEECH to Entry(TtsFacet::class, TtsFacet.serializer()),
        Capabilities.TEXT_EMBEDDING to Entry(EmbeddingFacet::class, EmbeddingFacet.serializer()),
        Capabilities.IMAGE_EMBEDDING to Entry(EmbeddingFacet::class, EmbeddingFacet.serializer()),
    )
    private val generic = Entry(GenericFacet::class, GenericFacet.serializer())

    private fun entryFor(id: CapabilityId): Entry? = typed[id] ?: generic.takeIf { id in Capabilities.BUILT_IN }

    fun isKnown(id: CapabilityId): Boolean = entryFor(id) != null

    internal fun decode(json: Json, id: CapabilityId, element: JsonElement): CapabilityFacet {
        val raw = when (element) {
            is JsonObject -> element
            JsonNull -> JsonObject(emptyMap())
            else -> return JsonCapabilityFacet(JsonObject(emptyMap()), "facet for $id must be an object, got $element")
        }
        val entry = entryFor(id) ?: return JsonCapabilityFacet(raw)
        return try {
            json.decodeFromJsonElement(entry.serializer, raw)
        } catch (e: SerializationException) {
            JsonCapabilityFacet(raw, e.message ?: e.toString())
        } catch (e: IllegalArgumentException) {
            JsonCapabilityFacet(raw, e.message ?: e.toString())
        }
    }

    internal fun encode(json: Json, id: CapabilityId, facet: CapabilityFacet): JsonElement {
        if (facet is JsonCapabilityFacet) return facet.raw
        val entry = entryFor(id)
            ?: throw SerializationException("No facet type registered for $id; use JsonCapabilityFacet for unknown capabilities")
        if (!entry.type.isInstance(facet)) {
            throw SerializationException("Capability $id expects ${entry.type.simpleName}, got ${facet::class.simpleName}")
        }
        @Suppress("UNCHECKED_CAST")
        return json.encodeToJsonElement(entry.serializer as KSerializer<CapabilityFacet>, facet)
    }
}

/** `{"text.translation": {...}, "audio.stt": {...}}` — keyed by capability id, decoded per [CapabilityFacets]. */
internal object CapabilityMapSerializer : KSerializer<Map<CapabilityId, CapabilityFacet>> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): Map<CapabilityId, CapabilityFacet> {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Capabilities support JSON only")
        val element = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("capabilities must be an object keyed by capability id")
        return element.entries.associate { (key, value) ->
            val id = CapabilityId(key)
            id to CapabilityFacets.decode(input.json, id, value)
        }
    }

    override fun serialize(encoder: Encoder, value: Map<CapabilityId, CapabilityFacet>) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Capabilities support JSON only")
        output.encodeJsonElement(
            buildJsonObject {
                value.forEach { (id, facet) -> put(id.id, CapabilityFacets.encode(output.json, id, facet)) }
            },
        )
    }
}
