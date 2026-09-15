package ai.localstudio.app.benchmark

import ai.localstudio.app.AppContainer
import ai.localstudio.app.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps a benchmark run alive when the screen turns off or the user
 * switches away from the app — same reasoning and same shape as
 * [ai.localstudio.app.whisper.FileTranscriptionService]. [BenchmarkOrchestrator]
 * already runs its work in an application-scoped coroutine independent of
 * any Activity, so it survives navigation and configuration changes on its
 * own; what it cannot survive is the OS killing the whole process, routine
 * the moment a phone with no foreground service goes into the background —
 * a real device report showed exactly this: a run sitting on the biggest
 * installed model (3.1GB) simply vanished, mid-run, after the screen was
 * locked for a few minutes, with no error and nothing saved.
 */
class BenchmarkService : Service() {

    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification())

        AppContainer.get(this).benchmarkOrchestrator.state
            .onEach { state ->
                if (state !is BenchmarkUiState.Running) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
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
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.benchmark_notification_title))
            .setContentText(getString(R.string.benchmark_notification_running))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.benchmark_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "benchmark"
        private const val NOTIFICATION_ID = 4273

        /** Called once per run start; a running service ignores a second start. */
        fun ensureStarted(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BenchmarkService::class.java))
        }
    }
}
