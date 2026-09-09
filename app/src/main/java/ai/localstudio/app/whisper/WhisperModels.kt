package ai.localstudio.app.whisper

data class WhisperModelSeed(
    val id: String,
    val title: String,
    val modelUrl: String,
    val approxSizeBytes: Long,
)

/**
 * Official ggml conversions from [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp)
 * — the canonical source whisper.cpp's own download script points at, not a
 * third party's one-off conversion. Each file is fully self-contained
 * (weights, tokenizer and mel filters together), unlike the old TFLite path
 * this replaced, which needed a separately-downloaded shared vocab.json.
 *
 * Medium and up use the q5_0-quantized release rather than the full fp16
 * one: quantization is what makes "large" actually installable on a phone
 * (2.9 GB fp16 vs 1.1 GB q5_0 for large-v3) rather than a menu entry nobody
 * can afford to download. Tiny/base/small stay fp16 — already small enough
 * that quantizing them buys little.
 */
object WhisperModels {

    private const val REPO = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    /** Fast enough to re-run every second or two during recording for a live preview; also the auto-downloaded first-launch default. */
    const val TINY_ID = "whisper-tiny"

    val SEEDS = listOf(
        WhisperModelSeed(
            id = TINY_ID,
            title = "Whisper Tiny",
            modelUrl = "$REPO/ggml-tiny.bin",
            approxSizeBytes = 75_000_000,
        ),
        WhisperModelSeed(
            id = "whisper-base",
            title = "Whisper Base",
            modelUrl = "$REPO/ggml-base.bin",
            approxSizeBytes = 142_000_000,
        ),
        WhisperModelSeed(
            id = "whisper-small",
            title = "Whisper Small",
            modelUrl = "$REPO/ggml-small.bin",
            approxSizeBytes = 466_000_000,
        ),
        WhisperModelSeed(
            id = "whisper-medium",
            title = "Whisper Medium",
            modelUrl = "$REPO/ggml-medium-q5_0.bin",
            approxSizeBytes = 539_000_000,
        ),
        // The distilled-decoder large: most of large-v3's accuracy, several
        // times faster — the practical way to get "large" quality on a phone
        // without also accepting large-v3's own decode speed.
        WhisperModelSeed(
            id = "whisper-large-turbo",
            title = "Whisper Large v3 Turbo",
            modelUrl = "$REPO/ggml-large-v3-turbo-q5_0.bin",
            approxSizeBytes = 574_000_000,
        ),
        WhisperModelSeed(
            id = "whisper-large",
            title = "Whisper Large v3",
            modelUrl = "$REPO/ggml-large-v3-q5_0.bin",
            approxSizeBytes = 1_181_000_000,
        ),
    )

    fun byId(id: String): WhisperModelSeed? = SEEDS.firstOrNull { it.id == id }
}
