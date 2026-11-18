package tw.notivault.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import tw.notivault.VaultViewModel
import tw.notivault.backup.NetworkGate
import tw.notivault.core.ArchiveRules
import tw.notivault.data.AppSettings
import tw.notivault.data.DayCount
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
internal fun RetentionSettings(
    model: VaultViewModel,
    settings: AppSettings,
    counts: List<DayCount>,
    onKeepDaysChange: (String) -> Unit,
) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val syncing by model.syncInProgress.collectAsStateWithLifecycle()
    val queued by model.backupQueued.collectAsStateWithLifecycle()
    val zone = remember(settings.zoneId) { ZoneId.of(settings.zoneId) }
    val date by currentArchiveDate(zone)
    val networkAllowed by backupNetworkAllowed(settings.configured, settings.allowMobile)

    RetentionSettingsCard(
        settings = settings,
        counts = counts,
        today = date,
        networkAllowed = networkAllowed,
        syncing = syncing,
        queued = queued,
        busy = busy,
        onKeepDaysChange = onKeepDaysChange,
        onBackup = model::backupNow,
    )
}

@Composable
internal fun RetentionSettingsCard(
    settings: AppSettings,
    counts: List<DayCount>,
    today: LocalDate,
    networkAllowed: Boolean,
    syncing: Boolean,
    queued: Boolean,
    busy: Boolean,
    onKeepDaysChange: (String) -> Unit,
    onBackup: () -> Unit,
) {
    val expired = remember(counts, today, settings.keepDays) {
        if (settings.keepDays == 0) emptyList()
        else counts.filter { ArchiveRules.expired(LocalDate.parse(it.date), today, settings.keepDays) }
    }
    val reason = when {
        settings.keepDays == 0 -> "已停用到期清理"
        expired.isEmpty() -> "目前沒有到期通知"
        !settings.configured -> "請先在「WebDAV」儲存備份設定"
        syncing -> "正在備份及驗證遠端內容"
        !networkAllowed -> if (settings.allowMobile) "等待可用網路" else "等待 Wi-Fi"
        queued -> "已排程，等待系統執行"
        !settings.automaticBackup -> "自動備份已關閉，可手動執行"
        else -> "等待自動備份"
    }

    VaultCard {
        SectionTitle("本機保留", "依日曆日計算，遠端驗證成功後清理")
        ChoiceMenu(
            label = "保存天數",
            value = if (settings.keepDays == 0) "不限" else "${settings.keepDays} 天",
            choices = listOf("7" to "7 天", "14" to "14 天", "30" to "30 天", "0" to "不限天數", "custom" to "自訂天數"),
            onSelect = onKeepDaysChange,
        )
        if (settings.keepDays > 0) {
            Text("到期待清理 ${number(expired.size.toLong())} 天 · ${number(expired.sumOf { it.count })} 筆")
        }
        Text(reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expired.isNotEmpty() && settings.configured && settings.backupStatus.isNotBlank()) {
            Text(settings.backupStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(
            onClick = onBackup,
            modifier = Modifier.fillMaxWidth(),
            enabled = settings.configured && settings.keepDays > 0 && !busy,
        ) {
            Text("立即備份並清理")
        }
    }
}

@Composable
private fun currentArchiveDate(zone: ZoneId): State<LocalDate> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDate.now(zone), zone, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = ZonedDateTime.now(zone)
                val today = now.toLocalDate()
                value = today
                val nextDay = today.plusDays(1).atStartOfDay(zone)
                delay(Duration.between(now, nextDay).toMillis() + 1)
            }
        }
    }
}

@Composable
private fun backupNetworkAllowed(configured: Boolean, allowMobile: Boolean): State<Boolean> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initiallyAllowed = remember(context, configured, allowMobile) {
        configured && NetworkGate.allowed(context, allowMobile)
    }
    return produceState(initiallyAllowed, context, lifecycle, configured, allowMobile) {
        if (!configured) {
            value = false
            return@produceState
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val changes = Channel<Unit>(Channel.CONFLATED)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { changes.trySend(Unit) }
                override fun onLost(network: Network) { changes.trySend(Unit) }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { changes.trySend(Unit) }
            }
            manager.registerDefaultNetworkCallback(callback)
            try {
                value = NetworkGate.allowed(context, allowMobile)
                changes.receiveAsFlow().collect { value = NetworkGate.allowed(context, allowMobile) }
            } finally {
                manager.unregisterNetworkCallback(callback)
                changes.cancel()
            }
        }
    }
}
