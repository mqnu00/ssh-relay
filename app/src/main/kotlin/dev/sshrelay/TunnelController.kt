package dev.sshrelay

import android.content.Context
import android.content.Intent
import dev.sshrelay.data.AppSettings
import dev.sshrelay.data.AuthKind
import dev.sshrelay.data.SecretKeys
import dev.sshrelay.forward.ForwardRule
import dev.sshrelay.forward.PortPolicy
import dev.sshrelay.service.TunnelService
import dev.sshrelay.ssh.AuthMethod
import dev.sshrelay.ssh.SshConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Turns persisted settings + Keystore secrets into a running tunnel, and owns the
 * foreground-service handshake.
 */
class TunnelController(
    private val context: Context,
    private val graph: AppGraph,
) {

    suspend fun currentSettings(): AppSettings = graph.settingsStore.settings.first()

    /**
     * Validates settings and builds the runtime config. A failure carries a
     * user-facing Chinese message, ready to drop into the UI.
     *
     * [settings] is nullable rather than defaulted to `currentSettings()`: Kotlin
     * does not allow a `suspend` call in a default parameter value.
     */
    suspend fun buildConfig(settings: AppSettings? = null): Result<Pair<SshConfig, List<ForwardRule>>> {
        val effective = settings ?: currentSettings()
        val profile = effective.profile
        // Keystore decryption is a binder round-trip to the keystore daemon; doing it
        // on the main thread while the user waits on a button press is an avoidable
        // ANR risk.
        val auth = withContext(Dispatchers.IO) {
            when (profile.authKind) {
                AuthKind.PASSWORD -> AuthMethod.Password(graph.secretStore.get(SecretKeys.PASSWORD).orEmpty())
                AuthKind.PRIVATE_KEY -> AuthMethod.PrivateKey(
                    privateKeyPem = graph.secretStore.get(SecretKeys.PRIVATE_KEY).orEmpty(),
                    passphrase = graph.secretStore.get(SecretKeys.PASSPHRASE),
                )
            }
        }
        val config = SshConfig(
            host = profile.host.trim(),
            port = profile.port,
            username = profile.username.trim(),
            auth = auth,
        )
        config.validate()?.let { return Result.failure(IllegalArgumentException(it)) }

        val rules = effective.rules.filter { it.enabled }.map { it.toForwardRule() }
        if (rules.isEmpty()) {
            return Result.failure(IllegalArgumentException("请至少启用一条转发规则"))
        }
        rules.forEach { rule ->
            val validation = PortPolicy.validate(rule)
            if (validation is PortPolicy.Validation.Invalid) {
                return Result.failure(IllegalArgumentException(validation.reason))
            }
        }
        // Two rules cannot share a local port; catching it here beats a BindException
        // on the second one after the first already started.
        rules.groupBy { it.localPort }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { port ->
            return Result.failure(IllegalArgumentException("本地端口 $port 被多条规则重复使用"))
        }

        return Result.success(config to rules)
    }

    /** Returns null when the tunnel was started, or a message explaining why not. */
    suspend fun start(): String? {
        val settings = currentSettings()
        val (config, rules) = buildConfig(settings).getOrElse {
            return it.message ?: "配置无效"
        }

        // Foreground service first: once the UI is backgrounded Android may freeze
        // the process, which would abort a handshake in progress.
        val failure = runCatching {
            context.startForegroundService(
                Intent(context, TunnelService::class.java).setAction(TunnelService.ACTION_START)
            )
        }.exceptionOrNull()
        if (failure != null) {
            return "无法启动前台服务：${failure.message}"
        }

        return runCatching { graph.manager.start(config, rules) }
            .exceptionOrNull()
            ?.let { it.message ?: "启动失败" }
    }

    fun stop(reason: String = "用户停止") {
        graph.manager.stop(reason)
        runCatching { context.stopService(Intent(context, TunnelService::class.java)) }
    }
}
