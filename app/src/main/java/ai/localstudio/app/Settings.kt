package ai.localstudio.app

import android.content.Context

/**
 * User-visible configuration. Everything here is optional: with no endpoint the
 * app runs against the built-in stub runtime, so it is usable the moment it is
 * installed.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("local-ai-studio", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var chatModel: String
        get() = prefs.getString(KEY_CHAT_MODEL, DEFAULT_CHAT_MODEL).orEmpty().ifBlank { DEFAULT_CHAT_MODEL }
        set(value) = prefs.edit().putString(KEY_CHAT_MODEL, value.trim()).apply()

    var speechModel: String
        get() = prefs.getString(KEY_ASR_MODEL, DEFAULT_ASR_MODEL).orEmpty().ifBlank { DEFAULT_ASR_MODEL }
        set(value) = prefs.edit().putString(KEY_ASR_MODEL, value.trim()).apply()

    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY, value).apply()

    val hasEndpoint: Boolean get() = endpoint.isNotBlank()

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_API_KEY = "apiKey"
        const val KEY_CHAT_MODEL = "chatModel"
        const val KEY_ASR_MODEL = "asrModel"
        const val KEY_MEMORY = "memoryEnabled"
        const val DEFAULT_CHAT_MODEL = "qwen3:8b"
        const val DEFAULT_ASR_MODEL = "whisper-1"
    }
}
