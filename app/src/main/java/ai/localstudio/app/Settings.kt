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

    /**
     * Which providers the router may actually use, in fallback order — never
     * unset, so this always has at least [providerId] in it even for someone
     * who has never touched the checkboxes. [CloudProviders.ALL] lists Local
     * first, so filtering it by this set is what keeps "local first, cloud as
     * the fallback" true regardless of the order things were enabled in.
     */
    var enabledProviderIds: Set<String>
        get() = prefs.getString(KEY_ENABLED_PROVIDERS, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(providerId)
        set(value) = prefs.edit().putString(KEY_ENABLED_PROVIDERS, value.joinToString(",")).apply()

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
        get() = chatModelFor(providerId)
        set(value) = prefs.edit().putString("$KEY_CHAT_MODEL:$providerId", value.trim()).apply()

    /**
     * Same storage [chatModel] reads/writes, parameterized by provider
     * instead of implicitly using [providerId] — for the fallback chain,
     * which needs each enabled provider's own configured model without
     * switching the "currently being edited" provider to read it.
     */
    fun chatModelFor(id: String): String =
        prefs.getString("$KEY_CHAT_MODEL:$id", null)
            ?.takeIf { it.isNotBlank() }
            ?: CloudProviders.byId(id).defaultModel

    fun apiKeyFor(id: String): String = prefs.getString("$KEY_API_KEY:$id", "").orEmpty().trim()

    var speechModel: String
        get() = prefs.getString(KEY_ASR_MODEL, DEFAULT_ASR_MODEL).orEmpty().ifBlank { DEFAULT_ASR_MODEL }
        set(value) = prefs.edit().putString(KEY_ASR_MODEL, value.trim()).apply()

    /** Which downloaded Whisper size to use for voice input. Empty means "whichever is installed". */
    var whisperModelId: String
        get() = prefs.getString(KEY_WHISPER_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WHISPER_MODEL, value).apply()

    /**
     * Share of total RAM a model may claim, in percent.
     *
     * Used to default to 90: reasoned as "this app's whole purpose is running
     * a model, and a cautious default kept every worthwhile model off a
     * 16 GB phone" — true, but the same 90% on a 6 GB phone budgets over
     * 5 GB to one model with the OS, launcher and everything else fighting
     * over what's left. That is not a graceful failure: Android's low-memory
     * killer terminates the process outright, no exception, no dialog — a
     * silent crash right after sending a message is exactly what that looks
     * like. 60% still lets a 16 GB phone reach for a large model; it no
     * longer waves a 6 GB phone into near-certain death.
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

    /**
     * Off by default: with 2+ providers enabled, the normal behaviour is
     * still the fallback chain (local first, cloud only if local fails) —
     * cheaper in battery and API quota. Turning this on sends the same
     * message to every enabled provider at once and shows every answer,
     * which always costs both, even when the first one would have been fine.
     */
    var compareMode: Boolean
        get() = prefs.getBoolean(KEY_COMPARE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPARE_MODE, value).apply()

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
        get() = prefs.getInt(KEY_CONTEXT_TOKENS, LlamaBridge.DEFAULT_CONTEXT_TOKENS).coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_TOKENS, value.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)).apply()

    /** How many tokens a single reply may run to, regardless of how much context is left. */
    var maxResponseTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS).coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)).apply()

    /**
     * What the assistant is told it is, before anything else — persona, tone,
     * house rules. Blank by default, and blank is a real state, not a
     * fallback to some built-in text: Google's own AI Edge Gallery ships its
     * general chat task with no system prompt at all (Task.defaultSystemPrompt
     * = ""), and a small quantized model is specifically weak at reliably
     * obeying an abstract meta-instruction like "answer in the user's
     * language" — it tends to just answer in whatever language it was asked
     * in when nothing is layered on top telling it otherwise. Empty here
     * means [NodeExecutors] gets `null` and skips the system fragment
     * entirely, not "some other Local AI Studio text instead" — the field is
     * purely opt-in, for a persona or house rules someone actually wants.
     */
    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value.trim()).apply()

    /**
     * Clears the sampling keys rather than writing the defaults back, so each
     * property falls through to the same default a fresh install would use —
     * one definition of "default", not two that can drift apart.
     */
    fun resetGenerationDefaults() {
        prefs.edit()
            .remove(KEY_TEMPERATURE)
            .remove(KEY_TOP_P)
            .remove(KEY_TOP_K)
            .remove(KEY_REPEAT_PENALTY)
            .remove(KEY_CONTEXT_TOKENS)
            .remove(KEY_MAX_TOKENS)
            .apply()
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_ENABLED_PROVIDERS = "enabledProviders"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_API_KEY = "apiKey"
        const val KEY_CHAT_MODEL = "chatModel"
        const val KEY_ASR_MODEL = "asrModel"
        const val KEY_WHISPER_MODEL = "whisperModelId"
        const val KEY_MEMORY = "memoryEnabled"
        const val KEY_COMPARE_MODE = "compareMode"
        const val KEY_RAM_PERCENT = "ramBudgetPercent"
        const val KEY_HF_TOKEN = "huggingFaceToken"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "topP"
        const val KEY_TOP_K = "topK"
        const val KEY_REPEAT_PENALTY = "repeatPenalty"
        const val KEY_CONTEXT_TOKENS = "contextTokens"
        const val KEY_MAX_TOKENS = "maxResponseTokens"
        const val KEY_SYSTEM_PROMPT = "systemPrompt"
        const val DEFAULT_RAM_PERCENT = 60
        const val DEFAULT_ASR_MODEL = "whisper-1"
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 0.95f
        const val DEFAULT_TOP_K = 40
        // 1.1 was not enough headroom for small quantized models specifically
        // — they degenerate into repeating a word or phrase far more readily
        // than larger models, and that is exactly what "маленькая модель
        // повторяет слова" is.
        const val DEFAULT_REPEAT_PENALTY = 1.2f

        // Unlike temperature/topP/topK/repeatPenalty, this one was never
        // clamped — a stray value here (0, negative, or absurdly large) goes
        // straight into a native n_ctx allocation. A negative Int cast to
        // uint32_t in llama_jni.cpp wraps to billions, which is a crash on
        // model load, not a graceful error.
        const val MIN_CONTEXT_TOKENS = 512
        const val MAX_CONTEXT_TOKENS = 8192

        const val DEFAULT_MAX_TOKENS = 1024
        const val MIN_MAX_TOKENS = 64
        const val MAX_MAX_TOKENS = 4096
    }
}
