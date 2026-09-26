package ai.localstudio.app.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import ai.localstudio.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-disk log of errors the app has hit, entirely separate from
 * logcat — which a phone's owner has no ordinary way to read or copy off a
 * device. The whole point of this is a report a user CAN get out: open the
 * chat's own overflow menu → "Журнал ошибок", tap "Скопировать", paste it
 * wherever it needs to go for someone else to actually diagnose it.
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
        record("PROCESS_EXIT", context.getString(R.string.log_process_exit, reason, description))

        // Documented as populated for REASON_ANR; on some OS versions it also
        // carries a native crash's trace (Android keeps one regardless of
        // reason and doesn't guarantee which reasons get it attached). Worth
        // trying unconditionally on every notable exit rather than only for
        // REASON_ANR — this device has no adb/root, so this stream is the
        // only realistic way a native segfault's actual trace ever reaches a
        // user-copyable log at all. Null or empty on most calls is expected,
        // not a bug.
        //
        // Modern Android tombstones are Protobuf, not plain text — a real
        // device capture confirmed this (readable fragments like the device
        // fingerprint and signal name sat inside otherwise binary noise).
        // Decoding the schema properly would need a protobuf dependency this
        // app has no other use for; [extractPrintableStrings] is the same
        // trick the `strings` command uses instead — every symbol name,
        // library path, thread name, and (crucially) any assertion message a
        // library compiled in as a literal C string survives as a clean
        // readable line, just with the binary offsets/addresses between them
        // dropped.
        //
        // A tombstone dumps *every* thread's state, not just the one that
        // crashed — two real captures showed several KB each of idle ART/
        // system daemon threads (GC, binder, hwui, thread-pool workers, all
        // just parked waiting) ahead of whatever thread was actually running
        // this app's own native code, past even a 150,000-char cutoff.
        // [relevantTraceLines] keeps the signal header plus a window around
        // any line that looks like it belongs to this app's own code path,
        // instead of a blind head-truncation of a dump this large.
        runCatching {
            last.traceInputStream?.use { it.readBytes() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { bytes -> record("PROCESS_EXIT_TRACE", relevantTraceLines(extractPrintableStrings(bytes))) }
        }
    }

    private fun extractPrintableStrings(bytes: ByteArray, minLength: Int = 4): List<String> {
        val out = mutableListOf<String>()
        var runStart = -1
        fun flush(end: Int) {
            if (runStart >= 0 && end - runStart >= minLength) {
                out.add(String(bytes, runStart, end - runStart, Charsets.US_ASCII))
            }
            runStart = -1
        }
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x20..0x7E) {
                if (runStart < 0) runStart = i
            } else {
                flush(i)
            }
        }
        flush(bytes.size)
        return out
    }

    /**
     * Everything this app's own native code touches carries one of these
     * markers somewhere nearby: its own JNI library/function names, the ggml/
     * llama.cpp symbols it links against, an assertion or abort message any
     * of those would emit on failure, or the name Kotlin coroutines gives an
     * IO-dispatcher worker thread (the one [LlamaCppRuntime]'s generation
     * worker actually runs on). A handful of false-positive matches (a path
     * or symbol that merely contains one of these substrings) costs a little
     * extra context around it; missing the one thread actually worth reading
     * costs the whole diagnosis.
     */
    private fun relevantTraceLines(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val markers = listOf(
            "llama", "ggml", "GGML_ASSERT", "assert", "abort",
            "DefaultDispatcher", "nativeGenerate", "nativeLoad", "libllama_jni",
        )
        val keep = sortedSetOf<Int>()
        for (i in 0 until minOf(HEADER_LINES, lines.size)) keep.add(i)
        lines.forEachIndexed { i, line ->
            if (markers.any { line.contains(it, ignoreCase = true) }) {
                for (j in (i - CONTEXT_LINES)..(i + CONTEXT_LINES)) {
                    if (j in lines.indices) keep.add(j)
                }
            }
        }
        // No markers anywhere — still better to hand back *something*
        // readable than nothing, even knowing it likely won't reach the
        // relevant thread.
        if (keep.size <= HEADER_LINES) {
            return lines.joinToString("\n").take(MAX_TRACE_CHARS)
        }
        val out = StringBuilder()
        var prev = -2
        for (i in keep) {
            if (prev != -2 && i != prev + 1) out.append("...\n")
            out.append(lines[i]).append('\n')
            prev = i
        }
        return out.toString().take(MAX_TRACE_CHARS)
    }

    private fun describeExitReason(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> context.getString(R.string.log_exit_reason_crash_native)
        ApplicationExitInfo.REASON_CRASH -> context.getString(R.string.log_exit_reason_crash)
        ApplicationExitInfo.REASON_LOW_MEMORY -> context.getString(R.string.log_exit_reason_low_memory)
        ApplicationExitInfo.REASON_ANR -> context.getString(R.string.log_exit_reason_anr)
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

        // [relevantTraceLines] already filters down to the signal header
        // plus context around anything marker-matched, so this is now just
        // a hard safety cap, not the thing doing the real trimming.
        const val MAX_TRACE_CHARS = 40_000

        // How many of the trace's own first lines (build fingerprint,
        // timestamp, signal name) to always keep regardless of markers.
        const val HEADER_LINES = 12

        // Lines of surrounding context to keep on each side of a marker
        // match — thread name, register dump, and a handful of backtrace
        // frames on either side of wherever the match landed.
        const val CONTEXT_LINES = 40
    }
}
