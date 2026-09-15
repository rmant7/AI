package ai.localstudio.app.vosk

data class VoskModelSeed(
    val id: String,
    val title: String,
    /** Tried in order by [VoskDownloads] — a mirror, not just one URL: `alphacephei.com` alone has proven unreliable to resolve on a real device even over a VPN, so a second host on a domain this app already downloads chat/embedding models from successfully (huggingface.co) is what actually gets someone past a DNS failure rather than just reporting one more clearly. */
    val downloadUrls: List<String>,
    val approxSizeBytes: Long,
)

/**
 * Official small/standard models, primarily from
 * [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) — the
 * same catalogue `vosk-android`'s own docs point at — with a Hugging Face
 * mirror as a fallback host. Each is a zip of a directory
 * ([VoskModelStore.extract] unpacks it); Small is the right size class for
 * a phone and the recommended first try, the larger, more-accurate one is
 * here mainly so there is a second point on the size/accuracy curve to
 * compare against, not because this spike expects anyone to reach for it
 * first.
 */
object VoskModels {

    private const val ALPHACEPHEI = "https://alphacephei.com/vosk/models"

    val SEEDS = listOf(
        // No Hugging Face fallback for the two Russian models: the guessed
        // mirror URL for the large one (localstack/vosk-models) turned out
        // to 404 on a real device — reported directly, after a partial
        // download against alphacephei.com itself had already gotten past
        // half. That guess is now known wrong, and neither could be
        // re-verified from this dev environment (this sandbox's own egress
        // policy blocks huggingface.co outright), so shipping another
        // unverified guess would just add a doomed retry cycle before the
        // real error, not actually help. alphacephei.com is the one host
        // confirmed to actually serve these two files.
        VoskModelSeed(
            id = "vosk-small-ru",
            title = "Vosk Small — Russian",
            downloadUrls = listOf("$ALPHACEPHEI/vosk-model-small-ru-0.22.zip"),
            approxSizeBytes = 45_000_000,
        ),
        VoskModelSeed(
            id = "vosk-small-en",
            title = "Vosk Small — English",
            downloadUrls = listOf(
                "$ALPHACEPHEI/vosk-model-small-en-us-0.15.zip",
                "https://huggingface.co/grimso/vosk-models/resolve/main/vosk-model-small-en-us-0.15.zip",
            ),
            approxSizeBytes = 40_000_000,
        ),
        VoskModelSeed(
            id = "vosk-ru",
            title = "Vosk — Russian (large, more accurate)",
            downloadUrls = listOf("$ALPHACEPHEI/vosk-model-ru-0.42.zip"),
            approxSizeBytes = 1_800_000_000,
        ),
    )

    fun byId(id: String): VoskModelSeed? = SEEDS.firstOrNull { it.id == id }
}
