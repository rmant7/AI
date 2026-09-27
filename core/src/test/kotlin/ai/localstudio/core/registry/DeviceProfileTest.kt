package ai.localstudio.core.registry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceProfileTest {

    // Real device numbers from the bug report: 16.3 GB total, ~7.7 GB free,
    // RAM budget raised so usableRamBytes lands near 13 GB.
    private val device = DeviceProfile(
        totalRamBytes = 16_331_000_000L,
        availableRamBytes = 7_700_000_000L,
        availableStorageBytes = 100_000_000_000L,
        cpuCores = 8,
        androidApiLevel = 37,
        supportedRuntimes = emptySet(),
        ramBudgetFraction = 0.80,
    )

    @Test
    fun `fitsBudget accepts a 10B model this device cannot actually load right now`() {
        // MADLAD-400 10B Q6_K: 8.80 GB file.
        assertTrue(device.fitsBudget(8_800_000_000L), "usableRamBytes (policy ceiling) should accept it")
    }

    @Test
    fun `fitsLiveMemory correctly refuses the same model`() {
        assertFalse(device.fitsLiveMemory(8_800_000_000L), "liveRamBytes (what's actually free) should refuse it")
    }

    @Test
    fun `a small model passes both checks`() {
        // MADLAD-400 3B: 1.65 GB file.
        assertTrue(device.fitsBudget(1_650_000_000L))
        assertTrue(device.fitsLiveMemory(1_650_000_000L))
    }

    @Test
    fun `fitsLiveMemory tracks live free memory, not the configured budget percentage`() {
        // A bigger device with most of its RAM genuinely free right now —
        // 8.80GB * 1.3 = 11.44GB required; needs availableRamBytes * 0.6 >= that.
        val plentyFree = device.copy(totalRamBytes = 32_000_000_000L, availableRamBytes = 20_000_000_000L)
        assertTrue(plentyFree.fitsLiveMemory(8_800_000_000L))
    }
}
