package ai.localstudio.app.models

import ai.localstudio.app.R
import ai.localstudio.core.capability.Capability

/**
 * Specialized translation-only models — distinct from [LocalModels], which
 * is chat GGUFs. Deliberately never merged into [LocalModels.SEEDS]: these
 * are T5-family encoder-decoder models ([ai.localstudio.app.llama.LlamaCppRuntime]
 * routes a seed here through [ai.localstudio.app.llama.LlamaBridge.nativeGenerateT5]
 * once loaded, detected via [ai.localstudio.app.llama.LlamaBridge.nativeHasEncoder]),
 * not chat models — offering one in the Text tab would let it be picked as
 * the chat model, where it would receive an ordinary chat prompt it was
 * never trained on and answer with nonsense, not an error.
 *
 * [AppContainer.installedSeeds] and therefore [AppContainer.localRegistry]
 * include these alongside [LocalModels.SEEDS] — both are backed by the same
 * download/install machinery ([AppContainer.downloads], [ModelStore]), this
 * list only changes which screen offers a seed, not how it is fetched or
 * stored.
 */
object TranslationModels {

    val SEEDS = listOf(
        LocalModelSeed(
            id = "madlad400-3b-mt-q4",
            title = "MADLAD-400 3B",
            repoIds = listOf(
                "mtsdurica/madlad400-3b-mt-Q4_K_M-GGUF",
                "jbochi/madlad400-3b-mt",
            ),
            paramsLabel = "3B · Q4 · T5 encoder-decoder",
            noteRes = R.string.note_madlad400_3b,
            approxSizeBytes = 1_650_000_000,
            // TEXT_GENERATION, not just TRANSLATION: a plain-text turn (which
            // is all TranslationActivity ever sends) routes through
            // CapabilityRouter to Capability.TEXT_GENERATION regardless of
            // what the request is actually for — there is no separate
            // "translation intent" routing anywhere in that router. Without
            // this, ModelSelector.select(TEXT_GENERATION) finds nothing in
            // a registry built from just this one candidate and throws
            // NoModelForCapabilityException — confirmed on a real device:
            // selecting MADLAD-400 made every translation fail outright,
            // before nativeGenerateT5 ever got a chance to run.
            capabilities = setOf(Capability.TRANSLATION, Capability.TEXT_GENERATION),
        ),
        // 7B and 10B below: same architecture, same known crash (a ggml repack
        // GEMM kernel faulting on this app's own T5 encoder path on a
        // REPACK-capable ARM CPU — see AppContainer.translationOrchestrator's
        // and LlamaBridge.nativeGenerateT5's own history/comments) as the 3B
        // seed above until that's actually fixed upstream or worked around
        // here. Listed anyway, for a high-RAM device to have ready once it
        // is — approxSizeBytes alone already keeps a low-RAM phone from
        // seeing them as installable (see DeviceProfile/SuitabilityScorer).
        LocalModelSeed(
            id = "madlad400-7b-mt-q5",
            title = "MADLAD-400 7B (Q5_K_M)",
            repoIds = listOf("egekocabas/madlad400-7b-mt-Q5_K_M-GGUF"),
            paramsLabel = "7B · Q5_K_M · T5 encoder-decoder",
            noteRes = R.string.note_madlad400_7b,
            // Not read off a file listing — no repo screenshot gave an exact
            // size for this one, only its URL. A rough Q5_K_M-for-7B
            // estimate; ModelDownloads' own space check uses the real size
            // HuggingFaceResolver reads live at download time regardless, so
            // this only affects the number shown before that happens.
            approxSizeBytes = 5_200_000_000,
            capabilities = setOf(Capability.TRANSLATION, Capability.TEXT_GENERATION),
        ),
        // thirteenbit/madlad400-10b-mt-gguf carries five quantisations of the
        // *same* 10B model as separate files in one repo — quantPriority
        // pins each seed below to its own, since HuggingFaceResolver would
        // otherwise pick whichever quant ranks first in the app-wide default
        // priority for every one of them, downloading the same file five
        // times over under five different names.
        LocalModelSeed(
            id = "madlad400-10b-mt-q3",
            title = "MADLAD-400 10B (Q3_K)",
            repoIds = listOf("thirteenbit/madlad400-10b-mt-gguf"),
            quantPriority = listOf("Q3_K"),
            paramsLabel = "10B · Q3_K · T5 encoder-decoder",
            noteRes = R.string.note_madlad400_10b,
            approxSizeBytes = 5_220_000_000,
            capabilities = setOf(Capability.TRANSLATION, Capability.TEXT_GENERATION),
        ),
        LocalModelSeed(
            id = "madlad400-10b-mt-q4",
            title = "MADLAD-400 10B (Q4_K)",
            repoIds = listOf("thirteenbit/madlad400-10b-mt-gguf"),
            quantPriority = listOf("Q4_K"),
            paramsLabel = "10B · Q4_K · T5 encoder-decoder",
            noteRes = R.string.note_madlad400_10b,
            approxSizeBytes = 6_690_000_000,
            capabilities = setOf(Capability.TRANSLATION, Capability.TEXT_GENERATION),
        ),
        LocalModelSeed(
            id = "madlad400-10b-mt-q5",
            title = "MADLAD-400 10B (Q5_K)",
            repoIds = listOf("thirteenbit/madlad400-10b-mt-gguf"),
            quantPriority = listOf("Q5_K"),
            paramsLabel = "10B · Q5_K · T5 encoder-decoder",
            noteRes = R.string.note_madlad400_10b,
            approxSizeBytes = 7_710_000_000,
            capabilities = setOf(Capability.TRANSLATION, Capability.TEXT_GENERATION),
        ),
        LocalModelSeed(
            id = "madlad400-10b-mt-q6",
            title = "MADLAD-400 10B (Q6_K)",
            repoIds = listOf("thirteenbit/madlad400-10b-mt-gguf"),
            quantPriority = listOf("Q6_K"),
            paramsLabel = "10B · Q6_K · T5 encoder-decoder",
            noteRes = R.string.note_madlad400_10b,
            // The on-device file listing showed this one oddly — smaller
            // than Q5_K's 7.71GB, which isn't possible for a heavier quant
            // of the same model. Estimated from the Q3→Q5 progression
            // instead of repeating a number that's almost certainly a
            // misread; HuggingFaceResolver's live resolution corrects this
            // before anything is actually downloaded either way.
            approxSizeBytes = 8_800_000_000,
            capabilities = setOf(Capability.TRANSLATION, Capability.TEXT_GENERATION),
        ),
        LocalModelSeed(
            id = "madlad400-10b-mt-q8",
            title = "MADLAD-400 10B (Q8_0)",
            repoIds = listOf("thirteenbit/madlad400-10b-mt-gguf"),
            quantPriority = listOf("Q8_0"),
            paramsLabel = "10B · Q8_0 · T5 encoder-decoder",
            noteRes = R.string.note_madlad400_10b,
            approxSizeBytes = 11_400_000_000,
            capabilities = setOf(Capability.TRANSLATION, Capability.TEXT_GENERATION),
        ),
    )
}
