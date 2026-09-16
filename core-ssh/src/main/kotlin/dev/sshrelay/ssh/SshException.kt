package dev.sshrelay.ssh

/** Why a connection attempt failed, in terms a user can act on. */
enum class SshFailureKind {
    /** Wrong password / key rejected. Never retry blindly. */
    AUTH,

    /** DNS, refused, no route, timeout. Worth retrying. */
    UNREACHABLE,

    /** The server's key is not the one we pinned: hard stop, potential MITM. */
    HOST_KEY_CHANGED,

    /** Bad configuration or a protocol-level disagreement (algorithms, key format). */
    PROTOCOL,

    UNKNOWN,
}

open class SshException(
    message: String,
    val kind: SshFailureKind = SshFailureKind.UNKNOWN,
    cause: Throwable? = null,
) : Exception(message, cause)

/** True for failures where an automatic reconnect loop makes sense. */
val SshException.isRetryable: Boolean
    get() = kind == SshFailureKind.UNREACHABLE || kind == SshFailureKind.UNKNOWN

internal object SshFailureClassifier {
    /**
     * Maps raw JSch/IO failure text onto [SshFailureKind]. JSch throws
     * `JSchException` with a message only, so message matching is the only signal
     * available — hence the deliberately broad patterns.
     */
    fun classify(message: String?): SshFailureKind {
        val m = message?.lowercase().orEmpty()
        return when {
            m.contains("hostkey has been changed") || m.contains("host key has been changed") ->
                SshFailureKind.HOST_KEY_CHANGED
            m.contains("auth fail") || m.contains("auth cancel") || m.contains("auth cancel") ||
                m.contains("userauth fail") || m.contains("publickey") && m.contains("fail") ||
                m.contains("identities_only") ->
                SshFailureKind.AUTH
            m.contains("connection refused") || m.contains("connectexception") ||
                m.contains("unknownhost") || m.contains("noroutetohost") ||
                m.contains("no route") || m.contains("timed out") || m.contains("timeout") ||
                m.contains("socket is not established") || m.contains("connection reset") ->
                SshFailureKind.UNREACHABLE
            m.contains("algorithm") || m.contains("invalid key") || m.contains("unsupported") ||
                m.contains("invalidprivatekey") || m.contains("unsupported key") ||
                m.contains("reject") ->
                SshFailureKind.PROTOCOL
            else -> SshFailureKind.UNKNOWN
        }
    }
}
