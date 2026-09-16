package dev.sshrelay.ssh

import dev.sshrelay.forward.RemoteConnector
import java.io.Closeable

/**
 * An authenticated SSH connection that can open `direct-tcpip` channels.
 *
 * The forwarding engine depends on this interface, not on JSch, so the transport
 * can be swapped (sshj / Apache MINA) without touching [dev.sshrelay.forward].
 */
interface SshTransport : RemoteConnector, Closeable {

    /** Blocking. Throws [SshException] with a classified [SshFailureKind]. */
    fun connect()

    val isConnected: Boolean

    /** Host key trust decision made during the last [connect], for the UI. */
    val hostKeyDecision: HostKeyDecision?

    /** Remote banner, e.g. `SSH-2.0-OpenSSH_9.6` — useful in the log panel. */
    val serverVersion: String?
}
