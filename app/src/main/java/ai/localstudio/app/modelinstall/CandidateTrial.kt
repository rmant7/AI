package ai.localstudio.app.modelinstall

import ai.localstudio.app.localai.TranslationPrompts
import ai.localstudio.model.install.CapabilityCheck
import ai.localstudio.model.install.CheckStatus
import ai.localstudio.model.install.DeviceVerification
import ai.localstudio.model.install.FailureKind
import ai.localstudio.model.install.ProbeStep
import ai.localstudio.model.install.VerifiedCapability
import java.text.Normalizer
import kotlin.coroutines.cancellation.CancellationException

/**
 * One functional check of a discovered candidate on this device: a prompt
 * whose correct answer is known in advance, so "the model works" is judged
 * from what it actually said, never from it merely producing text.
 */
data class FunctionalProbe(
    val prompt: String,
    val expectAnyOf: List<String>,
    val match: Match = Match.WORD,
    val title: String = prompt.lineSequence().first(),
    /** Shown to the model with [prompt], in this order; empty for a text-only question. */
    val images: List<ProbeImage> = emptyList(),
    /** Each must also be in the answer (whole word), on top of one of [expectAnyOf] -- a question about two images is answered for both. */
    val alsoExpect: List<String> = emptyList(),
    /**
     * Unload the model (weights and projector, whatever is resident) before
     * asking: the question is then answered by a fresh load -- what proves a
     * model comes back whole after eviction, not just once.
     */
    val reloadBefore: Boolean = false,
) {
    /**
     * WORD: the expectation as a whole word or number ("12" is not in "120").
     * LETTERS: letters and digits only, case, accents and spacing ignored --
     * for translations, where "Bonjour," / "bonjour" / "Bon jour" all carry
     * the word the check is about (the last is a spelling error, recorded in
     * the sample, not a failed translation).
     */
    enum class Match { WORD, LETTERS }

    /** [answer] is the final answer only (see [finalAnswer]). */
    fun passes(answer: String): Boolean =
        expectAnyOf.any { contains(answer, it, match) } && alsoExpect.all { contains(answer, it, Match.WORD) }

    private fun contains(answer: String, expected: String, match: Match): Boolean = when (match) {
        // Not inside a longer word or number: "3.12" and "12.5" do not contain the answer 12; "12." ending a sentence does.
        Match.WORD -> Regex("(?<![\\p{L}\\p{N}])(?<!\\p{N}[.,])" + Regex.escape(expected) + "(?![\\p{L}\\p{N}])(?![.,]\\p{N})", RegexOption.IGNORE_CASE)
            .containsMatchIn(answer)
        Match.LETTERS -> lettersOnly(answer).contains(lettersOnly(expected))
    }

    companion object {
        /** Decomposed (NFD), accents become separate combining marks -- not letters, so the filter drops them with spaces and punctuation. */
        fun lettersOnly(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

        val TEXT: List<FunctionalProbe> = listOf(
            FunctionalProbe("What is the capital of France? Answer with one word.", listOf("Paris")),
            FunctionalProbe("What is 7 + 5? Answer with the number only.", listOf("12", "twelve")),
        )

        /** English to French through the very prompt the Translation screen sends a generic model (see [TranslationPrompts]). */
        val TRANSLATION: List<FunctionalProbe> = listOf(
            translation("Good morning, my friend.", "bonjour"),
            translation("Thank you very much.", "merci"),
            translation("Where is the train station?", "gare"),
        )

        /**
         * The whole life of a vision model on one device, in order, each step
         * with an answer that is not a matter of opinion. Run after [TEXT]
         * on the same loaded model:
         * text -> one image -> another image -> two images in one turn ->
         * text -> unload everything -> reload -> one image again.
         * VISION passes only when every step does: an image turn that leaves
         * the model's memory so the next text answer is wrong, a second image
         * that never reaches the model, or a projector that does not come
         * back after a reload is not working vision.
         */
        val VISION: List<FunctionalProbe> = listOf(
            FunctionalProbe(
                "What digit is shown in this image? Answer with the digit only.",
                listOf("7", "seven"),
                title = "image: the digit 7",
                images = listOf(ProbeImage.Digit(7)),
            ),
            FunctionalProbe(
                "What color is the circle in this image? Answer with one word.",
                listOf("red"),
                title = "image: a red circle",
                images = listOf(ProbeImage.Disc(ProbeImage.Disc.RED)),
            ),
            FunctionalProbe(
                "There are two images. What digit is in the first image, and what color is the circle in the second? Answer briefly.",
                listOf("4", "four"),
                alsoExpect = listOf("blue"),
                title = "two images in one turn: the digit 4, a blue circle",
                images = listOf(ProbeImage.Digit(4), ProbeImage.Disc(ProbeImage.Disc.BLUE)),
            ),
            FunctionalProbe(
                "What is 7 + 5? Answer with the number only.",
                listOf("12", "twelve"),
                title = "text after the images: 7 + 5",
            ),
            FunctionalProbe(
                "What digit is shown in this image? Answer with the digit only.",
                listOf("3", "three"),
                title = "image after unloading and reloading the model: the digit 3",
                images = listOf(ProbeImage.Digit(3)),
                reloadBefore = true,
            ),
        )

        /** Every capability is checked on every candidate: a translation model failing chat questions is a translation model, not a broken one. */
        val SUITES: Map<String, List<FunctionalProbe>> = linkedMapOf(
            VerifiedCapability.TEXT to TEXT,
            VerifiedCapability.TRANSLATION to TRANSLATION,
        )

        /** [SUITES], plus [VISION] for a model that has the parts to see (see [ai.localstudio.model.install.ModelArtifact.canCheck]); without them VISION stays NOT_TESTED. */
        fun suitesFor(artifact: ai.localstudio.model.install.ModelArtifact): Map<String, List<FunctionalProbe>> =
            suitesFor(hasProjector = artifact.canCheck(VerifiedCapability.VISION))

        /**
         * The questions for an installed model: [TEXT] unless it only
         * translates (a T5 model is never asked to chat), [TRANSLATION]
         * through the very prompt the Translation screen sends it
         * ([translationFormat]), and [VISION] when its projector is installed.
         */
        fun suitesFor(
            hasProjector: Boolean,
            translationFormat: TranslationPrompts.Format = TranslationPrompts.Format.CHAT_INSTRUCTION,
            isoScriptCode: (String) -> String? = { null },
        ): Map<String, List<FunctionalProbe>> = buildMap {
            val chats = translationFormat != TranslationPrompts.Format.TARGET_TAG
            if (chats) put(VerifiedCapability.TEXT, TEXT)
            put(
                VerifiedCapability.TRANSLATION,
                if (translationFormat == TranslationPrompts.Format.CHAT_INSTRUCTION) TRANSLATION else translation(translationFormat, isoScriptCode),
            )
            if (hasProjector && chats) put(VerifiedCapability.VISION, VISION)
        }

        /** [TRANSLATION]'s sentences, asked in [format]. */
        fun translation(format: TranslationPrompts.Format, isoScriptCode: (String) -> String?): List<FunctionalProbe> =
            TRANSLATION_PAIRS.map { (text, expected) ->
                FunctionalProbe(
                    prompt = TranslationPrompts.forLocalModel(format, "English", "French", "fr", text, isoScriptCode),
                    expectAnyOf = listOf(expected),
                    match = Match.LETTERS,
                    title = "EN→FR \"$text\"",
                )
            }

        private val TRANSLATION_PAIRS = listOf(
            "Good morning, my friend." to "bonjour",
            "Thank you very much." to "merci",
            "Where is the train station?" to "gare",
        )

        private fun translation(text: String, vararg expected: String) = FunctionalProbe(
            prompt = TranslationPrompts.chatInstruction("English", "French", text),
            expectAnyOf = expected.toList(),
            match = Match.LETTERS,
            title = "EN→FR \"$text\"",
        )
    }
}

/**
 * The part of a reply that is the model's answer: everything after the last
 * `</think>`, or the whole reply when it never opened a `<think>` block.
 * Null when a `<think>` block was opened and never closed -- the model was
 * still reasoning when the reply ended, so whatever its draft mentions is
 * not an answer it gave.
 */
fun finalAnswer(reply: String): String? {
    val close = reply.lastIndexOf("</think>")
    return when {
        close >= 0 -> reply.substring(close + "</think>".length)
        reply.contains("<think>") -> null
        else -> reply
    }
}

/**
 * A test image described, not stored: the runtime side draws it (large,
 * centred, black or one plain colour on white), so the check carries no
 * asset and the answer depends on nothing but what is drawn.
 */
sealed interface ProbeImage {
    data class Digit(val digit: Int) : ProbeImage {
        init { require(digit in 0..9) }
    }

    /** A filled circle of [rgb] (0xRRGGBB). */
    data class Disc(val rgb: Int) : ProbeImage {
        companion object {
            const val RED = 0xE00000
            const val BLUE = 0x0030E0
        }
    }
}

/** A question that got no complete answer in the time a check allows it -- the device's limit, not a wrong answer. */
class ProbeTimeoutException(message: String) : Exception(message)

/** What [CandidateTrial.run] needs from the real runtime: load (once) and answer one probe. */
fun interface TrialRuntime {
    /**
     * Answers [probe] -- its prompt, with its images when it has any, after
     * unloading the model first when it asks for a reload --
     * streaming text to [onChunk]. Calls [onLoaded] once the weights are
     * actually loaded -- the only evidence of a load the trial accepts
     * besides text itself; a throw before either means the model never
     * loaded.
     */
    suspend fun answer(probe: FunctionalProbe, onLoaded: () -> Unit, onChunk: (String) -> Unit)
}

/**
 * Runs each capability's [FunctionalProbe]s through a [TrialRuntime] and
 * records what was observed, per capability -- nothing more. A capability
 * PASSes only when every one of its probes' final answers (after any
 * reasoning block) contains what it was expected to; one capability
 * failing does not stop the next from being checked. A model that never
 * loaded has nothing checked at all.
 */
class CandidateTrial(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Capabilities whose questions are all asked even after a wrong answer -- see [FunctionalProbe.VISION]. */
    private val askEveryStep: Set<String> = setOf(VerifiedCapability.VISION),
) {
    suspend fun run(
        deviceProfile: String,
        runtimeId: String,
        suites: Map<String, List<FunctionalProbe>>,
        runtime: TrialRuntime,
        /** What the result is evidence for: these bytes, this device, this runtime, these questions. */
        context: ai.localstudio.model.install.VerificationContext,
    ): DeviceVerification {
        require(suites.values.any { it.isNotEmpty() }) { "a trial needs at least one probe" }
        val answers = mutableListOf<String>()
        val checks = linkedMapOf<String, CapabilityCheck>()
        var intervals = 0
        var generatingMs = 0L
        var loaded = false

        fun verdict(error: String?) = DeviceVerification(
            deviceProfile = deviceProfile,
            runtimeId = runtimeId,
            loaded = loaded,
            inferenceOk = checks.values.any { it.status == CheckStatus.PASS },
            sampleOutput = answers.joinToString(" | ") { it.trim().replace('\n', ' ') }.take(SAMPLE_CHARS).ifBlank { null },
            tokensPerSecond = if (intervals > 0 && generatingMs > 0) intervals * 1000.0 / generatingMs else null,
            error = error,
            verifiedAtEpochMs = clock(),
            checkVersion = context.checkVersion,
            checks = checks,
            artifact = context.artifact,
            device = context.device,
            runtimeVersion = context.runtimeVersion,
        )

        for ((capability, probes) in suites) {
            if (probes.isEmpty()) continue
            val suiteAnswers = mutableListOf<String>()
            val steps = mutableListOf<ProbeStep>()
            var failure: String? = null
            var failureKind: FailureKind? = null
            fun failed(step: ProbeStep, why: String, kind: FailureKind) {
                steps += step
                if (failure == null) {
                    failure = why
                    failureKind = kind
                }
            }
            for (probe in probes) {
                val answer = StringBuilder()
                val askedAt = clock()
                var firstAt = 0L
                var lastAt = 0L
                var chunks = 0
                fun step(passed: Boolean, said: String?, error: String? = null) = ProbeStep(
                    title = probe.title,
                    passed = passed,
                    answer = said?.trim()?.replace('\n', ' ')?.take(STEP_ANSWER_CHARS)?.ifBlank { null },
                    error = error,
                    firstTokenMs = if (chunks > 0) firstAt - askedAt else null,
                    tokensPerSecond = if (chunks >= 2 && lastAt > firstAt) (chunks - 1) * 1000.0 / (lastAt - firstAt) else null,
                    totalMs = clock() - askedAt,
                    generatedTokens = chunks,
                )
                try {
                    runtime.answer(
                        probe,
                        onLoaded = { loaded = true },
                        onChunk = { chunk ->
                            val now = clock()
                            if (chunks == 0) firstAt = now
                            lastAt = now
                            chunks++
                            loaded = true
                            answer.append(chunk)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (answer.isNotEmpty()) suiteAnswers += answer.toString()
                    if (!loaded) {
                        answers += suiteAnswers
                        return verdict(error = "load failed: ${describe(t)}")
                    }
                    // The runtime's own state is unknown after a throw: this capability's remaining questions are not asked.
                    failed(step(false, answer.toString(), describe(t)), "generation failed: ${describe(t)}", kindOf(t))
                    break
                }
                // Between the first and the last chunk of each answer: excludes the
                // load and the prompt evaluation, which say nothing about how fast
                // this device generates with this model.
                if (chunks >= 2) {
                    intervals += chunks - 1
                    generatingMs += lastAt - firstAt
                }
                val reply = answer.toString()
                val final = finalAnswer(reply)
                val wrong = when {
                    final == null -> "no answer to: ${probe.title} -- still reasoning (<think> not closed) when the reply ended"
                    final.isBlank() -> "empty answer to: ${probe.title}"
                    !probe.passes(final) -> "wrong answer to: ${probe.title} (expected ${probe.expectAnyOf.joinToString(" or ")})"
                    else -> null
                }
                suiteAnswers += final ?: reply
                if (wrong == null) {
                    steps += step(true, final)
                    continue
                }
                failed(step(false, final ?: reply), wrong, FailureKind.MODEL_ANSWER)
                // A wrong answer leaves the runtime working: where the rest of the questions are
                // steps of one lifecycle (VISION), they are still asked, so the record says which step broke.
                if (capability !in askEveryStep) break
            }
            answers += suiteAnswers
            val sample = suiteAnswers.joinToString(" | ") { it.trim().replace('\n', ' ') }.take(SAMPLE_CHARS).ifBlank { null }
            checks[capability] = if (failure == null) {
                CapabilityCheck(CheckStatus.PASS, sample = sample, steps = steps)
            } else {
                CapabilityCheck(CheckStatus.FAIL, failure, sample, steps, failureKind)
            }
        }
        val failed = checks.filterValues { it.status == CheckStatus.FAIL }
        return verdict(error = failed.entries.joinToString("; ") { (cap, check) -> "$cap: ${check.detail}" }.ifBlank { null })
    }

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message.orEmpty()}".trimEnd(' ', ':')

    /** Out of memory or out of time is the device's limit; anything else the runtime failed at. */
    private fun kindOf(t: Throwable): FailureKind = when (t) {
        is ai.localstudio.core.runtime.InsufficientMemoryException, is OutOfMemoryError, is ProbeTimeoutException -> FailureKind.RESOURCE
        else -> FailureKind.RUNTIME
    }

    companion object {
        const val SAMPLE_CHARS = 300
        const val STEP_ANSWER_CHARS = 120
    }
}

/** Where the one running test is: loading the weights, then asking question [probe] of [probes]. */
data class CandidateTrialState(
    /** The checked model's [ai.localstudio.model.install.ArtifactId.key] -- a candidate's [DiscoveredCandidate.identity]. */
    val key: String,
    val phase: Phase,
    /** 1-based, while [phase] is [Phase.ANSWERING]. */
    val probe: Int = 0,
    val probes: Int = 0,
    /** Tokens the model has written for the current question so far: a slow answer shows it is moving. */
    val tokens: Int = 0,
    /** For a person: the repository or the installed model's id. */
    val name: String = key.substringBefore('|').substringAfter('/'),
) {
    enum class Phase { LOADING, ANSWERING }
}

data class CandidateDownload(val bytesDone: Long, val bytesTotal: Long) {
    val percent: Int? get() = if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100) else null
}

/**
 * Everything candidate-related in flight, keyed by the candidate's
 * [DiscoveredCandidate.identity] (its ArtifactId) -- not its repository: two
 * files of one repository are two models, downloaded and tested apart.
 * Downloads run side by side like any model's; tests run one at a time
 * (each loads a whole model into RAM), the rest wait in [queued].
 */
data class CandidateWork(
    val downloads: Map<String, CandidateDownload> = emptyMap(),
    val trial: CandidateTrialState? = null,
    val queued: List<String> = emptyList(),
    /** The last download failure per candidate, until it is retried. */
    val failures: Map<String, String> = emptyMap(),
) {
    val isIdle: Boolean get() = downloads.isEmpty() && trial == null && queued.isEmpty()

    /** Downloading, waiting for a test or being tested -- not a moment to delete its files or start it again. */
    fun isBusy(key: String): Boolean = key in downloads || key in queued || trial?.key == key
}
