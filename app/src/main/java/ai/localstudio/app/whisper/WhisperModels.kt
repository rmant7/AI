package ai.localstudio.app.whisper

data class WhisperModelSeed(
    val id: String,
    val title: String,
    val modelUrl: String,
    val approxSizeBytes: Long,
)

/**
 * Sizes and URLs match VirtualClone's `Whisper_Sizes` branch — the same
 * community TFLite conversion of Whisper it used, hosted on the same host
 * every other model in this app is already fetched from.
 */
object WhisperModels {

    /**
     * Every multilingual Whisper checkpoint through large-v2 shares one
     * tokenizer; there is exactly one vocabulary to fetch regardless of
     * which size the user picks.
     */
    const val VOCAB_URL = "https://huggingface.co/openai/whisper-base/resolve/main/vocab.json"

    val SEEDS = listOf(
        WhisperModelSeed(
            id = "whisper-tiny",
            title = "Whisper Tiny",
            modelUrl = "https://huggingface.co/cik009/whisper/resolve/main/whisper-tiny.tflite",
            approxSizeBytes = 75_000_000,
        ),
        WhisperModelSeed(
            id = "whisper-base",
            title = "Whisper Base",
            modelUrl = "https://huggingface.co/cik009/whisper/resolve/main/whisper-base.tflite",
            approxSizeBytes = 145_000_000,
        ),
        WhisperModelSeed(
            id = "whisper-small",
            title = "Whisper Small",
            modelUrl = "https://huggingface.co/cik009/whisper/resolve/main/whisper-small.tflite",
            approxSizeBytes = 480_000_000,
        ),
        WhisperModelSeed(
            id = "whisper-medium",
            title = "Whisper Medium",
            modelUrl = "https://huggingface.co/cik009/whisper/resolve/main/whisper-medium.tflite",
            approxSizeBytes = 1_500_000_000,
        ),
        WhisperModelSeed(
            id = "whisper-large",
            title = "Whisper Large",
            modelUrl = "https://huggingface.co/cik009/whisper/resolve/main/whisper-large.tflite",
            approxSizeBytes = 3_000_000_000,
        ),
    )

    fun byId(id: String): WhisperModelSeed? = SEEDS.firstOrNull { it.id == id }
}
