package ai.localstudio.whisper

import java.io.File

/**
 * Which CPU-feature build of a native library this device can run. The APK
 * ships three per library (see app/src/main/cpp/CMakeLists.txt):
 * `lib<name>.so` (plain ARMv8.0), `lib<name>_dotprod.so` (ARMv8.2 + dotprod +
 * fp16) and `lib<name>_i8mm.so` (+ int8 matrix multiply) — though [detect]
 * never actually picks that last one; see its own doc comment.
 *
 * Loading a build that uses instructions the CPU lacks isn't an error you can
 * catch — it's SIGILL mid-inference (real device report: a Snapdragon 865
 * running the i8mm build died inside ggml_gemm_q4_K_8x8_q8_K). So the choice
 * is made from the features the kernel itself reports in /proc/cpuinfo,
 * taking the intersection across every core listed: threads can be scheduled
 * on any of them.
 *
 * Declared in :whisper because it's the lowest Android module both native
 * libraries' loaders can see (:app depends on it); nothing here is
 * whisper-specific.
 */
enum class CpuVariant(val librarySuffix: String) {
    I8MM("_i8mm"),
    DOTPROD("_dotprod"),
    BASELINE(""),
    ;

    companion object {
        /**
         * Pure, for tests: the best variant [cpuinfo]'s `Features` lines
         * allow — capped at [DOTPROD], never [I8MM], even on a CPU that
         * supports it.
         *
         * Real device report, Pixel 10 Pro (which does have i8mm):
         * OmniTranslate translating Russian into Hebrew came back as a
         * garbled mix of Hebrew/Arabic/Chinese/Vietnamese script — not a
         * crash, wrong output, and every one of those turns had "0 reused"
         * in its own KV-cache stats, so it wasn't stale cache state either.
         * The same ARM i8mm 8x8-block Q4_K repack GEMM kernel this file's
         * own [I8MM] doc comment already names as having SIGILL'd a
         * different device (Snapdragon 865) is a documented source of real
         * *correctness* bugs upstream too, not just illegal-instruction
         * crashes on unsupported hardware — this project has no way to
         * verify llama.cpp's pinned commit against that history from this
         * sandbox. Given i8mm has now cost this project one crash and one
         * wrong-output report on two different real devices, it is not
         * worth the prompt-processing speedup until upstream's fix (if any)
         * is confirmed and this pin is bumped past it; dropping straight to
         * dotprod is the same trade this project already made once before,
         * just made permanent instead of device-conditional.
         */
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
            return if (dotprodFp16) DOTPROD else BASELINE
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
