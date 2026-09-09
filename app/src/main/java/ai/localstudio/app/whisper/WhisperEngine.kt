package ai.localstudio.app.whisper

/** Keeps one loaded [WhisperTranscriber] around across recordings instead of reloading per utterance. */
class WhisperEngine(private val store: WhisperStore) {

    private var transcriber: WhisperTranscriber? = null
    private var loadedSeedId: String? = null

    /** [language] is an ISO-639-1 code ("ru", "en", ...) or "auto" — see WhisperTranscriber's own doc comment. */
    suspend fun transcribe(seed: WhisperModelSeed, audio: ByteArray, language: String = "auto"): String {
        if (loadedSeedId != seed.id || transcriber == null) {
            transcriber?.release()
            val loaded = WhisperTranscriber(store.modelFile(seed))
            loaded.initialize()
            transcriber = loaded
            loadedSeedId = seed.id
        }
        return transcriber?.transcribe(audio, language).orEmpty()
    }

    fun release() {
        transcriber?.release()
        transcriber = null
        loadedSeedId = null
    }
}
