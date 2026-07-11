package xyz.limo060719.goclaw.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.data.remote.GoClawWsClient

/**
 * Periodically polls `exec.approval.list` and posts a notification for any pending shell approval
 * the user hasn't been told about yet — so a command blocked waiting for approval is noticed even
 * when the approval screen (or the whole app) isn't open. Runs only while the user has opted in
 * ([SettingsStore] `approvalNotifications`) and the gateway is configured.
 *
 * De-dup: the set of already-notified approval ids is kept in SharedPreferences. Each run we notify
 * only ids we haven't posted before, then remember the still-pending set (resolved ones drop out).
 * Ids we couldn't actually post (e.g. notifications disabled) are NOT marked seen, so they retry.
 */
@HiltWorker
class ApprovalCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsStore: SettingsStore,
    private val ws: GoClawWsClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsStore.current()
        if (!settings.approvalNotifications || !settings.isConfigured) return Result.success()

        val approvals = runCatching { ws.listExecApprovals(settings) }.getOrElse { return Result.retry() }
        val currentIds = approvals.map { it.id }.toSet()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alreadyNotified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()

        val fresh = approvals.filter { it.id !in alreadyNotified }
        val postedIds = if (fresh.isNotEmpty() && ApprovalNotifier.canPost(applicationContext)) {
            ApprovalNotifier.ensureChannel(applicationContext)
            fresh.forEach { ApprovalNotifier.notify(applicationContext, it) }
            fresh.map { it.id }.toSet()
        } else {
            emptySet()
        }

        // Keep previously-notified ids that are still pending, plus the ones we just posted.
        val nextNotified = alreadyNotified.intersect(currentIds) + postedIds
        prefs.edit().putStringSet(KEY_NOTIFIED, nextNotified).apply()
        return Result.success()
    }

    private companion object {
        const val PREFS = "approval_notify"
        const val KEY_NOTIFIED = "notified_ids"
    }
}
