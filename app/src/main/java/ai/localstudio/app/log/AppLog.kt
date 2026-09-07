package ai.localstudio.app.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-disk log of errors the app has hit, entirely separate from
 * logcat — which a phone's owner has no ordinary way to read or copy off a
 * device. The whole point of this is a report a user CAN get out: open
 * Settings → "Журнал ошибок", tap "Скопировать", paste it wherever it needs
 * to go for someone else to actually diagnose it.
 *
 * Deliberately flat, human-readable text rather than structured data — this
 * is read by a person, not parsed by code.
 */
class AppLog(private val context: Context) {

    private val file = File(context.filesDir, "app-log.txt")
    private val prefs = context.getSharedPreferences("app-log", Context.MODE_PRIVATE)
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun record(tag: String, message: String) {
        runCatching {
            file.appendText("[${format.format(Date())}] $tag: $message\n")
            trimIfTooLarge()
        }
    }

    fun readAll(): String = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()

    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * The one entry point in this class that can see a *native* crash after
     * the fact — a segfault in llama.cpp kills the process outright, with no
     * chance for any of our own Kotlin code to run and write anything at
     * that moment. [ActivityManager.getHistoricalProcessExitReasons]
     * (API 30+) is Android's own record of why the previous process
     * instance actually died, queried fresh on the next cold start — this is
     * what turns "sent a message, got nothing back, no error, no idea why"
     * into a log line naming the real cause.
     */
    fun recordProcessExitIfNotable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val last = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return

        // Android keeps this history around across many launches — without
        // remembering which one was already reported, every single cold
        // start would re-log the same old crash forever.
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        if (last.timestamp <= lastSeen) return
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, last.timestamp).apply()

        val reason = describeExitReason(last.reason) ?: return // ordinary exits aren't worth logging
        val description = last.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        record("PROCESS_EXIT", "Предыдущий запуск завершился: $reason$description")
    }

    private fun describeExitReason(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "нативный крэш (например, сегфолт в llama.cpp)"
        ApplicationExitInfo.REASON_CRASH -> "необработанное исключение в приложении"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "процесс убит системой из-за нехватки памяти (OOM)"
        ApplicationExitInfo.REASON_ANR -> "приложение не отвечало (ANR)"
        else -> null
    }

    private fun trimIfTooLarge() {
        if (file.length() <= MAX_BYTES) return
        val lines = file.readLines().takeLast(MAX_LINES)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val KEY_LAST_EXIT_TIMESTAMP = "lastExitTimestamp"

        // ~200KB / 1000 lines is generous for a human-readable error log and
        // still small enough that "copy" and "paste into a chat" stay fast.
        const val MAX_BYTES = 200_000L
        const val MAX_LINES = 1_000
    }
}
