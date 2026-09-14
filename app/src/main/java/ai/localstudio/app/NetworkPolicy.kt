package ai.localstudio.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.appcompat.app.AlertDialog

/**
 * One shared download policy for every download engine this app has — chat
 * models, Whisper, and the embedding model — see [Settings.DownloadPolicy]'s
 * own doc comment for why this is one setting rather than three.
 *
 * [autoDownloadAllowed]/[needsConfirmation] take [isUnmetered] as a plain
 * `Boolean` rather than a [Context] so the actual decision logic is testable
 * without a real device — [isUnmetered] itself is the one function here
 * that needs Android at all.
 */
object NetworkPolicy {

    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Whether a background task may start a download entirely on its own —
     * today, that is exactly [AppContainer]'s own automatic E5_BASE fetch.
     * [Settings.DownloadPolicy.ASK_EVERY_TIME] means exactly what it says:
     * there is nobody to ask from a background coroutine with no UI, so it
     * never auto-starts under that policy — the "asking" happens the next
     * time a person taps Download by hand instead.
     */
    fun autoDownloadAllowed(policy: Settings.DownloadPolicy, isUnmetered: Boolean): Boolean = when (policy) {
        Settings.DownloadPolicy.WIFI_ONLY -> isUnmetered
        Settings.DownloadPolicy.WIFI_AND_MOBILE -> true
        Settings.DownloadPolicy.ASK_EVERY_TIME -> false
    }

    /** Whether a manual Download tap should be interrupted with a confirmation first, rather than starting immediately. */
    fun needsConfirmation(policy: Settings.DownloadPolicy, isUnmetered: Boolean): Boolean = when (policy) {
        Settings.DownloadPolicy.WIFI_ONLY -> !isUnmetered
        Settings.DownloadPolicy.WIFI_AND_MOBILE -> false
        Settings.DownloadPolicy.ASK_EVERY_TIME -> true
    }

    /**
     * The one call every manual Download button in the app goes through:
     * runs [onProceed] immediately when the current policy and network
     * don't call for asking, otherwise shows one confirmation dialog first.
     * [context] must be an [android.app.Activity] — a dialog attached to
     * anything else can throw at [AlertDialog.show] time.
     */
    fun confirmIfNeeded(context: Context, settings: Settings, onProceed: () -> Unit) {
        val policy = settings.downloadPolicy
        val unmetered = isUnmetered(context)
        if (!needsConfirmation(policy, unmetered)) {
            onProceed()
            return
        }
        val messageRes = if (policy == Settings.DownloadPolicy.WIFI_ONLY && !unmetered) {
            R.string.download_confirm_message_metered
        } else {
            R.string.download_confirm_message_ask
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.download_confirm_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.model_download) { _, _ -> onProceed() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
