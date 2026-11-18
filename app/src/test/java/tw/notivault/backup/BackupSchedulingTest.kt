package tw.notivault.backup

import android.app.Application
import android.net.NetworkCapabilities
import androidx.work.NetworkType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.notivault.data.AppSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class BackupSchedulingTest {
    private val configured = AppSettings(serverUrl = "https://dav.example/")

    @Test fun shorteningRetentionOrSwitchingFromUnlimitedRequestsCatchUp() {
        assertTrue(shouldRequestRetentionCatchUp(configured, configured.copy(keepDays = 3)))
        assertTrue(shouldRequestRetentionCatchUp(configured.copy(keepDays = 0), configured))
        assertFalse(shouldRequestRetentionCatchUp(configured, configured.copy(keepDays = 14)))
        assertFalse(shouldRequestRetentionCatchUp(configured, configured.copy(keepDays = 0)))
    }

    @Test fun enablingBackupOrChangingItsDestinationOrNetworkPolicyRequestsCatchUp() {
        assertTrue(shouldRequestRetentionCatchUp(configured.copy(serverUrl = ""), configured))
        assertTrue(shouldRequestRetentionCatchUp(configured.copy(automaticBackup = false), configured))
        assertTrue(shouldRequestRetentionCatchUp(configured, configured.copy(remoteDirectory = "different")))
        assertTrue(shouldRequestRetentionCatchUp(configured, configured.copy(allowMobile = true)))
    }

    @Test fun disabledOrUnconfiguredBackupAndUnrelatedChangesDoNotRequestCatchUp() {
        val disabled = configured.copy(automaticBackup = false)
        val unconfigured = configured.copy(serverUrl = "")
        assertFalse(shouldRequestRetentionCatchUp(disabled, disabled.copy(keepDays = 3)))
        assertFalse(shouldRequestRetentionCatchUp(unconfigured, unconfigured.copy(keepDays = 3)))
        assertFalse(shouldRequestRetentionCatchUp(configured, configured.copy(theme = "light", backupStatus = "驗證中")))
    }

    @Test fun workManagerWaitsForWifiUnlessMobileIsAllowed() {
        val wifi = requireNotNull(backupConstraints(allowMobile = false).requiredNetworkRequest)
        assertTrue(wifi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(wifi.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(wifi.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))

        val mobile = backupConstraints(allowMobile = true)
        assertNull(mobile.requiredNetworkRequest)
        assertEquals(NetworkType.CONNECTED, mobile.requiredNetworkType)
    }

    @Test fun workerWaitsForTheDefaultRouteDuringWifiHandoff() = runTest {
        var defaultRouteAllowed = false
        launch {
            delay(1_200L)
            defaultRouteAllowed = true
        }
        assertTrue(awaitBackupNetwork { defaultRouteAllowed })
        assertFalse(awaitBackupNetwork { false })
    }
}
