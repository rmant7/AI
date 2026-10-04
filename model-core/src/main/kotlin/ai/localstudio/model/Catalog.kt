package ai.localstudio.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The one catalog shape, used both for the catalog bundled in the APK and
 * for a remote one fetched later.
 *
 * [allowedHosts] is only authoritative in a bundled catalog: a remote
 * catalog is checked against the *bundled* allowlist and its own field is
 * ignored — see [CatalogTrust.Remote].
 */
@Serializable
data class CatalogDocument(
    val schema: Int,
    val catalogId: String,
    val catalogVersion: String,
    val allowedHosts: Set<String> = emptySet(),
    val models: List<ModelDefinition>,
)

/**
 * One problem found in a catalog. [modelId] null means the whole document is
 * unusable; otherwise only that model is dropped and the rest stay usable —
 * one bad entry in a remote catalog must not take every other model with it.
 */
data class CatalogViolation(val modelId: String?, val path: String, val message: String)

/** A catalog after decoding and validation: only the models that passed, plus why the others didn't. */
data class ValidatedCatalog(
    val catalogId: String?,
    val catalogVersion: String?,
    val models: List<ModelDefinition>,
    val violations: List<CatalogViolation>,
) {
    val isUsable: Boolean get() = violations.none { it.modelId == null }
}

sealed interface CatalogTrust {
    /** Shipped inside the app: its own [CatalogDocument.allowedHosts] is the allowlist. */
    data object Bundled : CatalogTrust

    /**
     * Fetched over the network: artifacts may only point at [allowedHosts] —
     * taken from the bundled catalog, never from the remote file itself, so a
     * tampered remote catalog can mis-describe a model but cannot send a
     * download anywhere new.
     */
    data class Remote(val allowedHosts: Set<String>) : CatalogTrust
}

object CatalogCodec {
    const val SUPPORTED_SCHEMA = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(document: CatalogDocument): String = json.encodeToString(CatalogDocument.serializer(), document)

    /**
     * Decodes [text] model by model: a model whose JSON doesn't decode (a
     * value this build doesn't understand, a missing field) becomes a
     * violation and is skipped, the others are kept. A document whose header
     * is unreadable, or whose [CatalogDocument.schema] is newer than
     * [SUPPORTED_SCHEMA], yields no models at all.
     */
    fun decode(text: String): Pair<CatalogDocument?, List<CatalogViolation>> {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return null to listOf(CatalogViolation(null, "$", "Not a JSON object"))

        val schema = (root["schema"] as? JsonPrimitive)?.intOrNull
            ?: return null to listOf(CatalogViolation(null, "schema", "Missing or non-integer schema"))
        if (schema > SUPPORTED_SCHEMA) {
            return null to listOf(CatalogViolation(null, "schema", "Schema $schema is newer than supported $SUPPORTED_SCHEMA"))
        }
        val catalogId = (root["catalogId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val catalogVersion = (root["catalogVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (catalogId.isNullOrBlank() || catalogVersion.isNullOrBlank()) {
            return null to listOf(CatalogViolation(null, "$", "catalogId and catalogVersion are required"))
        }
        val allowedHosts = (root["allowedHosts"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.lowercase() }
            ?.toSet()
            .orEmpty()

        val violations = mutableListOf<CatalogViolation>()
        val models = (root["models"] as? JsonArray ?: JsonArray(emptyList())).mapIndexedNotNull { index, element ->
            try {
                json.decodeFromJsonElement(ModelDefinition.serializer(), element)
            } catch (e: SerializationException) {
                violations += CatalogViolation(modelIdOf(element) ?: "#$index", "models[$index]", e.message ?: e.toString())
                null
            } catch (e: IllegalArgumentException) {
                violations += CatalogViolation(modelIdOf(element) ?: "#$index", "models[$index]", e.message ?: e.toString())
                null
            }
        }
        return CatalogDocument(schema, catalogId, catalogVersion, allowedHosts, models) to violations
    }

    private fun modelIdOf(element: JsonElement): String? =
        ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Decode + validate in one step — what a caller loading a catalog file normally wants. */
object CatalogLoader {
    fun load(text: String, trust: CatalogTrust): ValidatedCatalog {
        val (document, decodeViolations) = CatalogCodec.decode(text)
        if (document == null) return ValidatedCatalog(null, null, emptyList(), decodeViolations)
        val validated = CatalogValidator.validate(document, trust)
        return validated.copy(violations = decodeViolations + validated.violations)
    }
}
