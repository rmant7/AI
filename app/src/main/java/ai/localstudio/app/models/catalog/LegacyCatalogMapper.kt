package ai.localstudio.app.models.catalog

import ai.localstudio.app.llama.EmbeddingModelSpec
import ai.localstudio.app.llama.EmbeddingPooling
import ai.localstudio.app.llama.ExperimentalEmbeddingModels
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.TranslationModels
import ai.localstudio.app.vosk.VoskModelSeed
import ai.localstudio.app.vosk.VoskModels
import ai.localstudio.app.whisper.WhisperModelSeed
import ai.localstudio.app.whisper.WhisperModels
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.registry.ArtifactResolver
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.Capabilities
import ai.localstudio.model.CapabilityFacet
import ai.localstudio.model.CapabilityId
import ai.localstudio.model.CatalogDocument
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.EmbeddingFacet
import ai.localstudio.model.FileSelector
import ai.localstudio.model.GenericFacet
import ai.localstudio.model.LanguageSet
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.ModelFamilyId
import ai.localstudio.model.ModelId
import ai.localstudio.model.ModelVariant
import ai.localstudio.model.RuntimeBinding
import ai.localstudio.model.Runtimes
import ai.localstudio.model.SpeechToTextFacet
import ai.localstudio.model.TranslationFacet
import ai.localstudio.model.UnpackSpec
import ai.localstudio.model.VariantId

/**
 * Describes today's hard-coded model lists ([LocalModels], [TranslationModels],
 * [WhisperModels], [VoskModels], [ExperimentalEmbeddingModels]) in the
 * `:model-core` domain — a normalization, not a re-decision: every entry
 * says exactly what the legacy code does today and nothing more.
 *
 * Consequently every entry is UNVERIFIED: nothing is pinned to a commit and
 * nothing carries a hash. GGUF entries keep their install-time selection
 * ([ArtifactSource.HuggingFaceSelection] over the seed's repositories, in
 * order, on "main"); fixed-URL entries (Whisper, Vosk, a vision projector's
 * exact file name) are described as the URLs they are, still unpinned.
 *
 * Deliberately left in `:app`, not carried into the domain:
 * - presentation: `noteRes`/`note` (string resources), `paramsLabel`,
 *   `isCustom`, [ai.localstudio.app.models.ModelPurpose] — the title is the
 *   only text that becomes domain data ([ModelDefinition.displayName]);
 * - install layout: legacy on-disk paths (`models/<id>.gguf`,
 *   `vosk-models/<id>/`, ...) and Vosk's top-level-folder stripping on
 *   unzip — the domain's [ArtifactSpec.fileName] is the name inside a
 *   variant's own install directory, and moving files is a later phase;
 * - speech registration flags (file transcription, language auto-detect) —
 *   runtime registration, not catalogue data;
 * - Vosk's native library (`vosk-android.aar`), shared by every Vosk model
 *   and fetched by the runtime, not an artifact of any one model.
 *
 * Nothing in the app reads this yet; the exported catalogue
 * (`model-catalog/local-models.json`) is produced from it by a unit test.
 */
object LegacyCatalogMapper {

    const val CATALOG_ID = "local-models-legacy"

    /** Bumped by hand when the mapping itself changes meaning, not when a seed changes. */
    const val CATALOG_VERSION = "legacy-mapping-1"

    /** The branch every legacy Hugging Face URL and lookup uses — the reason none of this is reproducible. */
    const val LEGACY_REVISION = "main"

    const val GGUF_EXTENSION = ".gguf"

    /** Every host a legacy URL points at. */
    val ALLOWED_HOSTS = setOf("huggingface.co", "alphacephei.com")

    /** [ai.localstudio.model.TranslationFacet.promptFormat] values. */
    const val PROMPT_MADLAD_TAG = "madlad_tag"
    const val PROMPT_CHAT = "chat"

    /** [RuntimeBinding.config] keys. */
    const val CONFIG_CONTEXT_TOKENS = "contextTokens"
    const val CONFIG_MODE = "mode"
    const val MODE_EMBEDDING = "embedding"

    /** Variant ids are `<model id>@legacy`: one variant per seed, its quantization chosen at install time. */
    const val LEGACY_VARIANT_SUFFIX = "@legacy"

