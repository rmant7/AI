package ai.localstudio.core.registry

import ai.localstudio.core.GB
import kotlin.test.Test
import kotlin.test.assertEquals

class RamMeasurementTest {

    private val mb = 1024L * 1024

    @Test
    fun `merging keeps the maximum peak, not an average`() {
        val merged = RamMeasurement(5 * GB, 1).merge(4 * GB).merge(6 * GB)
        assertEquals(6 * GB, merged.peakBytes)
        assertEquals(3, merged.sampleCount)
    }

    @Test
    fun `a single sample gets the wider margin`() {
        // 5.3 GB peak: 20% (1.06 GB) beats the 768 MB floor.
        val peak = 5_300 * mb
        assertEquals((peak * 1.2).toLong(), RamMeasurement(peak, 1).requiredBytes)
    }

    @Test
    fun `a confirmed measurement gets the narrower margin`() {
        val peak = 5_300 * mb
        assertEquals((peak * 1.1).toLong(), RamMeasurement(peak, 2).requiredBytes)
    }

    @Test
    fun `a small model's margin never drops below the fixed floor`() {
        val peak = 1_000 * mb
        assertEquals(peak + 768 * mb, RamMeasurement(peak, 1).requiredBytes)
        assertEquals(peak + 512 * mb, RamMeasurement(peak, 3).requiredBytes)
    }

    @Test
    fun `the measured MADLAD run would have been admitted where the file-size estimate refused it`() {
        // Real device numbers: 5.98 GB file, budget 7.03–7.35 GB, measured ~5.3 GB.
        val fileEstimate = RuntimeBinding(RuntimeKind.LLAMA_CPP, "madlad", fileSizeBytes = 5_980_000_000).effectiveRequiredRamBytes
        val budget = 7_033_464_627L
        val measured = RamMeasurement(5_300 * mb, 1).requiredBytes
        assert(fileEstimate > budget)
        assert(measured < budget)
    }
}
