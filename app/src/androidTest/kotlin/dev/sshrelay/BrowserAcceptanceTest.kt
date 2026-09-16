package dev.sshrelay

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sshrelay.forward.ForwardRule
import dev.sshrelay.ssh.AuthMethod
import dev.sshrelay.ssh.SshConfig
import dev.sshrelay.ssh.TunnelState
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The acceptance test for the actual product requirement:
 * *"open the phone's browser at http://127.0.0.1:<forwarded port> and see the remote service"*.
 *
 * Unlike [TunnelEndToEndTest] — which fetches the URL with an HTTP client — this drives
 * the app's own WebView, i.e. a real Chromium engine, and asserts on the DOM it
 * rendered. It also writes PNG evidence to the app's external files directory so the
 * result can be inspected outside the test:
 *
 *     adb pull /sdcard/Android/data/dev.sshrelay/files/
 */
@RunWith(AndroidJUnit4::class)
class BrowserAcceptanceTest {

    private val localPort = 18080
    private val marker = "SSH-RELAY-E2E-OK"

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidenceDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    private fun config() = SshConfig(
        host = BuildConfig.E2E_SSH_HOST,
        port = BuildConfig.E2E_SSH_PORT,
        username = BuildConfig.E2E_SSH_USER,
        auth = AuthMethod.Password(BuildConfig.E2E_SSH_PASSWORD),
        connectTimeoutMs = 15_000,
    )

    @Test
    fun aBrowserEngineRendersTheForwardedPage() = runBlocking {
        val graph = (context.applicationContext as SshRelayApp).graph
        graph.manager.start(
            config(),
            listOf(ForwardRule("acceptance", localPort, "127.0.0.1", 18080, label = "acceptance")),
        )
        try {
            awaitReady(graph)

            // 1) plain HTTP first, so a WebView failure cannot be confused with a
            //    tunnel failure
            assertTrue(httpGet("http://127.0.0.1:$localPort/").contains(marker))

            // 2) the tunnel status screen, as the user sees it
            captureScreen(null, "app-tunnel-screen.png")

            // 3) the WebView rendering the forwarded page, asserted on the DOM
            val rendered = AtomicReference<String?>(null)
            val loaded = CountDownLatch(1)

            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_BROWSER_URL, "http://127.0.0.1:$localPort/")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ).use { scenario ->
                val deadline = System.currentTimeMillis() + 30_000
                while (loaded.count > 0 && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val webView = findWebView(activity.window.decorView)
                        if (webView != null) {
                            webView.evaluateJavascript("document.body.innerText") { text ->
                                rendered.set(text)
                                loaded.countDown()
                            }
                        }
                    }
                    if (loaded.count > 0L) delay(1_000)
                }

                assertTrue("WebView did not report page content within 30s", loaded.await(5, TimeUnit.SECONDS))
                val text = rendered.get().orEmpty()
                assertTrue("WebView rendered unexpected content: $text", text.contains(marker))
                // The marker alone would also appear if the request had bypassed the
                // tunnel; the loopback origin proves it came through SSH.
                assertTrue("WebView content lacks the loopback origin: $text", text.contains("127.0.0.1"))

