package ai.localstudio.app.models.catalog

import ai.localstudio.app.llama.EmbeddingModelSpec
import ai.localstudio.app.llama.ExperimentalEmbeddingModels
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.TranslationModels
import ai.localstudio.app.vosk.VoskModelSeed
import ai.localstudio.app.vosk.VoskModels
import ai.localstudio.app.whisper.WhisperModelSeed
import ai.localstudio.app.whisper.WhisperModels
import ai.localstudio.core.registry.ArtifactResolver
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.Capabilities
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.CatalogTrust
import ai.localstudio.model.CatalogValidator
import ai.localstudio.model.EmbeddingFacet
import ai.localstudio.model.FileSelector
import ai.localstudio.model.GenericFacet
import ai.localstudio.model.LanguageSet
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.ModelRequirement
import ai.localstudio.model.RequirementMatcher
import ai.localstudio.model.Runtimes
import ai.localstudio.model.SpeechToTextFacet
import ai.localstudio.model.TranslationFacet
import ai.localstudio.model.TranslationRequirement
import ai.localstudio.model.UnpackSpec
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden: the domain catalogue says the same thing the legacy lists say —
 * capability, languages, artifacts and their sources, runtime binding, sizes
 * and key metadata — seed by seed, read straight off the legacy objects.
 *
 * What this does not check is the exported file's stability; that is
 * [LegacyCatalogSnapshotTest]'s job.
 */
class LegacyCatalogGoldenTest {

    private val document = LegacyCatalog.document
    private val llamaSeeds = LocalModels.SEEDS + TranslationModels.SEEDS

    private fun model(id: String): ModelDefinition = document.models.single { it.id.id == id }

    private fun ModelDefinition.onlyVariant() = variants.single().also { assertEquals("$id@legacy", it.id.id) }

    // --- every legacy field is either carried or explicitly excluded ---

    /**
     * Each legacy seed type's fields, split into what the mapping carries and
     * what it deliberately leaves in :app. A field added to a legacy type
     * fails here until someone decides which side it belongs on.
     */
    private val fieldClassification: Map<Class<*>, Pair<Set<String>, Set<String>>> = mapOf(
        LocalModelSeed::class.java to (
            setOf(
                "id", "title", "repoIds", "approxSizeBytes", "capabilities", "contextTokens",
                "mmprojFileName", "mmprojApproxSizeBytes", "quantPriority", "isT5EncoderDecoder",
            ) to setOf(
                // Presentation: string resource / free text / label / Settings origin.
                "note", "noteRes", "paramsLabel", "isCustom",
            )
            ),
        WhisperModelSeed::class.java to (setOf("id", "title", "modelUrl", "approxSizeBytes") to setOf("isCustom")),
        VoskModelSeed::class.java to (setOf("id", "title", "downloadUrls", "approxSizeBytes") to emptySet()),
        EmbeddingModelSpec::class.java to (
            setOf("id", "title", "repoId", "quantLabel", "dimension", "pooling", "queryPrefix", "passagePrefix") to emptySet()
            ),
    )

    @Test
    fun `every field of every legacy seed type is either mapped or explicitly excluded`() {
        for ((type, classification) in fieldClassification) {
            val (mapped, excluded) = classification
            val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()
            assertEquals(emptySet(), mapped intersect excluded, "${type.simpleName}: a field can't be both")
            assertEquals(fields, mapped + excluded, "${type.simpleName}: unclassified or stale field names")
        }
    }

    @Test
    fun `the purpose enum and UI-only metadata never reach the domain`() {
        val text = ai.localstudio.model.CatalogCodec.encode(document)
        for (seed in llamaSeeds) {
            if (seed.paramsLabel.isNotBlank()) assertFalse("\"${seed.paramsLabel}\"" in text, "paramsLabel ${seed.paramsLabel} leaked")
        }
        assertFalse("noteRes" in text || "isCustom" in text || "purpose" in text, "UI field leaked into the catalogue")
    }

    // --- coverage ---

    @Test
    fun `every bundled legacy entry is present exactly once, nothing else, all unverified and valid`() {
        val legacyIds = llamaSeeds.map { it.id } + WhisperModels.SEEDS.map { it.id } +
            VoskModels.SEEDS.map { it.id } + ExperimentalEmbeddingModels.ALL.map { it.id }
        assertEquals(legacyIds, document.models.map { it.id.id })
        assertTrue(document.models.all { it.status == CatalogStatus.UNVERIFIED })

        val validated = CatalogValidator.validate(document, CatalogTrust.Bundled)
        assertEquals(emptyList(), validated.violations)
        assertEquals(legacyIds.size, validated.models.size)
    }

