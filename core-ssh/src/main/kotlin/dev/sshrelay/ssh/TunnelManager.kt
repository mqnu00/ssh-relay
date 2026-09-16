package dev.sshrelay.ssh

import dev.sshrelay.forward.ForwardRule
import dev.sshrelay.forward.ForwardStats
import dev.sshrelay.forward.LocalForwardServer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Coarse lifecycle of the tunnel, as shown in the UI and the notification. */
sealed interface TunnelState {
    data object Idle : TunnelState
    data class Connecting(val attempt: Int, val target: String) : TunnelState
    data class Connected(
        val sinceEpochMs: Long,
        val target: String,
        val serverVersion: String?,
    ) : TunnelState

    data class Reconnecting(val attempt: Int, val retryInMs: Long, val reason: String?) : TunnelState
    data class Failed(val reason: String, val kind: SshFailureKind) : TunnelState
    data class Stopped(val reason: String?) : TunnelState
}

val TunnelState.isWorking: Boolean
    get() = this is TunnelState.Connecting || this is TunnelState.Connected || this is TunnelState.Reconnecting

/** Status of one configured rule at this instant. */
data class ForwardStatus(
    val rule: ForwardRule,
    val running: Boolean,
    val boundPort: Int,
    val error: String? = null,
    val stats: ForwardStats = ForwardStats(),
) {
    /** What the user types into the phone browser. */
    val localUrl: String
        get() = "http://127.0.0.1:$boundPort"
}

/**
 * Owns the whole tunnel lifecycle: connect, expose every enabled rule on
 * loopback, watch for drops, reconnect with backoff, and tear everything down.
 *
 * Reconnection is deliberately *not* exponential-forever for authentication and
 * host-key failures — retrying a wrong password or a changed host key just spams
 * the server, so those transition straight to [TunnelState.Failed].
 */
