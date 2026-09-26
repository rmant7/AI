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
        // 16 GB total, 12.8 GB available, default 35% budget -> usableRamBytes
        // = max(5.6 GB, 7.68 GB) = 7.68 GB, well above every size below —
        // fitsBudget never kicks in here, so this is purely the total-RAM
        // heuristic: recommended ceiling 5.6 GB, lightweight 2.24 GB.
        val phone = deviceWithRam(16)

        assertEquals(ModelFit.LIGHTWEIGHT, phone.classifyFit(2 * GB))
        assertEquals(ModelFit.RECOMMENDED, phone.classifyFit(5_600_000_000))
        assertEquals(ModelFit.ADVANCED, phone.classifyFit(5_700_000_000))
        assertEquals(ModelFit.TOO_LARGE, phone.classifyFit(9 * GB))
    }

    @Test
    fun `the same artifact changes tier with the device`() {
        val artifactSize = 4_800_000_000 // ~8B at Q4_K_M

        assertEquals(ModelFit.TOO_LARGE, deviceWithRam(8).classifyFit(artifactSize))
        // 12 GB total, 9.6 GB available, default 35% budget -> usableRamBytes
        // = max(4.2 GB, 5.76 GB) = 5.76 GB. This artifact's estimated
        // footprint (4.8 GB * 1.3 = 6.24 GB) exceeds that, so it is TOO_LARGE
        // here too, not ADVANCED — classifyFit must never call a size
        // "Advanced" (probably runs) when fitsBudget would already refuse it.
        assertEquals(ModelFit.TOO_LARGE, deviceWithRam(12).classifyFit(artifactSize))
        assertEquals(ModelFit.RECOMMENDED, deviceWithRam(16).classifyFit(artifactSize))
    }

    @Test
    fun `classifyFit never recommends a size fitsBudget would refuse`() {
        // Real device report: lowering the RAM budget percentage still
        // showed several models as "Recommended" that immediately failed to
        // load. Reproduced here with a low budget fraction *and* little free
        // RAM, so usableRamBytes (1.92 GB) sits well under this artifact's
        // 4 GB, even though 4 GB is comfortably under 35% of the 16 GB total
        // (5.6 GB) — the old, budget-unaware threshold this used to classify
        // against.
        val device = DeviceProfile(
            totalRamBytes = 16 * GB,
            availableRamBytes = 2 * GB,
            availableStorageBytes = 100 * GB,
            cpuCores = 8,
            androidApiLevel = 35,
            supportedRuntimes = setOf(RuntimeKind.LLAMA_CPP),
            ramBudgetFraction = 0.12,
        )
        val artifactSize = 4 * GB
        assertEquals(false, device.fitsBudget(artifactSize))
        assertEquals(ModelFit.TOO_LARGE, device.classifyFit(artifactSize))
    }

    @Test
    fun `liveRamBytes ignores a raised policy ceiling that live memory does not back up`() {
        // Real device report: raising ramBudgetFraction to 80% on a 16.3GB
        // phone put usableRamBytes at ~13GB while only ~5GB was genuinely
        // free — MADLAD-400 7B (~6.76GB estimated) cleared that ceiling,
        // loaded, and the whole process was OOM-killed moments into
        // generation. liveRamBytes must stay governed by what's actually
        // free regardless of how high the policy floor is set.
        val device = DeviceProfile(
            totalRamBytes = 16_331_000_000,
            availableRamBytes = 5_386_000_000,
            availableStorageBytes = 100 * GB,
            cpuCores = 8,
            androidApiLevel = 35,
            supportedRuntimes = setOf(RuntimeKind.LLAMA_CPP),
            ramBudgetFraction = 0.8,
            freeRamSafetyFactor = 0.95,
        )
        assertTrue(device.usableRamBytes > 13 * GB, "usableRamBytes was ${device.usableRamBytes}")
        assertTrue(device.liveRamBytes < 5_386_000_000, "liveRamBytes was ${device.liveRamBytes}")
        assertTrue(device.liveRamBytes < 6_760_000_000, "MADLAD-7B's estimate must not clear liveRamBytes here")
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