    /**
     * Everything shipped, in the order the legacy lists declare it. Custom
     * models (Settings) are not here: they are mapped one at a time by
     * [customModel] / [customWhisperModel] from what the user typed.
     *
     * [madladTargetLanguages]: the codes of `madlad_languages.json`
     * (MadladLanguages), passed in so this stays free of a Context.
     */
    fun bundledDocument(madladTargetLanguages: Collection<String>): CatalogDocument = CatalogDocument(
        schema = 1,
        catalogId = CATALOG_ID,
        catalogVersion = CATALOG_VERSION,
        allowedHosts = ALLOWED_HOSTS,
        models = LocalModels.SEEDS.map { localModel(it, madladTargetLanguages) } +
            TranslationModels.SEEDS.map { localModel(it, madladTargetLanguages) } +
            WhisperModels.SEEDS.map(::whisperModel) +
            VoskModels.SEEDS.map(::voskModel) +
            ExperimentalEmbeddingModels.ALL.map(::embeddingModel),
    )

    /** A user-added GGUF repository (chat or translation): same mapping, never part of the bundled catalogue. */
    fun customModel(seed: LocalModelSeed): ModelDefinition = localModel(seed, madladTargetLanguages = emptyList())

    /** A user-added Whisper URL: same mapping, never part of the bundled catalogue. */
    fun customWhisperModel(seed: WhisperModelSeed): ModelDefinition = whisperModel(seed)

    // --- llama.cpp GGUF seeds (chat and translation) ---

    fun localModel(seed: LocalModelSeed, madladTargetLanguages: Collection<String>): ModelDefinition {
        val hasProjector = seed.mmprojFileName != null
        val capabilities = linkedMapOf<CapabilityId, CapabilityFacet>()
        for (capability in seed.capabilities) {
            capabilities[capabilityId(capability)] = facetFor(capability, seed, madladTargetLanguages)
        }
        // Legacy vision is implicit: a seed with a projector gets image input
        // once the projector is installed. Made explicit here, tied to the role.
        if (hasProjector) capabilities[Capabilities.VISION] = GenericFacet(requiresRoles = setOf(ArtifactRoles.PROJECTOR))

        val artifacts = buildList {
            add(
                ArtifactSpec(
                    role = ArtifactRoles.WEIGHTS,
                    fileName = "model$GGUF_EXTENSION",
                    sizeBytes = seed.approxSizeBytes,
                    source = ArtifactSource.HuggingFaceSelection(
                        repoIds = seed.repoIds,
                        revision = LEGACY_REVISION,
                        file = FileSelector.ByQuantization(
                            seed.quantPriority ?: ArtifactResolver.DEFAULT_QUANT_PRIORITY,
                            GGUF_EXTENSION,
                        ),
                    ),
                ),
            )
            seed.mmprojFileName?.let { name ->
                add(
                    ArtifactSpec(
                        role = ArtifactRoles.PROJECTOR,
                        fileName = "projector$GGUF_EXTENSION",
                        sizeBytes = seed.mmprojApproxSizeBytes,
                        // Same repositories, same order, exact file name — HuggingFaceResolver.resolveExact.
                        source = ArtifactSource.HuggingFaceSelection(seed.repoIds, LEGACY_REVISION, FileSelector.ExactName(name)),
                        // Best-effort in legacy: a missing projector leaves a working text-only model.
                        optional = true,
                    ),
                )
            }
        }
        val binding = RuntimeBinding(
            runtime = Runtimes.LLAMA_CPP,
            requiredRoles = setOf(ArtifactRoles.WEIGHTS),
            optionalRoles = if (hasProjector) setOf(ArtifactRoles.PROJECTOR) else emptySet(),
            config = mapOf(CONFIG_CONTEXT_TOKENS to seed.contextTokens.toString()),
        )
        return ModelDefinition(
            id = ModelId(seed.id),
            family = ModelFamilyId(familyOf(seed.id)),
            displayName = seed.title,
            status = CatalogStatus.UNVERIFIED,
            capabilities = capabilities,
            variants = listOf(ModelVariant(VariantId(seed.id + LEGACY_VARIANT_SUFFIX), artifacts = artifacts, bindings = listOf(binding))),
        )
    }

