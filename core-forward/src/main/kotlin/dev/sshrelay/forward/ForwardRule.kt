package dev.sshrelay.forward

/**
 * One `-L` style rule: listen on the *device's own* loopback at [localPort] and
 * tunnel every accepted connection to [remoteHost]:[remotePort] as seen *from
 * the SSH server*.
 */
data class ForwardRule(
    val id: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val label: String = "",
    val enabled: Boolean = true,
) {
    val displayName: String
        get() = label.ifBlank { "${remoteHost}:$remotePort" }

    /** `127.0.0.1:8080 -> db.internal:5432` — used in the UI and the notification. */
    fun describe(): String = "$localPort -> $remoteHost:$remotePort"
}
