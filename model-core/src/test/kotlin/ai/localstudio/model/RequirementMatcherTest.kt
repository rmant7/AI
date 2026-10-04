package ai.localstudio.model

import ai.localstudio.model.Fixtures.sampleModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RequirementMatcherTest {

    private fun reasons(model: ModelDefinition, requirement: ModelRequirement): Set<MismatchReason> =
        assertIs<RequirementMatch.NotMatched>(RequirementMatcher.match(model, requirement)).reasons

    private fun translateInto(target: String, source: String? = null) =
        ModelRequirement(Capabilities.TRANSLATION, TranslationRequirement(targetLanguage = target, sourceLanguage = source))

    @Test
    fun `translation into a supported target matches`() {
        assertIs<RequirementMatch.Matched>(RequirementMatcher.match(sampleModel("translategemma-4b-it"), translateInto("he", "ru")))
    }

    @Test
    fun `direction matters - a model that reads a language but cannot write it does not match`() {
        // TranslateGemma's sources are "*" (it reads anything) but its targets don't include Japanese.
        val model = sampleModel("translategemma-4b-it")
        assertEquals(setOf(MismatchReason.TARGET_LANGUAGE_UNSUPPORTED), reasons(model, translateInto("ja", "he")))
        assertIs<RequirementMatch.Matched>(RequirementMatcher.match(model, translateInto("he", "ja")))
    }

    @Test
    fun `a restricted source language set is enforced`() {
        val enOnly = Fixtures.model(
            capabilities = mapOf(Capabilities.TRANSLATION to TranslationFacet(LanguageSet.of("en"), LanguageSet.All)),
        )
        assertEquals(setOf(MismatchReason.SOURCE_LANGUAGE_UNSUPPORTED), reasons(enOnly, translateInto("he", "ru")))
        assertIs<RequirementMatch.Matched>(RequirementMatcher.match(enOnly, translateInto("he")))
    }

    @Test
    fun `legacy Hebrew tag in a requirement still matches`() {
        assertIs<RequirementMatch.Matched>(RequirementMatcher.match(sampleModel("translategemma-4b-it"), translateInto("iw")))
    }

    @Test
    fun `a missing capability is reported as such`() {
        assertEquals(setOf(MismatchReason.CAPABILITY_MISSING), reasons(sampleModel("whisper-base"), translateInto("he")))
    }

    @Test
    fun `a constraint against a facet it cannot read is not silently treated as satisfied`() {
        val unknownFacet = Fixtures.model(
            capabilities = mapOf(Capabilities.TRANSLATION to JsonCapabilityFacet(kotlinx.serialization.json.JsonObject(emptyMap()))),
        )
        assertEquals(setOf(MismatchReason.CONSTRAINT_NOT_CHECKABLE), reasons(unknownFacet, translateInto("he")))
    }

    @Test
    fun `vision is offered only by variants that ship a projector`() {
        val match = assertIs<RequirementMatch.Matched>(
            RequirementMatcher.match(sampleModel("gemma-4-e4b-it"), ModelRequirement(Capabilities.VISION)),
        )
        assertEquals(listOf("gemma-4-e4b-it@q4_k_m"), match.variants.map { it.id.id })
    }

    @Test
    fun `text generation on the same model is offered by every variant`() {
        val match = assertIs<RequirementMatch.Matched>(
            RequirementMatcher.match(sampleModel("gemma-4-e4b-it"), ModelRequirement(Capabilities.TEXT_GENERATION)),
        )
        assertEquals(2, match.variants.size)
    }

    @Test
    fun `one model serves several capabilities without a dedicated model type`() {
        val gemma = sampleModel("gemma-4-e4b-it")
        for (capability in listOf(Capabilities.TEXT_GENERATION, Capabilities.VISION, Capabilities.TRANSLATION)) {
            assertIs<RequirementMatch.Matched>(RequirementMatcher.match(gemma, ModelRequirement(capability)), "$capability")
        }
    }

    @Test
    fun `speech-to-text constraints check language, streaming and timestamps`() {
        val whisper = sampleModel("whisper-base")
        assertIs<RequirementMatch.Matched>(
            RequirementMatcher.match(whisper, ModelRequirement(Capabilities.SPEECH_TO_TEXT, SpeechToTextRequirement("he", streaming = true, timestamps = true))),
        )
        val batchOnly = Fixtures.model(capabilities = mapOf(Capabilities.SPEECH_TO_TEXT to SpeechToTextFacet(LanguageSet.of("en"))))
        assertEquals(
            setOf(MismatchReason.LANGUAGE_UNSUPPORTED, MismatchReason.STREAMING_UNSUPPORTED),
            reasons(batchOnly, ModelRequirement(Capabilities.SPEECH_TO_TEXT, SpeechToTextRequirement("he", streaming = true))),
        )
    }

    @Test
    fun `tts constraints check locale, cloning and reference audio`() {
        val piper = sampleModel("piper-en-us-amy-low")
        assertIs<RequirementMatch.Matched>(RequirementMatcher.match(piper, ModelRequirement(Capabilities.TEXT_TO_SPEECH, TtsRequirement("en"))))
        assertEquals(
            setOf(MismatchReason.VOICE_CLONING_UNSUPPORTED),
            reasons(piper, ModelRequirement(Capabilities.TEXT_TO_SPEECH, TtsRequirement(voiceCloning = true))),
        )
        val cloner = Fixtures.model(
            capabilities = mapOf(
                Capabilities.TEXT_TO_SPEECH to TtsFacet(LanguageSet.All, 24_000, referenceAudio = ReferenceAudio.REQUIRED, voiceCloning = true),
            ),
        )
        assertIs<RequirementMatch.Matched>(RequirementMatcher.match(cloner, ModelRequirement(Capabilities.TEXT_TO_SPEECH, TtsRequirement(voiceCloning = true))))
        assertEquals(
            setOf(MismatchReason.REFERENCE_AUDIO_REQUIRED),
            reasons(cloner, ModelRequirement(Capabilities.TEXT_TO_SPEECH, TtsRequirement(withoutReferenceAudio = true))),
        )
    }

    @Test
    fun `matching across a catalog returns only the models that qualify`() {
        val matched = RequirementMatcher.matching(Fixtures.sample().models, translateInto("he"))
        assertEquals(setOf("translategemma-4b-it", "gemma-4-e4b-it"), matched.map { it.model.id.id }.toSet())
    }
}
