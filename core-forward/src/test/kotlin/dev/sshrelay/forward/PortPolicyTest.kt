package dev.sshrelay.forward

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortPolicyTest {

    @Test
    fun `local port below 1024 is rejected with an actionable message`() {
        // Android refuses to bind privileged ports for a normal app, so this must
        // fail in the UI rather than at runtime.
        val result = PortPolicy.validateLocalPort(80)
        assertTrue(result is PortPolicy.Validation.Invalid)
        assertTrue(result.errorOrNull()!!.contains("1024"))
    }

    @Test
    fun `local port 1024 and 65535 are accepted`() {
        assertTrue(PortPolicy.validateLocalPort(1024).isOk())
        assertTrue(PortPolicy.validateLocalPort(65535).isOk())
    }

    @Test
    fun `local port above 65535 is rejected`() {
        assertTrue(PortPolicy.validateLocalPort(65536) is PortPolicy.Validation.Invalid)
    }

    @Test
    fun `remote port must be 1 to 65535`() {
        assertTrue(PortPolicy.validateRemotePort(0) is PortPolicy.Validation.Invalid)
        assertTrue(PortPolicy.validateRemotePort(1).isOk())
        assertTrue(PortPolicy.validateRemotePort(65535).isOk())
        assertTrue(PortPolicy.validateRemotePort(70000) is PortPolicy.Validation.Invalid)
    }

    @Test
    fun `remote host rejects blank and whitespace`() {
        assertTrue(PortPolicy.validateRemoteHost("") is PortPolicy.Validation.Invalid)
        assertTrue(PortPolicy.validateRemoteHost("   ") is PortPolicy.Validation.Invalid)
        assertTrue(PortPolicy.validateRemoteHost("my host") is PortPolicy.Validation.Invalid)
        assertTrue(PortPolicy.validateRemoteHost("db.internal").isOk())
        // hostnames with a port suffix are a common user mistake; they are just
        // hosts as far as we are concerned and get rejected by DNS later.
        assertTrue(PortPolicy.validateRemoteHost("10.0.0.5").isOk())
    }

    @Test
    fun `rule validation reports the first problem`() {
        val bad = ForwardRule(id = "a", localPort = 22, remoteHost = "ok", remotePort = 80)
        assertTrue(PortPolicy.validate(bad) is PortPolicy.Validation.Invalid)

        val good = ForwardRule(id = "b", localPort = 8080, remoteHost = "ok", remotePort = 80)
        assertEquals(PortPolicy.Validation.Ok, PortPolicy.validate(good))
    }

    @Test
    fun `describe renders a readable rule`() {
        val rule = ForwardRule(id = "x", localPort = 8080, remoteHost = "db.internal", remotePort = 5432)
        assertEquals("8080 -> db.internal:5432", rule.describe())
        assertEquals("db.internal:5432", rule.displayName)
    }
}
