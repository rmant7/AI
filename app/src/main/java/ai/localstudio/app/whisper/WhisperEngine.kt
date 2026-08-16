package ai.localstudio.app.whisper

/** Keeps one loaded [WhisperTranscriber] around across recordings instead of reloading per utterance. */
class WhisperEngine(private val store: WhisperStore) {

    private var transcriber: WhisperTranscriber? = null
    private var loadedSeedId: String? = null

    suspend fun transcribe(seed: WhisperModelSeed, audio: ByteArray): String {
        if (loadedSeedId != seed.id || transcriber == null) {
            transcriber?.release()
            val loaded = WhisperTranscriber(store.modelFile(seed), store.vocabFile())
            loaded.initialize()
            transcriber = loaded
            loadedSeedId = seed.id
        }
        return transcriber?.transcribe(audio).orEmpty()
    }

    fun release() {
        transcriber?.release()
        transcriber = null
        loadedSeedId = null
    }
}
