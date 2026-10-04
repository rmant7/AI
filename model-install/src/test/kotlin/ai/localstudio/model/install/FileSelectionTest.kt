package ai.localstudio.model.install

import ai.localstudio.core.registry.ArtifactResolver as LegacyResolver
import ai.localstudio.core.registry.RemoteArtifact
import ai.localstudio.model.FileSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSelectionTest {

    private fun legacy(files: List<RepoFile>, priority: List<String>, extension: String = ".gguf"): String? =
        LegacyResolver.pickBest(files.map { RemoteArtifact(it.path, it.sizeBytes) }, extension, priority)?.path

    private fun domain(files: List<RepoFile>, priority: List<String>, extension: String = ".gguf"): String? =
        FileSelection.select(FileSelector.ByQuantization(priority, extension), files)?.path

    private val listings: List<List<RepoFile>> = listOf(
        listOf(RepoFile("model-Q8_0.gguf", 800), RepoFile("model-Q4_K_M.gguf", 400), RepoFile("mmproj-F16.gguf", 90)),
        listOf(RepoFile("model-q4_k_m.GGUF", 400), RepoFile("README.md", 1)),
        listOf(RepoFile("model-Q4_K_M-00001-of-00002.gguf", 300), RepoFile("model-Q4_K_M-00002-of-00002.gguf", 300), RepoFile("model-Q8_0.gguf", 900)),
        listOf(RepoFile("weird-name-a.gguf", 500), RepoFile("weird-name-b.gguf", 200), RepoFile("weird-name-c.gguf", 700)),
        listOf(RepoFile("model-IQ4_XS.gguf", 350), RepoFile("model-Q3_K_M.gguf", 300), RepoFile("model-Q5_K_M.gguf", 500)),
        listOf(RepoFile("madlad-Q3_K.gguf", 1), RepoFile("madlad-Q4_K.gguf", 2), RepoFile("madlad-Q4_K_M.gguf", 3), RepoFile("madlad-Q6_K.gguf", 4)),
        listOf(RepoFile("config.json", 1), RepoFile("tokenizer.json", 2)),
        emptyList(),
        listOf(RepoFile("model-Q4_0.gguf", 100), RepoFile("model-Q4_K_S.gguf", 110), RepoFile("ggml-model-q4_0.bin", 5)),
    )

    private val priorities = listOf(
        LegacyResolver.DEFAULT_QUANT_PRIORITY,
        listOf("Q3_K"), listOf("Q4_K"), listOf("Q6_K"), listOf("Q8_0"), listOf("IQ4_XS"), emptyList(),
    )

    @Test
    fun `quantization selection picks exactly what the legacy resolver picks`() {
        for (files in listings) for (priority in priorities) {
            assertEquals(legacy(files, priority), domain(files, priority), "listing $files, priority $priority")
        }
        // A different extension too (whisper-style .bin).
        for (files in listings) assertEquals(legacy(files, listOf("q4_0"), ".bin"), domain(files, listOf("q4_0"), ".bin"))
    }

    @Test
    fun `exact selection matches the base name only`() {
        val files = listOf(RepoFile("model.gguf", 1), RepoFile("mmproj-F16.gguf", 2))
        assertEquals("mmproj-F16.gguf", FileSelection.select(FileSelector.ExactName("mmproj-F16.gguf"), files)?.path)
        assertNull(FileSelection.select(FileSelector.ExactName("mmproj-f16.gguf"), files))
    }
}
