package ai.localstudio.app.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ai.localstudio.app.AppContainer
import ai.localstudio.app.R
import ai.localstudio.app.whisper.WhisperDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps model downloads alive when the screen turns off or the user switches
 * away from the app.
 *
 * [ModelDownloads] already runs its transfers in an application-scoped
 * coroutine, independent of any Activity — that part survives navigation and
 * configuration changes on its own. What it cannot survive is the OS killing
 * the whole process, which is routine the moment a phone with no foreground
 * service goes into the background: a multi-gigabyte GGUF download stops
 * exactly where the user reported it stopping. A foreground service with an
 * ongoing notification is the standard, and only, fix for that — the process
 * is not eligible for that kind of kill while one is running.
 *
 * The service does not run any download itself; it observes the same state
 * every download screen observes — GGUF chat models and Whisper voice
 * models alike, since both die the same way in the background without
 * this — and exists purely to hold the process open and show progress. It
 * starts itself when a download begins and stops itself the moment none
 * are left running, in either category.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification("Подготовка загрузки…"))

        val container = AppContainer.get(this)
        combine(container.downloads.state, container.whisperDownloads.state) { ggufStates, whisperStates ->
            ggufStates to whisperStates
        }
            .onEach { (ggufStates, whisperStates) ->
                val ggufRunning = ggufStates.values.filterIsInstance<DownloadState.Running>()
                val ggufResolving = ggufStates.values.any { it is DownloadState.Resolving }
                val whisperRunning = whisperStates.values.filterIsInstance<WhisperDownloadState.Running>()
                val activeCount = ggufRunning.size + whisperRunning.size

                if (activeCount == 0 && !ggufResolving) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@onEach
                }

                val parts = buildList {
                    ggufRunning.forEach { add("${(it.progress.fraction * 100).toInt()}%") }
                    whisperRunning.forEach { add("Whisper: ${(it.progress.fraction * 100).toInt()}%") }
                    if (isEmpty() && ggufResolving) add("Поиск файла…")
                }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(parts.joinToString(" · "), activeCount))
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, activeCount: Int = 0): Notification {
        val title = if (activeCount > 1) "Загрузка моделей ($activeCount)" else "Загрузка модели"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Загрузка моделей", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4271

        /** Called once per download start; a running service ignores a second start. */
        fun ensureStarted(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
        }
    }
}
