package ai.localstudio.app.whisper

import ai.localstudio.core.speech.AudioChunk
import ai.localstudio.core.speech.Language
import ai.localstudio.core.speech.LanguageIdResult
import ai.localstudio.core.speech.LanguageIdentifier

/**
 * A cheap, honestly-limited first [LanguageIdentifier] — per docs/15-
 * speech-routing.md's own architecture note: "initial implementation may
 * use Whisper itself for language identification if that is already
 * available and cheap enough". Runs a quick whisper.cpp auto-language pass
 * over the router's own rolling LID window
 * ([WhisperCppSpeechModel.detectLanguage]), then classifies the
 * *resulting text* by which Unicode script dominates it (Cyrillic -> RU,
 * Hebrew -> HE, Latin -> EN) — a proxy riding on Whisper's own
 * transcription, not a real language-ID model's output. Swappable for a
 * dedicated LID model later without [ai.localstudio.core.speech.StreamingSpeechRouter]
 * or anything above it changing — that is the entire reason
 * [LanguageIdentifier] is an interface.
 *
 * Not cheap in absolute terms: a full whisper pass every LID window is
 * real CPU cost, competing with whichever ASR model the router currently
 * has active. [ai.localstudio.app.AppContainer] is expected to wire this
 * with a several-second `lidStrideMs`, not the sub-second value the
 * interface would technically allow.
 */
internal class WhisperLanguageIdentifier(
    private val whisperModel: suspend () -> WhisperCppSpeechModel,
) : LanguageIdentifier {

    override suspend fun identify(audio: AudioChunk): LanguageIdResult {
        val text = try {
            val floats = FloatArray(audio.pcm.size) { audio.pcm[it] / 32768f }
            whisperModel().detectLanguage(floats)
        } catch (e: Exception) {
            return LanguageIdResult(language = Language.UNKNOWN, confidence = 0f)
        }
        return classify(text)
    }

    private fun classify(text: String): LanguageIdResult {
        if (text.isBlank()) return LanguageIdResult(language = Language.UNKNOWN, confidence = 0f)

        var cyrillic = 0
        var hebrew = 0
        var latin = 0
        for (ch in text) {
            when {
                ch in 'Ѐ'..'ӿ' -> cyrillic++
                ch in '֐'..'׿' -> hebrew++
                ch.isLetter() && ch.code < 128 -> latin++
            }
        }
        val total = cyrillic + hebrew + latin
        if (total == 0) return LanguageIdResult(language = Language.UNKNOWN, confidence = 0f)

        val (language, count) = listOf(Language.RU to cyrillic, Language.HE to hebrew, Language.EN to latin)
            .maxBy { it.second }
        val confidence = count.toFloat() / total
        // More than one script actually present, with no single one
        // dominant, is itself a mixed-language signal in a short window —
        // not just low confidence in one guess.
        val isMixed = listOf(cyrillic, hebrew, latin).count { it > 0 } > 1 && confidence < 0.85f
        return LanguageIdResult(language = language, confidence = confidence, isMixed = isMixed)
    }
}
