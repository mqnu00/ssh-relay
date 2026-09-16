package dev.sshrelay.ssh

/** How we authenticate to the SSH server. */
sealed interface AuthMethod {
    /** Interactive-password style authentication. */
    data class Password(val password: String) : AuthMethod

    /**
     * Public-key authentication. [privateKeyPem] is the *contents* of the key
     * file (OpenSSH `-----BEGIN OPENSSH PRIVATE KEY-----` or PEM/PKCS#8).
     */
    data class PrivateKey(
        val privateKeyPem: String,
        val passphrase: String? = null,
        val nameHint: String = "relay-key",
    ) : AuthMethod
}

/**
 * Everything needed to reach the SSH server. Credentials are supplied by the
 * caller (the app reads them out of Keystore-encrypted storage) and never logged.
 */
data class SshConfig(
    val host: String,
    val username: String,
    val auth: AuthMethod,
    val port: Int = DEFAULT_PORT,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    /** Idle keepalive so a NAT/firewall does not silently eat the tunnel. */
    val keepAliveIntervalMs: Int = DEFAULT_KEEPALIVE_MS,
    val keepAliveCountMax: Int = DEFAULT_KEEPALIVE_COUNT_MAX,
    /** Timeout for opening an individual forwarded channel. */
    val channelConnectTimeoutMs: Int = DEFAULT_CHANNEL_TIMEOUT_MS,
) {
    fun validate(): String? = when {
        host.isBlank() -> "SSH 主机不能为空"
        username.isBlank() -> "SSH 用户名不能为空"
        port !in 1..65535 -> "SSH 端口必须在 1-65535 之间"
        auth is AuthMethod.Password && auth.password.isEmpty() -> "密码不能为空"
        auth is AuthMethod.PrivateKey && auth.privateKeyPem.isBlank() -> "私钥内容不能为空"
        else -> null
    }

    /** Never leaks the credential: safe for logs, notifications and UI headers. */
    fun describe(): String = "$username@$host:$port"

    companion object {
        const val DEFAULT_PORT = 22
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_KEEPALIVE_MS = 30_000
        const val DEFAULT_KEEPALIVE_COUNT_MAX = 3
        const val DEFAULT_CHANNEL_TIMEOUT_MS = 15_000
    }
}
