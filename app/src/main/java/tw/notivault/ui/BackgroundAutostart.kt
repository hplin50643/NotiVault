package tw.notivault.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build

internal const val BACKGROUND_AUTOSTART_INSTRUCTIONS =
    "請在「設定 → 應用程式 → 背景自動啟動」開啟「通知典藏」。部分版本需先進入「權限」。"

internal fun isXiaomiOrPocoDevice(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any { name ->
    name.equals("Xiaomi", ignoreCase = true) ||
        name.equals("POCO", ignoreCase = true) ||
        name.equals("Redmi", ignoreCase = true)
}

internal fun openBackgroundAutostartSettings(context: Context): Boolean {
    // Xiaomi's documented intent delegates the setting to the user in the system Security app.
    val intent = Intent("miui.intent.action.OP_AUTO_START")
        .addCategory(Intent.CATEGORY_DEFAULT)
        .setPackage("com.miui.securitycenter")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
