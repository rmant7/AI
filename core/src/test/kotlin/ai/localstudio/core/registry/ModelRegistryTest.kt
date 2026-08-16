package ai.localstudio.core.registry

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRegistryTest {

    @Test
    fun `merge adds new models and never uninstalls existing ones`() {
        val installed = model("text-a", family = "text", version = "1.0")
        val registry = ModelRegistry(
            listOf(RegistryEntry(installed, InstallState.INSTALLED, installedPath = "/models/text/a")),
        )

        val fresh = registry.merge(
            ModelCatalog(
                updatedAt = "2026-08-15",
                models = listOf(installed, model("text-b", family = "text", version = "2.0")),
            ),
        )

        assertEquals(listOf("text-b"), fresh.map { it.id })
        val entry = registry.find("text-a")!!
        assertEquals(InstallState.INSTALLED, entry.state)
        assertEquals("/models/text/a", entry.installedPath)
        assertEquals(setOf("text-a"), registry.installedIds())
    }

    @Test
    fun `diff reports newer members of an installed family with deltas`() {
        val current = model(
            "text-1",
            family = "text",
            version = "3.9",
            benchmarks = Benchmarks(reasoning = 70.0),
        )
        val newer = model(
            "text-2",
            family = "text",
            version = "3.10",
            benchmarks = Benchmarks(reasoning = 82.0),
        )
        val registry = ModelRegistry(
            listOf(
                RegistryEntry(current, InstallState.INSTALLED),
                RegistryEntry(newer, InstallState.AVAILABLE),
            ),
        )

        val updates = registry.diff(Capability.REASONING)

        assertEquals(1, updates.size)
        assertEquals("text-2", updates.single().candidate.id)
        assertEquals(12.0, updates.single().qualityDelta)
    }

    @Test
    fun `an unrelated family is not proposed as a replacement`() {
        val registry = ModelRegistry(
            listOf(
                RegistryEntry(model("text-1", family = "text", version = "1.0"), InstallState.INSTALLED),
                RegistryEntry(model("vision-1", family = "vision", version = "9.0"), InstallState.AVAILABLE),
            ),
        )

        assertTrue(registry.diff(Capability.REASONING).isEmpty())
    }

    @Test
    fun `providing filters by capability and install state`() {
        val registry = ModelRegistry(
            listOf(
                RegistryEntry(
                    model("asr", capabilities = setOf(Capability.SPEECH_TO_TEXT)),
                    InstallState.INSTALLED,
                ),
                RegistryEntry(
                    model("asr-next", capabilities = setOf(Capability.SPEECH_TO_TEXT)),
                    InstallState.AVAILABLE,
                ),
            ),
        )

        assertEquals(2, registry.providing(Capability.SPEECH_TO_TEXT).size)
        assertEquals(
            listOf("asr"),
            registry.providing(Capability.SPEECH_TO_TEXT, onlyInstalled = true).map { it.id },
        )
        assertTrue(registry.providing(Capability.CODING).isEmpty())
        assertNull(registry.find("missing"))
    }
}
