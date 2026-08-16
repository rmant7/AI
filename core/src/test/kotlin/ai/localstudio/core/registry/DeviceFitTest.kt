package ai.localstudio.core.registry

import ai.localstudio.core.GB
import ai.localstudio.core.binding
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.device
import ai.localstudio.core.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceFitTest {

    private fun deviceWithRam(totalGb: Long) = DeviceProfile(
        totalRamBytes = totalGb * GB,
        availableRamBytes = totalGb * GB * 8 / 10,
        availableStorageBytes = 100 * GB,
        cpuCores = 8,
        androidApiLevel = 35,
        supportedRuntimes = setOf(RuntimeKind.LLAMA_CPP),
    )

    @Test
    fun `tiers follow the documented fractions of total ram`() {
        val phone = deviceWithRam(16) // recommended ceiling 5.6 GB, advanced 8.8 GB, lightweight 2.24 GB

        assertEquals(ModelFit.LIGHTWEIGHT, phone.classifyFit(2 * GB))
        assertEquals(ModelFit.RECOMMENDED, phone.classifyFit(5_600_000_000))
        assertEquals(ModelFit.ADVANCED, phone.classifyFit(5_700_000_000))
        assertEquals(ModelFit.TOO_LARGE, phone.classifyFit(9 * GB))
    }

    @Test
    fun `the same artifact changes tier with the device`() {
        val artifactSize = 4_800_000_000 // ~8B at Q4_K_M

        assertEquals(ModelFit.TOO_LARGE, deviceWithRam(8).classifyFit(artifactSize))
        assertEquals(ModelFit.ADVANCED, deviceWithRam(12).classifyFit(artifactSize))
        assertEquals(ModelFit.RECOMMENDED, deviceWithRam(16).classifyFit(artifactSize))
    }

    @Test
    fun `nonsense input is too large rather than a crash`() {
        assertEquals(ModelFit.TOO_LARGE, deviceWithRam(16).classifyFit(0))
        assertEquals(ModelFit.TOO_LARGE, deviceWithRam(16).classifyFit(-1))
    }

    @Test
    fun `ranking carries the fit label`() {
        val ranked = SuitabilityScorer().rank(
            models = listOf(model("m", bindings = listOf(binding(ramBytes = 2 * GB, fileSizeBytes = 2 * GB)))),
            device = device(),
            capability = Capability.TEXT_GENERATION,
        )

        assertEquals(ModelFit.RECOMMENDED, ranked.single().fit)
    }

    @Test
    fun `an unmeasured binding is planned for pessimistically, not optimistically`() {
        val measured = RuntimeBinding(
            runtime = RuntimeKind.LLAMA_CPP,
            artifact = "m.gguf",
            fileSizeBytes = 4 * GB,
            requiredRamBytes = 4_200_000_000,
        )
        val unmeasured = measured.copy(requiredRamBytes = null)

        assertEquals(4_200_000_000, measured.effectiveRequiredRamBytes)
        assertTrue(unmeasured.effectiveRequiredRamBytes > unmeasured.fileSizeBytes)
        assertTrue(unmeasured.isRamEstimated)
        assertTrue(!measured.isRamEstimated)
    }

    @Test
    fun `the estimate is what the scorer filters on when nothing was measured`() {
        // 6 GB usable: the file fits, the estimated footprint does not.
        val device = device(availableRamBytes = 10 * GB)
        val unmeasured = model(
            "unmeasured",
            bindings = listOf(
                RuntimeBinding(
                    runtime = RuntimeKind.LLAMA_CPP,
                    artifact = "big.gguf",
                    fileSizeBytes = 5 * GB,
                ),
            ),
        )

        val result = SuitabilityScorer().evaluate(unmeasured, device, Capability.TEXT_GENERATION)

        assertTrue(IncompatibilityReason.NOT_ENOUGH_RAM in assertIs<Suitability.Incompatible>(result).reasons)
    }
}
