package ai.localstudio.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class CpuVariantTest {
    private fun cpuinfo(vararg cores: String) = cores.joinToString("\n\n") { "processor\t: 0\nFeatures\t: $it" }

    @Test
    fun every_core_must_have_a_feature_for_its_build_to_be_picked() {
        val dotprod = "fp asimd fphp asimdhp asimddp"
        assertEquals(CpuVariant.I8MM, CpuVariant.detect(cpuinfo("$dotprod i8mm", "$dotprod i8mm")))
        // Snapdragon 865 (S20 FE): dotprod and fp16, no i8mm -- the i8mm build died there with SIGILL.
        assertEquals(CpuVariant.DOTPROD, CpuVariant.detect(cpuinfo("$dotprod i8mm", dotprod)))
        assertEquals(CpuVariant.BASELINE, CpuVariant.detect(cpuinfo("fp asimd")))
        assertEquals(CpuVariant.BASELINE, CpuVariant.detect("no features lines on x86_64"))
    }
}
