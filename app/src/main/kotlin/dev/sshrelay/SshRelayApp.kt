package dev.sshrelay

import android.app.Application
import android.content.Context
import dev.sshrelay.data.FileHostKeyStore
import dev.sshrelay.data.SecretStore
import dev.sshrelay.data.SettingsStore
import dev.sshrelay.ssh.JschTransport
import dev.sshrelay.ssh.TofuHostKeyPolicy
import dev.sshrelay.ssh.TunnelManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide object graph. Hand-wired instead of Hilt: on a first build in a
 * fresh, bandwidth-limited environment, one fewer annotation processor (KSP) is
 * one fewer thing that can fail.
 */
class AppGraph(context: Context) {

    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(appContext)
    val secretStore = SecretStore(appContext)
    val hostKeyStore = FileHostKeyStore(File(appContext.filesDir, "known_hosts.json"))
    val hostKeyPolicy = TofuHostKeyPolicy(hostKeyStore)

    /** SSH-layer messages, surfaced in the in-app log console next to tunnel events. */
    private val _sshLog = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 256)
    val sshLog: SharedFlow<String> = _sshLog.asSharedFlow()

    val manager = TunnelManager(
        scope = appScope,
        transportFactory = { config ->
            JschTransport(config, hostKeyPolicy) { line -> _sshLog.tryEmit(line) }
        },
    )

    val controller = TunnelController(appContext, this)
}

class SshRelayApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
