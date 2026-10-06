package ai.localstudio.app.llama

import android.content.SharedPreferences

/**
 * The little persistence [MeasuredRamStore] needs -- strings by key -- so the
 * engine's RAM bookkeeping is testable on a plain JVM ([InMemoryKeyValueStore])
 * and stored in SharedPreferences on a device ([SharedPreferencesStore]).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(keys: Collection<String>)
    /** Every entry, for scanning by key prefix. */
    fun all(): Map<String, String>
}

class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }
    override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) { values[key] = value }
    override fun remove(keys: Collection<String>) { keys.forEach { values.remove(it) } }
    override fun all(): Map<String, String> = HashMap(values)
}
