package dev.sshrelay.forward

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Live counters for one forwarded port. */
data class ForwardStats(
    val activeConnections: Int = 0,
    val totalConnections: Long = 0,
    val bytesToRemote: Long = 0,
    val bytesToLocal: Long = 0,
    val lastError: String? = null,
)

sealed interface ForwardEvent {
    data class Started(val bindHost: String, val port: Int) : ForwardEvent
    data class Stopped(val reason: String?) : ForwardEvent
    data class ConnectionOpened(val id: Long, val origin: String) : ForwardEvent
    data class ConnectionClosed(
        val id: Long,
        val origin: String,
        val bytesToRemote: Long,
        val bytesToLocal: Long,
        val error: String? = null,
    ) : ForwardEvent

    data class Failed(val message: String, val cause: Throwable? = null) : ForwardEvent
}

/**
 * Listens on the device's own loopback and relays every accepted connection
 * through [connector] (an SSH `direct-tcpip` channel in production).
 *
 * This is deliberately hand-written rather than delegating to the SSH library's
 * built-in `-L` support so that we control: the bind address (loopback only,
 * never 0.0.0.0), per-connection lifecycle and accounting, graceful shutdown,
 * and multiple concurrently running rules.
 *
 * Threading: [start]/[stop] are safe to call from any thread. All blocking IO
 * happens on [ioDispatcher].
 */
