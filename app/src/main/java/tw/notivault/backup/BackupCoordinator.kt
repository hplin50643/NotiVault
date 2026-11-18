package tw.notivault.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tw.notivault.VaultApplication
import tw.notivault.core.ArchiveRules
import tw.notivault.data.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

fun AppSettings.webDavConfig() = WebDavConfig(serverUrl, remoteDirectory, username, password, allowHttp)

object NetworkGate {
    fun allowed(context: Context, mobileAllowed: Boolean): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (mobileAllowed || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }
}

// WorkManager can start as Wi-Fi connects, before Android moves the default route off mobile.
internal suspend fun awaitBackupNetwork(allowed: () -> Boolean): Boolean = withTimeoutOrNull(10_000L) {
    while (!allowed()) delay(250L)
    true
} ?: false

internal fun backupConstraints(allowMobile: Boolean): Constraints {
    val builder = Constraints.Builder()
    if (allowMobile) return builder.setRequiredNetworkType(NetworkType.CONNECTED).build()
    val wifi = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    return builder.setRequiredNetworkRequest(wifi, NetworkType.CONNECTED).build()
}

internal fun shouldRequestRetentionCatchUp(previous: AppSettings, current: AppSettings): Boolean {
    if (!current.configured || !current.automaticBackup || current.keepDays == 0) return false
    return !previous.configured || !previous.automaticBackup || previous.keepDays == 0 || current.keepDays < previous.keepDays ||
        current.allowMobile != previous.allowMobile || current.destinationId() != previous.destinationId() || current.zoneId != previous.zoneId
}

internal enum class BackupRun(val workName: String) {
    PERIODIC("vault-periodic-backup"),
    MANUAL("vault-manual-backup"),
    RETENTION("vault-retention-catch-up");

    fun input() = workDataOf("backupRun" to workName)
}

class BackupCoordinator(private val context: Context, private val settings: SettingsStore, private val repository: ArchiveRepository, private val scope: CoroutineScope) {
    val busy = MutableStateFlow(false)
    val syncInProgress = MutableStateFlow(false)
    private val mutex = Mutex()
    private val schedulingMutex = Mutex()
    private val workManager get() = WorkManager.getInstance(context)
    private val cache = File(context.cacheDir, "backup").apply { mkdirs() }
    private val access = object : BackupAccess {
        override suspend fun dates() = repository.dao.recordDates()
        override suspend fun revision(date: String) = repository.dao.day(date)?.takeUnless { it.deleting }?.revision
        override suspend fun snapshot(date: String) = repository.snapshot(date)
        override suspend fun receipt(destination: String, date: String) = repository.dao.receipt(destination, date)
        override suspend fun saveReceipt(receipt: BackupReceipt) = repository.dao.saveReceipt(receipt)
        override suspend fun stillCurrent(destination: String) = settings.get().let { it.configured && it.destinationId() == destination && NetworkGate.allowed(context, it.allowMobile) }
        override suspend fun isExpired(date: LocalDate, today: LocalDate, keepDays: Int): Boolean {
            val current = settings.get()
            return ArchiveRules.expired(date, LocalDate.now(ZoneId.of(current.zoneId)), current.keepDays)
        }
        override suspend fun deleteVerified(date: String, revision: Long, destination: String) = repository.deleteVerified(date, revision, destination)
    }

