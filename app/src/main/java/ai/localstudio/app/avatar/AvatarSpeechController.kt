package ai.localstudio.app.avatar

import android.content.Context

/**
 * Glues the three pieces of the Phase 1 pipeline together: the LLM's own
 * growing answer text (fed in from `ChatActivity.send()`'s existing
 * `partial: MutableStateFlow<String?>`/`onPartialText`, unchanged), a
 * [SentenceChunker] to turn that into speakable sentences as soon as each
 * one completes, and an [AvatarTtsEngine] whose callbacks drive [view]'s
 * [AvatarState].
 *
 * Deliberately not a `ViewModel`/lifecycle-aware component of its own:
 * [ChatActivity] owns exactly one of these for as long as
 * [ai.localstudio.app.Settings.avatarEnabled] is on, creating it in
 * `onCreate` and calling [shutdown] from `onDestroy` — the same ownership
 * shape [ChatActivity] already uses for `whisperEngine`/`whisperPreviewEngine`.
 *
 * No language selection here yet: [AvatarTtsEngine.speak] is called with a
 * `null` locale, meaning "whatever the device's default configured TTS
 * voice already is" — good enough for the skeleton this phase is (see the
 * `avatar` branch's own scope notes); matching the answer's actual language
 * is exactly the kind of thing [ai.localstudio.app.models.TtsVoiceFallback]
 * exists for, once this needs it.
 */
class AvatarSpeechController(context: Context, private val view: AvatarView) {

    private val chunker = SentenceChunker()
    private val engine = AvatarTtsEngine(context, onEvent = ::handleEvent)

    // The shape the most recent Range event named, kept across Audio events
    // for the same utterance — onAudioAvailable's own chunks carry no text
    // range of their own, only raw PCM (see AvatarTtsEngine's own doc
    // comment on why the two callbacks report different things).
    @Volatile
    private var currentShape = MouthShape.CLOSED

    /** Called on every growing-text update from the LLM's own stream — see this class's own doc comment. */
    fun onPartialText(fullText: String) {
        chunker.consume(stripAttribution(fullText)).forEach(::enqueue)
    }

    /** The turn finished generating: speaks whatever text never reached a sentence-ending mark. */
    fun onGenerationDone(fullText: String) {
        chunker.flushRemainder(stripAttribution(fullText))?.let(::enqueue)
    }

    // FallbackTextRuntime.attributionFooter() (and ChatActivity's own copy of
    // it for the single-candidate case) appends "\n\n---\n" plus an
    // "Answer from: <model>" line to the *displayed* answer — useful in the chat bubble,
    // not something that should ever be read aloud. Cutting at the marker
    // rather than stripping it after the fact keeps SentenceChunker's own
    // "same string, or longer" contract intact: once the footer starts
    // streaming in (always in one piece — see attributionFooter's own single
    // emit), the text this returns simply stops growing instead of shrinking.
    private fun stripAttribution(text: String): String = text.substringBefore(ATTRIBUTION_MARKER)

    /** The user hit Stop, or a new turn is starting — drops anything still queued/speaking and resets for the next turn. */
    fun onInterrupted() {
        engine.stop()
        currentShape = MouthShape.CLOSED
        view.updateState(AvatarState(mouthOpen = 0f, mouthShape = MouthShape.CLOSED))
    }

    /** Must be called before [onPartialText] sees a new turn's text (which starts shorter than the previous turn's final text). */
    fun onNewTurn() {
        chunker.reset()
    }

    fun shutdown() {
        engine.shutdown()
    }

    private fun enqueue(sentence: String) {
        engine.speak(sentence, locale = null)
    }

    private fun handleEvent(event: AvatarTtsEngine.Event) {
        when (event) {
            is AvatarTtsEngine.Event.Started -> Unit
            is AvatarTtsEngine.Event.Range -> {
                val text = engine.textFor(event.utteranceId) ?: return
                currentShape = VisemeMapper.shapeForRange(text, event.start, event.end)
                view.updateState(AvatarState(mouthOpen = OPEN_ON_RANGE, mouthShape = currentShape))
            }
            is AvatarTtsEngine.Event.Audio -> {
                val level = PcmEnvelopeAnalyzer.rms(event.pcm)
                view.updateState(AvatarState(mouthOpen = level, mouthShape = currentShape))
            }
            is AvatarTtsEngine.Event.Done, is AvatarTtsEngine.Event.Failed -> {
                currentShape = MouthShape.CLOSED
                view.updateState(AvatarState(mouthOpen = 0f, mouthShape = MouthShape.CLOSED))
            }
        }
    }

    private companion object {
        // Used only when onRangeStart fires but the engine this device runs
        // never delivers onAudioAvailable (not every TTS engine implements
        // it — see AvatarTtsEngine's own doc comment) — a fixed, moderate
        // open amount so the mouth still visibly moves per range instead of
        // sitting shut for the whole utterance.
        const val OPEN_ON_RANGE = 0.45f

        const val ATTRIBUTION_MARKER = "\n\n---"
    }
}
