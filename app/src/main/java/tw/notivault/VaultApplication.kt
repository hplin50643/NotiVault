package tw.notivault

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.Configuration
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import tw.notivault.backup.BackupCoordinator
import tw.notivault.backup.shouldRequestRetentionCatchUp
import tw.notivault.capture.*
import tw.notivault.data.*

class VaultApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()
    val graph: AppGraph by lazy { AppGraph(this) }
    override fun onCreate() { super.onCreate(); graph }
}

class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsStore(context)
    val database = Room.databaseBuilder(context, VaultDatabase::class.java, "vault.db")
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                val mode = db.query("PRAGMA auto_vacuum").use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                if (mode == 0) { db.execSQL("PRAGMA auto_vacuum=INCREMENTAL"); db.execSQL("VACUUM") }
            }
        }).build()
    val repository = ArchiveRepository(context, database, settings, scope)
    val backup = BackupCoordinator(context, settings, repository, scope)
    val listenerConnected = MutableStateFlow(false)
    val captureQueue = Channel<CaptureEvent>(Channel.UNLIMITED)
    init {
        scope.launch {
            settings.initialize()
            var previous: AppSettings? = null
            settings.flow.distinctUntilChangedBy {
                listOf(it.schedulingKey(), it.destinationId(), it.keepDays, it.zoneId)
            }.collect { current ->
                val prior = previous
                previous = current
                try {
                    if (prior?.schedulingKey() != current.schedulingKey()) backup.schedule(current)
                    if (prior != null) {
                        if (prior.allowMobile != current.allowMobile) backup.refreshPendingManualNetwork()
                        if (shouldRequestRetentionCatchUp(prior, current) || (prior.keepDays != 0 && current.keepDays == 0)) {
                            backup.requestRetentionCatchUp(replacePending = true)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    repository.error.value = "備份排程失敗：${failure.message}"
                }
            }
        }
        scope.launch {
            for (event in captureQueue) {
                try {
                    when (event) {
                        is CaptureEvent.Posted -> {
                            val packageName = event.sbn.packageName
                            if (!settings.getCaptureSettings().recordsPackage(packageName)) continue
                            val capture = NotificationSerializer(context, packageName).capture(event.sbn, event.ranking, event.receivedAt)
                            repository.capture(capture)
                        }
                        is CaptureEvent.Removed -> repository.removed(event.key)
                        is CaptureEvent.Reconcile -> repository.reconcileActive(event.keys)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { repository.error.value = "通知保存失敗：${failure.message}" }
            }
        }
    }
}
