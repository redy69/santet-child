package com.santethp

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * A short, isolated heartbeat task for the parent ONLINE indicator.
 *
 * WorkManager remains inexact and Android may defer this task during Doze or
 * OEM background restrictions. Keeping it independent prevents usage-history,
 * schedule, or app-block reporting from affecting heartbeat execution/results.
 */
class ChildHeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Heartbeat worker started (attempt=${runAttemptCount + 1})")
        return try {
            ChildDeviceStatusReporter.publish(applicationContext)
            Log.i(TAG, "Heartbeat worker completed")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Heartbeat failed (${error.javaClass.simpleName}): ${error.message}",
                error
            )
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ChildHeartbeatWorker"
        private const val PERIODIC_NAME = "child-heartbeat-periodic"
        private const val INITIAL_NAME = "child-heartbeat-initial"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val initial = OneTimeWorkRequestBuilder<ChildHeartbeatWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_NAME,
                ExistingWorkPolicy.KEEP,
                initial
            )

            val periodic = PeriodicWorkRequestBuilder<ChildHeartbeatWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )
            Log.i(TAG, "Independent heartbeat scheduled (periodic interval=15m)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(INITIAL_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