    /**
     * The legacy capability enum → the domain's open ids. Exhaustive on
     * purpose: a new legacy capability fails compilation here instead of
     * being dropped.
     */
    fun capabilityId(capability: Capability): CapabilityId = when (capability) {
        Capability.SPEECH_TO_TEXT -> Capabilities.SPEECH_TO_TEXT
        Capability.SPEAKER_DIARIZATION -> Capabilities.DIARIZATION
        Capability.TEXT_GENERATION -> Capabilities.TEXT_GENERATION
        Capability.REASONING -> Capabilities.REASONING
        Capability.CODING -> Capabilities.CODING
        Capability.TRANSLATION -> Capabilities.TRANSLATION
        Capability.VISION -> Capabilities.VISION
        Capability.OCR -> Capabilities.OCR
        Capability.IMAGE_UNDERSTANDING -> Capabilities.VISION
        Capability.VIDEO_UNDERSTANDING -> Capabilities.VIDEO_UNDERSTANDING
        Capability.EMBEDDING -> Capabilities.TEXT_EMBEDDING
        Capability.RERANKING -> Capabilities.RERANKING
    }

    private fun facetFor(capability: Capability, seed: LocalModelSeed, madladTargetLanguages: Collection<String>): CapabilityFacet =
        when (capability) {
            Capability.TRANSLATION -> TranslationFacet(
                // Source: legacy never constrains it (MADLAD auto-detects) — unconstrained, not "verified all".
                sourceLanguages = LanguageSet.All,
                // Only MADLAD has a declared list (its target-tag table); the others never said.
                targetLanguages = if (seed.isT5EncoderDecoder) LanguageSet.Of(madladTargetLanguages) else LanguageSet.All,
                promptFormat = if (seed.isT5EncoderDecoder) PROMPT_MADLAD_TAG else PROMPT_CHAT,
            )
            Capability.VISION, Capability.IMAGE_UNDERSTANDING ->
                GenericFacet(requiresRoles = if (seed.mmprojFileName != null) setOf(ArtifactRoles.PROJECTOR) else emptySet())
            // A GGUF seed declaring these would need facts it doesn't carry (dimensions, languages).
            Capability.SPEECH_TO_TEXT, Capability.EMBEDDING ->
                throw IllegalArgumentException("${seed.id}: legacy seed declares $capability, which needs facet data a LocalModelSeed doesn't have")
            else -> GenericFacet()
        }

    // --- whisper.cpp ---

    fun whisperModel(seed: WhisperModelSeed): ModelDefinition = ModelDefinition(
        id = ModelId(seed.id),
        family = ModelFamilyId(familyOf(seed.id)),
        displayName = seed.title,
        status = CatalogStatus.UNVERIFIED,
        capabilities = mapOf(
            // WhisperRegisteredSpeechModel: no language list (generalist), live mic supported.
            // Timestamps are never relied on by the legacy path, so not claimed.
            Capabilities.SPEECH_TO_TEXT to SpeechToTextFacet(languages = LanguageSet.All, streaming = true, timestamps = false),
        ),
        variants = listOf(
            ModelVariant(
                id = VariantId(seed.id + LEGACY_VARIANT_SUFFIX),
                artifacts = listOf(
                    ArtifactSpec(
                        role = ArtifactRoles.WEIGHTS,
                        fileName = "model.bin",
                        sizeBytes = seed.approxSizeBytes,
                        source = sourceForUrl(seed.modelUrl),
                    ),
                ),
                bindings = listOf(RuntimeBinding(Runtimes.WHISPER_CPP, requiredRoles = setOf(ArtifactRoles.WEIGHTS))),
            ),
        ),
    )

    // --- Vosk ---

    /** AppContainer registers vosk-small-ru as RU and vosk-small-en as EN; vosk-ru is unregistered and Russian by name. */
    private val VOSK_LANGUAGES = mapOf(
        "vosk-small-ru" to "ru",
        "vosk-small-en" to "en",
        "vosk-ru" to "ru",
    )

    fun voskModel(seed: VoskModelSeed): ModelDefinition {
        val language = requireNotNull(VOSK_LANGUAGES[seed.id]) { "${seed.id}: no known language for this Vosk model" }
        val sources = seed.downloadUrls.map(::sourceForUrl)
        return ModelDefinition(
            id = ModelId(seed.id),
            family = ModelFamilyId("vosk"),
            displayName = seed.title,
            status = CatalogStatus.UNVERIFIED,
            capabilities = mapOf(
                Capabilities.SPEECH_TO_TEXT to SpeechToTextFacet(languages = LanguageSet.of(language), streaming = true, timestamps = false),
            ),
            variants = listOf(
                ModelVariant(
                    id = VariantId(seed.id + LEGACY_VARIANT_SUFFIX),
                    artifacts = listOf(
                        ArtifactSpec(
                            role = ArtifactRoles.ARCHIVE,
                            fileName = "model.zip",
                            sizeBytes = seed.approxSizeBytes,
                            source = sources.first(),
                            // The same release archive re-hosted — asserted by its name, not verified (no hash exists).
                            mirrors = sources.drop(1),
                            unpack = UnpackSpec("zip"),
                        ),
                    ),
                    bindings = listOf(RuntimeBinding(Runtimes.VOSK, requiredRoles = setOf(ArtifactRoles.ARCHIVE))),
                ),
            ),
        )
    }

