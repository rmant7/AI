package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.CapabilityFacet
import ai.localstudio.model.CapabilityId
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.ModelFamilyId
import ai.localstudio.model.ModelId
import ai.localstudio.model.ModelVariant
import ai.localstudio.model.RuntimeBinding
import ai.localstudio.model.RuntimeId
import ai.localstudio.model.VariantId
import kotlinx.serialization.Serializable

/**
 * What a real device actually observed trying to use a discovered
 * candidate -- the only thing [CandidateTier] ever upgrades on. Every field
 * is what one load-and-prompt attempt measured, never inferred: a
 * candidate this app has not yet tried to run has no [DeviceVerification]
 * at all, not one with everything false.
 */
@Serializable
data class DeviceVerification(
    /** A normalized profile, not a device id -- "Pixel 10 Pro / API 37 / 16.3 GB / llama.cpp b10448 (i8mm)"; enough to judge fit, not to identify a person. */
    val deviceProfile: String,
    val runtimeId: String,
    val loaded: Boolean,
    val inferenceOk: Boolean,
    /** Truncated, for a human to sanity-check -- never used to decide [CandidateTier] itself. */
    val sampleOutput: String? = null,
    val tokensPerSecond: Double? = null,
    /** Set on either a load or an inference failure; which one is told apart by [loaded]. */
    val error: String? = null,
    val verifiedAtEpochMs: Long,
    /**
     * Which version of the answer check produced [inferenceOk]. A record
     * from before [CURRENT_CHECK] (absent in older files: 1) is not trusted
     * for FUNCTIONAL -- the first check also credited a word found inside an
     * unfinished reasoning draft.
     */
    val checkVersion: Int = 1,
) {
    companion object {
        const val CURRENT_CHECK = 2
    }
}

/**
 * UNVERIFIED is where every discovered candidate starts and stays until a
 * real [DeviceVerification] exists -- discovery itself (the header check,
 * the size check) proves a file is *plausible*, never that any device can
 * actually load or use it. Never settable directly; always derived from
 * the verification record that is the only evidence for it.
 */
enum class CandidateTier {
    UNVERIFIED,
    LOADABLE,
    FUNCTIONAL,
}

/** The one place a [DeviceVerification] becomes a [CandidateTier] -- see that enum's own doc comment on why this is the only path to anything past UNVERIFIED. */
fun DeviceVerification?.tier(): CandidateTier = when {
    this == null -> CandidateTier.UNVERIFIED
    inferenceOk && checkVersion >= DeviceVerification.CURRENT_CHECK -> CandidateTier.FUNCTIONAL
    // An older check's "answered" still proves it loaded and produced text.
    inferenceOk || loaded -> CandidateTier.LOADABLE
    else -> CandidateTier.UNVERIFIED
}

/**
 * Builds the install-ready [ModelDefinition] for a discovery candidate --
 * pinned to the exact commit and file [GgufProbe] already judged loadable,
 * never re-resolved by quantization priority, so what [ModelInstaller]
 * fetches is byte-for-byte the file whose header was actually checked.
 *
 * [CatalogStatus.UNVERIFIED] is the only status this ever carries --
 * nothing here claims more than the catalog's own definition of that
 * status already allows ("not pinned, not hash-checked"). It is a
 * formality the type needs to exist, not a verdict. The real verdict is
 * [CandidateTier], tracked separately and only ever set by an actual
 * device run.
 */
object CandidateModel {
    fun of(
        repoId: String,
        commit: String,
        filePath: String,
        sizeBytes: Long,
        sha256: String?,
        variantId: String,
        capability: CapabilityId,
        capabilityFacet: CapabilityFacet,
        runtime: RuntimeId,
        config: Map<String, String> = emptyMap(),
    ): ModelDefinition = ModelDefinition(
        id = ModelId("discovered-${sanitize(repoId)}"),
        family = ModelFamilyId("discovered"),
        displayName = repoId,
        status = CatalogStatus.UNVERIFIED,
        capabilities = mapOf(capability to capabilityFacet),
        variants = listOf(
            ModelVariant(
                id = VariantId(variantId),
                artifacts = listOf(
                    ArtifactSpec(
                        role = ArtifactRoles.WEIGHTS,
                        fileName = "model.gguf",
                        sizeBytes = sizeBytes,
                        sha256 = sha256,
                        source = ArtifactSource.HuggingFace(repo = repoId, revision = commit, path = filePath),
                    ),
                ),
                bindings = listOf(RuntimeBinding(runtime = runtime, requiredRoles = setOf(ArtifactRoles.WEIGHTS), config = config)),
            ),
        ),
    )

    private fun sanitize(repoId: String) = repoId.replace('/', '_').lowercase()
}
