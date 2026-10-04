package ai.localstudio.model

import java.security.MessageDigest

/**
 * Identifies an embedding vector space: two vectors are comparable only if
 * they come from the same space. Derived from everything that changes the
 * vectors — the model, the exact weights (a Q4 and a Q8 of the same model
 * produce slightly different vectors, which quietly degrades retrieval when
 * mixed in one index), dimensions, pooling, normalization, prefixes and the
 * similarity function.
 *
 * A stored index records the id it was built with; a different id for the
 * model now selected means "reindex required" — keyed on the space, not on
 * the model id, so swapping to an identical re-upload doesn't force a
 * reindex and a silent upstream change does.
 */
object EmbeddingSpace {

    private const val VERSION = "es1"

    /** Null when [model] has no embedding facet for [capability]. */
    fun idFor(
        model: ModelDefinition,
        variant: ModelVariant,
        capability: CapabilityId = Capabilities.TEXT_EMBEDDING,
    ): String? {
        val facet = model.facet(capability) as? EmbeddingFacet ?: return null
        val weights = variant.artifacts
            .filterNot { it.optional }
            .sortedBy { it.fileName }
            .joinToString(",") { it.sha256 ?: "${sourceIdentity(it.source)}#${it.sizeBytes}" }
        val canonical = listOf(
            VERSION,
            model.id.id,
            capability.id,
            weights,
            facet.dimensions.toString(),
            facet.pooling,
            facet.normalized.toString(),
            facet.queryPrefix.orEmpty(),
            facet.documentPrefix.orEmpty(),
            facet.similarity,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return VERSION + "-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun sourceIdentity(source: ArtifactSource): String = when (source) {
        is ArtifactSource.HuggingFace -> "hf:${source.repo}@${source.revision}/${source.path}"
        is ArtifactSource.DirectUrl -> "url:${source.url}"
    }
}
