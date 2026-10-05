package ai.localstudio.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The discovery sweep, once a week, on its own: new releases of the watched
 * families (see [ai.localstudio.model.install.Lineages]) show up on the
 * candidates screen without anyone remembering to search. Only on an
 * unmetered network with the battery not low -- a sweep is a few hundred
 * small requests (searches, file lists, file headers), never a model
 * download. Its results open by themselves the next time Models is shown,
 * like a sweep started by hand.
 */
class DiscoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppContainer.get(applicationContext).runDiscoverySweep("weekly")
        return Result.success()
    }

    companion object {
        private const val NAME = "discovery-weekly"

        /** Idempotent: KEEP leaves an already scheduled sweep's timing alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DiscoveryWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
