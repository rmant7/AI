package ai.localstudio.model

/** Builders for small, valid-by-default catalog pieces; each test breaks exactly the rule it's about. */
object Fixtures {
    const val PINNED = "0123456789abcdef0123456789abcdef01234567"
    val SHA_A = "a".repeat(64)
    val SHA_B = "b".repeat(64)

    fun sampleText(): String =
        requireNotNull(Fixtures::class.java.getResource("/catalog-sample.json")) { "fixture missing" }.readText()

    fun sample(): ValidatedCatalog = CatalogLoader.load(sampleText(), CatalogTrust.Bundled)

    fun sampleModel(id: String): ModelDefinition = sample().models.single { it.id.id == id }

    fun hf(path: String = "model.gguf", revision: String = PINNED, repo: String = "example/repo") =
        ArtifactSource.HuggingFace(repo, revision, path)

    fun artifact(
        role: ArtifactRole = ArtifactRoles.WEIGHTS,
        fileName: String = "model.gguf",
        sizeBytes: Long = 1_000,
        sha256: String? = SHA_A,
        source: ArtifactSource = hf(fileName),
        optional: Boolean = false,
        unpack: UnpackSpec? = null,
        mirrors: List<ArtifactSource> = emptyList(),
    ) = ArtifactSpec(role, fileName, sizeBytes, sha256, source, mirrors, optional, unpack)

    fun binding(
        runtime: RuntimeId = Runtimes.LLAMA_CPP,
        required: Set<ArtifactRole> = setOf(ArtifactRoles.WEIGHTS),
        optional: Set<ArtifactRole> = emptySet(),
    ) = RuntimeBinding(runtime, required, optional)

    fun variant(
        id: String = "m@q4",
        artifacts: List<ArtifactSpec> = listOf(artifact()),
        bindings: List<RuntimeBinding> = listOf(binding()),
        metrics: Map<String, Double> = emptyMap(),
    ) = ModelVariant(VariantId(id), artifacts = artifacts, bindings = bindings, metrics = metrics)

    fun model(
        id: String = "m",
        status: CatalogStatus = CatalogStatus.VERIFIED,
        capabilities: Map<CapabilityId, CapabilityFacet> = mapOf(Capabilities.TEXT_GENERATION to GenericFacet()),
        variants: List<ModelVariant> = listOf(variant(id = "$id@q4")),
    ) = ModelDefinition(ModelId(id), ModelFamilyId("fam"), "Model $id", status = status, capabilities = capabilities, variants = variants)

    fun document(vararg models: ModelDefinition, allowedHosts: Set<String> = setOf("huggingface.co")) =
        CatalogDocument(1, "test", "1", allowedHosts, models.toList())

    fun validate(vararg models: ModelDefinition, trust: CatalogTrust = CatalogTrust.Bundled): ValidatedCatalog =
        CatalogValidator.validate(document(*models), trust)
}
