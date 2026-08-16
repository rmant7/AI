package ai.localstudio.app

import android.content.Context

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

    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY, value).apply()

    val hasEndpoint: Boolean get() = endpoint.isNotBlank()

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_API_KEY = "apiKey"
        const val KEY_CHAT_MODEL = "chatModel"
        const val KEY_ASR_MODEL = "asrModel"
        const val KEY_MEMORY = "memoryEnabled"
        const val DEFAULT_ASR_MODEL = "whisper-1"
    }
}
