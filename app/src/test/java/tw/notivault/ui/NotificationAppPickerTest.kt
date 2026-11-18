package tw.notivault.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.notivault.data.AppChoice
import tw.notivault.data.AppSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, qualifiers = "zh-rTW-w411dp-h891dp-xhdpi")
class NotificationAppPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchDistinguishesSameNameAppsAndSavedUninstalledPackageCanBeRemoved() {
        var saved: Pair<Boolean, Set<String>>? = null
        compose.setContent {
            VaultTheme("dark") {
                NotificationAppPicker(AppSettings(recordAllApps = false, includedPackages = setOf("previous.chat")),
                    onDismiss = {}, onSave = { all, packages -> saved = all to packages },
                    loadApps = { listOf(AppChoice("first.chat", "同名聊天"), AppChoice("second.chat", "同名聊天")) })
            }
        }
        compose.onNodeWithText("搜尋 App 或套件名稱").performTextInput("previous.chat")
        compose.onNode(hasText("previous.chat") and isToggleable()).assertIsOn().performClick()
        compose.onNodeWithText("未勾選 App，儲存後將暫停記錄").assertExists()
        compose.onNodeWithText("搜尋 App 或套件名稱").performTextReplacement("同名聊天")
        compose.onNodeWithText("first.chat").assertIsOff()
        compose.onNodeWithText("second.chat").assertIsOff()
        compose.onNodeWithText("搜尋 App 或套件名稱").performTextReplacement("SECOND.CHAT")
        compose.onNodeWithText("first.chat").assertDoesNotExist()
        compose.onNode(hasText("second.chat") and isToggleable()).performClick()
        compose.onNodeWithText("全部記錄").performClick()
        compose.onNodeWithText("儲存").performClick()
        assertEquals(true to setOf("second.chat"), saved)
    }

    @Test fun cancellingDraftLeavesOriginalSelectionForNextOpen() {
        val open = mutableStateOf(true)
        var saved: Pair<Boolean, Set<String>>? = null
        compose.setContent {
            VaultTheme("dark") {
                if (open.value) {
                    NotificationAppPicker(AppSettings(recordAllApps = true, includedPackages = setOf("first.chat")),
                        onDismiss = { open.value = false }, onSave = { all, packages -> saved = all to packages },
                        loadApps = { listOf(AppChoice("first.chat", "聊天")) })
                }
            }
        }
        compose.onNodeWithText("只記錄勾選的 App").performClick()
        compose.onNodeWithText("first.chat").assertIsOn().performClick()
        compose.onNodeWithText("取消").performClick()
        assertNull(saved)
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText("全部記錄").assertIsSelected()
        compose.onNodeWithText("只記錄勾選的 App").performClick()
        compose.onNodeWithText("first.chat").assertIsOn()
    }
}
