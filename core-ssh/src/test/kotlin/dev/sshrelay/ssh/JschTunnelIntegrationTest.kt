package dev.sshrelay.ssh

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * End-to-end coverage with no device, no emulator and no external host:
 *
 *   HttpURLConnection -> 127.0.0.1:localPort -> LocalForwardServer
 *                     -> JschTransport (direct-tcpip over real SSH)
 *                     -> EmbeddedSshServer -> 127.0.0.1:httpPort
 *
 * i.e. exactly the data path the phone browser will use, minus Android itself.
 */
class JschTunnelIntegrationTest {

    private lateinit var sshd: EmbeddedSshServer
    private lateinit var http: TestHttpServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        sshd = EmbeddedSshServer()
        http = TestHttpServer()
    }

    @After
    fun tearDown() {
        runCatching { http.close() }
        runCatching { sshd.close() }
        scope.cancel()
    }

    private fun config(
        auth: AuthMethod = AuthMethod.Password(sshd.password),
        port: Int = sshd.port,
    ) = SshConfig(host = "127.0.0.1", port = port, username = sshd.username, auth = auth)

    private fun httpGet(port: Int, path: String = "/", host: String = "127.0.0.1"): String {
        val connection = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun awaitConnected(manager: TunnelManager, timeoutMs: Long = 20_000) {
        withTimeout(timeoutMs) {
            while (manager.state.value !is TunnelState.Connected) {
                val state = manager.state.value
                if (state is TunnelState.Failed) fail("tunnel failed: ${state.reason}")
                delay(50)
            }
        }
    }

    private suspend fun awaitForwardPort(manager: TunnelManager, timeoutMs: Long = 20_000): Int =
        withTimeout(timeoutMs) {
            while (true) {
                val forward = manager.forwards.value.firstOrNull()
                if (forward != null && forward.running && forward.boundPort != 0) break
                delay(50)
            }
            manager.forwards.value.first().boundPort
        }

    // ------------------------------------------------------------ tests

    @Test
    fun `browser style request travels through a real ssh tunnel`() {
        val store = InMemoryHostKeyStore()
        val transport = JschTransport(config(), TofuHostKeyPolicy(store))
        transport.connect()
        assertTrue(transport.isConnected)
        assertNotNull(transport.serverVersion)

        val server = dev.sshrelay.forward.LocalForwardServer(
            rule = dev.sshrelay.forward.ForwardRule("e2e", 0, "127.0.0.1", http.port),
            connector = transport,
            scope = scope,
        )
        server.start()
        try {
            val body = httpGet(server.boundPort)
            assertTrue("got: $body", body.contains("SSH-RELAY-E2E-OK"))
            assertEquals(1L, server.stats.value.totalConnections)
        } finally {
            server.stop("test over")
            transport.close()
        }
    }

    @Test
    fun `first connection pins the host key and the second one trusts it`() {
        val store = InMemoryHostKeyStore()
        val first = JschTransport(config(), TofuHostKeyPolicy(store))
        first.connect()
        try {
            val decision = first.hostKeyDecision
            assertTrue("expected FirstUse, got $decision", decision is HostKeyDecision.FirstUse)
            assertEquals(1, store.list().size)
            assertTrue(store.list().first().fingerprint.startsWith("SHA256:"))
        } finally {
            first.close()
        }

        val second = JschTransport(config(), TofuHostKeyPolicy(store))
        second.connect()
        try {
            assertTrue(second.hostKeyDecision is HostKeyDecision.Trusted)
        } finally {
            second.close()
        }
    }

    @Test
    fun `a changed host key is refused instead of being trusted silently`() {
        val store = InMemoryHostKeyStore()
        JschTransport(config(), TofuHostKeyPolicy(store)).use { it.connect() }
        val pinned = store.list().single()

        // Simulate a different server answering on the same host:port.
        store.store(pinned.copy(keyBase64 = Base64.getEncoder().encodeToString(ByteArray(140) { 7 })))

        val attacker = JschTransport(config(), TofuHostKeyPolicy(store))
        try {
            attacker.connect()
            fail("a changed host key must abort the connection")
        } catch (e: SshException) {
            assertEquals(SshFailureKind.HOST_KEY_CHANGED, e.kind)
        } finally {
            attacker.close()
        }
    }

    @Test
    fun `wrong password is classified as non-retryable auth failure`() {
        val transport = JschTransport(
            config(auth = AuthMethod.Password("definitely-not-it")),
            TofuHostKeyPolicy(InMemoryHostKeyStore()),
        )
        try {
            transport.connect()
            fail("expected authentication to fail")
        } catch (e: SshException) {
            assertEquals(SshFailureKind.AUTH, e.kind)
            assertTrue("auth failures must not be retried forever", !e.isRetryable)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `unreachable server is classified as retryable`() {
        val deadPort = freePort()
        val transport = JschTransport(
            config(port = deadPort),
            TofuHostKeyPolicy(InMemoryHostKeyStore()),
        )
        try {
            transport.connect()
            fail("expected connection to fail")
        } catch (e: SshException) {
            assertEquals(SshFailureKind.UNREACHABLE, e.kind)
            assertTrue(e.isRetryable)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `forwarding a port that nothing listens on fails only that connection`() {
        val deadPort = freePort()
        val transport = JschTransport(config(), TofuHostKeyPolicy(InMemoryHostKeyStore()))
        transport.connect()
        val server = dev.sshrelay.forward.LocalForwardServer(
            rule = dev.sshrelay.forward.ForwardRule("dead", 0, "127.0.0.1", deadPort),
            connector = transport,
            scope = scope,
        )
        server.start()
        try {
            try {
                httpGet(server.boundPort)
                fail("expected the upstream to be unreachable")
            } catch (_: IOException) {
                // expected: the tunnel is healthy, the target is not
            }
            // the tunnel itself must survive
            assertTrue(transport.isConnected)
        } finally {
            server.stop("test over")
            transport.close()
        }
    }

    @Test
    fun `tunnel manager drives the whole lifecycle`() = runBlocking {
        val store = InMemoryHostKeyStore()
        val manager = TunnelManager(
            scope = scope,
            transportFactory = { cfg -> JschTransport(cfg, TofuHostKeyPolicy(store)) },
            backoff = BackoffPolicy(initialDelayMs = 100, maxDelayMs = 300),
        )
        val rule = dev.sshrelay.forward.ForwardRule("web", 0, "127.0.0.1", http.port)

        manager.start(config(), listOf(rule))
        awaitConnected(manager)
        val port = awaitForwardPort(manager)

        assertTrue(httpGet(port).contains("SSH-RELAY-E2E-OK"))
        assertTrue(manager.isRunning)

        manager.stop("test over")
        assertTrue(manager.state.value is TunnelState.Stopped)
        assertTrue(!manager.isRunning)
        // the port must be released
        withTimeout(10_000) {
            while (true) {
                try {
                    Socket("127.0.0.1", port).use { }
                    delay(50)
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    @Test
    fun `tunnel manager gives up on a bad password instead of looping`() = runBlocking {
        val manager = TunnelManager(
            scope = scope,
            transportFactory = { cfg -> JschTransport(cfg, TofuHostKeyPolicy(InMemoryHostKeyStore())) },
            backoff = BackoffPolicy(initialDelayMs = 50, maxDelayMs = 100),
        )
        manager.start(config(auth = AuthMethod.Password("nope")), listOf(
            dev.sshrelay.forward.ForwardRule("web", 0, "127.0.0.1", http.port),
        ))
        withTimeout(20_000) {
            while (manager.state.value !is TunnelState.Failed) {
                delay(50)
            }
        }
        val state = manager.state.value as TunnelState.Failed
        assertEquals(SshFailureKind.AUTH, state.kind)
        assertTrue(!manager.isRunning)
    }

    @Test
    fun `host key fingerprint is stable across reconnects`() {
        val storeA = InMemoryHostKeyStore()
        JschTransport(config(), TofuHostKeyPolicy(storeA)).use { it.connect() }
        val storeB = InMemoryHostKeyStore()
        JschTransport(config(), TofuHostKeyPolicy(storeB)).use { it.connect() }
        assertEquals(storeA.list().single().fingerprint, storeB.list().single().fingerprint)
    }

    @Test
    fun `loopback address is used for the local listener`() {
        val transport = JschTransport(config(), TofuHostKeyPolicy(InMemoryHostKeyStore()))
        transport.connect()
        val server = dev.sshrelay.forward.LocalForwardServer(
            rule = dev.sshrelay.forward.ForwardRule("web", 0, "127.0.0.1", http.port),
            connector = transport,
            scope = scope,
        )
        server.start()
        try {
            Socket(InetAddress.getByName("127.0.0.1"), server.boundPort).use { it.soTimeout = 3000 }
        } finally {
            server.stop("test over")
            transport.close()
        }
    }
}