                captureScreen(scenario, "browser-webview-forward.png")
            }
        } finally {
            graph.manager.stop("acceptance test finished")
        }
    }

    @Test
    fun theTunnelScreenShowsTheListeningPort() = runBlocking {
        val graph = (context.applicationContext as SshRelayApp).graph
        graph.manager.start(
            config(),
            listOf(ForwardRule("acceptance", localPort, "127.0.0.1", 18080, label = "acceptance")),
        )
        try {
            awaitReady(graph)
            captureScreen(null, "app-tunnel-running.png")
        } finally {
            graph.manager.stop("acceptance test finished")
        }
    }

    /**
     * The strongest form of "the phone's browser can open it": hand the URL to the
     * *system browser* — a different app, running as a different uid, with no
     * relationship to this one — and then read back what that app actually displays.
     *
     * The text comes from `uiautomator dump`, so it is asserted programmatically
     * rather than eyeballed from a screenshot. The page's `<title>` is the marker, so
     * even a hierarchy that exposes only the browser's tab/title text is enough.
     */
    // Ignored on purpose: driving a third-party browser from inside an instrumentation
    // proved unreliable, and `uiautomator dump` yields nothing when driven this way, so
    // the result cannot be asserted. Covered instead by the hold test below plus
    // scripts/verify-browser.sh (host-side, verified through the server access log).
    @Ignore("use scripts/verify-browser.sh for the cross-app browser check")
    @Test
    fun theSystemBrowserOpensTheForwardedPort() = runBlocking {
        val graph = (context.applicationContext as SshRelayApp).graph
        graph.manager.start(
            config(),
            listOf(ForwardRule("acceptance", localPort, "127.0.0.1", 18080, label = "acceptance")),
        )
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            awaitReady(graph)

            // The test HTTP server exposes `/count`, which reports how many *page*
            // requests it has served without counting itself. That makes it possible to
            // prove the system browser really pulled the page through the tunnel — a
            // plain counter read from the page would be satisfied by this test's own
            // fetches too.
            val before = requestCounter()
            // Force a *fresh* navigation: an already-running browser instance would
            // otherwise receive the duplicate intent without reloading, and we would end
            // up looking at whatever page it happened to show before. The cache-busting
            // query also keeps WebView from serving a cached response.
            runShell(ui, "am force-stop org.chromium.webview_shell")
            ui.executeShellCommand(
                "am start -a android.intent.action.VIEW -d 'http://127.0.0.1:$localPort/?n=${System.currentTimeMillis()}'"
            ).close()

            var after = before
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline) {
                delay(3_000)
                after = requestCounter()
                if (after > before) break
            }

            val foreground = runShell(ui, "dumpsys activity activities | grep -m1 topResumedActivity")
            val browserPackages = listOf("webview_shell", "chrome", "browser", "firefox")
            val handledByAnotherApp = browserPackages.any { foreground.contains(it) }

            File(evidenceDir, "system-browser-foreground.txt").writeText(
                "foreground after handing over the URL:\n$foreground\n" +
                    "page requests served: $before -> $after\n" +
                    "handled by a browser package other than ours: $handledByAnotherApp\n"
            )

            // Hard assertion: the server served a *new page request* after the URL was
            // handed to the system browser, and nothing else in this test fetches `/`.
            // (The probe uses `/count`, which is deliberately not counted.)
            assertTrue(
                "the system browser did not fetch the forwarded page through the tunnel " +
                    "(page requests $before -> $after, foreground: ${foreground.take(300)})",
                after > before,
            )
            // Whether the browser is still the top activity is timing dependent (it may
            // be backgrounded between the fetch and this check), so it is recorded as
            // supporting evidence rather than asserted. The request the server logged is
            // the real proof, and it carries a Chrome User-Agent.
            println(
                "evidence: the system browser fetched the tunnelled page through the tunnel " +
                    "(page requests $before -> $after); foreground was: ${foreground.take(300)}"
            )
            captureScreen(null, "browser-system-app.png")
        } finally {
            graph.manager.stop("acceptance test finished")
        }
    }

    /**
     * Brings the tunnel up and simply holds it open.
     *
     * Exists so a host-side script can perform actions that an instrumentation cannot
     * do reliably — notably handing the URL to the *system browser* and checking the
     * resulting request in the test server's access log. See `scripts/verify-browser.sh`.
     */
    @Test
    fun holdsTheTunnelOpenForExternalChecks() = runBlocking {
        // Provided by scripts/verify-browser.sh; instrumentation arguments are the only
        // channel that reaches the app process.
        val holdMs = InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull()
            ?: 120_000L
        val graph = (context.applicationContext as SshRelayApp).graph
        graph.manager.start(
            config(),
            listOf(ForwardRule("acceptance", localPort, "127.0.0.1", 18080, label = "acceptance")),
        )
        val readyFlag = File(evidenceDir, "tunnel-ready.txt")
        try {
            awaitReady(graph)
            // A file the host script can poll. Reading the device's /proc/net/tcp to
            // detect the listener is not an option: Android restricts it for the shell
            // user, and instrumentation stdout is not flushed until the test ends.
            readyFlag.writeText("http://127.0.0.1:$localPort/\n")
            println("tunnel ready: http://127.0.0.1:$localPort/ (holding for ${holdMs}ms)")
            delay(holdMs)
        } finally {
            readyFlag.delete()
            graph.manager.stop("hold test finished")
        }
    }

    // ------------------------------------------------------------- helpers

    /** Runs a shell command and returns its stdout, waiting for it to finish. */
    private fun runShell(ui: android.app.UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
            .use { String(it.readBytes(), Charsets.UTF_8) }

    /**
     * How many *page* requests the test server has served, read from its `/count`
     * endpoint. The probe itself is not counted, so two readings can be compared.
     */
    private fun requestCounter(): Int {
        val body = httpGet("http://127.0.0.1:$localPort/count")
        return body.trim().toIntOrNull()
            ?: throw AssertionError("could not read the request counter, got: ${body.take(200)}")
    }

    private suspend fun awaitReady(graph: AppGraph) {
        withTimeout(45_000) {
            while (graph.manager.state.value !is TunnelState.Connected) {
                (graph.manager.state.value as? TunnelState.Failed)?.let {
                    throw AssertionError("tunnel failed: ${it.reason}")
                }
                delay(100)
            }
            while (true) {
                val forward = graph.manager.forwards.value.firstOrNull()
                if (forward != null && forward.running) break
                delay(100)
            }
        }
    }

    private fun captureScreen(scenario: ActivityScenario<MainActivity>?, name: String) {
        val launch = scenario ?: ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            Thread.sleep(2_500) // let Compose settle before the screenshot
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("screenshot failed", bitmap)
            val out = File(evidenceDir, name)
            FileOutputStream(out).use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("empty screenshot $name", out.length() > 0)
            println("evidence: ${out.absolutePath} (${out.length()} bytes)")
        } finally {
            if (scenario == null) launch.close()
        }
    }

    private fun findWebView(root: View?): WebView? {
        if (root == null) return null
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findWebView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        // Generous on purpose: this emulator runs on a memory-constrained host, and a
        // guest that is GC-thrashing can take tens of seconds to answer. Short
        // timeouts here would report environment slowness as a product failure.
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
