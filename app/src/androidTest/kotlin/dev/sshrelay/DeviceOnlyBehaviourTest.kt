package dev.sshrelay

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sshrelay.data.SecretStore
import dev.sshrelay.forward.ForwardRule
import dev.sshrelay.service.TunnelNotifications
import dev.sshrelay.ssh.ForwardStatus
import dev.sshrelay.ssh.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only checks: the Android Keystore and the foreground-service notification
 * have no JVM equivalent, so they can only be verified here.
 */
@RunWith(AndroidJUnit4::class)
class DeviceOnlyBehaviourTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Notification channels and posts behave differently depending on whether
     * POST_NOTIFICATIONS was granted, so grant it rather than testing an incidental
     * permission state.
     */
    @Before
    fun grantNotificationPermission() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                .close()
        }
    }

    @Test
    fun credentialsRoundTripThroughTheAndroidKeystore() {
        val store = SecretStore(context)
        val key = "test.roundtrip"
        val secret = "p@ssw0rd-\u4e2d\u6587-\u00e9\u00e0\u00fc"

        assertNull(store.get(key))
        store.put(key, secret)
        assertEquals(secret, store.get(key))

        // overwriting must work (IV must not be reused naively)
        store.put(key, "second-value")
        assertEquals("second-value", store.get(key))

        store.remove(key)
        assertNull(store.get(key))
    }

    @Test
    fun storingAnEmptySecretClearsIt() {
        val store = SecretStore(context)
        store.put("test.empty", "something")
        store.put("test.empty", "")
        assertNull(store.get("test.empty"))
    }

    @Test
    fun foregroundNotificationIsWellFormed() {
        val notifications = TunnelNotifications(context)
        notifications.ensureChannel()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(
            "the FGS channel must exist before startForeground is called",
            manager.getNotificationChannel(TunnelNotifications.CHANNEL_ID),
        )

        val connected = TunnelState.Connected(
            sinceEpochMs = System.currentTimeMillis(),
            target = "tester@10.0.2.2:2222",
            serverVersion = "SSH-2.0-TestServer",
        )
        val forwards = listOf(
            ForwardStatus(
                rule = ForwardRule("r", 18080, "127.0.0.1", 18080),
                running = true,
                boundPort = 18080,
            )
        )
        val notification = notifications.build(connected, forwards)
        assertNotNull("build() returned no notification", notification)
        val actions = notification.actions
        assertNotNull("the notification must expose a stop action", actions)
        assertEquals("expected exactly one action (stop)", 1, actions!!.size)

        // Every state must produce a notification without throwing.
        listOf(
            TunnelState.Idle,
            TunnelState.Connecting(1, "tester@host:22"),
            TunnelState.Reconnecting(2, 4_000, "connection reset"),
            TunnelState.Stopped("user"),
            TunnelState.Failed("auth", dev.sshrelay.ssh.SshFailureKind.AUTH),
        ).forEach { state ->
            assertNotNull("build() failed for $state", notifications.build(state, forwards))
        }
    }
}
