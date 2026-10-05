package ai.localstudio.app

import ai.localstudio.model.install.CheckStatus
import ai.localstudio.model.install.DeviceVerification
import ai.localstudio.model.install.VerificationContext
import ai.localstudio.model.install.VerifiedCapability
import android.content.Context
import java.util.Locale

/** How a device check reads to a person -- one line for a list, the whole record for details. */
object VerificationText {

    /** "Chat: works · Translation: works · Images: did not pass · 12.3 tok/s"; with why it is out of date when it is. */
    fun summary(context: Context, v: DeviceVerification, now: VerificationContext): String = buildString {
        if (!v.loaded) {
            append(context.getString(R.string.candidate_check_fail))
            v.error?.let { append(" — ").append(it.take(160)) }
            return@buildString
        }
        append(
            VerifiedCapability.ALL.filter { it in v.checks }.joinToString(" · ") { cap ->
                val status = when (v.status(cap, now)) {
                    CheckStatus.PASS -> context.getString(R.string.candidate_check_pass)
                    CheckStatus.FAIL -> context.getString(R.string.candidate_check_fail)
                    CheckStatus.NOT_TESTED -> context.getString(R.string.candidate_check_not_tested)
                    CheckStatus.STALE -> context.getString(R.string.candidate_check_stale_short)
                }
                "${capability(context, cap)}: $status"
            },
        )
        v.tokensPerSecond?.let { append(String.format(Locale.ROOT, " · %.1f tok/s", it)) }
        v.staleReason(now)?.let { append("\n").append(context.getString(R.string.candidate_stale_reason, it)) }
    }

    /** Every question of every capability, in order: passed or not, what was said, how fast; then the record's own facts. */
    fun details(context: Context, v: DeviceVerification, now: VerificationContext): String = buildString {
        append(summary(context, v, now))
        append("\n\n").append(v.deviceProfile)
        append("\n").append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(v.verifiedAtEpochMs)))
        v.artifact?.let { a ->
            append("\n").append(if (a.isPinned) "${a.repository}@${a.revision.take(12)} ${a.mainFile}" + (a.projectorFile?.let { " + $it" } ?: "") else context.getString(R.string.model_check_unpinned))
        }
        v.runtimeVersion?.let { append("\n").append(it) }
        v.checks.forEach { (cap, check) ->
            append("\n\n").append(capability(context, cap)).append(": ").append(check.status.name)
            check.failureKind?.let { append(" (").append(it.name).append(")") }
            check.detail?.let { append("\n").append(it) }
            check.steps.forEach { step ->
                append("\n").append(if (step.passed) "✓ " else "✗ ").append(step.title)
                step.answer?.let { append(" — «").append(it).append("»") }
                step.error?.let { append(" — ").append(it) }
                val timing = listOfNotNull(
                    step.firstTokenMs?.let { String.format(Locale.ROOT, "%.1f s", it / 1000.0) },
                    step.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f tok/s", it) },
                )
                if (timing.isNotEmpty()) append(" [").append(timing.joinToString(", ")).append("]")
            }
            if (check.steps.isEmpty()) check.sample?.let { append("\n").append(context.getString(R.string.candidate_check_said, it)) }
        }
        if (v.checks.isEmpty()) v.error?.let { append("\n\n").append(it) }
    }

    private fun capability(context: Context, cap: String) = context.getString(
        when (cap) {
            VerifiedCapability.TRANSLATION -> R.string.candidate_cap_translation
            VerifiedCapability.VISION -> R.string.candidate_cap_vision
            else -> R.string.candidate_cap_text
        },
    )
}
