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
 *
 * Known limitation, deliberate for now: every non-optional artifact of the
 * variant counts as an input, so a change to a file that doesn't actually
 * affect the vectors (a config the embedding path never reads) still yields
 * a new id — a false "reindex required", never a missed one. Narrowing this
 * to the artifacts and runtime config the embedding path really consumes is
 * a decision for when installation/runtime integration exists (Phase 3+).
 *
 * A dynamic source ([ArtifactSource.HuggingFaceSelection]) only identifies
 * the *selection rule*, not the bytes: an upstream re-upload under the same
 * name keeps the same id. That is the price of such an entry being
 * UNVERIFIED; the installation layer is expected to key an installed copy on
 * what it actually resolved.
 *
 * 128 bits of the SHA-256: ids are long-lived and decide whether an index is
 * still valid, so collision room is not worth saving a few characters on.
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
        return VERSION + "-" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun sourceIdentity(source: ArtifactSource): String = when (source) {
        is ArtifactSource.HuggingFace -> "hf:${source.repo}@${source.revision}/${source.path}"
        is ArtifactSource.DirectUrl -> "url:${source.url}"
        is ArtifactSource.HuggingFaceSelection -> "hfsel:${source.repoIds.joinToString("|")}@${source.revision}/${selectorIdentity(source.file)}"
    }

    private fun selectorIdentity(selector: FileSelector): String = when (selector) {
        is FileSelector.ByQuantization -> "quant:${selector.quantPriority.joinToString("|")}*${selector.extension}"
        is FileSelector.ExactName -> "exact:${selector.fileName}"
    }
}
