package ai.localstudio.core.registry

/** One file offered by a model repository. */
data class RemoteArtifact(val path: String, val sizeBytes: Long)

/**
 * Picks the file to download out of everything a model repository offers.
 *
 * Quantized-model repos are restructured by their maintainers on a timescale of
 * weeks: file names change, offered quant levels change, sometimes the canonical
 * repo for a family changes. A file name baked into the catalog at build time is
 * therefore wrong within months, which is why [ModelDescriptor] pins a *family*
 * and the concrete artifact is resolved at browse time.
 *
 * The ranking is pure and separated from any network call, because this is the
 * part most likely to need tuning as new naming conventions turn up.
 *
 * Note for whoever writes the HTTP layer: on Hugging Face the real byte size of
 * a large file lives under `lfs.size`, while the top-level `size` is the size of
 * the LFS pointer. Reading the wrong one produces a catalog full of 130-byte
 * models.
 */
object ArtifactResolver {

    /**
     * Ordered by what matters on a phone: Q4_K_M is the standard quality/size
     * sweet spot for llama.cpp; the rest are fallbacks for repos that skip it.
     */
    val DEFAULT_QUANT_PRIORITY = listOf("Q4_K_M", "Q4_K_S", "Q4_0", "IQ4_XS", "Q3_K_M", "Q5_K_M", "Q8_0")

    /**
     * Multi-part artifacts ("model-00001-of-00003.gguf") are excluded outright:
     * downloading only the first part yields a truncated, unusable model that
     * fails much later and much more confusingly.
     */
    private val SPLIT_SUFFIX = Regex("""-\d{5}-of-\d{5}\.[a-z0-9]+$""", RegexOption.IGNORE_CASE)

    /**
     * LiteRT-LM repos on Hugging Face carry a plain, universal file
     * alongside one or more chip-specific ahead-of-time-compiled ones named
     * e.g. `gemma-4-E2B-it_Google_Tensor_G5.litertlm` — built for one exact
     * NPU generation and, per real repo listings, not necessarily smaller
     * than the universal file, so nothing about the existing ranking
     * guarantees the universal one wins by luck. Excluded outright: this app
     * has no per-chip-generation selection logic, so downloading a G5-only
     * file on a device with a different (or no) matching NPU would be a
     * multi-gigabyte download of a file that cannot run there.
     */
    private val CHIP_SPECIFIC = Regex("""_Google_Tensor_""", RegexOption.IGNORE_CASE)

    fun pickBest(
        candidates: List<RemoteArtifact>,
        extension: String = ".gguf",
        quantPriority: List<String> = DEFAULT_QUANT_PRIORITY,
    ): RemoteArtifact? {
        val usable = candidates.filter {
            it.path.endsWith(extension, ignoreCase = true) &&
                !SPLIT_SUFFIX.containsMatchIn(it.path) &&
                !CHIP_SPECIFIC.containsMatchIn(it.path)
        }
        if (usable.isEmpty()) return null

        for (quant in quantPriority) {
            usable.firstOrNull { it.path.contains(quant, ignoreCase = true) }?.let { return it }
        }
        // Unrecognised naming convention: prefer the smallest, which is the safer
        // default on a device when the quality/size trade-off is unknown.
        return usable.minByOrNull { it.sizeBytes }
    }
}