    @Test
    fun `custom models are not in the bundled catalogue and map to unverified user-supplied entries`() {
        val custom = LegacyCatalogMapper.customModel(LocalModels.custom("someone/My-Model-GGUF"))
        val customTranslation = LegacyCatalogMapper.customModel(LocalModels.customTranslation("someone/Translate-GGUF"))
        val customWhisper = LegacyCatalogMapper.customWhisperModel(WhisperModels.custom("https://huggingface.co/a/b/resolve/main/ggml-x.bin"))
        val bundledIds = document.models.map { it.id }.toSet()
        for (model in listOf(custom, customTranslation, customWhisper)) {
            assertFalse(model.id in bundledIds, "${model.id} must not be bundled")
            assertEquals(CatalogStatus.UNVERIFIED, model.status)
            assertEquals("custom", model.family.id)
        }
        val selection = assertIs<ArtifactSource.HuggingFaceSelection>(custom.onlyVariant().artifacts.single().source)
        assertEquals(listOf("someone/My-Model-GGUF"), selection.repoIds)
        val translation = assertIs<TranslationFacet>(customTranslation.facet(Capabilities.TRANSLATION))
        assertEquals(LanguageSet.All, translation.targetLanguages)
        assertEquals(LegacyCatalogMapper.PROMPT_CHAT, translation.promptFormat)
        assertEquals(ArtifactSource.HuggingFace("a/b", "main", "ggml-x.bin"), customWhisper.onlyVariant().artifacts.single().source)
    }

    // --- llama.cpp GGUF seeds: chat and translation ---

    @Test
    fun `GGUF seeds keep their repositories in order, the quant priority and the dynamic main-branch selection`() {
        for (seed in llamaSeeds) {
            val weights = model(seed.id).onlyVariant().artifacts.single { it.role == ArtifactRoles.WEIGHTS }
            val source = assertIs<ArtifactSource.HuggingFaceSelection>(weights.source, seed.id)
            assertEquals(seed.repoIds, source.repoIds, "${seed.id}: repository fallback order")
            assertEquals("main", source.revision, seed.id)
            assertEquals(
                FileSelector.ByQuantization(seed.quantPriority ?: ArtifactResolver.DEFAULT_QUANT_PRIORITY, ".gguf"),
                source.file,
                seed.id,
            )
            assertTrue(weights.mirrors.isEmpty(), "${seed.id}: repoIds are fallbacks, not mirrors")
            assertNull(weights.sha256, seed.id)
            assertFalse(weights.optional, seed.id)
            assertEquals(seed.approxSizeBytes, weights.sizeBytes, "${seed.id}: size")
        }
    }

    @Test
    fun `GGUF seeds keep display name, capabilities, context size and the llama binding`() {
        for (seed in llamaSeeds) {
            val model = model(seed.id)
            assertEquals(seed.title, model.displayName, seed.id)
            val expected = seed.capabilities.map(LegacyCatalogMapper::capabilityId).toSet() +
                if (seed.mmprojFileName != null) setOf(Capabilities.VISION) else emptySet()
            assertEquals(expected, model.capabilities.keys, "${seed.id}: capabilities")

            val binding = model.onlyVariant().bindings.single()
            assertEquals(Runtimes.LLAMA_CPP, binding.runtime, seed.id)
            assertEquals(setOf(ArtifactRoles.WEIGHTS), binding.requiredRoles, seed.id)
            assertEquals(seed.contextTokens.toString(), binding.config[LegacyCatalogMapper.CONFIG_CONTEXT_TOKENS], seed.id)
        }
    }

