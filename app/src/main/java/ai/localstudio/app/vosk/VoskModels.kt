package ai.localstudio.app.vosk

data class VoskModelSeed(
    val id: String,
    val title: String,
    val downloadUrl: String,
    val approxSizeBytes: Long,
)

/**
 * Official small/standard models from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)
 * — the same catalogue `vosk-android`'s own docs point at. Each is a zip of
 * a directory ([VoskModelStore.extract] unpacks it); Small is the right
 * size class for a phone and the recommended first try, the larger,
 * more-accurate one is here mainly so there is a second point on the
 * size/accuracy curve to compare against, not because this spike expects
 * anyone to reach for it first.
 */
object VoskModels {

    private const val REPO = "https://alphacephei.com/vosk/models"

    val SEEDS = listOf(
        VoskModelSeed(
            id = "vosk-small-ru",
            title = "Vosk Small — Russian",
            downloadUrl = "$REPO/vosk-model-small-ru-0.22.zip",
            approxSizeBytes = 45_000_000,
        ),
        VoskModelSeed(
            id = "vosk-small-en",
            title = "Vosk Small — English",
            downloadUrl = "$REPO/vosk-model-small-en-us-0.15.zip",
            approxSizeBytes = 40_000_000,
        ),
        VoskModelSeed(
            id = "vosk-ru",
            title = "Vosk — Russian (large, more accurate)",
            downloadUrl = "$REPO/vosk-model-ru-0.42.zip",
            approxSizeBytes = 1_800_000_000,
        ),
    )

    fun byId(id: String): VoskModelSeed? = SEEDS.firstOrNull { it.id == id }
}
