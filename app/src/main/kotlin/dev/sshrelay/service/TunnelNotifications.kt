package dev.sshrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.sshrelay.MainActivity
import dev.sshrelay.R
import dev.sshrelay.ssh.ForwardStatus
import dev.sshrelay.ssh.TunnelState

/** Builds the persistent foreground-service notification. */
class TunnelNotifications(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                // LOW: the tunnel is long-lived; a sound would be noise, but the
                // notification still has to exist for the FGS contract.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
        )
    }

    fun build(
        state: TunnelState,
        forwards: List<ForwardStatus>,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, TunnelActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val running = forwards.filter { it.running }
        val text = when {
            running.isNotEmpty() -> running.joinToString("，") { "127.0.0.1:${it.boundPort}" }
            else -> state.describe()
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notification_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(notification: Notification) {
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val CHANNEL_ID = "tunnel_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "dev.sshrelay.action.STOP_TUNNEL"
    }
}

/** One-line human summary of a tunnel state, shared by the notification and the UI. */
fun TunnelState.describe(): String = when (this) {
    is TunnelState.Idle -> "空闲"
    is TunnelState.Connecting -> "正在连接 $target（第 $attempt 次）"
    is TunnelState.Connected -> "已连接 $target"
    is TunnelState.Reconnecting ->
        "连接中断，${retryInMs / 1000} 秒后重连（第 $attempt 次）" + (reason?.let { "：$it" } ?: "")
    is TunnelState.Failed -> "失败：$reason"
    is TunnelState.Stopped -> "已停止" + (reason?.let { "（$it）" } ?: "")
}
