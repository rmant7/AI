package ai.localstudio.model

import ai.localstudio.model.Fixtures.artifact
import ai.localstudio.model.Fixtures.binding
import ai.localstudio.model.Fixtures.hf
import ai.localstudio.model.Fixtures.model
import ai.localstudio.model.Fixtures.validate
import ai.localstudio.model.Fixtures.variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogValidatorTest {

    private fun rejects(model: ModelDefinition, expected: String, trust: CatalogTrust = CatalogTrust.Bundled) {
        val result = validate(model, trust = trust)
        assertTrue(result.models.isEmpty(), "expected ${model.id} to be rejected")
        assertTrue(
            result.violations.any { expected in it.message },
            "expected a violation containing \"$expected\", got ${result.violations.map { it.message }}",
        )
    }

    @Test
    fun `the sample catalog passes with no violations`() {
        val loaded = Fixtures.sample()
        assertEquals(emptyList(), loaded.violations)
        assertEquals(6, loaded.models.size)
    }

    @Test
    fun `a valid model is accepted`() {
        assertEquals(1, validate(model()).models.size)
    }

    // --- integrity tiers ---

    @Test
    fun `verified requires sha256`() =
        rejects(model(variants = listOf(variant(artifacts = listOf(artifact(sha256 = null))))), "verified requires sha256")

    @Test
    fun `verified and experimental require a commit-pinned Hugging Face revision`() {
        val unpinned = listOf(variant(artifacts = listOf(artifact(source = hf(revision = "main")))))
        rejects(model(status = CatalogStatus.VERIFIED, variants = unpinned), "pinned to a commit")
        rejects(model(status = CatalogStatus.EXPERIMENTAL, variants = unpinned), "pinned to a commit")
    }

    @Test
    fun `an unpinned mirror is rejected just like an unpinned source`() =
        rejects(
            model(variants = listOf(variant(artifacts = listOf(artifact(mirrors = listOf(hf(revision = "main"))))))),
            "pinned to a commit",
        )

    @Test
    fun `verified and experimental require a known size`() {
        val unknownSize = listOf(variant(artifacts = listOf(artifact(sizeBytes = 0))))
        rejects(model(status = CatalogStatus.VERIFIED, variants = unknownSize), "requires a known size")
        rejects(model(status = CatalogStatus.EXPERIMENTAL, variants = unknownSize), "requires a known size")
    }

    @Test
    fun `experimental may omit sha256, unverified may omit sha256, size and pinning`() {
        val noHash = listOf(variant(artifacts = listOf(artifact(sha256 = null))))
        assertEquals(1, validate(model(status = CatalogStatus.EXPERIMENTAL, variants = noHash)).models.size)
        val loose = listOf(variant(artifacts = listOf(artifact(sha256 = null, sizeBytes = 0, source = hf(revision = "main")))))
        assertEquals(1, validate(model(status = CatalogStatus.UNVERIFIED, variants = loose)).models.size)
    }

    @Test
    fun `sha256 must be lowercase hex of the right length`() =
        rejects(model(variants = listOf(variant(artifacts = listOf(artifact(sha256 = "ABC"))))), "64 lowercase hex")

    // --- sources & trust ---

    @Test
    fun `plain http is never accepted`() =
        rejects(
            model(variants = listOf(variant(artifacts = listOf(artifact(source = ArtifactSource.DirectUrl("http://huggingface.co/x.gguf")))))),
            "must be https",
        )

    @Test
    fun `non-network schemes are never accepted`() =
        rejects(
            model(variants = listOf(variant(artifacts = listOf(artifact(source = ArtifactSource.DirectUrl("file:///sdcard/x.gguf")))))),
            "must be https",
        )

    @Test
    fun `a host outside the allowlist is rejected`() =
        rejects(
            model(variants = listOf(variant(artifacts = listOf(artifact(source = ArtifactSource.DirectUrl("https://evil.example/x.gguf")))))),
            "not in the allowlist",
        )

    @Test
    fun `a remote catalog cannot widen the allowlist with its own allowedHosts`() {
        val evil = model(variants = listOf(variant(artifacts = listOf(artifact(source = ArtifactSource.DirectUrl("https://evil.example/x.gguf"))))))
        val remoteDocument = Fixtures.document(evil, allowedHosts = setOf("huggingface.co", "evil.example"))
        val result = CatalogValidator.validate(remoteDocument, CatalogTrust.Remote(allowedHosts = setOf("huggingface.co")))
        assertTrue(result.models.isEmpty())
        assertTrue(result.violations.any { "not in the allowlist" in it.message })
    }

    // --- structure ---

    @Test
    fun `a binding cannot require a role the variant doesn't ship`() =
        rejects(
            model(variants = listOf(variant(bindings = listOf(binding(required = setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.TOKENIZER)))))),
            "missing or optional",
        )

    @Test
    fun `a binding cannot require a role that only an optional artifact provides`() {
        val artifacts = listOf(artifact(), artifact(role = ArtifactRoles.PROJECTOR, fileName = "projector.gguf", optional = true))
        rejects(
            model(variants = listOf(variant(artifacts = artifacts, bindings = listOf(binding(required = setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR)))))),
            "missing or optional",
        )
    }

    @Test
    fun `a binding cannot declare the same role both required and optional`() =
        rejects(
            model(variants = listOf(variant(bindings = listOf(binding(required = setOf(ArtifactRoles.WEIGHTS), optional = setOf(ArtifactRoles.WEIGHTS)))))),
            "both required and optional",
        )

    @Test
    fun `a capability requiring a role no variant provides is rejected`() =
        rejects(
            model(capabilities = mapOf(Capabilities.VISION to GenericFacet(setOf(ArtifactRoles.PROJECTOR)))),
            "no variant provides",
        )

    @Test
    fun `file names must be unique and must stay inside the install directory`() {
        rejects(model(variants = listOf(variant(artifacts = listOf(artifact(), artifact(role = ArtifactRoles.CONFIG))))), "duplicate file name")
        for (bad in listOf("../model.gguf", "/data/model.gguf", "a\\b.gguf", "a/./b", "a//b")) {
            rejects(model(variants = listOf(variant(artifacts = listOf(artifact(fileName = bad, source = hf("x.gguf")))))), "file name")
        }
    }

    @Test
    fun `nested relative file names are allowed`() {
        val nested = artifact(fileName = "tokenizer/vocab.json", role = ArtifactRoles.TOKENIZER, source = hf("vocab.json"))
        assertEquals(1, validate(model(variants = listOf(variant(artifacts = listOf(artifact(), nested))))).models.size)
    }

    @Test
    fun `empty variants, artifacts or bindings are rejected`() {
        rejects(model(variants = emptyList()), "declares no variants")
        rejects(model(variants = listOf(variant(artifacts = emptyList(), bindings = listOf(binding(required = emptySet()))))), "declares no artifacts")
        rejects(model(variants = listOf(variant(bindings = emptyList()))), "declares no runtime bindings")
        rejects(model(capabilities = emptyMap()), "declares no capabilities")
    }

    @Test
    fun `unknown archive formats and nonsense metrics are rejected`() {
        rejects(
            model(variants = listOf(variant(artifacts = listOf(artifact(role = ArtifactRoles.ARCHIVE, unpack = UnpackSpec("rar", 10))), bindings = listOf(binding(required = setOf(ArtifactRoles.ARCHIVE)))))),
            "unknown archive format",
        )
        rejects(model(variants = listOf(variant(metrics = mapOf("translation.he" to 1.5)))), "within 0..1")
    }

    @Test
    fun `an unknown unpacked size is acceptable only below experimental`() {
        fun archiveModel(status: CatalogStatus) = model(
            status = status,
            variants = listOf(
                variant(
                    artifacts = listOf(artifact(role = ArtifactRoles.ARCHIVE, fileName = "m.zip", source = hf("m.zip", revision = "main"), sha256 = null, unpack = UnpackSpec("zip"))),
                    bindings = listOf(binding(required = setOf(ArtifactRoles.ARCHIVE))),
                ),
            ),
        )
        assertEquals(1, validate(archiveModel(CatalogStatus.UNVERIFIED)).models.size)
        rejects(archiveModel(CatalogStatus.EXPERIMENTAL), "requires a known unpacked size")
    }

    @Test
    fun `duplicate model and variant ids drop the later model only`() {
        val result = validate(model(id = "a"), model(id = "a"))
        assertEquals(1, result.models.size)

        val shared = variant(id = "same@q4")
        val byVariant = validate(model(id = "x", variants = listOf(shared)), model(id = "y", variants = listOf(shared)))
        assertEquals(listOf("x"), byVariant.models.map { it.id.id })
        assertTrue(byVariant.violations.any { "duplicate variant id" in it.message })
    }

    @Test
    fun `a known capability whose facet failed to decode is rejected`() =
        rejects(
            model(capabilities = mapOf(Capabilities.TRANSLATION to JsonCapabilityFacet(kotlinx.serialization.json.JsonObject(emptyMap()), "bad"))),
            "unreadable facet",
        )
}
