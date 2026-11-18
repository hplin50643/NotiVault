package tw.notivault.capture

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationListenerService
import tw.notivault.VaultApplication
import tw.notivault.core.ArchiveRules
import tw.notivault.data.SettingsStore
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = VaultApplication::class)
class NotificationCapturePolicyTest {
    @Test fun previousVersionSettingsDefaultToAllAndSelectionPersistsAcrossStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<VaultApplication>()
        val directory = Files.createTempDirectory("capture-preferences").toFile()
        fun open(scope: CoroutineScope) = PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "settings.preferences_pb")
        }
        try {
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val preferences = open(firstScope)
                preferences.edit {
                    it[stringPreferencesKey("settings_v1")] =
                        """{"installationId":"previous-install","keepDays":14,"theme":"light"}"""
                }
                val settings = SettingsStore(context, preferences)
                assertTrue(settings.get().recordsPackage("newly.installed"))
                settings.update { it.copy(recordAllApps = false, includedPackages = setOf("allowed.chat")) }
                assertFalse(settings.get().recordsPackage("newly.installed"))
                settings.update { it.copy(recordAllApps = true) }
            } finally {
                firstScope.coroutineContext[Job]!!.cancelAndJoin()
            }
            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val settings = SettingsStore(context, open(reopenedScope))
                val reopened = settings.get()
                assertEquals("previous-install", reopened.installationId)
                assertEquals(14, reopened.keepDays)
                assertEquals("light", reopened.theme)
                assertEquals(setOf("allowed.chat"), reopened.includedPackages)
                assertTrue(reopened.recordsPackage("newly.installed"))
                settings.update { it.copy(recordAllApps = false) }
                assertTrue(settings.get().recordsPackage("allowed.chat"))
                assertFalse(settings.get().recordsPackage("newly.installed"))
            } finally {
                reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun livePostsAndReconnectReplayHonorSelectionAndEmptySelectionRejectsPendingWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<VaultApplication>()
        val graph = context.graph
        val timestamp = System.currentTimeMillis()
        val date = ArchiveRules.date(timestamp, "Asia/Taipei").toString()
        fun notification(packageName: String, id: Int, title: String): StatusBarNotification {
            val payload = Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).build()
            return StatusBarNotification(packageName, packageName, id, null, Process.myUid(), Process.myPid(), 0,
                payload, Process.myUserHandle(), timestamp)
        }
        val allowed = "allowed.chat"
        val blocked = "blocked.chat"
        val service = Robolectric.buildService(VaultNotificationListener::class.java).create()
        val listener = service.get()
        try {
            graph.settings.update { it.copy(recordAllApps = false, includedPackages = setOf(allowed)) }
            listener.onNotificationPosted(notification(blocked, 1, "已封鎖的即時通知"), null)
            listener.onNotificationPosted(notification(allowed, 2, "允許的即時通知"), null)
            val liveRecords = withTimeout(10_000) {
                var records = graph.repository.dao.records(date)
                while (records.none { it.title == "允許的即時通知" }) {
                    delay(10)
                    records = graph.repository.dao.records(date)
                }
                records
            }
            assertEquals(listOf(allowed), liveRecords.map { it.packageName })

            val shadow = Shadow.extract<ShadowNotificationListenerService>(listener)
            shadow.addActiveNotification(notification(blocked, 3, "已封鎖的重連通知"))
            shadow.addActiveNotification(notification(allowed, 4, "允許的重連通知"))
            listener.onListenerConnected()
            listener.onNotificationPosted(notification(allowed, 5, "重連後的即時通知"), null)
            val replayRecords = withTimeout(10_000) {
                var records = graph.repository.dao.records(date)
                while (records.none { it.title == "重連後的即時通知" }) {
                    delay(10)
                    records = graph.repository.dao.records(date)
                }
                records
            }
            assertEquals(setOf("允許的即時通知", "允許的重連通知", "重連後的即時通知"), replayRecords.map { it.title }.toSet())

            val pending = NotificationSerializer(context, allowed).capture(notification(allowed, 6, "待寫入"), null, timestamp)
            graph.settings.update { it.copy(includedPackages = emptySet()) }
            graph.repository.capture(pending)
            assertEquals(replayRecords, graph.repository.dao.records(date))
        } finally {
            service.destroy()
        }
        Unit
    }
}
