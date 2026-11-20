package dev.sshrelay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sshrelay.ui.BrowserScreen
import dev.sshrelay.ui.SshRelayTheme
import dev.sshrelay.ui.TunnelScreen
import dev.sshrelay.ui.TunnelViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 enforces edge-to-edge; the Compose UI handles the insets itself.
        enableEdgeToEdge()

        val graph = (application as SshRelayApp).graph
        val initialBrowserUrl = intent?.getStringExtra(EXTRA_BROWSER_URL)

        setContent {
            SshRelayTheme {
                val viewModel: TunnelViewModel = viewModel(factory = TunnelViewModel.factory(graph))
                SshRelayRoot(viewModel, initialBrowserUrl)
            }
        }
    }

    companion object {
        /**
         * Opens the in-app WebView preview straight away:
         *
         *   adb shell am start -n dev.sshrelay/.MainActivity \
         *       -e browser_url http://127.0.0.1:8080/
         *
         * Used by the acceptance script so the "a browser engine renders the tunnelled
         * page" evidence can be captured without hand-tapping the UI.
         */
        const val EXTRA_BROWSER_URL = "browser_url"
    }
}

@Composable
private fun SshRelayRoot(viewModel: TunnelViewModel, initialBrowserUrl: String? = null) {
    var browserUrl by remember { mutableStateOf(initialBrowserUrl) }
    // An Activity method is not in scope inside a composable; take the context.
    val context = LocalContext.current

    // The user grants the battery exemption in the system Settings app, so the state
    // can only be re-read when this app comes back to the foreground. Without this
    // the dialog/card would keep claiming "未允许" until the process restarts.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (viewModel.refreshBatteryStatus()) {
            viewModel.showMessage("已允许后台运行，系统不会再冻结隧道")
        }
    }

    val current = browserUrl
    if (current != null) {
        BrowserScreen(url = current, onClose = { browserUrl = null })
    } else {
        TunnelScreen(
            viewModel = viewModel,
            onOpenInAppBrowser = { browserUrl = it },
            onOpenInSystemBrowser = { url ->
                // Chrome and friends handle http://127.0.0.1:<port> natively; the
                // system browser is the real acceptance target for this app.
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure {
                    viewModel.showMessage("系统浏览器不可用：${it.message}")
                }
            },
        )
    }
}
