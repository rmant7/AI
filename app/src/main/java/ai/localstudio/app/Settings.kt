package ai.localstudio.app

import android.content.Context
import ai.localstudio.app.llama.LlamaBridge

/**
 * User-visible configuration. Everything here is optional: with no endpoint the
 * app runs against the built-in stub runtime, so it is usable the moment it is
 * installed.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("local-ai-studio", Context.MODE_PRIVATE)

    var providerId: String
        get() = prefs.getString(KEY_PROVIDER, CloudProviders.DEMO.id).orEmpty()
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    val provider: CloudProvider get() = CloudProviders.byId(providerId)

    /** Typed by the user only for a self-hosted server; presets supply their own. */
    var customEndpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    /** The address actually used. Empty means the built-in stub runtime. */
    val endpoint: String
        get() = provider.let { if (it.editableUrl) customEndpoint else it.baseUrl }

    // Keyed by provider: a key and a model name belong to one provider, and
    // carrying them over on a switch produces an authentication error that
    // looks like a bug in the app.
    var apiKey: String
        get() = prefs.getString("$KEY_API_KEY:$providerId", "").orEmpty().trim()
        set(value) = prefs.edit().putString("$KEY_API_KEY:$providerId", value.trim()).apply()

    var chatModel: String
        get() = prefs.getString("$KEY_CHAT_MODEL:$providerId", null)
            ?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel
        set(value) = prefs.edit().putString("$KEY_CHAT_MODEL:$providerId", value.trim()).apply()

    var speechModel: String
        get() = prefs.getString(KEY_ASR_MODEL, DEFAULT_ASR_MODEL).orEmpty().ifBlank { DEFAULT_ASR_MODEL }
        set(value) = prefs.edit().putString(KEY_ASR_MODEL, value.trim()).apply()

    /**
     * Share of total RAM a model may claim, in percent. Defaults high: this is
     * an app whose whole purpose is running a model, and on a 16 GB phone the
     * cautious default kept every worthwhile model out.
     */
    var ramBudgetPercent: Int
        get() = prefs.getInt(KEY_RAM_PERCENT, DEFAULT_RAM_PERCENT).coerceIn(10, 95)
        set(value) = prefs.edit().putInt(KEY_RAM_PERCENT, value.coerceIn(10, 95)).apply()

    val ramBudgetFraction: Double get() = ramBudgetPercent / 100.0

    /** Only needed for gated repositories; community mirrors work without it. */
    var huggingFaceToken: String
        get() = prefs.getString(KEY_HF_TOKEN, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_HF_TOKEN, value.trim()).apply()

    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY, value).apply()

    val hasEndpoint: Boolean get() = endpoint.isNotBlank()

    // Sampling: how the model picks its next token. Exposed because a fixed
    // choice cannot be right for every model — a small quantized model that
    // degenerates into repeated phrases at the defaults needs a stronger
    // repeat penalty, not a code change.
    var temperature: Double
        get() = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE).toDouble().coerceIn(0.0, 2.0)
        set(value) = prefs.edit().putFloat(KEY_TEMPERATURE, value.coerceIn(0.0, 2.0).toFloat()).apply()

    var topP: Double
        get() = prefs.getFloat(KEY_TOP_P, DEFAULT_TOP_P).toDouble().coerceIn(0.0, 1.0)
        set(value) = prefs.edit().putFloat(KEY_TOP_P, value.coerceIn(0.0, 1.0).toFloat()).apply()

    var topK: Int
        get() = prefs.getInt(KEY_TOP_K, DEFAULT_TOP_K).coerceIn(0, 200)
        set(value) = prefs.edit().putInt(KEY_TOP_K, value.coerceIn(0, 200)).apply()

    /** 1.0 disables the penalty; higher discourages repeated tokens more. */
    var repeatPenalty: Double
        get() = prefs.getFloat(KEY_REPEAT_PENALTY, DEFAULT_REPEAT_PENALTY).toDouble().coerceIn(1.0, 2.0)
        set(value) = prefs.edit().putFloat(KEY_REPEAT_PENALTY, value.coerceIn(1.0, 2.0).toFloat()).apply()

    /**
     * Local-only: the native context window, in tokens. Larger holds more
     * conversation but costs RAM for every model regardless of size — this
     * is what pushed a model that barely fit into being killed for memory
     * once raised app-wide, so it defaults low and is opt-in to raise.
     */
    var contextTokens: Int
        get() = prefs.getInt(KEY_CONTEXT_TOKENS, LlamaBridge.DEFAULT_CONTEXT_TOKENS)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_TOKENS, value).apply()

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_API_KEY = "apiKey"
        const val KEY_CHAT_MODEL = "chatModel"
        const val KEY_ASR_MODEL = "asrModel"
        const val KEY_MEMORY = "memoryEnabled"
        const val KEY_RAM_PERCENT = "ramBudgetPercent"
        const val KEY_HF_TOKEN = "huggingFaceToken"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "topP"
        const val KEY_TOP_K = "topK"
        const val KEY_REPEAT_PENALTY = "repeatPenalty"
        const val KEY_CONTEXT_TOKENS = "contextTokens"
        const val DEFAULT_RAM_PERCENT = 90
        const val DEFAULT_ASR_MODEL = "whisper-1"
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 0.95f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_REPEAT_PENALTY = 1.1f
    }
}
