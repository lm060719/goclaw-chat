package xyz.limo060719.goclaw.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import xyz.limo060719.goclaw.MainActivity
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.remote.ExecApproval

/**
 * Posts "a shell command is waiting for your approval" notifications and routes taps to the
 * in-app approval screen. Kept free of injected state so both the worker and (potentially) the
 * foreground app can post through it.
 */
object ApprovalNotifier {
    private const val CHANNEL_ID = "approvals"
    /** Intent extra read by [MainActivity] to deep-link to a route on launch. */
    const val EXTRA_OPEN_ROUTE = "goclaw.open_route"
    const val ROUTE_APPROVALS = "approvals"

    /** Whether notifications can actually be shown (POST_NOTIFICATIONS granted + not disabled). */
    fun canPost(context: Context): Boolean =
        hasPostPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** POST_NOTIFICATIONS is a runtime permission on API 33+; auto-granted below. */
    private fun hasPostPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_approvals),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notif_channel_approvals_desc) }
        mgr.createNotificationChannel(channel)
    }

    fun notify(context: Context, approval: ExecApproval) {
        // Inline permission check (satisfies lint's MissingPermission right at the notify() call).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val body = approval.command.ifBlank { approval.reason }
            .ifBlank { context.getString(R.string.notif_approval_generic) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notif_approval_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(approvalsIntent(context))
            .build()
        // Stable per-approval id so re-posting the same one updates rather than stacks.
        runCatching { NotificationManagerCompat.from(context).notify(approval.id.hashCode(), notification) }
    }

    private fun approvalsIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ROUTE, ROUTE_APPROVALS)
        }
        return PendingIntent.getActivity(
            context, ROUTE_APPROVALS.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
