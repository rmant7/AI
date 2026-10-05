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
 * What a check is valid for: these exact bytes ([artifact]), on this device
 * model ([device]), run by this native runtime ([runtimeVersion] -- the
 * llama.cpp build and this app's own JNI revision), asked this set of
 * questions ([checkVersion]). A recorded result counts only while all four
 * are what they were; change any one and it is [CheckStatus.STALE].
 */
@Serializable
data class VerificationContext(
    val artifact: ArtifactId,
    val device: String,
    val runtimeVersion: String,
    val checkVersion: Int = DeviceVerification.CURRENT_CHECK,
)

/**
 * What a real device actually observed trying to use a model -- the only
 * thing [CandidateTier] ever upgrades on. Every field is what one
 * load-and-prompt attempt measured, never inferred: a model this app has
 * not yet tried to run has no [DeviceVerification] at all, not one with
 * everything false. What it is evidence for is [context]; asked about any
 * other context it answers [CheckStatus.STALE], never PASS.
 */
@Serializable
data class DeviceVerification(
    /** A normalized profile, not a device id -- "Pixel 10 Pro / API 37 / 16.3 GB / llama.cpp b10448 (i8mm)"; for a human reading it. */
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
     * Which version of the questions produced [checks] (absent in older
     * files: 1). The first check also credited a word found inside an
     * unfinished reasoning draft.
     */
    val checkVersion: Int = 1,
    /**
     * What each capability's own check on this device found, by
     * [VerifiedCapability] name -- absent means NOT_TESTED. A translation
     * model failing chat questions is a translation-only model, not a
     * failed one.
     */
    val checks: Map<String, CapabilityCheck> = emptyMap(),
    /** The bytes checked; null in a record from before it was kept. */
    val artifact: ArtifactId? = null,
    /** The device model ("Google Pixel 10 Pro"); null in a record from before it was kept. */
    val device: String? = null,
    /** The native runtime that ran the check; null in a record from before it was kept. */
    val runtimeVersion: String? = null,
) {
    /** What this record is evidence for; null for a record from before that was kept -- valid for nothing now. */
    val context: VerificationContext?
        get() = if (artifact != null && device != null && runtimeVersion != null) VerificationContext(artifact, device, runtimeVersion, checkVersion) else null

    fun isValidFor(current: VerificationContext): Boolean = context == current

    /** What was observed when the check ran, whatever has changed since -- for showing history, never for deciding. */
    fun recorded(capability: String): CheckStatus = checks[capability]?.status ?: CheckStatus.NOT_TESTED

    /** What the check says now, in [current]: the recorded result while still valid, STALE once anything it depended on changed. */
    fun status(capability: String, current: VerificationContext): CheckStatus {
        val observed = checks[capability]?.status ?: return CheckStatus.NOT_TESTED
        return if (isValidFor(current)) observed else CheckStatus.STALE
    }

    fun passes(capability: String, current: VerificationContext): Boolean = status(capability, current) == CheckStatus.PASS

    /** The capabilities that pass in [current], in [VerifiedCapability.ALL] order. */
    fun passed(current: VerificationContext): List<String> = VerifiedCapability.ALL.filter { passes(it, current) }

    /** Why this record no longer applies in [current] -- the first thing that changed; null while it is valid. */
    fun staleReason(current: VerificationContext): String? {
        val was = context ?: return "checked before this app recorded what a check depends on"
        return when {
            was.artifact != current.artifact -> "other files: checked ${was.artifact.key}"
            was.device != current.device -> "checked on another device: ${was.device}"
            was.runtimeVersion != current.runtimeVersion -> "runtime changed: ${was.runtimeVersion} -> ${current.runtimeVersion}"
            was.checkVersion != current.checkVersion -> "the questions changed: version ${was.checkVersion} -> ${current.checkVersion}"
            else -> null
        }
    }

    companion object {
        /** 4: VISION also asks two images in one turn and one after an unload and reload. */
        const val CURRENT_CHECK = 4
    }
}

/** PASS / FAIL / NOT_TESTED are what a check records; STALE is only ever answered, never stored: a recorded result whose context changed. */
@Serializable
enum class CheckStatus { PASS, FAIL, NOT_TESTED, STALE }

/**
 * One capability's check: [detail] says why it failed (or what was noted),
 * [sample] what the model actually said. [steps] is each question's own
 * outcome (VISION: one image, another, two at once, text after, after a
 * reload) and [failureKind] what kind of failure the first failed step was --
 * evidence for a person, never part of what the check publicly answers.
 */
@Serializable
data class CapabilityCheck(
    val status: CheckStatus,
    val detail: String? = null,
    val sample: String? = null,
    val steps: List<ProbeStep> = emptyList(),
    val failureKind: FailureKind? = null,
)

/**
 * What one question of a check observed. [firstTokenMs] is from asking to
 * the first piece of the answer (a load or a reload included, when the
 * question caused one); [tokensPerSecond] the generation after it. Both are
 * performance, a separate axis from [passed].
 */
@Serializable
data class ProbeStep(
    val title: String,
    val passed: Boolean,
    val answer: String? = null,
    val error: String? = null,
    val firstTokenMs: Long? = null,
    val tokensPerSecond: Double? = null,
)

/**
 * Why a step failed. MODEL_ANSWER: the model answered, wrongly (or not at
 * all, still reasoning). RUNTIME: the runtime failed the request (an image
 * that never reached the model, a native error). RESOURCE: this device ran
 * out of memory or time for it -- says as much about the phone as the model.
 */
@Serializable
enum class FailureKind { MODEL_ANSWER, RUNTIME, RESOURCE }

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
fun DeviceVerification?.tier(current: VerificationContext): CandidateTier = when {
    this == null -> CandidateTier.UNVERIFIED
    passed(current).isNotEmpty() -> CandidateTier.FUNCTIONAL
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
