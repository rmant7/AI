package ai.localstudio.core.runtime

import java.io.File

/**
 * Which CPU-feature build of a native library this device can run. The APK
 * ships three per library (see llama-runtime/src/main/cpp/CMakeLists.txt):
 * `lib<name>.so` (plain ARMv8.0), `lib<name>_dotprod.so` (ARMv8.2 + dotprod +
 * fp16) and `lib<name>_i8mm.so` (+ int8 matrix multiply).
 *
 * Loading a build that uses instructions the CPU lacks isn't an error you can
 * catch — it's SIGILL mid-inference (real device report: a Snapdragon 865
 * running the i8mm build died inside ggml_gemm_q4_K_8x8_q8_K). So the choice
 * is made from the features the kernel itself reports in /proc/cpuinfo,
 * taking the intersection across every core listed: threads can be scheduled
 * on any of them.
 *
 * In :core, the one module both native libraries' loaders depend on
 * (:llama-runtime for llama_jni, :whisper for whisper_jni -- which keeps
 * the old name as a typealias). Pure JVM: /proc/cpuinfo and
 * System.loadLibrary, nothing Android-specific.
 */
enum class CpuVariant(val librarySuffix: String) {
    I8MM("_i8mm"),
    DOTPROD("_dotprod"),
    BASELINE(""),
    ;

    companion object {
        /** Pure, for tests: the best variant [cpuinfo]'s `Features` lines allow. */
        fun detect(cpuinfo: String): CpuVariant {
            val perCore = cpuinfo.lineSequence()
                .filter { it.trimStart().startsWith("Features") && ':' in it }
                .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet() }
                .toList()
            if (perCore.isEmpty()) return BASELINE
            val common = perCore.reduce { acc, features -> acc intersect features }
            // armv8.2-a+dotprod+fp16: dot product plus half-precision FP in
            // both the scalar (fphp) and SIMD (asimdhp) units.
            val dotprodFp16 = "asimddp" in common && "fphp" in common && "asimdhp" in common
            return when {
                dotprodFp16 && "i8mm" in common -> I8MM
                dotprodFp16 -> DOTPROD
                else -> BASELINE
            }
        }

        /** This device's best variant; [BASELINE] when /proc/cpuinfo can't be read (or on x86_64, which has no `Features` lines). */
        val current: CpuVariant by lazy {
            runCatching { detect(File("/proc/cpuinfo").readText()) }.getOrDefault(BASELINE)
        }

        /**
         * Loads the best build of [baseName] this CPU supports, falling back
         * to simpler ones if a file is missing (an x86_64 emulator build only
         * has the baseline). Returns the library name actually loaded, or null
         * if none could be.
         */
        fun loadBest(baseName: String, loader: (String) -> Unit = System::loadLibrary): String? {
            for (variant in entries.dropWhile { it != current }) {
                val name = baseName + variant.librarySuffix
                if (runCatching { loader(name) }.isSuccess) return name
            }
            return null
        }
    }
}
