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

/**
 * "The agent finished replying" notifications, posted only while the app is in the background
 * (a long run finished after the user switched away). A tap opens that conversation.
 */
object ReplyNotifier {
    private const val CHANNEL_ID = "replies"
    /** Intent extra read by [MainActivity] to open a conversation on launch. */
    const val EXTRA_OPEN_CONVERSATION = "goclaw.open_conversation"

    /** Set by [MainActivity] onStart/onStop — the app is single-activity. */
    @Volatile var appInForeground: Boolean = false

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_replies),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notif_channel_replies_desc) }
        mgr.createNotificationChannel(channel)
    }

    /** Posts a reply notification for [conversationId] if the app isn't visible. */
    fun notifyIfBackground(context: Context, conversationId: String, title: String, reply: String) {
        if (appInForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val body = reply.trim().take(300).ifBlank { context.getString(R.string.notif_reply_generic) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.ifBlank { context.getString(R.string.notif_reply_title) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, conversationId))
            .build()
        // One notification per conversation: a newer reply replaces the older one.
        runCatching { NotificationManagerCompat.from(context).notify(conversationId.hashCode(), notification) }
    }

    /** Clears a conversation's reply notification (e.g. once it has been opened in-app). */
    fun cancel(context: Context, conversationId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(conversationId.hashCode()) }
    }

    private fun openIntent(context: Context, conversationId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CONVERSATION, conversationId)
        }
        return PendingIntent.getActivity(
            context, conversationId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