class LocalForwardServer(
    val rule: ForwardRule,
    private val connector: RemoteConnector,
    private val scope: CoroutineScope,
    private val bindHost: String = LOOPBACK,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val serverSocketFactory: (InetSocketAddress) -> ServerSocket = { addr ->
        ServerSocket().apply {
            reuseAddress = true
            bind(addr, BACKLOG)
        }
    },
) {
    private val _stats = MutableStateFlow(ForwardStats())
    val stats: StateFlow<ForwardStats> = _stats.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _events = MutableSharedFlow<ForwardEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<ForwardEvent> = _events.asSharedFlow()

    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionSeq = AtomicLong(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    /** The port actually bound — differs from [ForwardRule.localPort] when it was 0 (auto). */
    val boundPort: Int
        get() = serverSocket?.localPort ?: rule.localPort

    /**
     * Binds and starts accepting. Throws [IllegalArgumentException] on an invalid
     * rule and [java.net.BindException] when the port is taken — both are surfaced
     * to the UI verbatim.
     */
    fun start() {
        check(!_isRunning.value) { "forward server for port ${rule.localPort} is already running" }
        if (rule.localPort != AUTO_PORT) {
            val validation = PortPolicy.validate(rule)
            if (validation is PortPolicy.Validation.Invalid) {
                throw IllegalArgumentException(validation.reason)
            }
        }
        val socket = serverSocketFactory(InetSocketAddress(bindHost, rule.localPort))
        serverSocket = socket
        _isRunning.value = true
        acceptJob = scope.launch(ioDispatcher) { acceptLoop(socket) }
    }

    /** Stops accepting and force-closes every live connection. Idempotent. */
    fun stop(reason: String? = null) {
        val wasRunning = _isRunning.value
        _isRunning.value = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        acceptJob?.cancel()
        acceptJob = null
        _stats.update { it.copy(activeConnections = 0) }
        if (wasRunning) {
            _events.tryEmit(ForwardEvent.Stopped(reason))
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        _events.tryEmit(ForwardEvent.Started(bindHost, socket.localPort))
        while (currentCoroutineContext().isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break // stop() closed the listening socket
            } catch (e: IOException) {
                _events.tryEmit(ForwardEvent.Failed("accept 失败: ${e.message}", e))
                delay(ACCEPT_RETRY_DELAY_MS)
                continue
            }
            runCatching { client.tcpNoDelay = true }
            clients += client
            scope.launch(ioDispatcher) { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(client: Socket) {
        val id = connectionSeq.incrementAndGet()
        val originHost = client.inetAddress?.hostAddress ?: "unknown"
        val origin = "$originHost:${client.port}"
        var channel: RemoteChannel? = null
        var toRemote = 0L
        var toLocal = 0L
        var failure: String? = null
        val lastActivity = AtomicLong(System.currentTimeMillis())

        val idleWatchdog = scope.launch(ioDispatcher) {
            watchIdle(client, lastActivity) { channel }
        }

        // Counted on accept, not on a successful upstream open: a rule whose
        // target is unreachable must still show up as traffic in the UI, otherwise
        // a misconfigured forward looks identical to no traffic at all.
        _stats.update { it.copy(totalConnections = it.totalConnections + 1) }

        try {
            channel = withContext(ioDispatcher) {
                connector.open(rule.remoteHost, rule.remotePort, originHost, client.port)
            }
            _stats.update { it.copy(activeConnections = it.activeConnections + 1) }
            _events.tryEmit(ForwardEvent.ConnectionOpened(id, origin))

            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val remote = channel

            // client -> remote: on client EOF we close the SSH channel, which is
            // the only "half close" a direct-tcpip channel supports.
            val up = scope.async(ioDispatcher) {
                pump(clientIn, remote.output, lastActivity) { n ->
                    toRemote += n
                    _stats.update { it.copy(bytesToRemote = it.bytesToRemote + n) }
                }
                runCatching { remote.close() }
            }
            // remote -> client: on remote EOF we half-close the client socket so
            // the browser/app sees its response terminate instead of hanging.
            val down = scope.async(ioDispatcher) {
                pump(remote.input, clientOut, lastActivity) { n ->
                    toLocal += n
                    _stats.update { it.copy(bytesToLocal = it.bytesToLocal + n) }
                }
                runCatching { client.shutdownOutput() }
            }
            joinAll(up, down)
        } catch (e: Exception) {
            failure = e.message ?: e::class.java.simpleName
            _stats.update { it.copy(lastError = failure) }
            _events.tryEmit(ForwardEvent.Failed("转发 $origin 失败: $failure", e))
        } finally {
            idleWatchdog.cancel()
            runCatching { channel?.close() }
            runCatching { client.close() }
            clients -= client
            _stats.update { it.copy(activeConnections = (it.activeConnections - 1).coerceAtLeast(0)) }
            _events.tryEmit(ForwardEvent.ConnectionClosed(id, origin, toRemote, toLocal, failure))
        }
    }

    /**
     * Reaps connections that the peer never closed. A browser that crashes or a
     * half-open TCP connection would otherwise pin an SSH channel forever.
     */
    private suspend fun watchIdle(
        client: Socket,
        lastActivity: AtomicLong,
        channelProvider: () -> RemoteChannel?,
    ) {
        if (idleTimeoutMs <= 0) return
        while (currentCoroutineContext().isActive) {
            delay(IDLE_POLL_MS)
            val idleFor = System.currentTimeMillis() - lastActivity.get()
            if (idleFor > idleTimeoutMs) {
                _events.tryEmit(
                    ForwardEvent.Failed("连接空闲 ${idleFor / 1000}s 超过上限，已回收")
                )
                runCatching { client.close() }
                runCatching { channelProvider()?.close() }
                return
            }
        }
    }

    private fun pump(
        src: InputStream,
        dst: OutputStream,
        lastActivity: AtomicLong,
        onBytes: (Long) -> Unit,
    ) {
        val buffer = ByteArray(bufferSize)
        try {
            while (true) {
                val read = src.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                dst.write(buffer, 0, read)
                dst.flush()
                lastActivity.set(System.currentTimeMillis())
                onBytes(read.toLong())
            }
        } catch (_: IOException) {
            // Normal on teardown: the peer closed, or stop() closed the socket.
        } finally {
            runCatching { dst.flush() }
        }
    }

    companion object {
        /** Only ever loopback: Android has no per-app loopback isolation, so 0.0.0.0 would expose the tunnel to the whole LAN. */
        const val LOOPBACK = "127.0.0.1"

        /** `localPort = 0` asks the OS for a free ephemeral port (used by tests). */
        const val AUTO_PORT = 0

        const val DEFAULT_IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val IDLE_POLL_MS = 30_000L
        private const val ACCEPT_RETRY_DELAY_MS = 200L
        private const val BACKLOG = 64
    }
}
