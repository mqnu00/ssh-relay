package dev.sshrelay.testsshd

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider

/**
 * A throwaway SSH server plus an HTTP target, both in user space.
 *
 * This box has no root and therefore cannot install `openssh-server`, so the
 * "remote host" that the phone tunnels into is this process instead. It also
 * doubles as the acceptance target: the HTTP page it serves proves the request
 * really travelled through the SSH tunnel.
 *
 * Layout:
 *   0.0.0.0:2222  SSH server (password auth)  <- the phone connects here
 *   127.0.0.1:18080  HTTP target              <- reached only via the SSH tunnel
 *
 * From an emulator running on this same machine, the host is `10.0.2.2`; from a
 * real device on the same LAN, use this machine's LAN address.
 */
private const val DEFAULT_SSH_PORT = 2222
private const val DEFAULT_HTTP_PORT = 18080
private const val MARKER = "SSH-RELAY-E2E-OK"

fun main() {
    val sshPort = System.getenv("SSH_PORT")?.toIntOrNull() ?: DEFAULT_SSH_PORT
    val httpPort = System.getenv("HTTP_PORT")?.toIntOrNull() ?: DEFAULT_HTTP_PORT
    val user = System.getenv("SSH_USER") ?: "tester"
    val pass = System.getenv("SSH_PASSWORD") ?: "testpass"
    val sshHost = System.getenv("SSH_BIND") ?: "0.0.0.0"
    val keyPath: Path = Path.of(System.getenv("HOST_KEY_PATH") ?: "build/test-sshd/hostkey.ser")

    val requests = AtomicLong(0)
    val http = startHttpTarget(httpPort, requests)
    val sshd = startSshServer(sshHost, sshPort, user, pass, keyPath)

    println()
    println("=".repeat(72))
    println(" test SSH server ready")
    println("   SSH   : $sshHost:$sshPort    user=$user  password=$pass")
    println("   HTTP  : 127.0.0.1:$httpPort  (reachable only through the tunnel)")
    println("   marker: $MARKER")
    println("   from an emulator, connect the app to 10.0.2.2:$sshPort")
    println("=".repeat(72))
    println()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { sshd.stop(true) }
            runCatching { http.stop(0) }
        }
    )

    Thread.currentThread().join()
}

private fun startHttpTarget(port: Int, requests: AtomicLong): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { exchange ->
        val uri = exchange.requestURI.toString()

        // A probe that answers with the current request count *without* counting
        // itself. Without this, a test cannot tell "the browser fetched the page"
        // apart from "the test fetched the page twice" — both just move the counter.
        if (uri.startsWith("/count")) {
            val body = requests.get().toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            return@createContext
        }

        val count = requests.incrementAndGet()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val peer = exchange.remoteAddress?.toString() ?: "unknown"
        val userAgent = exchange.requestHeaders.getFirst("User-Agent") ?: "unknown"
        println("http request #$count  uri=$uri  peer=$peer  ua=$userAgent")
        val body = """
            <!doctype html>
            <html lang="zh">
            <head><meta charset="utf-8"><title>$MARKER</title>
            <style>
              body{font-family:system-ui,sans-serif;margin:0;padding:32px;background:#0f172a;color:#e2e8f0}
              h1{color:#7FD1AE;margin:0 0 8px;font-size:28px}
              .badge{display:inline-block;background:#1B5E9B;padding:4px 10px;border-radius:4px;font-size:13px}
              table{border-collapse:collapse;margin-top:20px}
              td{padding:6px 14px 6px 0;font-size:14px;border-bottom:1px solid #1e293b}
              td:first-child{color:#94a3b8}
              code{font-family:ui-monospace,Menlo,monospace;color:#fbbf24}
            </style></head>
            <body>
              <div class="badge">通过 SSH 隧道访问成功</div>
              <h1>$MARKER</h1>
              <p>这个页面由 WSL 宿主机上的测试 HTTP 服务提供，<b>只有</b>经由 SSH 端口转发才能到达。</p>
              <table>
                <tr><td>请求路径</td><td><code>$uri</code></td></tr>
                <tr><td>第几次请求</td><td><code>$count</code></td></tr>
                <tr><td>服务端时间</td><td><code>$now</code></td></tr>
                <tr><td>HTTP 服务看到的来源</td><td><code>$peer</code></td></tr>
              </table>
              <p style="color:#94a3b8;font-size:13px">
                来源地址显示为 127.0.0.1，说明连接是由 SSH 服务端在本机发起的，
                即请求确实穿过了隧道，而不是手机直接连到了远端。
              </p>
            </body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.executor = null
    server.start()
    return server
}

private fun startSshServer(
    host: String,
    port: Int,
    user: String,
    password: String,
    keyPath: Path,
): SshServer {
    keyPath.parent?.let { Files.createDirectories(it) }
    val server = SshServer.setUpDefaultServer()
    server.host = host
    server.port = port
    server.keyPairProvider = SimpleGeneratorHostKeyProvider().apply {
        path = keyPath
        algorithm = "RSA"
    }
    server.passwordAuthenticator = PasswordAuthenticator { username, pass, _ ->
        username == user && pass == password
    }
    // Throwaway server, so any key is accepted: this lets the *standard* OpenSSH
    // client (and the app's private-key auth path) be exercised against it without
    // distributing an authorized_keys file.
    server.publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
    // `-L` requires the direct-tcpip channel to be permitted; MINA rejects all
    // forwarding by default.
    server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
    server.start()
    return server
}