    internal suspend fun sync(run: BackupRun): BackupResult? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val configuration = settings.get()
            if (!configuration.configured || (run != BackupRun.MANUAL && !configuration.automaticBackup)) return@withLock null
            if (run == BackupRun.RETENTION && !repository.hasExpiredRecords(configuration)) return@withLock null
            val destination = configuration.destinationId()
            busy.value = true
            try {
                check(awaitBackupNetwork { NetworkGate.allowed(context, configuration.allowMobile) }) {
                    if (configuration.allowMobile) "等待可用網路" else "等待 Wi-Fi；可在設定中允許行動網路"
                }
                syncInProgress.value = true
                val selection = when (run) {
                    BackupRun.PERIODIC -> BackupSelection.CHANGED_OR_EXPIRED
                    BackupRun.MANUAL -> BackupSelection.ALL
                    BackupRun.RETENTION -> BackupSelection.EXPIRED_ONLY
                }
                val result = BackupEngine(access, cache).sync(
                    WebDavClient(configuration.webDavConfig()),
                    destination,
                    configuration.installationId,
                    LocalDate.now(ZoneId.of(configuration.zoneId)),
                    configuration.keepDays,
                    selection,
                ) { message ->
                    updateStatus(destination) { it.copy(backupStatus = message) }
                }
                updateStatus(destination) { current ->
                    val status = when {
                        result.failures.isNotEmpty() -> "部分備份失敗：${result.failures.take(3).joinToString("；")}"
                        result.verified == 0 -> "目前沒有需要備份或清理的日期"
                        else -> "已驗證 ${result.verified} 天 · 清理 ${result.deleted} 天"
                    }
                    val lastBackupAt = if (result.verified > 0 && result.failures.isEmpty()) {
                        System.currentTimeMillis()
                    } else current.lastBackupAt
                    if (status == current.backupStatus && lastBackupAt == current.lastBackupAt) current
                    else current.copy(backupStatus = status, lastBackupAt = lastBackupAt)
                }
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateStatus(destination) { it.copy(backupStatus = failure.message ?: "備份失敗，將重試") }
                throw failure
            } finally {
                syncInProgress.value = false
                busy.value = false
            }
        }
    }

    private suspend fun updateStatus(destination: String, transform: (AppSettings) -> AppSettings) {
        settings.update { if (it.destinationId() == destination) transform(it) else it }
    }

    suspend fun testConnection(configuration: AppSettings, progress: (String) -> Unit = {}, timing: (DavRequestTiming) -> Unit = {}): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            busy.value = true
            try {
                check(NetworkGate.allowed(context, configuration.allowMobile)) { "目前網路不符合備份設定" }
                withInteractiveDav(configuration.webDavConfig(), timing) { client ->
                    ConnectionProbe(cache).run(client, configuration.installationId, progress) { paths ->
                        scope.launch(Dispatchers.IO) {
                            val cleaner = WebDavClient.cleanup(configuration.webDavConfig())
                            paths.forEach { path -> runCatching { cleaner.deleteTemporary(path) } }
                        }
                    }
                }
            } finally { busy.value = false }
        }
    }

    suspend fun schedule(configuration: AppSettings) = schedulingMutex.withLock {
        if (configuration.configured && configuration.automaticBackup) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(configuration.backupMinutes.toLong(), TimeUnit.MINUTES)
                .setInputData(BackupRun.PERIODIC.input())
                .setConstraints(backupConstraints(configuration.allowMobile))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(BackupRun.PERIODIC.workName, ExistingPeriodicWorkPolicy.UPDATE, request).await()
        } else {
            workManager.cancelUniqueWork(BackupRun.PERIODIC.workName).await()
            workManager.cancelUniqueWork(BackupRun.RETENTION.workName).await()
        }
        Unit
    }

    suspend fun requestRetentionCatchUp(replacePending: Boolean = false) = schedulingMutex.withLock {
        val configuration = settings.get()
        if (!configuration.configured || !configuration.automaticBackup || configuration.keepDays == 0) {
            if (replacePending) workManager.cancelUniqueWork(BackupRun.RETENTION.workName).await()
            return@withLock
        }
        if (repository.hasExpiredRecords(configuration)) {
            val policy = if (replacePending) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            enqueueOnce(BackupRun.RETENTION, configuration, policy)
        }
    }

    suspend fun enqueueManual(): Boolean = schedulingMutex.withLock {
        val configuration = settings.get()
        if (!configuration.configured) return@withLock false
        updateStatus(configuration.destinationId()) { it.copy(backupStatus = "已排程備份，等待符合設定的網路") }
        enqueueOnce(BackupRun.MANUAL, configuration, ExistingWorkPolicy.REPLACE)
        true
    }

    suspend fun refreshPendingManualNetwork() = schedulingMutex.withLock {
        val configuration = settings.get()
        if (configuration.configured && workManager.getWorkInfosForUniqueWorkFlow(BackupRun.MANUAL.workName).first().any { it.state == WorkInfo.State.ENQUEUED }) {
            enqueueOnce(BackupRun.MANUAL, configuration, ExistingWorkPolicy.REPLACE)
        }
    }

    fun observeBackupQueued() = combine(
        workManager.getWorkInfosForUniqueWorkFlow(BackupRun.MANUAL.workName),
        workManager.getWorkInfosForUniqueWorkFlow(BackupRun.RETENTION.workName),
    ) { manual, retention -> manual.any { !it.state.isFinished } || retention.any { !it.state.isFinished } }

    private suspend fun enqueueOnce(run: BackupRun, configuration: AppSettings, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(run.input())
            .setConstraints(backupConstraints(configuration.allowMobile))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        workManager.enqueueUniqueWork(run.workName, policy, request).await()
    }
}

class BackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as VaultApplication).graph
        val run = BackupRun.entries.firstOrNull { it.workName == inputData.getString("backupRun") } ?: BackupRun.MANUAL
        return try {
            val result = graph.backup.sync(run)
            if (result == null || result.failures.isEmpty()) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
