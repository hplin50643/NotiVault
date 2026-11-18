package tw.notivault.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.notivault.data.AppChoice
import tw.notivault.data.AppSettings
import java.text.Collator

@Suppress("DEPRECATION")
internal suspend fun installedNotificationApps(context: Context): List<AppChoice> = withContext(Dispatchers.IO) {
    val packages = context.packageManager
    val installed = if (Build.VERSION.SDK_INT >= 33) {
        packages.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        packages.getInstalledApplications(0)
    }
    installed.map { app ->
        val label = packages.getApplicationLabel(app).toString().ifBlank { app.packageName }
        AppChoice(app.packageName, label)
    }
}

private val packageSelectionSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

@Composable
internal fun NotificationAppPicker(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (Boolean, Set<String>) -> Unit,
    loadApps: suspend () -> List<AppChoice>,
) {
    var recordAll by rememberSaveable { mutableStateOf(settings.recordAllApps) }
    var selection by rememberSaveable(stateSaver = packageSelectionSaver) { mutableStateOf(settings.includedPackages) }
    var search by rememberSaveable { mutableStateOf("") }
    var installed by remember { mutableStateOf(emptyList<AppChoice>()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        loading = true
        loadFailed = false
        try {
            installed = loadApps()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }
    val installedNames = remember(installed) { installed.mapTo(mutableSetOf()) { it.packageName } }
    val apps = remember(installed, settings.includedPackages) {
        val savedApps = (settings.includedPackages - installedNames).map { AppChoice(it, it) }
        (installed + savedApps).sortedWith(compareBy(Collator.getInstance()) { app: AppChoice -> app.appLabel }
            .thenBy { it.packageName })
    }
    val matchingApps = remember(apps, search) {
        val query = search.trim()
        apps.filter { it.appLabel.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.safeDrawingPadding().padding(16.dp)
                .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 600.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = !recordAll)) {
                    item { Text("記錄通知的 App", style = MaterialTheme.typography.headlineSmall) }
                    item {
                        Column(Modifier.selectableGroup()) {
                            RecordingMode("全部記錄", recordAll) { recordAll = true }
                            RecordingMode("只記錄勾選的 App", !recordAll) { recordAll = false }
                        }
                    }
                    if (!recordAll) {
                        item {
                            OutlinedTextField(
                                value = search,
                                onValueChange = { search = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("搜尋 App 或套件名稱") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            )
                        }
                        item {
                            Text(
                                if (selection.isEmpty()) "未勾選 App，儲存後將暫停記錄" else "已勾選 ${selection.size} 個 App",
                                Modifier.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (loadFailed) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("無法讀取已安裝的 App", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                    TextButton({ refresh++ }) { Text("重試") }
                                }
                            }
                        }
                        when {
                            loading -> item {
                                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                                }
                            }
                            matchingApps.isEmpty() && !loadFailed -> item {
                                Text("沒有符合搜尋條件的 App", Modifier.padding(vertical = 12.dp))
                            }
                            else -> items(matchingApps, key = { it.packageName }) { app ->
                                val subtitle = when {
                                    app.packageName !in installedNames -> "已儲存的套件"
                                    app.appLabel != app.packageName -> app.packageName
                                    else -> null
                                }
                                RecordingApp(app.appLabel, subtitle, app.packageName in selection) { checked ->
                                    selection = if (checked) {
                                        selection + app.packageName
                                    } else {
                                        selection - app.packageName
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("取消") }
                    TextButton({ onSave(recordAll, selection) }, enabled = recordAll || !loading) { Text("儲存") }
                }
            }
        }
    }
}

@Composable
private fun RecordingApp(label: String, subtitle: String?, selected: Boolean, onSelectionChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .toggleable(selected, role = Role.Checkbox, onValueChange = onSelectionChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecordingMode(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null)
        Text(label)
    }
}
