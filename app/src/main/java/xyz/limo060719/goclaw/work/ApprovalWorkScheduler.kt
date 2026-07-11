package xyz.limo060719.goclaw.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the [ApprovalCheckWorker]. 15 minutes is WorkManager's periodic floor — the practical
 * latency ceiling for background polling without a foreground service or push (the gateway has no
 * FCM). Good enough to surface a blocked approval; the in-app screen is still the fast path.
 */
object ApprovalWorkScheduler {
    private const val WORK_NAME = "approval_check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ApprovalCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
