package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.FileSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolutionTest {

    private val hf = FakeHuggingFace()
    private val resolver = ArtifactResolver(hf)

    private fun spec(source: ArtifactSource, size: Long = 0, sha: String? = null, mirrors: List<ArtifactSource> = emptyList()) =
        ArtifactSpec(ArtifactRoles.WEIGHTS, "model.gguf", size, sha, source, mirrors)

    private fun resolved(spec: ArtifactSpec, status: CatalogStatus = CatalogStatus.UNVERIFIED) =
        assertIs<ArtifactResolution.Resolved>(resolver.resolve(spec, status)).artifact

    @Test
    fun `a branch is resolved to its commit and downloaded from that commit`() {
        hf.repo("org/m", COMMIT_A, RepoFile("m.gguf", 1234, "f".repeat(64)))
        val artifact = resolved(spec(ArtifactSource.HuggingFace("org/m", "main", "m.gguf"), size = 999))
        val candidate = artifact.candidates.single()
        assertEquals("https://huggingface.co/org/m/resolve/$COMMIT_A/m.gguf", candidate.url)
        assertEquals(COMMIT_A, candidate.origin.commit)
        assertEquals("main", candidate.origin.requestedRevision)
        assertEquals(1234, candidate.expectedSizeBytes, "the listing's exact size, not the unverified estimate")
        assertEquals("f".repeat(64), candidate.expectedSha256)
        assertEquals(IntegrityBasis.UPSTREAM_SHA256, artifact.integrity)
    }

    @Test
    fun `an unverified estimate is never an expected size, a verified size is`() {
        hf.repo("org/m", COMMIT_A, RepoFile("m.gguf", 0, null))
        assertNull(resolved(spec(ArtifactSource.HuggingFace("org/m", "main", "m.gguf"), size = 999)).candidates.single().expectedSizeBytes)
        val verified = resolved(spec(ArtifactSource.HuggingFace("org/m", COMMIT_A, "m.gguf"), size = 999, sha = "c".repeat(64)), CatalogStatus.VERIFIED)
        assertEquals(999, verified.candidates.single().expectedSizeBytes)
        assertEquals(IntegrityBasis.CATALOG_SHA256, verified.integrity)
    }

    @Test
    fun `a pinned source with a catalog hash needs no network at all`() {
        resolved(spec(ArtifactSource.HuggingFace("org/m", COMMIT_A, "m.gguf"), size = 5, sha = "c".repeat(64)), CatalogStatus.VERIFIED)
        assertEquals(emptyList(), hf.calls)
    }

    @Test
    fun `an upstream hash contradicting the catalog rejects the source before downloading`() {
        hf.repo("org/m", COMMIT_A, RepoFile("m.gguf", 5, "d".repeat(64)))
        val result = resolver.resolve(spec(ArtifactSource.HuggingFace("org/m", "main", "m.gguf"), sha = "c".repeat(64)), CatalogStatus.UNVERIFIED)
        assertIs<ArtifactResolution.Unresolved>(result)
        assertTrue(result.failures.single().message.contains("differs from the catalog"))
    }

    @Test
    fun `a selection walks its repositories in order past gated and empty ones`() {
        hf.failures["gated/m"] = SourceException(SourceException.Kind.ACCESS_DENIED, "401")
        hf.repo("empty/m", COMMIT_A, RepoFile("README.md", 1))
        hf.repo("good/m", COMMIT_B, RepoFile("m-Q8_0.gguf", 800, "e".repeat(64)), RepoFile("m-Q4_K_M.gguf", 400, "4".repeat(64)))
        val selection = ArtifactSource.HuggingFaceSelection(
            listOf("gated/m", "empty/m", "good/m"), "main", FileSelector.ByQuantization(listOf("Q4_K_M", "Q8_0"), ".gguf"),
        )
        val result = assertIs<ArtifactResolution.Resolved>(resolver.resolve(spec(selection, size = 1), CatalogStatus.UNVERIFIED))
        val candidate = result.artifact.candidates.single()
        assertEquals("https://huggingface.co/good/m/resolve/$COMMIT_B/m-Q4_K_M.gguf", candidate.url)
        assertEquals(400, candidate.expectedSizeBytes)
        assertEquals("4".repeat(64), candidate.expectedSha256)
        assertEquals(listOf(SourceException.Kind.ACCESS_DENIED, SourceException.Kind.NOT_FOUND), result.failures.map { it.kind })
    }

    @Test
    fun `a network failure stops a selection instead of repeating it for every repository`() {
        hf.failures["a/m"] = SourceException(SourceException.Kind.NETWORK, "no route")
        hf.repo("b/m", COMMIT_A, RepoFile("m-Q4_K_M.gguf", 1))
        val selection = ArtifactSource.HuggingFaceSelection(listOf("a/m", "b/m"), "main", FileSelector.ByQuantization(listOf("Q4_K_M"), ".gguf"))
        val result = assertIs<ArtifactResolution.Unresolved>(resolver.resolve(spec(selection), CatalogStatus.UNVERIFIED))
        assertEquals(SourceException.Kind.NETWORK, result.failures.single().kind)
        assertTrue(hf.calls.none { "b/m" in it }, "b/m must not be tried: ${hf.calls}")
    }

    @Test
    fun `alternatives and mirrors become further candidates in order`() {
        hf.repo("mirror/m", COMMIT_A, RepoFile("m.zip", 10, null))
        val alternatives = ArtifactSource.Alternatives(
            listOf(ArtifactSource.DirectUrl("https://alphacephei.com/m.zip"), ArtifactSource.HuggingFace("mirror/m", "main", "m.zip")),
        )
        val viaAlternatives = resolved(spec(alternatives))
        assertEquals(
            listOf("https://alphacephei.com/m.zip", "https://huggingface.co/mirror/m/resolve/$COMMIT_A/m.zip"),
            viaAlternatives.candidates.map { it.url },
        )
        assertNull(viaAlternatives.candidates[0].expectedSizeBytes)
        assertEquals(10, viaAlternatives.candidates[1].expectedSizeBytes, "each alternative carries its own expectations")

        val mirrored = resolved(
            spec(ArtifactSource.DirectUrl("https://example.org/m.gguf"), size = 7, sha = "c".repeat(64), mirrors = listOf(ArtifactSource.DirectUrl("https://example.net/m.gguf"))),
            CatalogStatus.VERIFIED,
        )
        assertEquals(listOf("https://example.org/m.gguf", "https://example.net/m.gguf"), mirrored.candidates.map { it.url })
        assertTrue(mirrored.candidates.all { it.expectedSha256 == "c".repeat(64) && it.expectedSizeBytes == 7L })
    }

    @Test
    fun `a nested path is looked up in its own directory and URL-encoded`() {
        hf.repo("org/m", COMMIT_A, RepoFile("sub dir/m 1.bin", 3, null))
        val candidate = resolved(spec(ArtifactSource.HuggingFace("org/m", "main", "sub dir/m 1.bin"))).candidates.single()
        assertEquals("https://huggingface.co/org/m/resolve/$COMMIT_A/sub%20dir/m%201.bin", candidate.url)
        assertEquals(3, candidate.expectedSizeBytes)
        assertEquals(IntegrityBasis.SIZE_ONLY, candidate.integrity(fromCatalog = false))
    }
}
