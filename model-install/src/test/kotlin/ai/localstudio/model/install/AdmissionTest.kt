package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.ModelVariant
import ai.localstudio.model.ResourceRequirements
import ai.localstudio.model.RuntimeBinding
import ai.localstudio.model.RuntimeId
import ai.localstudio.model.Runtimes
import ai.localstudio.model.VariantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdmissionTest {

    private val weights = ArtifactSpec(ArtifactRoles.WEIGHTS, "model.gguf", 100, null, ArtifactSource.DirectUrl("https://a/m"))
    private val projector = weights.copy(role = ArtifactRoles.PROJECTOR, fileName = "projector.gguf", optional = true)

    private fun variant(bindings: List<RuntimeBinding>, resources: ResourceRequirements = ResourceRequirements()) =
        ModelVariant(VariantId("m@q4"), artifacts = listOf(weights, projector), bindings = bindings, resources = resources)

    private val llama = RuntimeBinding(Runtimes.LLAMA_CPP, setOf(ArtifactRoles.WEIGHTS), setOf(ArtifactRoles.PROJECTOR))
    private val device = DeviceProfile(androidApi = 34, cpuFeatures = setOf("asimddp"), runtimes = setOf(Runtimes.LLAMA_CPP))

    @Test
    fun `storage admission keeps the slack free and leaves an unknown size to the caller`() {
        assertEquals(Admission.Admit, ResourceAdmission.storage(1_000, freeBytes = 1_000 + ResourceAdmission.STORAGE_SLACK_BYTES))
        assertIs<Admission.Deny>(ResourceAdmission.storage(1_001, freeBytes = 1_000 + ResourceAdmission.STORAGE_SLACK_BYTES))
        assertIs<Admission.Unknown>(ResourceAdmission.storage(null, freeBytes = 0))
    }

    @Test
    fun `memory admission counts only the roles in use, a measured peak wins`() {
        val v = variant(listOf(llama), ResourceRequirements(ramEstimateFactor = 1.5))
        val sizes = mapOf(ArtifactRoles.WEIGHTS to 1_000L, ArtifactRoles.PROJECTOR to 600L)
        assertEquals(Admission.Admit, ResourceAdmission.memory(v, setOf(ArtifactRoles.WEIGHTS), sizes, availableBytes = 1_500))
        assertEquals(Admission.Deny(2_400, 1_500), ResourceAdmission.memory(v, setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), sizes, 1_500))
        val measured = variant(listOf(llama), ResourceRequirements(measuredPeakRamBytes = 900))
        assertEquals(Admission.Admit, ResourceAdmission.memory(measured, setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), sizes, 1_000))
        assertIs<Admission.Unknown>(ResourceAdmission.memory(v, setOf(ArtifactRoles.WEIGHTS), emptyMap(), 1_000))
    }

    @Test
    fun `the first binding the device can run wins, with the optional roles actually installed`() {
        val npuOnly = RuntimeBinding(RuntimeId("qnn"), setOf(ArtifactRoles.WEIGHTS), requiresNpu = true)
        val choice = assertIs<RuntimeChoice.Chosen>(
            BindingSelector.choose(variant(listOf(npuOnly, llama)), setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), device),
        )
        assertEquals(llama, choice.binding)
        assertEquals(setOf(ArtifactRoles.PROJECTOR), choice.optionalRoles)

        val textOnly = assertIs<RuntimeChoice.Chosen>(BindingSelector.choose(variant(listOf(llama)), setOf(ArtifactRoles.WEIGHTS), device))
        assertEquals(emptySet(), textOnly.optionalRoles)
    }

    @Test
    fun `every reason a binding can't run is reported`() {
        val demanding = RuntimeBinding(
            RuntimeId("future"), setOf(ArtifactRoles.WEIGHTS), minAndroidApi = 35, cpuFeatures = setOf("i8mm"), requiresGpu = true, requiresNpu = true,
        )
        val unavailable = assertIs<RuntimeChoice.Unavailable>(BindingSelector.choose(variant(listOf(demanding)), emptySet(), device))
        assertEquals(BindingProblem.entries.toSet(), unavailable.problems.getValue(demanding))
    }
}
