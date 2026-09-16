package dev.sshrelay.ssh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Private-key authentication, verified against a real SSH server in-process.
 *
 * The keys below are throwaway test material generated with
 * `ssh-keygen -t ed25519` purely for this suite (the embedded server accepts any
 * public key). They exercise JSch's handling of the OpenSSH `openssh-key-v1`
 * format — including the bcrypt-encrypted (`aes256-ctr` + `bcrypt`) form — which is
 * the modern default and the format a user is most likely to paste into the app.
 */
class PrivateKeyAuthTest {

    private lateinit var sshd: EmbeddedSshServer

    @Before
    fun setUp() {
        sshd = EmbeddedSshServer()
    }

    @After
    fun tearDown() {
        runCatching { sshd.close() }
    }

    private fun config(
        pem: String,
        passphrase: String? = null,
    ) = SshConfig(
        host = "127.0.0.1",
        port = sshd.port,
        username = sshd.username,
        auth = AuthMethod.PrivateKey(privateKeyPem = pem, passphrase = passphrase),
    )

    @Test
    fun `ed25519 key without passphrase authenticates`() {
        val transport = JschTransport(config(PLAIN_KEY), TofuHostKeyPolicy(InMemoryHostKeyStore()))
        try {
            transport.connect()
            assertTrue(transport.isConnected)
        } catch (e: SshException) {
            fail("private-key auth failed: ${e.message}")
        } finally {
            transport.close()
        }
    }

    @Test
    fun `ed25519 key with passphrase authenticates`() {
        val transport = JschTransport(
            config(PASSPHRASE_KEY, PASS_PHRASE),
            TofuHostKeyPolicy(InMemoryHostKeyStore()),
        )
        try {
            transport.connect()
            assertTrue(transport.isConnected)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `a wrong passphrase fails as an auth error, not a crash`() {
        val transport = JschTransport(
            config(PASSPHRASE_KEY, "not-the-passphrase"),
            TofuHostKeyPolicy(InMemoryHostKeyStore()),
        )
        try {
            transport.connect()
            fail("expected the wrong passphrase to fail")
        } catch (e: SshException) {
            assertEquals(SshFailureKind.PROTOCOL, e.kind)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `a missing passphrase on an encrypted key fails cleanly`() {
        val transport = JschTransport(
            config(PASSPHRASE_KEY, null),
            TofuHostKeyPolicy(InMemoryHostKeyStore()),
        )
        try {
            transport.connect()
            fail("expected an encrypted key without a passphrase to fail")
        } catch (e: SshException) {
            assertTrue(
                "expected a protocol-class failure, got ${e.kind}: ${e.message}",
                e.kind == SshFailureKind.PROTOCOL || e.kind == SshFailureKind.AUTH,
            )
        } finally {
            transport.close()
        }
    }

    @Test
    fun `a forwarded port works with private-key auth`() {
        val http = TestHttpServer()
        val transport = JschTransport(config(PLAIN_KEY), TofuHostKeyPolicy(InMemoryHostKeyStore()))
        transport.connect()
        val server = dev.sshrelay.forward.LocalForwardServer(
            rule = dev.sshrelay.forward.ForwardRule("keyauth", 0, "127.0.0.1", http.port),
            connector = transport,
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
            ),
        )
        try {
            server.start()
            java.net.Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.soTimeout = 8_000
                socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                val body = socket.getInputStream().readBytes().decodeToString()
                assertTrue("got: $body", body.contains("SSH-RELAY-E2E-OK"))
            }
        } finally {
            server.stop("test over")
            transport.close()
            http.close()
        }
    }

    private companion object {
        const val PASS_PHRASE = "relay-test-pass"

        val PLAIN_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACBcRoSyqxS9cuqonf3Q1TOjxgS8XsIzpfRiubrOlXULKAAAAJipE46pqROO
            qQAAAAtzc2gtZWQyNTUxOQAAACBcRoSyqxS9cuqonf3Q1TOjxgS8XsIzpfRiubrOlXULKA
            AAAEBwW6EXam3YdEKbyBR+34TsaQFcnQEtXTCawSiX3suTCVxGhLKrFL1y6qid/dDVM6PG
            BLxewjOl9GK5us6VdQsoAAAAFXNzaC1yZWxheS10ZXN0LW5vcGFzcw==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        val PASSPHRASE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABA7GRDDS1
            5jGK6dNos0rn86AAAAEAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAII7/688SHec394DN
            qand4z3O383qy2WToOF/NWOTwxiGAAAAoH68QKJ0DSJSV695TZa9CcHqXgKYFlsu4wSaIF
            +REu9XaQqxGcmQvQu8+xQx9JM0DBEC6WNj/KOhM4HHxtunRhRNSmI5YDjPWWmhnB+d2Owx
            bBIzQqAkZ28yQptPi3JJBZ4JwQZ5IEpjsosgzVgpOxc2jyJZ0GmslwZQDNtgUGTLzIC4nt
            exiG6vpwdx/B7/wB01+xFNdmmHs+l7Pcm2ofU=
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}
