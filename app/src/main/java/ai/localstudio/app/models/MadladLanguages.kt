package ai.localstudio.app.models

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry of MADLAD-400's own `<2xx>` language tag table — [code] is
 * exactly what [ai.localstudio.app.TranslationActivity] embeds in that
 * format for [TranslationModels], and [name] doubles as the English name a
 * general chat model's instruction prompt uses for the same language (see
 * [ai.localstudio.app.TranslationActivity.buildChatPrompt]) — one list backs
 * both, since MADLAD-400's names are in English regardless of which model
 * ends up answering.
 */
@Serializable
data class MadladLanguage(val code: String, val name: String)

@Serializable
private data class MadladLanguageFile(val languages: List<MadladLanguage>)

/**
 * The full language list [TranslationActivity]'s language pickers search —
 * sourced from the `LangMap/langid_mapping.py` table a GGUF-based MADLAD-400
 * UI (mamei16/MADLAD-400-WebUI) ships, itself built from MADLAD-400's own
 * BCP-47 tag set — not hand-typed: fabricating even one code here would
 * silently mistranslate whichever language it's wrong for. 417 entries;
 * MADLAD-400's paper cites 419 languages in its training data, so this may
 * be missing one or two obscure ones the source list itself didn't carry —
 * close enough to complete that a gap is worth fixing if it's ever hit,
 * not worth blocking on.
 */
object MadladLanguages {
    private const val ASSET_PATH = "madlad_languages.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<MadladLanguage>? = null

    fun load(context: Context): List<MadladLanguage> {
        cached?.let { return it }
        val text = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return json.decodeFromString<MadladLanguageFile>(text).languages.also { cached = it }
    }
}