    @Test
    fun `a vision projector is an optional exact-name artifact that gates vision`() {
        val withProjector = llamaSeeds.filter { it.mmprojFileName != null }
        assertTrue(withProjector.isNotEmpty(), "legacy has at least one projector seed (gemma-4-e4b)")
        for (seed in withProjector) {
            val model = model(seed.id)
            val variant = model.onlyVariant()
            val projector = variant.artifacts.single { it.role == ArtifactRoles.PROJECTOR }
            assertEquals(
                ArtifactSource.HuggingFaceSelection(seed.repoIds, "main", FileSelector.ExactName(seed.mmprojFileName!!)),
                projector.source,
                seed.id,
            )
            assertTrue(projector.optional, "${seed.id}: legacy treats a missing projector as text-only, not a failure")
            assertEquals(seed.mmprojApproxSizeBytes, projector.sizeBytes, seed.id)
            assertEquals(setOf(ArtifactRoles.PROJECTOR), model.facet(Capabilities.VISION)!!.requiresRoles, seed.id)
            assertEquals(setOf(ArtifactRoles.PROJECTOR), variant.bindings.single().optionalRoles, seed.id)
        }
        for (seed in llamaSeeds - withProjector.toSet()) {
            assertEquals(listOf(ArtifactRoles.WEIGHTS), model(seed.id).onlyVariant().artifacts.map { it.role }, seed.id)
            assertFalse(model(seed.id).supports(Capabilities.VISION), seed.id)
        }
    }

    @Test
    fun `translation seeds keep their prompt format and declare languages only where legacy did`() {
        val translators = llamaSeeds.filter { ai.localstudio.core.capability.Capability.TRANSLATION in it.capabilities }
        assertTrue(translators.any { it.isT5EncoderDecoder } && translators.any { !it.isT5EncoderDecoder })
        val madlad = LanguageSet.Of(LegacyCatalog.madladCodes)
        for (seed in translators) {
            val facet = assertIs<TranslationFacet>(model(seed.id).facet(Capabilities.TRANSLATION), seed.id)
            assertEquals(LanguageSet.All, facet.sourceLanguages, "${seed.id}: legacy never constrains the source")
            if (seed.isT5EncoderDecoder) {
                assertEquals(LegacyCatalogMapper.PROMPT_MADLAD_TAG, facet.promptFormat, seed.id)
                assertEquals(madlad.tags, assertIs<LanguageSet.Of>(facet.targetLanguages).tags, seed.id)
            } else {
                assertEquals(LegacyCatalogMapper.PROMPT_CHAT, facet.promptFormat, seed.id)
                assertEquals(LanguageSet.All, facet.targetLanguages, "${seed.id}: no declared list → unconstrained")
            }
        }
        assertEquals(LegacyCatalog.madladCodes.size, madlad.tags.size, "no MADLAD code lost to normalization")
    }

    @Test
    fun `a translation requirement finds what legacy would offer and nothing that can't translate`() {
        val matched = RequirementMatcher.matching(document.models, ModelRequirement(Capabilities.TRANSLATION, TranslationRequirement("he")))
            .map { it.model.id.id }.toSet()
        val legacyTranslators = llamaSeeds.filter { ai.localstudio.core.capability.Capability.TRANSLATION in it.capabilities }.map { it.id }.toSet()
        assertEquals(legacyTranslators, matched)
    }

    // --- whisper.cpp ---

    @Test
    fun `whisper seeds keep URL, size and a generalist streaming speech facet`() {
        for (seed in WhisperModels.SEEDS) {
            val model = model(seed.id)
            assertEquals(seed.title, model.displayName, seed.id)
            assertEquals(SpeechToTextFacet(LanguageSet.All, streaming = true, timestamps = false), model.facet(Capabilities.SPEECH_TO_TEXT), seed.id)
            assertEquals(setOf(Capabilities.SPEECH_TO_TEXT), model.capabilities.keys, seed.id)
            val variant = model.onlyVariant()
            val weights = variant.artifacts.single()
            assertEquals(seed.modelUrl, urlOf(weights.source), "${seed.id}: download URL")
            assertEquals(seed.approxSizeBytes, weights.sizeBytes, seed.id)
            assertEquals(Runtimes.WHISPER_CPP, variant.bindings.single().runtime, seed.id)
        }
        assertEquals("whisper-ivrit", model("whisper-ivrit-large").family.id)
        assertEquals("whisper", model("whisper-base").family.id)
    }

    // --- Vosk ---

