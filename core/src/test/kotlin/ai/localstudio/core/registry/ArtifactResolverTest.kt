package ai.localstudio.core.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtifactResolverTest {

    private fun artifact(path: String, mb: Long) = RemoteArtifact(path, mb * 1_000_000)

    @Test
    fun `the phone-friendly quant wins regardless of listing order`() {
        val best = ArtifactResolver.pickBest(
            listOf(
                artifact("model-Q8_0.gguf", 8_500),
                artifact("model-Q4_K_M.gguf", 4_800),
                artifact("model-Q5_K_M.gguf", 5_700),
            ),
        )

        assertEquals("model-Q4_K_M.gguf", best?.path)
    }

    @Test
    fun `the priority order is respected when the preferred quant is missing`() {
        val best = ArtifactResolver.pickBest(
            listOf(artifact("model-Q8_0.gguf", 8_500), artifact("model-Q4_0.gguf", 4_600)),
        )

        assertEquals("model-Q4_0.gguf", best?.path)
    }

    @Test
    fun `split artifacts are excluded rather than picked and failing later`() {
        val best = ArtifactResolver.pickBest(
            listOf(
                artifact("model-Q4_K_M-00001-of-00003.gguf", 4_000),
                artifact("model-Q4_K_M-00002-of-00003.gguf", 4_000),
                artifact("model-Q8_0.gguf", 8_500),
            ),
        )

        assertEquals("model-Q8_0.gguf", best?.path)
    }

    @Test
    fun `an unfamiliar naming convention falls back to the smallest file`() {
        val best = ArtifactResolver.pickBest(
            listOf(
                artifact("model-mystery-a.gguf", 9_000),
                artifact("model-mystery-b.gguf", 3_200),
            ),
        )

        assertEquals("model-mystery-b.gguf", best?.path)
    }

    @Test
    fun `non-matching files are ignored`() {
        assertNull(
            ArtifactResolver.pickBest(
                listOf(artifact("README.md", 1), artifact("config.json", 1), artifact("model.safetensors", 4_000)),
            ),
        )
    }

    @Test
    fun `an empty repository resolves to nothing`() {
        assertNull(ArtifactResolver.pickBest(emptyList()))
    }

    @Test
    fun `a different runtime's extension can be resolved with the same ranking`() {
        val best = ArtifactResolver.pickBest(
            candidates = listOf(artifact("model-int4.task", 2_600), artifact("model-Q4_K_M.gguf", 4_800)),
            extension = ".task",
        )

        assertEquals("model-int4.task", best?.path)
    }
}
