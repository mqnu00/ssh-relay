package dev.sshrelay.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import dev.sshrelay.forward.RemoteChannel
import java.io.InputStream
import java.io.OutputStream

/**
 * [SshTransport] on top of mwiede/jsch.
 *
 * Chosen over sshj / Apache MINA because it is pure Java with no transitive
 * dependencies and, crucially, ships no native libraries — Android 15 requires
 * every bundled `.so` to be 16 KB-page aligned, and having none sidesteps that
 * entirely.
 */
class JschTransport(
    private val config: SshConfig,
    private val hostKeyPolicy: TofuHostKeyPolicy,
    private val log: (String) -> Unit = {},
) : SshTransport {

    @Volatile
    private var session: Session? = null

    @Volatile
    private var decision: HostKeyDecision? = null

    override val isConnected: Boolean
        get() = session?.isConnected == true

    override val hostKeyDecision: HostKeyDecision?
        get() = decision

    override val serverVersion: String?
        get() = runCatching { session?.serverVersion }.getOrNull()

    override fun connect() {
        config.validate()?.let { throw SshException(it, SshFailureKind.PROTOCOL) }

        val jsch = JSch()
        jsch.hostKeyRepository = TofuHostKeyRepository(config.port, hostKeyPolicy) { d ->
            decision = d
            when (d) {
                is HostKeyDecision.FirstUse ->
                    log("首次连接该主机，已按 TOFU 记录主机密钥 ${d.key.keyType} ${d.key.fingerprint}")
                is HostKeyDecision.Trusted ->
                    log("主机密钥校验通过：${d.key.fingerprint}")
                is HostKeyDecision.Changed ->
                    log("⚠ 主机密钥已变更（可能是中间人攻击）！期望 ${d.expected.fingerprint}，实际 ${d.presented.fingerprint}")
            }
        }

        // Every JSch call below can throw JSchException (getSession, addIdentity and
        // even setServerAliveInterval), so they all live inside one try.
        var created: Session? = null
        try {
            val client = jsch.getSession(config.username, config.host, config.port)
            created = client

            // "yes" makes JSch delegate every trust decision to our TOFU repository.
            client.setConfig("StrictHostKeyChecking", "yes")
            when (val auth = config.auth) {
                is AuthMethod.Password -> {
                    client.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                    client.setPassword(auth.password)
                }

                is AuthMethod.PrivateKey -> {
                    client.setConfig("PreferredAuthentications", "publickey")
                    val passphrase = auth.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray()
                    // Parse the key up front. Otherwise a bad passphrase makes JSch
                    // offer no identity at all and the user is told "Auth fail" —
                    // which sends them looking at the wrong problem entirely.
                    val keyType = validatePrivateKey(jsch, auth.privateKeyPem, passphrase)
                    log("使用私钥认证（$keyType${if (passphrase != null) "，已加密" else ""}）")
                    jsch.addIdentity(
                        auth.nameHint,
                        auth.privateKeyPem.toByteArray(Charsets.UTF_8),
                        null,
                        passphrase,
                    )
                }
            }

            // Keepalive: without it a NAT/firewall silently eats an idle tunnel and
            // the browser only finds out when it hangs.
            client.setServerAliveInterval(config.keepAliveIntervalMs)
            client.setServerAliveCountMax(config.keepAliveCountMax)

            client.connect(config.connectTimeoutMs)
            session = client
        } catch (e: JSchException) {
            runCatching { created?.disconnect() }
            val kind = SshFailureClassifier.classify(e.message)
            throw SshException(describe(kind, e.message), kind, e)
        }

        log("SSH 已连接：${config.describe()}（服务端 ${serverVersion ?: "未知"}）")
    }

    /**
     * Parses a pasted private key and unlocks it, so that a bad passphrase or an
     * unsupported format is reported precisely instead of surfacing later as a
     * generic authentication failure.
     *
     * @return the key type (`ssh-ed25519`, `ssh-rsa`, ...) for the log.
     */
    private fun validatePrivateKey(jsch: JSch, pem: String, passphrase: ByteArray?): String {
        val keyPair = try {
            KeyPair.load(jsch, pem.toByteArray(Charsets.UTF_8), null)
        } catch (e: JSchException) {
            throw SshException(
                "私钥无法解析：${e.message}（支持 OpenSSH 与 PEM 格式）",
                SshFailureKind.PROTOCOL,
                e,
            )
        }
        // For an unencrypted key decrypt() is a no-op that returns true.
        val unlocked = runCatching { keyPair.decrypt(passphrase ?: ByteArray(0)) }.getOrDefault(false)
        if (!unlocked) {
            throw SshException(
                "私钥口令不正确，无法解密私钥（若该私钥没有口令，请清空口令输入框）",
                SshFailureKind.PROTOCOL,
            )
        }
        return runCatching { keyPair.keyTypeString }.getOrDefault("unknown")
    }

    override fun open(
        remoteHost: String,
        remotePort: Int,
        originHost: String,
        originPort: Int,
    ): RemoteChannel {
        val active = session?.takeIf { it.isConnected }
            ?: throw SshException("SSH 会话未连接", SshFailureKind.UNREACHABLE)

        val channel = try {
            active.openChannel("direct-tcpip") as ChannelDirectTCPIP
        } catch (e: JSchException) {
            val kind = SshFailureClassifier.classify(e.message)
            throw SshException("无法打开 direct-tcpip 通道：${e.message}", kind, e)
        }

        // All four must be set before connect(): JSch builds the channel-open
        // payload from them, and the server logs originHost:originPort for auditing.
        channel.setHost(remoteHost)
        channel.setPort(remotePort)
        channel.setOrgIPAddress(originHost)
        channel.setOrgPort(originPort)

        try {
            channel.connect(config.channelConnectTimeoutMs)
        } catch (e: JSchException) {
            runCatching { channel.disconnect() }
            val kind = SshFailureClassifier.classify(e.message)
            throw SshException("转发到 $remoteHost:$remotePort 失败：${e.message}", kind, e)
        }

        return object : RemoteChannel {
            override val input: InputStream = channel.inputStream
            override val output: OutputStream = channel.outputStream
            override fun close() {
                runCatching { channel.disconnect() }
            }
        }
    }

    override fun close() {
        val current = session
        session = null
        runCatching { current?.disconnect() }
    }

    private fun describe(kind: SshFailureKind, raw: String?): String = when (kind) {
        SshFailureKind.AUTH -> "认证失败：用户名、密码或私钥被拒绝（${raw.orEmpty()}）"
        SshFailureKind.UNREACHABLE -> "无法连接 ${config.host}:${config.port}：网络不可达或超时（${raw.orEmpty()}）"
        SshFailureKind.HOST_KEY_CHANGED ->
            "主机密钥与已记录的指纹不一致，连接已被拒绝（${raw.orEmpty()}）。请在“已知主机”中确认后删除旧记录。"
        SshFailureKind.PROTOCOL -> "协议/配置错误：${raw.orEmpty()}"
        SshFailureKind.UNKNOWN -> "SSH 连接失败：${raw.orEmpty()}"
    }
}
