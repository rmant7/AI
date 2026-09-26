package ai.localstudio.app.llama

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a local generation alive when the screen turns off or the user
 * switches away — same reasoning and same shape as
 * [ai.localstudio.app.benchmark.BenchmarkService]. A real device report:
 * chat generation collapsed to 1.8 tok/s and translation never finished at
 * all, both immediately after a `TRIM_MEMORY_BACKGROUND` log line — the
 * process had dropped out of the foreground/visible LRU bucket, and Android
 * throttles CPU hard for a process in that state. [LlamaCppRuntime]'s own
 * worker thread already asks for [android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY],
 * which only orders threads *within* the process — it cannot lift the whole
 * process out of the background bucket the OS scheduler is throttling.
 *
 * [begin]/[end] are reference-counted rather than start/stop-per-call: chat's
 * own turn, a Compare-mode source and a translation can all want this at
 * once, and the service must not stop while any of them is still running
 * just because another one finished first.
 */
class GenerationKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.generation_notification_title))
            .setContentText(getString(R.string.generation_notification_running))
            .setSmallIcon(R.drawable.ic_send)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.generation_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 4274

        private val activeCount = AtomicInteger(0)

        /** Call once per generation that wants to survive backgrounding; pair with [end]. */
        fun begin(context: Context) {
            if (activeCount.getAndIncrement() == 0) {
                ContextCompat.startForegroundService(context, Intent(context, GenerationKeepAliveService::class.java))
            }
        }

        /** Must be called exactly once for every [begin], generation succeeded or not — usually from a `finally`. */
        fun end(context: Context) {
            if (activeCount.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
                context.stopService(Intent(context, GenerationKeepAliveService::class.java))
            }
        }
    }
}
