package dev.sshrelay.forward

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the whole forwarding engine against a plain TCP peer — no SSH, no
 * Android, no emulator. This is the layer that must be correct before an SSH
 * transport is even worth wiring up.
 */
class LocalForwardServerTest {

    private lateinit var scope: CoroutineScope
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
        scope.cancel()
    }

    // ------------------------------------------------------------ helpers

    /** Echoes every byte back, upper-cased on the first byte so we can prove the relay is not a no-op. */
    private fun startEchoServer(): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        closeables += server
        thread(isDaemon = true, name = "echo-server") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                thread(isDaemon = true) {
                    try {
                        socket.getOutputStream().use { out ->
                            val input = socket.getInputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                out.flush()
                            }
                        }
                    } catch (_: IOException) {
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }
        }
        return server.localPort
    }

    /** Minimal HTTP/1.0 responder that closes the connection after the body. */
    private fun startHttpServer(body: String): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        closeables += server
        thread(isDaemon = true, name = "http-server") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                thread(isDaemon = true) {
                    try {
                        val input = socket.getInputStream()
                        // read until end of headers
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val c = input.read()
                            if (c < 0) break
                            header.append(c.toChar())
                        }
                        val bytes = body.toByteArray()
                        val response = "HTTP/1.0 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        socket.getOutputStream().write(response.toByteArray())
                        socket.getOutputStream().write(bytes)
                        socket.getOutputStream().flush()
                    } catch (_: IOException) {
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }
        }
        return server.localPort
    }

    private fun newServer(
        rule: ForwardRule,
        connector: RemoteConnector,
        idleTimeoutMs: Long = 0,
    ) = LocalForwardServer(
        rule = rule,
        connector = connector,
        scope = scope,
        idleTimeoutMs = idleTimeoutMs,
    ).also { closeables += AutoCloseable { it.stop("test teardown") } }

    private fun rule(localPort: Int, remoteHost: String, remotePort: Int) =
        ForwardRule(id = "r", localPort = localPort, remoteHost = remoteHost, remotePort = remotePort)

    private fun awaitUntil(timeoutMs: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for: $what")
    }

    // -------------------------------------------------------------- tests

    @Test
    fun `relays bytes to the remote peer and back`() {
        val echoPort = startEchoServer()
        val server = newServer(rule(0, "127.0.0.1", echoPort), SocketRemoteConnector())
        server.start()
        assertNotEquals(0, server.boundPort)

        Socket("127.0.0.1", server.boundPort).use { client ->
            client.soTimeout = 5_000
            val payload = "hello ssh-relay".toByteArray()
            client.getOutputStream().write(payload)
            client.getOutputStream().flush()
            val received = ByteArray(payload.size)
            DataInputStream(client.getInputStream()).readFully(received)
            assertEquals("hello ssh-relay", String(received))
        }

        awaitUntil(what = "connection closed") { server.stats.value.activeConnections == 0 }
        val stats = server.stats.value
        assertEquals(1L, stats.totalConnections)
        assertTrue("bytesToRemote=${stats.bytesToRemote}", stats.bytesToRemote >= 15)
        assertTrue("bytesToLocal=${stats.bytesToLocal}", stats.bytesToLocal >= 15)
        assertTrue(server.isRunning.value)
    }

    @Test
    fun `serves an http response through the tunnel and terminates it correctly`() {
        // Guards the half-close behaviour: the browser must see the body *and* a
        // clean EOF, not hang waiting for a connection that never closes.
        val httpPort = startHttpServer("<html>SSH-RELAY-OK</html>")
        val server = newServer(rule(0, "127.0.0.1", httpPort), SocketRemoteConnector())
        server.start()

        Socket("127.0.0.1", server.boundPort).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            val text = client.getInputStream().readBytes().decodeToString()
            assertTrue("response was:\n$text", text.startsWith("HTTP/1.0 200 OK"))
            assertTrue("response was:\n$text", text.endsWith("SSH-RELAY-OK</html>"))
        }
    }

    @Test
    fun `handles many concurrent connections without cross-talk`() = runBlocking {
        val echoPort = startEchoServer()
        val server = newServer(rule(0, "127.0.0.1", echoPort), SocketRemoteConnector())
        server.start()

        val parallelism = 24
        val pool = Executors.newFixedThreadPool(parallelism)
        val errors = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(parallelism)
        repeat(parallelism) { index ->
            pool.execute {
                start.await()
                try {
                    Socket("127.0.0.1", server.boundPort).use { client ->
                        client.soTimeout = 10_000
                        val payload = ("conn-$index-" + "x".repeat(2048)).toByteArray()
                        client.getOutputStream().write(payload)
                        client.getOutputStream().flush()
                        val received = ByteArray(payload.size)
                        DataInputStream(client.getInputStream()).readFully(received)
                        if (!payload.contentEquals(received)) errors.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("concurrent clients timed out", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(0, errors.get())
        awaitUntil(what = "all connections accounted for") {
            server.stats.value.totalConnections == parallelism.toLong()
        }
    }

    @Test
    fun `relays a multi-megabyte payload without corruption`() {
        val echoPort = startEchoServer()
        val server = newServer(rule(0, "127.0.0.1", echoPort), SocketRemoteConnector())
        server.start()

        val size = 4 * 1024 * 1024
        val payload = ByteArray(size).also { java.util.Random(42).nextBytes(it) }
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(payload)

        Socket("127.0.0.1", server.boundPort).use { client ->
            client.soTimeout = 30_000
            val writer = thread(isDaemon = true) {
                client.getOutputStream().write(payload)
                client.getOutputStream().flush()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            val input = client.getInputStream()
            var remaining = size
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (n < 0) break
                digest.update(buffer, 0, n)
                remaining -= n
            }
            assertEquals(0, remaining)
            assertTrue(expectedDigest.contentEquals(digest.digest()))
            writer.join(5_000)
        }
    }

    @Test
    fun `listens on loopback only and never on a routable address`() {
        val routable: InetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?: run {
                assumeTrue("host has no non-loopback IPv4 address to probe", false)
                return
            }

        val echoPort = startEchoServer()
        val server = newServer(rule(0, "127.0.0.1", echoPort), SocketRemoteConnector())
        server.start()

        // reachable on loopback ...
        Socket("127.0.0.1", server.boundPort).use { it.soTimeout = 2_000 }

        // ... and not reachable from the LAN interface. This is the guarantee that
        // keeps the tunnel private to the device.
        try {
            Socket().use { probe ->
                probe.connect(InetSocketAddress(routable, server.boundPort), 2_000)
            }
            fail("tunnel was reachable on ${routable.hostAddress} - it must bind loopback only")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `rejects a privileged local port before binding`() {
        val echoPort = startEchoServer()
        val server = newServer(rule(80, "127.0.0.1", echoPort), SocketRemoteConnector())
        try {
            server.start()
            fail("expected IllegalArgumentException for port 80")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1024"))
        }
        assertFalse(server.isRunning.value)
    }

    @Test
    fun `reports an occupied local port as a bind failure`() {
        val echoPort = startEchoServer()
        val blocker = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        closeables += blocker
        val server = newServer(rule(blocker.localPort, "127.0.0.1", echoPort), SocketRemoteConnector())
        try {
            server.start()
            fail("expected BindException")
        } catch (e: java.net.BindException) {
            assertFalse(server.isRunning.value)
        }
    }

    @Test
    fun `stop closes the listener and every live connection`() {
        val echoPort = startEchoServer()
        val server = newServer(rule(0, "127.0.0.1", echoPort), SocketRemoteConnector())
        server.start()
        val port = server.boundPort

        val client = Socket("127.0.0.1", port)
        client.soTimeout = 10_000
        client.getOutputStream().write("ping".toByteArray())
        client.getOutputStream().flush()
        awaitUntil(what = "connection registered") { server.stats.value.activeConnections == 1 }

        server.stop("user pressed stop")
        assertFalse(server.isRunning.value)
        awaitUntil(what = "active connections drained") { server.stats.value.activeConnections == 0 }
        runCatching { client.close() }

        // the port is free again
        awaitUntil(what = "listener released") {
            try {
                ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { }
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    @Test
    fun `idle watchdog reaps a connection whose peer went silent`() {
        val echoPort = startEchoServer()
        val server = newServer(
            rule = rule(0, "127.0.0.1", echoPort),
            connector = SocketRemoteConnector(),
            idleTimeoutMs = 300,
        )
        server.start()
        Socket("127.0.0.1", server.boundPort).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write("hi".toByteArray())
            client.getOutputStream().flush()
            awaitUntil(timeoutMs = 10_000, what = "idle connection reaped") {
                server.stats.value.activeConnections == 0
            }
        }
    }

    @Test
    fun `a failed upstream open is reported and the client socket is closed`() {
        // Reserve then release a port so that nothing is listening on it.
        val deadPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val server = newServer(rule(0, "127.0.0.1", deadPort), SocketRemoteConnector(connectTimeoutMs = 1_000))
        server.start()

        Socket("127.0.0.1", server.boundPort).use { client ->
            client.soTimeout = 5_000
            // The relay must close our socket instead of leaving us hanging.
            assertEquals(-1, client.getInputStream().read())
        }

        awaitUntil(what = "failure recorded") { server.stats.value.lastError != null }
        assertTrue(server.stats.value.lastError!!.isNotEmpty())
        awaitUntil(what = "connection released") { server.stats.value.activeConnections == 0 }
        assertEquals(1L, server.stats.value.totalConnections)
    }
}
