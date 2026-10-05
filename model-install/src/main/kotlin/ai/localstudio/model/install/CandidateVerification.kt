package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.Capabilities
import ai.localstudio.model.GenericFacet
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
    /**
     * What each capability's own check on this device found, by
     * [VerifiedCapability] name -- absent means NOT_TESTED. From
     * [CURRENT_CHECK] 3 on this, not [inferenceOk] alone, is what a model
     * is good for: a translation model failing chat questions is a
     * translation-only model, not a failed one.
     */
    val checks: Map<String, CapabilityCheck> = emptyMap(),
) {
    fun status(capability: String): CheckStatus = checks[capability]?.status ?: CheckStatus.NOT_TESTED

    /** Passed by the current check -- never by an older version's weaker one. */
    fun passes(capability: String): Boolean = checkVersion >= CURRENT_CHECK && status(capability) == CheckStatus.PASS

    /** The capabilities that passed, in [VerifiedCapability.ALL] order. */
    val passed: List<String> get() = VerifiedCapability.ALL.filter(::passes)

    companion object {
        const val CURRENT_CHECK = 3
    }
}

@Serializable
enum class CheckStatus { PASS, FAIL, NOT_TESTED }

/** One capability's check: [detail] says why it failed (or what was noted), [sample] what the model actually said. */
@Serializable
data class CapabilityCheck(val status: CheckStatus, val detail: String? = null, val sample: String? = null)

/** What a local model can be verified for -- the names [DeviceVerification.checks] uses, and what a model is offered for. */
object VerifiedCapability {
    const val TEXT = "text"
    const val TRANSLATION = "translation"
    const val VISION = "vision"
    val ALL = listOf(TEXT, TRANSLATION, VISION)
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
    passed.isNotEmpty() -> CandidateTier.FUNCTIONAL
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
    /**
     * The install-ready definition of a whole [ModelArtifact]: the main file
     * as WEIGHTS and, when it has one, its projector as PROJECTOR -- two roles
     * of one model, both pinned to the commit discovery read them at, never
     * re-resolved. TEXT_GENERATION needs the weights alone; VISION is declared
     * only with a projector and needs it (the catalog's own way of saying
     * VISION = MAIN + PROJECTOR). The projector is required, not optional: a
     * vision candidate is installed and checked as the pair, or not at all.
     */
    fun of(artifact: ModelArtifact, variantId: String, runtime: RuntimeId): ModelDefinition {
        val main = artifact.main
        val projector = artifact.projector
        return ModelDefinition(
            id = ModelId("discovered-${sanitize(main.repo)}"),
            family = ModelFamilyId("discovered"),
            displayName = main.repo,
            status = CatalogStatus.UNVERIFIED,
            capabilities = buildMap {
                put(Capabilities.TEXT_GENERATION, GenericFacet())
                if (projector != null) put(Capabilities.VISION, GenericFacet(setOf(ArtifactRoles.PROJECTOR)))
            },
            variants = listOf(
                ModelVariant(
                    id = VariantId(variantId),
                    artifacts = listOfNotNull(
                        ArtifactSpec(
                            role = ArtifactRoles.WEIGHTS,
                            fileName = "model.gguf",
                            sizeBytes = main.sizeBytes,
                            sha256 = main.sha256,
                            source = ArtifactSource.HuggingFace(repo = main.repo, revision = main.revision, path = main.path),
                        ),
                        projector?.let {
                            ArtifactSpec(
                                role = ArtifactRoles.PROJECTOR,
                                fileName = PROJECTOR_FILE_NAME,
                                sizeBytes = it.file.sizeBytes,
                                sha256 = it.file.sha256,
                                source = ArtifactSource.HuggingFace(repo = it.file.repo, revision = it.file.revision, path = it.file.path),
                            )
                        },
                    ),
                    bindings = listOf(
                        RuntimeBinding(
                            runtime = runtime,
                            requiredRoles = setOf(ArtifactRoles.WEIGHTS),
                            optionalRoles = if (projector != null) setOf(ArtifactRoles.PROJECTOR) else emptySet(),
                        ),
                    ),
                ),
            ),
        )
    }

    /** The projector's file name inside an installed variant -- the same one the legacy catalogue's vision models use. */
    const val PROJECTOR_FILE_NAME = "projector.gguf"

    private fun sanitize(repoId: String) = repoId.replace('/', '_').lowercase()
}
