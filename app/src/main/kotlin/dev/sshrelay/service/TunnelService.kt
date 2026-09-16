package dev.sshrelay.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.sshrelay.SshRelayApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import dev.sshrelay.ssh.TunnelState

/**
 * Keeps the tunnel alive while the app is backgrounded.
 *
 * Declared as a `specialUse` foreground service rather than `dataSync`: Android 15
 * imposes a 6-hour-per-day budget on `dataSync` services, which would tear down a
 * long-running tunnel without warning.
 *
 * The service owns no tunnel state of its own — it reflects [dev.sshrelay.ssh.TunnelManager]
 * from [dev.sshrelay.AppGraph], so the UI and the notification always agree.
 */
class TunnelService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: TunnelNotifications

    @Volatile
    private var observeJob: Job? = null

    private val graph get() = (application as SshRelayApp).graph

    override fun onCreate() {
        super.onCreate()
        notifications = TunnelNotifications(this)
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            graph.controller.stop("用户在通知中停止")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // Must happen immediately: Android requires startForeground within ~5s of
        // startForegroundService, and it also grants the "user initiated" grace.
        val initial = notifications.build(graph.manager.state.value, graph.manager.forwards.value)
        runCatching {
            ServiceCompat.startForeground(
                this,
                TunnelNotifications.NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            Log.e(TAG, "startForeground 失败", it)
            stopSelfSafely()
            return START_NOT_STICKY
        }

        observeTunnel()
        return START_NOT_STICKY
    }

    /**
     * Mirrors the tunnel state into the notification and shuts the service down once
     * there is nothing left to keep alive.
     *
     * Note the treatment of [TunnelState.Idle]: `TunnelController.start()` starts this
     * service *before* calling `TunnelManager.start()`, so the first emission of the
     * state flow can still be `Idle`. Stopping on `Idle` would tear the foreground
     * service down in that window and leave a running tunnel unprotected against
     * background process kills — exactly the opposite of the intent. Only a terminal
     * state (Stopped/Failed) ends the service.
     */
    private fun observeTunnel() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            combine(graph.manager.state, graph.manager.forwards) { state, forwards -> state to forwards }
                .collect { (state, forwards) ->
                    notifications.update(notifications.build(state, forwards))
                    if (state is TunnelState.Stopped || state is TunnelState.Failed) {
                        stopSelfSafely()
                    }
                }
        }
    }

    private fun stopSelfSafely() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TunnelService"
        const val ACTION_START = "dev.sshrelay.action.START_TUNNEL"
        const val ACTION_STOP = "dev.sshrelay.action.STOP_TUNNEL"
    }
}

/**
 * Notification action handler.
 *
 * A receiver (not a service intent) because starting a service from a background
 * app is restricted on Android 8+, while delivering a broadcast to a manifest
 * receiver is not.
 */
class TunnelActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TunnelService.ACTION_STOP) {
            (context.applicationContext as? SshRelayApp)?.graph?.controller?.stop("用户在通知中停止")
        }
    }
}
