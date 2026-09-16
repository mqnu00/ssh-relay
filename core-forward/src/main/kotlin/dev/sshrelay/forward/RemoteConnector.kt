package dev.sshrelay.forward

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The byte pipe to the far side of a tunnel. Deliberately transport-agnostic so
 * that [LocalForwardServer] knows nothing about SSH (and can therefore be tested
 * against a plain socket).
 */
interface RemoteChannel : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * Opens one [RemoteChannel] per accepted browser/app connection. Backed by an SSH
 * `direct-tcpip` channel in production and by a plain [Socket] in tests.
 *
 * Implementations may block; callers always invoke this from an IO dispatcher.
 */
interface RemoteConnector {
    /**
     * @param originHost/originPort the address the local client connected *from*,
     *   forwarded to the server for its own logging and ACL decisions.
     */
    fun open(remoteHost: String, remotePort: Int, originHost: String, originPort: Int): RemoteChannel
}

/** Test/dev connector that dials the target directly, with no SSH in the middle. */
class SocketRemoteConnector(private val connectTimeoutMs: Int = 10_000) : RemoteConnector {
    override fun open(
        remoteHost: String,
        remotePort: Int,
        originHost: String,
        originPort: Int,
    ): RemoteChannel {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(remoteHost, remotePort), connectTimeoutMs)
        return object : RemoteChannel {
            override val input: InputStream = socket.getInputStream()
            override val output: OutputStream = socket.getOutputStream()
            override fun close() = socket.close()
        }
    }
}
