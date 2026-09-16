package dev.sshrelay.ssh

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider

/** Grabs a port the OS says is free. Used by tests that must know the port up front. */
internal fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

/**
 * A real SSH server (Apache MINA SSHD) living inside the test JVM.
 *
 * This is what makes SSH coverage possible on a machine with no device and no
 * remote host: the client under test speaks real SSH to a real server, and the
 * only thing simulated is the *location* of the far end.
 */
internal class EmbeddedSshServer(
    val username: String = "tester",
    val password: String = "testpass",
    /** Reuse the same host key across restarts so TOFU tests can compare fingerprints. */
    val hostKeyPath: Path = Files.createTempDirectory("sshrelay-hostkey").resolve("hostkey.ser"),
) : AutoCloseable {

    val port: Int = freePort()
    private val server: SshServer = SshServer.setUpDefaultServer()

    init {
        server.host = "127.0.0.1"
        server.port = port
        server.keyPairProvider = SimpleGeneratorHostKeyProvider().apply {
            path = hostKeyPath
            // RSA keeps the client-side algorithm matrix simple and is what most
            // real-world servers still present.
            algorithm = "RSA"
        }
        server.passwordAuthenticator = PasswordAuthenticator { user, pass, _ ->
            user == username && pass == password
        }
        // Accept any client key: these tests are about the client's key handling
        // (OpenSSH format, bcrypt passphrases), not about authorization.
        server.publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
        server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        server.start()
    }

    override fun close() {
        runCatching { server.stop(true) }
    }
}

/** Tiny HTTP server standing in for "the service behind the company firewall". */
internal class TestHttpServer(
    private val path: String = "/",
    private val body: String = DEFAULT_BODY,
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val port: Int get() = server.address.port

    init {
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()
    }

    override fun close() {
        runCatching { server.stop(0) }
    }

    companion object {
        /** Distinctive marker so assertions cannot pass on some unrelated page. */
        const val DEFAULT_BODY = "<html><body>SSH-RELAY-E2E-OK</body></html>"
    }
}
