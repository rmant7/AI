package ai.localstudio.model

/**
 * Structural and integrity rules for a decoded catalog. A model that breaks
 * any rule is dropped (with a [CatalogViolation] saying why); the rest of the
 * catalog stays usable.
 *
 * Integrity is tiered by [CatalogStatus]:
 * - VERIFIED: every artifact has a size and a sha256, every Hugging Face
 *   source is pinned to a commit. A direct URL is acceptable here only
 *   because the sha256 then pins the bytes.
 * - EXPERIMENTAL: sizes known, Hugging Face sources pinned; sha256 optional.
 * - UNVERIFIED / DEPRECATED / WITHDRAWN: structure only.
 *
 * Every source, at every level, must be https and on the allowlist for the
 * given [CatalogTrust].
 */
object CatalogValidator {

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun validate(document: CatalogDocument, trust: CatalogTrust): ValidatedCatalog {
        val allowedHosts = when (trust) {
            CatalogTrust.Bundled -> document.allowedHosts
            is CatalogTrust.Remote -> trust.allowedHosts
        }.mapTo(hashSetOf()) { it.lowercase() }

        val violations = mutableListOf<CatalogViolation>()
        val seenModels = hashSetOf<ModelId>()
        val seenVariants = hashSetOf<VariantId>()
        val accepted = mutableListOf<ModelDefinition>()

        for ((index, model) in document.models.withIndex()) {
            val problems = mutableListOf<String>()
            val path = "models[$index]"

            if (!seenModels.add(model.id)) problems += "duplicate model id ${model.id}"
            if (model.displayName.isBlank()) problems += "displayName is blank"
            if (model.capabilities.isEmpty()) problems += "declares no capabilities"
            if (model.variants.isEmpty()) problems += "declares no variants"

            for ((capability, facet) in model.capabilities) {
                if (facet is JsonCapabilityFacet && facet.decodeError != null) {
                    problems += "capability $capability: unreadable facet (${facet.decodeError})"
                }
                val missing = facet.requiresRoles.filter { role -> model.variants.none { role in it.roles } }
                if (missing.isNotEmpty()) problems += "capability $capability requires roles $missing that no variant provides"
            }

            for (variant in model.variants) {
                if (!seenVariants.add(variant.id)) problems += "duplicate variant id ${variant.id}"
                problems += checkVariant(variant, model.status, allowedHosts).map { "variant ${variant.id}: $it" }
            }

            if (problems.isEmpty()) {
                accepted += model
            } else {
                violations += problems.map { CatalogViolation(model.id.id, path, it) }
            }
        }

        return ValidatedCatalog(document.catalogId, document.catalogVersion, accepted, violations)
    }

    private fun checkVariant(variant: ModelVariant, status: CatalogStatus, allowedHosts: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        if (variant.artifacts.isEmpty()) problems += "declares no artifacts"
        if (variant.bindings.isEmpty()) problems += "declares no runtime bindings"

        val names = hashSetOf<String>()
        for (artifact in variant.artifacts) {
            val label = "artifact ${artifact.fileName}"
            if (!names.add(artifact.fileName)) problems += "$label: duplicate file name"
            unsafeFileName(artifact.fileName)?.let { problems += "$label: $it" }
            if (artifact.sizeBytes < 0) problems += "$label: negative size"
            artifact.sha256?.let { if (!SHA256.matches(it)) problems += "$label: sha256 must be 64 lowercase hex characters" }
            artifact.unpack?.let { unpack ->
                if (unpack.format !in UnpackSpec.KNOWN_FORMATS) problems += "$label: unknown archive format ${unpack.format}"
                if (unpack.unpackedSizeBytes <= 0) problems += "$label: unpackedSizeBytes must be positive"
            }
            for (source in listOf(artifact.source) + artifact.mirrors) {
                sourceProblem(source, allowedHosts)?.let { problems += "$label: $it" }
            }
            problems += integrityProblems(artifact, status).map { "$label: $it" }
        }

        for (binding in variant.bindings) {
            val label = "binding ${binding.runtime}"
            if (binding.requiredRoles.isEmpty()) problems += "$label: requires no artifact roles"
            val notShipped = binding.requiredRoles - variant.mandatoryRoles
            if (notShipped.isNotEmpty()) problems += "$label: required roles $notShipped are missing or optional"
            val unknownOptional = binding.optionalRoles - variant.roles
            if (unknownOptional.isNotEmpty()) problems += "$label: optional roles $unknownOptional are not in this variant"
            if (binding.minAndroidApi < 0) problems += "$label: negative minAndroidApi"
        }

        for ((metric, value) in variant.metrics) {
            if (metric.isBlank()) problems += "blank metric id"
            if (value.isNaN() || value < 0.0 || value > 1.0) problems += "metric $metric must be within 0..1"
        }
        if (variant.resources.ramEstimateFactor < 1.0) problems += "ramEstimateFactor below 1.0 would under-plan RAM"
        variant.resources.measuredPeakRamBytes?.let { if (it <= 0) problems += "measuredPeakRamBytes must be positive" }
        return problems
    }

    private fun integrityProblems(artifact: ArtifactSpec, status: CatalogStatus): List<String> {
        val problems = mutableListOf<String>()
        val pinsRequired = status == CatalogStatus.VERIFIED || status == CatalogStatus.EXPERIMENTAL
        if (pinsRequired) {
            if (artifact.sizeBytes <= 0) problems += "${status.name.lowercase()} requires a known size"
            val unpinned = (listOf(artifact.source) + artifact.mirrors)
                .filterIsInstance<ArtifactSource.HuggingFace>()
                .filterNot { it.isPinned }
            if (unpinned.isNotEmpty()) {
                problems += "${status.name.lowercase()} requires Hugging Face sources pinned to a commit, got ${unpinned.map { it.revision }}"
            }
        }
        if (status == CatalogStatus.VERIFIED && artifact.sha256 == null) problems += "verified requires sha256"
        return problems
    }

    private fun sourceProblem(source: ArtifactSource, allowedHosts: Set<String>): String? {
        if (source.scheme != "https") return "source must be https, got ${source.scheme ?: "no scheme"}"
        val host = source.host ?: return "source has no host"
        if (host !in allowedHosts) return "host $host is not in the allowlist"
        if (source is ArtifactSource.HuggingFace) {
            if (source.repo.isBlank() || source.revision.isBlank() || source.path.isBlank()) return "huggingface source needs repo, revision and path"
        }
        return null
    }

    /** Null when [name] is a safe relative path inside an install directory. */
    private fun unsafeFileName(name: String): String? = when {
        name.isBlank() -> "blank file name"
        name.startsWith("/") -> "absolute file name"
        '\\' in name || '\u0000' in name -> "file name contains a backslash or NUL"
        name.split('/').any { it == ".." || it == "." || it.isEmpty() } -> "file name must not contain '.', '..' or empty segments"
        else -> null
    }
}
