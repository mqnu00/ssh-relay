package dev.sshrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sshrelay.forward.ForwardRule
import dev.sshrelay.ssh.AuthMethod
import dev.sshrelay.ssh.SshConfig
import dev.sshrelay.ssh.TunnelState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end test: the app itself opens a real SSH connection to the
 * test server running on the host (10.0.2.2 as seen from the emulator), forwards
 * `127.0.0.1:18080` to `18080` on the far side, and then fetches that URL the way
 * a browser would.
 *
 * Prerequisites (see scripts/run-test-sshd.sh):
 *   -- SSH server on the host at 0.0.0.0:2222 (tester/testpass)
 *   -- HTTP target on the host at 127.0.0.1:18080
 */
@RunWith(AndroidJUnit4::class)
class TunnelEndToEndTest {

    private val localPort = 18080

    @Test
    fun browserStyleRequestReachesTheForwardedPortOnLoopback() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SshRelayApp
        val graph = app.graph

        val config = SshConfig(
            host = BuildConfig.E2E_SSH_HOST,
            port = BuildConfig.E2E_SSH_PORT,
            username = BuildConfig.E2E_SSH_USER,
            auth = AuthMethod.Password(BuildConfig.E2E_SSH_PASSWORD),
            connectTimeoutMs = 15_000,
        )
        val rule = ForwardRule(
            id = "e2e",
            localPort = localPort,
            remoteHost = "127.0.0.1",
            remotePort = 18080,
            label = "e2e",
        )

        graph.manager.start(config, listOf(rule))
        try {
            awaitConnected(graph)
            awaitForward(graph)

            // 1) the URL a user would type, fetched straight from the device loopback
            val body = httpGet("http://127.0.0.1:$localPort/")
            assertTrue("unexpected body:\n$body", body.contains("SSH-RELAY-E2E-OK"))

            // 2) it really is a tunnel, not a direct hit: the HTTP server sees the
            //    request coming from the SSH server's own loopback.
            assertTrue("expected loopback origin in:\n$body", body.contains("127.0.0.1"))

            // 3) the listener must not be exposed on the device's LAN address
            firstNonLoopbackIPv4()?.let { lan ->
                try {
                    Socket().use { it.connect(InetSocketAddress(lan, localPort), 2_000) }
                    fail("tunnel is reachable on $lan:$localPort - it must bind 127.0.0.1 only")
                } catch (_: IOException) {
                    // expected
                }
            }

            // 4) concurrent browser connections (a page with several resources)
            val bodies = (1..6).map { httpGet("http://127.0.0.1:$localPort/") }
            assertTrue(bodies.all { it.contains("SSH-RELAY-E2E-OK") })
        } finally {
            graph.manager.stop("instrumented test finished")
        }
    }

    @Test
    fun aBadPasswordFailsFastInsteadOfRetryingForever() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SshRelayApp
        val graph = app.graph

        graph.manager.start(
            SshConfig(
                host = BuildConfig.E2E_SSH_HOST,
                port = BuildConfig.E2E_SSH_PORT,
                username = BuildConfig.E2E_SSH_USER,
                auth = AuthMethod.Password("wrong-password"),
            ),
            listOf(ForwardRule("bad", 18081, "127.0.0.1", 18080)),
        )
        try {
            withTimeout(30_000) {
                while (graph.manager.state.value !is TunnelState.Failed) delay(100)
            }
        } finally {
            graph.manager.stop("instrumented test finished")
        }
    }

    private suspend fun awaitConnected(graph: AppGraph) {
        withTimeout(45_000) {
            while (graph.manager.state.value !is TunnelState.Connected) {
                (graph.manager.state.value as? TunnelState.Failed)?.let { fail("tunnel failed: ${it.reason}") }
                delay(100)
            }
        }
    }

    private suspend fun awaitForward(graph: AppGraph) {
        withTimeout(20_000) {
            while (true) {
                val forward = graph.manager.forwards.value.firstOrNull()
                if (forward != null && forward.running) return@withTimeout
                delay(100)
            }
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        // Generous timeouts: this emulator shares a memory-constrained host, and a
        // GC-thrashing guest can take tens of seconds to answer. A short timeout
        // would report environment slowness as a product failure.
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun firstNonLoopbackIPv4(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
}
