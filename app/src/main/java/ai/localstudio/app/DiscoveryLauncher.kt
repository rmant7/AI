package ai.localstudio.app

import ai.localstudio.app.models.ModelDownloadService
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** "Find new models": starts a discovery sweep from whichever screen offers it (Models, Discovered candidates). */
object DiscoveryLauncher {
    fun start(activity: AppCompatActivity) {
        val container = AppContainer.get(activity)
        // Logged before anything else, synchronously (AppLog.record writes
        // to disk on the spot) -- a device report (build #455) showed zero
        // DISCOVERY lines and no visible effect at all after tapping this;
        // this line tells "never reached" from "returned early" apart.
        val available = container.modelInstallation.discovery != null
        container.appLog.record("DISCOVERY", "menu item tapped; discovery ${if (available) "available" else "unavailable (hub cannot search)"}")
        when {
            !available -> Toast.makeText(activity, R.string.discover_unavailable, Toast.LENGTH_LONG).show()
            container.discoveryRunning.value -> Toast.makeText(activity, R.string.discover_already_running, Toast.LENGTH_SHORT).show()
            else -> {
                Toast.makeText(activity, R.string.discover_running, Toast.LENGTH_SHORT).show()
                ModelDownloadService.ensureStarted(activity)
                container.startDiscovery()
            }
        }
    }
}