class TunnelManager(
    private val scope: CoroutineScope,
    private val transportFactory: (SshConfig) -> SshTransport,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = 500,
    private val idleTimeoutMs: Long = LocalForwardServer.DEFAULT_IDLE_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _forwards = MutableStateFlow<List<ForwardStatus>>(emptyList())
    val forwards: StateFlow<List<ForwardStatus>> = _forwards.asStateFlow()

    /** Rolling log for the in-app console; `replay` keeps recent lines for late subscribers. */
    private val _log = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 512)
    val log: SharedFlow<String> = _log.asSharedFlow()

    private val servers = ConcurrentHashMap<String, LocalForwardServer>()
    private val lifecycleLock = Any()

    @Volatile
    private var supervisor: Job? = null

    @Volatile
    private var statsJob: Job? = null

    @Volatile
    private var transport: SshTransport? = null

    @Volatile
    private var running = false

    private var config: SshConfig? = null
    private var rules: List<ForwardRule> = emptyList()

    val isRunning: Boolean
        get() = running

    /** Starts (or restarts) the tunnel. Throws [IllegalArgumentException] on an invalid config. */
    fun start(config: SshConfig, rules: List<ForwardRule>) {
        config.validate()?.let { throw IllegalArgumentException(it) }
        synchronized(lifecycleLock) {
            stopInternal("重新启动")
            this.config = config
            this.rules = rules
            running = true
            _forwards.value = rules.filter { it.enabled }
                .map { ForwardStatus(it, running = false, boundPort = it.localPort) }
        }
        emit("开始连接 ${config.describe()}")
        supervisor = scope.launch { supervise() }
    }

    /** Stops everything: forwards first, then the SSH session. Idempotent. */
    fun stop(reason: String? = null) {
        synchronized(lifecycleLock) { stopInternal(reason ?: "已停止") }
        emit("隧道已停止")
    }

    /** Drop the current attempt and reconnect immediately (used by the "重试" button). */
    fun retryNow() {
        val cfg = config ?: return
        start(cfg, rules)
    }

    fun shutdown() = stop("应用退出")

    private fun stopInternal(reason: String) {
        running = false
        supervisor?.cancel()
        supervisor = null
        stopForwards()
        runCatching { transport?.close() }
        transport = null
        if (_state.value !is TunnelState.Failed) {
            _state.value = TunnelState.Stopped(reason)
        }
    }

    private suspend fun supervise() {
        var attempt = 0
        var reason: String? = null

        while (running && currentCoroutineContext().isActive) {
            val cfg = config ?: return
            _state.value = if (attempt == 0) {
                TunnelState.Connecting(1, cfg.describe())
            } else {
                TunnelState.Reconnecting(attempt + 1, 0, reason)
            }

            var current: SshTransport? = null
            var failure: SshException? = null

            try {
                current = transportFactory(cfg)
                withContext(ioDispatcher) { current.connect() }
                transport = current
                _state.value = TunnelState.Connected(
                    sinceEpochMs = System.currentTimeMillis(),
                    target = cfg.describe(),
                    serverVersion = current.serverVersion,
                )
                startForwards(current)
                attempt = 0

                // Stay here while the session is healthy.
                while (running && current.isConnected) {
                    delay(pollIntervalMs)
                }
                if (running) {
                    reason = "SSH 连接已断开"
                    emit("SSH 连接已断开，准备重连")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SshException) {
                failure = e
                reason = e.message
                emit("连接失败：${e.message}")
            } catch (e: Throwable) {
                reason = e.message ?: e.toString()
                emit("连接异常：$reason")
            } finally {
                stopForwards()
                runCatching { current?.close() }
                if (transport === current) transport = null
            }

            if (!running) break

            // Auth failures and host-key changes never fix themselves by retrying;
            // a bounded number of attempts also stops a deterministic bug from
            // spinning forever.
            val sshFailure = failure
            if ((sshFailure != null && !sshFailure.isRetryable) || attempt >= MAX_ATTEMPTS) {
                val kind = sshFailure?.kind ?: SshFailureKind.UNKNOWN
                val text = if (sshFailure != null) {
                    reason ?: "连接失败"
                } else {
                    "已重试 $MAX_ATTEMPTS 次仍未成功：${reason ?: "未知原因"}"
                }
                _state.value = TunnelState.Failed(text, kind)
                running = false
                emit("已停止自动重连：$text")
                break
            }

            attempt++
            val delayMs = backoff.delayFor(attempt)
            _state.value = TunnelState.Reconnecting(attempt, delayMs, reason)
            emit("${delayMs / 1000} 秒后重连（第 $attempt 次）")
            delay(delayMs)
        }

        if (_state.value !is TunnelState.Failed) {
            _state.value = TunnelState.Stopped(reason)
        }
    }

    private fun startForwards(session: SshTransport) {
        servers.clear()
        val statuses = rules.filter { it.enabled }.map { rule ->
            val server = LocalForwardServer(
                rule = rule,
                connector = session,
                scope = scope,
                idleTimeoutMs = idleTimeoutMs,
            )
            try {
                server.start()
                servers[rule.id] = server
                emit("已监听 127.0.0.1:${server.boundPort} → ${rule.remoteHost}:${rule.remotePort}")
                ForwardStatus(rule, running = true, boundPort = server.boundPort)
            } catch (e: Exception) {
                emit("本地端口 ${rule.localPort} 监听失败：${e.message}")
                ForwardStatus(rule, running = false, boundPort = rule.localPort, error = e.message ?: "监听失败")
            }
        }
        _forwards.value = statuses
        startStatsMirror()
    }

    private fun stopForwards() {
        statsJob?.cancel()
        statsJob = null
        servers.values.forEach { runCatching { it.stop("隧道停止") } }
        servers.clear()
        _forwards.update { list -> list.map { it.copy(running = false, stats = ForwardStats()) } }
    }

    private fun startStatsMirror() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_POLL_MS)
                _forwards.update { list ->
                    list.map { status ->
                        val server = servers[status.rule.id] ?: return@map status
                        status.copy(
                            running = server.isRunning.value,
                            boundPort = server.boundPort,
                            stats = server.stats.value,
                        )
                    }
                }
            }
        }
    }

    private fun emit(message: String) {
        _log.tryEmit(message)
    }

    private companion object {
        const val STATS_POLL_MS = 500L
        const val MAX_ATTEMPTS = 20
    }
}