    // --- experimental embeddings (llama.cpp embedding mode) ---

    fun embeddingModel(spec: EmbeddingModelSpec): ModelDefinition = ModelDefinition(
        id = ModelId(spec.id),
        family = ModelFamilyId("multilingual-e5"),
        displayName = spec.title,
        status = CatalogStatus.UNVERIFIED,
        capabilities = mapOf(
            Capabilities.TEXT_EMBEDDING to EmbeddingFacet(
                dimensions = spec.dimension,
                pooling = poolingName(spec.pooling),
                // LlamaBridge.nativeEmbed returns L2-normalized vectors.
                normalized = true,
                queryPrefix = spec.queryPrefix,
                documentPrefix = spec.passagePrefix,
                similarity = "cosine",
            ),
        ),
        variants = listOf(
            ModelVariant(
                id = VariantId(spec.id + LEGACY_VARIANT_SUFFIX),
                artifacts = listOf(
                    ArtifactSpec(
                        role = ArtifactRoles.WEIGHTS,
                        fileName = "model$GGUF_EXTENSION",
                        // EmbeddingModelSpec carries no size estimate.
                        sizeBytes = 0,
                        source = ArtifactSource.HuggingFaceSelection(
                            repoIds = listOf(spec.repoId),
                            revision = LEGACY_REVISION,
                            // ExperimentalEmbeddingDownloads: quantPriority = listOf(quantLabel).
                            file = FileSelector.ByQuantization(listOf(spec.quantLabel), GGUF_EXTENSION),
                        ),
                    ),
                ),
                bindings = listOf(
                    RuntimeBinding(Runtimes.LLAMA_CPP, requiredRoles = setOf(ArtifactRoles.WEIGHTS), config = mapOf(CONFIG_MODE to MODE_EMBEDDING)),
                ),
            ),
        ),
    )

    fun poolingName(pooling: EmbeddingPooling): String = when (pooling) {
        EmbeddingPooling.MEAN -> "mean"
        EmbeddingPooling.CLS -> "cls"
        EmbeddingPooling.LAST -> "last"
    }

    // --- shared ---

    private val HF_RESOLVE = Regex("^https://huggingface\\.co/([^/]+/[^/]+)/resolve/([^/]+)/(.+)$")

    /** A Hugging Face `resolve` URL becomes a structured source (still on its branch, so unpinned); anything else stays a URL. */
    fun sourceForUrl(url: String): ArtifactSource {
        val match = HF_RESOLVE.matchEntire(url) ?: return ArtifactSource.DirectUrl(url)
        val (repo, revision, path) = match.destructured
        return ArtifactSource.HuggingFace(repo, revision, path)
    }

    /**
     * Family = the lineage a newer version would replace within (gemma-3 →
     * a later gemma-3.x), so sizes of one generation share it and a
     * fine-tune of another lineage (ivrit.ai's Whisper) gets its own.
     */
    fun familyOf(modelId: String): String = when {
        modelId.startsWith("gemma-4-") -> "gemma-4"
        modelId.startsWith("gemma-3-") -> "gemma-3"
        modelId.startsWith("qwen3.5-") -> "qwen3.5"
        modelId.startsWith("llama-3.1-") -> "llama-3.1"
        modelId.startsWith("madlad400-") -> "madlad400"
        modelId.startsWith("translategemma-") -> "translategemma"
        modelId.startsWith("omnitranslate-") -> "omnitranslate"
        modelId.startsWith("whisper-ivrit-") -> "whisper-ivrit"
        modelId.startsWith("whisper-") -> "whisper"
        modelId.startsWith("custom-") -> "custom"
        else -> throw IllegalArgumentException("$modelId: no family assigned — add it to LegacyCatalogMapper.familyOf")
    }
}
