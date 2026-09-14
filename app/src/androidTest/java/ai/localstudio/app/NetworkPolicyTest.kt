package ai.localstudio.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [NetworkPolicy.autoDownloadAllowed]/[NetworkPolicy.needsConfirmation] take
 * a plain `Boolean` for "is the network unmetered" rather than a
 * [android.content.Context], specifically so this decision logic — the part
 * that actually encodes what each [Settings.DownloadPolicy] means — is
 * testable without a real device's [android.net.ConnectivityManager].
 */
@RunWith(AndroidJUnit4::class)
class NetworkPolicyTest {

    @Test
    fun wifi_only_allows_auto_download_only_when_unmetered() {
        assertTrue(NetworkPolicy.autoDownloadAllowed(Settings.DownloadPolicy.WIFI_ONLY, isUnmetered = true))
        assertFalse(NetworkPolicy.autoDownloadAllowed(Settings.DownloadPolicy.WIFI_ONLY, isUnmetered = false))
    }

    @Test
    fun wifi_and_mobile_always_allows_auto_download() {
        assertTrue(NetworkPolicy.autoDownloadAllowed(Settings.DownloadPolicy.WIFI_AND_MOBILE, isUnmetered = true))
        assertTrue(NetworkPolicy.autoDownloadAllowed(Settings.DownloadPolicy.WIFI_AND_MOBILE, isUnmetered = false))
    }

    @Test
    fun ask_every_time_never_auto_downloads_regardless_of_network() {
        // There is nobody to ask from a background coroutine — see the
        // function's own doc comment for why this is "never", not "ask".
        assertFalse(NetworkPolicy.autoDownloadAllowed(Settings.DownloadPolicy.ASK_EVERY_TIME, isUnmetered = true))
        assertFalse(NetworkPolicy.autoDownloadAllowed(Settings.DownloadPolicy.ASK_EVERY_TIME, isUnmetered = false))
    }

    @Test
    fun wifi_only_needs_confirmation_only_on_metered_networks() {
        assertFalse(NetworkPolicy.needsConfirmation(Settings.DownloadPolicy.WIFI_ONLY, isUnmetered = true))
        assertTrue(NetworkPolicy.needsConfirmation(Settings.DownloadPolicy.WIFI_ONLY, isUnmetered = false))
    }

    @Test
    fun wifi_and_mobile_never_needs_confirmation() {
        assertFalse(NetworkPolicy.needsConfirmation(Settings.DownloadPolicy.WIFI_AND_MOBILE, isUnmetered = true))
        assertFalse(NetworkPolicy.needsConfirmation(Settings.DownloadPolicy.WIFI_AND_MOBILE, isUnmetered = false))
    }

    @Test
    fun ask_every_time_always_needs_confirmation_regardless_of_network() {
        assertTrue(NetworkPolicy.needsConfirmation(Settings.DownloadPolicy.ASK_EVERY_TIME, isUnmetered = true))
        assertTrue(NetworkPolicy.needsConfirmation(Settings.DownloadPolicy.ASK_EVERY_TIME, isUnmetered = false))
    }
}
