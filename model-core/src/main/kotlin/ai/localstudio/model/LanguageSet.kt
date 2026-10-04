package ai.localstudio.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * A set of BCP-47 language tags, or "every language" — written in a catalog
 * as `"*"` or as an array (`["en", "he", "pt-BR"]`).
 *
 * Tags are normalized on the way in (see [LanguageTags.normalize]), so a
 * catalog saying "iw" and a requirement saying "he" still meet.
 */
@Serializable(with = LanguageSetSerializer::class)
sealed interface LanguageSet {
    fun contains(tag: String): Boolean

    /**
     * Unconstrained: the catalog states no restriction — written `"*"`.
     * Not a verified claim that every language works; most entries carry
     * this because their language support was simply never declared (the
     * legacy catalogue), and a model with a known list says so with [Of].
     * Matching treats it as "may be tried", ranking (later) as "unknown".
     */
    data object All : LanguageSet {
        override fun contains(tag: String): Boolean = true
    }

    class Of(tags: Collection<String>) : LanguageSet {
        val tags: Set<String> = tags.map(LanguageTags::normalize).toSortedSet()

        override fun contains(tag: String): Boolean {
            val wanted = LanguageTags.normalize(tag)
            return tags.any { LanguageTags.matches(supported = it, requested = wanted) }
        }

        override fun equals(other: Any?): Boolean = other is Of && other.tags == tags
        override fun hashCode(): Int = tags.hashCode()
        override fun toString(): String = "LanguageSet.Of($tags)"
    }

    companion object {
        fun of(vararg tags: String): LanguageSet = Of(tags.toList())
    }
}

object LanguageTags {
    /** Java's own legacy primary-subtag aliases — "iw" is still what some Android APIs hand back for Hebrew. */
    private val LEGACY_PRIMARY = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv")

    fun normalize(tag: String): String {
        val parts = tag.trim().replace('_', '-').lowercase().split('-').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Blank language tag" }
        val primary = LEGACY_PRIMARY[parts.first()] ?: parts.first()
        return (listOf(primary) + parts.drop(1)).joinToString("-")
    }

    /**
     * Exact match, or one side is a bare primary tag with the same language:
     * a model listing "pt" serves a request for "pt-br" and vice versa. Two
     * different regional/script tags of the same language do NOT match —
     * "zh-hans" output is not "zh-hant" output.
     */
    fun matches(supported: String, requested: String): Boolean {
        if (supported == requested) return true
        val supportedPrimary = supported.substringBefore('-')
        val requestedPrimary = requested.substringBefore('-')
        if (supportedPrimary != requestedPrimary) return false
        return supported == supportedPrimary || requested == requestedPrimary
    }
}

internal object LanguageSetSerializer : KSerializer<LanguageSet> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): LanguageSet {
        val input = decoder as? JsonDecoder ?: throw SerializationException("LanguageSet supports JSON only")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (element.isString && element.content == "*") LanguageSet.All
                else throw SerializationException("Language set must be \"*\" or an array, got $element")
            }
            is JsonArray -> LanguageSet.Of(element.map { it.jsonPrimitive.content })
            else -> throw SerializationException("Language set must be \"*\" or an array, got $element")
        }
    }

    override fun serialize(encoder: Encoder, value: LanguageSet) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("LanguageSet supports JSON only")
        val element: JsonElement = when (value) {
            LanguageSet.All -> JsonPrimitive("*")
            is LanguageSet.Of -> JsonArray(value.tags.map(::JsonPrimitive))
        }
        output.encodeJsonElement(element)
    }
}