    @Test
    fun `vosk seeds keep every URL in order, size, zip unpacking and their language`() {
        val languages = mapOf("vosk-small-ru" to "ru", "vosk-small-en" to "en", "vosk-ru" to "ru")
        assertEquals(languages.keys, VoskModels.SEEDS.map { it.id }.toSet(), "a new Vosk seed needs its language stated")
        for (seed in VoskModels.SEEDS) {
            val model = model(seed.id)
            assertEquals(seed.title, model.displayName, seed.id)
            assertEquals(
                SpeechToTextFacet(LanguageSet.of(languages.getValue(seed.id)), streaming = true, timestamps = false),
                model.facet(Capabilities.SPEECH_TO_TEXT),
                seed.id,
            )
            val variant = model.onlyVariant()
            val archive: ArtifactSpec = variant.artifacts.single()
            assertEquals(ArtifactRoles.ARCHIVE, archive.role, seed.id)
            assertTrue(archive.mirrors.isEmpty(), "${seed.id}: legacy URLs are fallbacks, not proven-identical mirrors")
            val sources = (archive.source as? ArtifactSource.Alternatives)?.sources ?: listOf(archive.source)
            assertEquals(seed.downloadUrls, sources.map(::urlOf), "${seed.id}: URLs in order")
            assertEquals(seed.downloadUrls.size > 1, archive.source is ArtifactSource.Alternatives, seed.id)
            assertEquals(UnpackSpec("zip"), archive.unpack, seed.id)
            assertEquals(seed.approxSizeBytes, archive.sizeBytes, seed.id)
            assertEquals(Runtimes.VOSK, variant.bindings.single().runtime, seed.id)
        }
    }

    // --- embeddings ---

    @Test
    fun `embedding specs keep repository, quantization, dimensions, pooling and prefixes`() {
        for (spec in ExperimentalEmbeddingModels.ALL) {
            val model = model(spec.id)
            assertEquals(spec.title, model.displayName, spec.id)
            val facet = assertIs<EmbeddingFacet>(model.facet(Capabilities.TEXT_EMBEDDING), spec.id)
            assertEquals(spec.dimension, facet.dimensions, spec.id)
            assertEquals(spec.pooling.name.lowercase(), facet.pooling, spec.id)
            assertTrue(facet.normalized, spec.id)
            assertEquals(spec.queryPrefix, facet.queryPrefix, spec.id)
            assertEquals(spec.passagePrefix, facet.documentPrefix, spec.id)
            assertEquals("cosine", facet.similarity, spec.id)

            val variant = model.onlyVariant()
            val weights = variant.artifacts.single()
            assertEquals(
                ArtifactSource.HuggingFaceSelection(listOf(spec.repoId), "main", FileSelector.ByQuantization(listOf(spec.quantLabel), ".gguf")),
                weights.source,
                spec.id,
            )
            assertEquals(0L, weights.sizeBytes, "${spec.id}: legacy has no size estimate")
            val binding = variant.bindings.single()
            assertEquals(Runtimes.LLAMA_CPP, binding.runtime, spec.id)
            assertEquals(LegacyCatalogMapper.MODE_EMBEDDING, binding.config[LegacyCatalogMapper.CONFIG_MODE], spec.id)
        }
    }

    @Test
    fun `no legacy entry claims byte-identical mirrors`() {
        for (model in document.models) for (variant in model.variants) for (artifact in variant.artifacts) {
            assertTrue(artifact.mirrors.isEmpty(), "${model.id}/${artifact.fileName}: legacy has no hash to prove a mirror with")
        }
    }

    @Test
    fun `nothing in the legacy catalogue claims a capability facet it has no data for`() {
        for (model in document.models) {
            for ((capability, facet) in model.capabilities) {
                when (capability) {
                    Capabilities.TRANSLATION -> assertIs<TranslationFacet>(facet)
                    Capabilities.SPEECH_TO_TEXT -> assertIs<SpeechToTextFacet>(facet)
                    Capabilities.TEXT_EMBEDDING -> assertIs<EmbeddingFacet>(facet)
                    else -> assertIs<GenericFacet>(facet)
                }
            }
        }
    }

    private fun urlOf(source: ArtifactSource): String = when (source) {
        is ArtifactSource.HuggingFace -> "https://huggingface.co/${source.repo}/resolve/${source.revision}/${source.path}"
        is ArtifactSource.DirectUrl -> source.url
        is ArtifactSource.HuggingFaceSelection -> error("a fixed-URL legacy entry must not become a selection: $source")
        is ArtifactSource.Alternatives -> error("nested alternatives: $source")
    }
}
