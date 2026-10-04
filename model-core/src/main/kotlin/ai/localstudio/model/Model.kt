package ai.localstudio.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How much this app vouches for a catalog entry. Only VERIFIED is meant for
 * automatic selection; the stricter integrity rules that come with each
 * level are enforced by [CatalogValidator].
 */
@Serializable
enum class CatalogStatus {
    /** Pinned revision, size and sha256 on every artifact. Eligible for automatic selection. */
    @SerialName("verified")
    VERIFIED,

    /** Pinned revision and size; sha256 recommended. Manual selection only. */
    @SerialName("experimental")
    EXPERIMENTAL,

    /** Not pinned, not hash-checked — installing one is an explicit user decision. */
    @SerialName("unverified")
    UNVERIFIED,

    /** Still known, must not be newly installed or auto-selected. */
    @SerialName("deprecated")
    DEPRECATED,

    /** Pulled — known only so an installed copy can be flagged. */
    @SerialName("withdrawn")
    WITHDRAWN,
}

@Serializable
data class LicenseInfo(
    val id: String,
    val url: String? = null,
    /** The upstream repository requires accepting a licence / an access token. */
    val gated: Boolean = false,
)

/**
 * One logical model: identity, licence, what it can do. Language support and
 * other capability semantics live here (in [capabilities]), not on the
 * variants — a Q4 and a Q8 of the same model support the same languages;
 * how *well* each does is a per-variant [ModelVariant.metrics] question.
 */
@Serializable
data class ModelDefinition(
    val id: ModelId,
    val family: ModelFamilyId,
    val displayName: String,
    val version: String? = null,
    val license: LicenseInfo? = null,
    val status: CatalogStatus,
    @Serializable(with = CapabilityMapSerializer::class)
    val capabilities: Map<CapabilityId, CapabilityFacet>,
    val variants: List<ModelVariant>,
) {
    fun supports(capability: CapabilityId): Boolean = capability in capabilities

    fun facet(capability: CapabilityId): CapabilityFacet? = capabilities[capability]

    /** Variants that carry every artifact role [capability] needs — e.g. only those shipping a projector for vision. */
    fun variantsOffering(capability: CapabilityId): List<ModelVariant> {
        val facet = capabilities[capability] ?: return emptyList()
        return variants.filter { it.roles.containsAll(facet.requiresRoles) }
    }
}

/**
 * One installable form of a model — the unit that is downloaded, installed,
 * loaded and uninstalled. Files shared between variants (one projector for
 * every quantization) are expected to be deduplicated by sha256 at the
 * storage layer later, not modelled as a separate entity here.
 */
@Serializable
data class ModelVariant(
    val id: VariantId,
    val quantization: String? = null,
    val parameterCount: Long? = null,
    val artifacts: List<ArtifactSpec>,
    val bindings: List<RuntimeBinding>,
    val resources: ResourceRequirements = ResourceRequirements(),
    /**
     * Quality signals keyed by metric id — "translation.he", "asr.wer.ru",
     * "general" — normalized to 0..1, higher is better. Open-ended on
     * purpose: a new capability's metric needs no schema change.
     */
    val metrics: Map<String, Double> = emptyMap(),
) {
    val roles: Set<ArtifactRole> get() = artifacts.mapTo(linkedSetOf()) { it.role }

    /** Roles a runtime can rely on being present once this variant is installed. */
    val mandatoryRoles: Set<ArtifactRole> get() = artifacts.filterNot { it.optional }.mapTo(linkedSetOf()) { it.role }

    val totalSizeBytes: Long get() = artifacts.sumOf { it.sizeBytes }
}

/**
 * One file of a variant. [fileName] is the name it gets on disk inside the
 * variant's install directory — it matters for runtimes that resolve
 * siblings by name (ONNX external data, a voice pack's config next to its
 * model). [sizeBytes] is 0 when unknown, which only an UNVERIFIED entry may be.
 */
@Serializable
data class ArtifactSpec(
    val role: ArtifactRole,
    val fileName: String,
    val sizeBytes: Long = 0,
    /** Lowercase hex SHA-256 of the file as downloaded (before any unpacking). */
    val sha256: String? = null,
    val source: ArtifactSource,
    /** Alternative locations of the very same bytes — same [sha256]. */
    val mirrors: List<ArtifactSource> = emptyList(),
    /** Not needed for the variant to run at all — only for capabilities that list its role in [CapabilityFacet.requiresRoles]. */
    val optional: Boolean = false,
    val unpack: UnpackSpec? = null,
)

/**
 * Where an artifact's bytes come from. Fetching them is not this module's
 * concern; adding a new kind of source means a new subtype here plus a
 * locator for it in the installation layer.
 */
@Serializable
sealed interface ArtifactSource {
    /** The host the bytes are fetched from — checked against a catalog's allowlist. */
    val host: String?

    /** URL scheme — only https is ever accepted. */
    val scheme: String?

    @Serializable
    @SerialName("huggingface")
    data class HuggingFace(
        val repo: String,
        /** A commit sha pins the bytes; a branch name ("main") does not — see [isPinned]. */
        val revision: String,
        val path: String,
    ) : ArtifactSource {
        override val host: String get() = HOST
        override val scheme: String get() = "https"

        val isPinned: Boolean get() = COMMIT_SHA.matches(revision)

        companion object {
            const val HOST = "huggingface.co"
            private val COMMIT_SHA = Regex("^[0-9a-f]{40}$")
        }
    }

    @Serializable
    @SerialName("url")
    data class DirectUrl(val url: String) : ArtifactSource {
        private val parsed get() = runCatching { java.net.URI(url) }.getOrNull()
        override val host: String? get() = parsed?.host?.lowercase()
        override val scheme: String? get() = parsed?.scheme?.lowercase()
    }
}

/** The artifact is an archive to unpack on install; [unpackedSizeBytes] counts toward the free-space check. */
@Serializable
data class UnpackSpec(
    val format: String,
    val unpackedSizeBytes: Long,
) {
    companion object {
        val KNOWN_FORMATS = setOf("zip", "tar", "tar.gz", "tar.bz2", "tar.xz")
    }
}

/**
 * One way to execute a variant: which runtime, which of the variant's
 * artifact roles it needs, and what the device must offer. Compatibility is
 * decided per binding — a model isn't "compatible with a phone", one of its
 * bindings is.
 */
@Serializable
data class RuntimeBinding(
    val runtime: RuntimeId,
    val requiredRoles: Set<ArtifactRole>,
    val optionalRoles: Set<ArtifactRole> = emptySet(),
    val minAndroidApi: Int = 0,
    /** /proc/cpuinfo feature flags the binding's native code needs ("asimddp", "i8mm"). */
    val cpuFeatures: Set<String> = emptySet(),
    val requiresGpu: Boolean = false,
    val requiresNpu: Boolean = false,
    /** Runtime-specific knobs (context size, chat template override, ...) — opaque here. */
    val config: Map<String, String> = emptyMap(),
)

@Serializable
data class ResourceRequirements(
    /** Peak RAM measured on a real device; null until someone has measured it. */
    val measuredPeakRamBytes: Long? = null,
    /** Unmeasured fallback: artifact size × this factor — deliberately pessimistic. */
    val ramEstimateFactor: Double = 1.3,
)
