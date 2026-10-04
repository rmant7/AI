package ai.localstudio.model

/**
 * What a caller needs done — never which model does it. "Translate into
 * Hebrew", not "Gemma Q4".
 *
 * [constraint] carries the capability-specific part of the request, typed
 * per capability ([TranslationRequirement], [SpeechToTextRequirement], ...)
 * rather than as a string map, so a fundamental condition like the target
 * language can't be misspelled or silently ignored.
 *
 * Whether a cloud provider may serve the request is not decided here: this
 * module only knows local models. That choice belongs to the layer that sees
 * both (the future ModelService).
 */
data class ModelRequirement(
    val capability: CapabilityId,
    val constraint: CapabilityConstraint? = null,
)

/**
 * The capability-specific half of a [ModelRequirement]. Each constraint
 * checks itself against a facet, so a new capability brings its own
 * constraint type without the matcher changing.
 */
interface CapabilityConstraint {
    /** Empty when [facet] satisfies this constraint. */
    fun mismatches(facet: CapabilityFacet): Set<MismatchReason>
}

@JvmInline
value class MismatchReason(val id: String) {
    override fun toString(): String = id

    companion object {
        val CAPABILITY_MISSING = MismatchReason("capability_missing")

        /** The model's facet for this capability isn't one the constraint can read (unknown/unreadable facet). */
        val CONSTRAINT_NOT_CHECKABLE = MismatchReason("constraint_not_checkable")

        /** The model declares the capability, but no variant carries the artifact roles it requires. */
        val NO_VARIANT_OFFERS_CAPABILITY = MismatchReason("no_variant_offers_capability")
        val SOURCE_LANGUAGE_UNSUPPORTED = MismatchReason("source_language_unsupported")
        val TARGET_LANGUAGE_UNSUPPORTED = MismatchReason("target_language_unsupported")
        val LANGUAGE_UNSUPPORTED = MismatchReason("language_unsupported")
        val STREAMING_UNSUPPORTED = MismatchReason("streaming_unsupported")
        val TIMESTAMPS_UNSUPPORTED = MismatchReason("timestamps_unsupported")
        val VOICE_CLONING_UNSUPPORTED = MismatchReason("voice_cloning_unsupported")
        val REFERENCE_AUDIO_REQUIRED = MismatchReason("reference_audio_required")
    }
}

/** Translate from [sourceLanguage] (null: any / auto-detected) into [targetLanguage]. */
data class TranslationRequirement(
    val targetLanguage: String,
    val sourceLanguage: String? = null,
) : CapabilityConstraint {
    override fun mismatches(facet: CapabilityFacet): Set<MismatchReason> {
        if (facet !is TranslationFacet) return setOf(MismatchReason.CONSTRAINT_NOT_CHECKABLE)
        return buildSet {
            if (!facet.targetLanguages.contains(targetLanguage)) add(MismatchReason.TARGET_LANGUAGE_UNSUPPORTED)
            if (sourceLanguage != null && !facet.sourceLanguages.contains(sourceLanguage)) {
                add(MismatchReason.SOURCE_LANGUAGE_UNSUPPORTED)
            }
        }
    }
}

data class SpeechToTextRequirement(
    val language: String? = null,
    val streaming: Boolean = false,
    val timestamps: Boolean = false,
) : CapabilityConstraint {
    override fun mismatches(facet: CapabilityFacet): Set<MismatchReason> {
        if (facet !is SpeechToTextFacet) return setOf(MismatchReason.CONSTRAINT_NOT_CHECKABLE)
        return buildSet {
            if (language != null && !facet.languages.contains(language)) add(MismatchReason.LANGUAGE_UNSUPPORTED)
            if (streaming && !facet.streaming) add(MismatchReason.STREAMING_UNSUPPORTED)
            if (timestamps && !facet.timestamps) add(MismatchReason.TIMESTAMPS_UNSUPPORTED)
        }
    }
}

/**
 * [withoutReferenceAudio]: the caller has no reference recording to give, so
 * a voice that requires one is unusable for it.
 */
data class TtsRequirement(
    val locale: String? = null,
    val voiceCloning: Boolean = false,
    val withoutReferenceAudio: Boolean = false,
) : CapabilityConstraint {
    override fun mismatches(facet: CapabilityFacet): Set<MismatchReason> {
        if (facet !is TtsFacet) return setOf(MismatchReason.CONSTRAINT_NOT_CHECKABLE)
        return buildSet {
            if (locale != null && !facet.locales.contains(locale)) add(MismatchReason.LANGUAGE_UNSUPPORTED)
            if (voiceCloning && !facet.voiceCloning) add(MismatchReason.VOICE_CLONING_UNSUPPORTED)
            if (withoutReferenceAudio && facet.referenceAudio == ReferenceAudio.REQUIRED) {
                add(MismatchReason.REFERENCE_AUDIO_REQUIRED)
            }
        }
    }
}

sealed interface RequirementMatch {
    /** [variants]: the ones that carry every role the capability needs — never empty. */
    data class Matched(val model: ModelDefinition, val variants: List<ModelVariant>) : RequirementMatch

    data class NotMatched(val model: ModelDefinition, val reasons: Set<MismatchReason>) : RequirementMatch
}

/**
 * Does a model, on paper, do what a requirement asks? Catalog-level only:
 * whether a variant is installed, fits this device or has a runtime here
 * comes later (compatibility), and so does ranking by [ModelVariant.metrics].
 * Catalog status is not filtered here either — that's selection policy.
 */
object RequirementMatcher {

    fun match(model: ModelDefinition, requirement: ModelRequirement): RequirementMatch {
        val facet = model.facet(requirement.capability)
            ?: return RequirementMatch.NotMatched(model, setOf(MismatchReason.CAPABILITY_MISSING))

        val reasons = requirement.constraint?.mismatches(facet).orEmpty()
        if (reasons.isNotEmpty()) return RequirementMatch.NotMatched(model, reasons)

        val variants = model.variantsOffering(requirement.capability)
        if (variants.isEmpty()) {
            return RequirementMatch.NotMatched(model, setOf(MismatchReason.NO_VARIANT_OFFERS_CAPABILITY))
        }
        return RequirementMatch.Matched(model, variants)
    }

    fun matching(models: List<ModelDefinition>, requirement: ModelRequirement): List<RequirementMatch.Matched> =
        models.map { match(it, requirement) }.filterIsInstance<RequirementMatch.Matched>()
}
