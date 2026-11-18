package tw.notivault.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.notivault.data.AppSettings
import tw.notivault.data.DayCount
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class RetentionSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsExpiredTotalsAndBackupStateAndAllowsManualCleanup() {
        val today = LocalDate.of(2026, 11, 18)
        val counts = listOf(
            DayCount(today.minusDays(8).toString(), 2),
            DayCount(today.minusDays(7).toString(), 3),
            DayCount(today.minusDays(6).toString(), 4),
        )
        val settings = mutableStateOf(AppSettings())
        val connected = mutableStateOf(false)
        val syncing = mutableStateOf(false)
        val queued = mutableStateOf(false)
        var requested = 0
        compose.setContent {
            VaultTheme("dark") {
                RetentionSettingsCard(
                    settings = settings.value,
                    counts = counts,
                    today = today,
                    networkAllowed = connected.value,
                    syncing = syncing.value,
                    queued = queued.value,
                    busy = syncing.value,
                    onKeepDaysChange = {},
                    onBackup = { requested++ },
                )
            }
        }

        compose.onNodeWithText("到期待清理 2 天 · 5 筆").assertExists()
        compose.onNodeWithText("請先在「WebDAV」儲存備份設定").assertExists()
        val manual = compose.onNodeWithText("立即備份並清理")
        manual.assertIsNotEnabled()

        compose.runOnIdle {
            settings.value = settings.value.copy(
                serverUrl = "https://example.invalid",
                automaticBackup = false,
                backupStatus = "部分備份失敗：HTTP 401",
            )
        }
        compose.onNodeWithText("等待 Wi-Fi").assertExists()
        compose.onNodeWithText("部分備份失敗：HTTP 401").assertExists()

        compose.runOnIdle { connected.value = true }
        compose.onNodeWithText("自動備份已關閉，可手動執行").assertExists()
        manual.performClick()
        compose.runOnIdle {
            assertEquals(1, requested)
            queued.value = true
        }
        compose.onNodeWithText("已排程，等待系統執行").assertExists()

        compose.runOnIdle { syncing.value = true }
        compose.onNodeWithText("正在備份及驗證遠端內容").assertExists()
        manual.assertIsNotEnabled()

        compose.runOnIdle { settings.value = settings.value.copy(keepDays = 0) }
        compose.onNodeWithText("已停用到期清理").assertExists()
        compose.onNodeWithText("到期待清理 2 天 · 5 筆").assertDoesNotExist()
    }
}
