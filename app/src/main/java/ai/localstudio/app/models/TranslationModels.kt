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
    )
}
