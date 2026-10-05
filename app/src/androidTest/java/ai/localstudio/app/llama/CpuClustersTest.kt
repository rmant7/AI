package ai.localstudio.app.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuClustersTest {

    private fun ghz(vararg cores: Double) = cores.map { (it * 1_000_000).toLong() }

    @Test
    fun tensor_g5_keeps_its_mid_cluster_and_drops_the_efficiency_cores() {
        // The device this was found on: threads=1 for every local model.
        val freqs = ghz(2.25, 2.25, 3.05, 3.05, 3.05, 3.05, 3.05, 3.78)
        assertEquals(6, CpuClusters.performanceCoreCount(freqs, 8))
    }

    @Test
    fun classic_layouts() {
        assertEquals(4, CpuClusters.performanceCoreCount(ghz(1.8, 1.8, 1.8, 1.8, 2.5, 2.5, 2.5, 3.0), 8))
        assertEquals(4, CpuClusters.performanceCoreCount(ghz(1.8, 1.8, 1.8, 1.8, 2.4, 2.4, 2.4, 2.4), 8))
        assertEquals(8, CpuClusters.performanceCoreCount(ghz(3.53, 3.53, 3.53, 3.53, 3.53, 3.53, 4.32, 4.32), 8))
    }

    @Test
    fun nothing_to_tell_apart_falls_back() {
        assertNull(CpuClusters.performanceCoreCount(ghz(2.0, 2.0, 2.0, 2.0), 4))
        assertNull("a core's frequency unreadable", CpuClusters.performanceCoreCount(ghz(2.0, 3.0), 4))
        assertNull(CpuClusters.performanceCoreCount(emptyList(), 0))
    }
}
