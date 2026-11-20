package dev.sshrelay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.sshrelay.AppGraph
import dev.sshrelay.data.AppSettings
import dev.sshrelay.data.AuthKind
import dev.sshrelay.data.Profile
import dev.sshrelay.data.RuleConfig
import dev.sshrelay.data.SecretKeys
import dev.sshrelay.ssh.AuthMethod
import dev.sshrelay.ssh.ForwardStatus
import dev.sshrelay.ssh.KnownHostKey
import dev.sshrelay.ssh.RunningSettings
import dev.sshrelay.ssh.TunnelState
import dev.sshrelay.system.BatteryOptimization
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LogSource { TUNNEL, SSH }

data class LogLine(val time: String, val source: LogSource, val text: String)

/**
 * Drives the single screen. Edits are persisted with a short debounce so typing a
 * host name does not hammer DataStore, while [start] always flushes first so the
 * tunnel can never start from stale settings.
 */
class TunnelViewModel(private val graph: AppGraph) : ViewModel() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _privateKey = MutableStateFlow("")
    val privateKey: StateFlow<String> = _privateKey.asStateFlow()

    private val _passphrase = MutableStateFlow("")
    val passphrase: StateFlow<String> = _passphrase.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _knownHosts = MutableStateFlow<List<KnownHostKey>>(emptyList())
    val knownHosts: StateFlow<List<KnownHostKey>> = _knownHosts.asStateFlow()

    /**
     * Edits that are saved but not live yet. Credentials live in the Keystore rather
     * than in [AppSettings], so they are tracked by a flag instead of a comparison.
     */
    private val secretsChanged = MutableStateFlow(false)

    /**
     * The editor starts out as [AppSettings] defaults until DataStore answers, and
     * "everything changed" is not a useful thing to show in that window.
     */
    private val settingsLoaded = MutableStateFlow(false)

    val tunnelState: StateFlow<TunnelState> = graph.manager.state
    val forwards: StateFlow<List<ForwardStatus>> = graph.manager.forwards

    /** True while the tunnel runs with a configuration that differs from the editor. */
    val pendingChanges: StateFlow<Boolean> =
        combine(_settings, graph.manager.applied, secretsChanged, settingsLoaded) { settings, applied, dirty, loaded ->
            loaded && applied != null && (dirty || !settings.matches(applied))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether the system exempts this app from battery optimisation. Read from the
     * platform, never persisted: the user can revoke it in Settings at any time.
     */
    private val _batteryExempt = MutableStateFlow(BatteryOptimization.isExempt(graph.appContext))
    val batteryExempt: StateFlow<Boolean> = _batteryExempt.asStateFlow()

    private val _batteryDialogVisible = MutableStateFlow(false)
    val batteryDialogVisible: StateFlow<Boolean> = _batteryDialogVisible.asStateFlow()

    /**
     * The launch prompt is offered once per process. Otherwise every return from the
     * in-app browser (which recreates the screen) would pop it again; the "后台保活"
     * card stays available as the way back to the settings page.
     */
    private var batteryPromptOffered = false

    private var settingsSaveJob: Job? = null
    private var secretSaveJob: Job? = null

    init {
        viewModelScope.launch {
            _settings.value = graph.settingsStore.settings.first()
            // Only now is the editor's content meaningful; until then every field
            // still holds the AppSettings() defaults, which would make the tunnel
            // look "changed" for a moment on every cold start.
            settingsLoaded.value = true
        }
        viewModelScope.launch(Dispatchers.IO) {
            _password.value = graph.secretStore.get(SecretKeys.PASSWORD).orEmpty()
            _privateKey.value = graph.secretStore.get(SecretKeys.PRIVATE_KEY).orEmpty()
            _passphrase.value = graph.secretStore.get(SecretKeys.PASSPHRASE).orEmpty()
            _knownHosts.value = graph.hostKeyStore.list()
        }
        viewModelScope.launch { graph.manager.log.collect { append(LogSource.TUNNEL, it) } }
        viewModelScope.launch { graph.sshLog.collect { append(LogSource.SSH, it) } }
    }

    // ------------------------------------------------------------- editing

    fun updateProfile(transform: (Profile) -> Profile) = mutateSettings { it.copy(profile = transform(it.profile)) }

    fun updateRules(transform: (List<RuleConfig>) -> List<RuleConfig>) =
        mutateSettings { it.copy(rules = transform(it.rules)) }

    fun addRule() = updateRules { rules ->
        val used = rules.map { it.localPort }.toSet()
        val port = generateSequence(8080) { it + 1 }.first { it !in used }
        rules + RuleConfig(
            id = UUID.randomUUID().toString().take(8),
            localPort = port,
            remoteHost = "127.0.0.1",
            remotePort = 80,
            label = "",
        )
    }

    fun removeRule(id: String) = updateRules { rules -> rules.filterNot { it.id == id } }

    fun setPassword(value: String) {
        _password.value = value
        secretsChanged.value = true
        persistSecret(SecretKeys.PASSWORD, value)
    }

    fun setPrivateKey(value: String) {
        _privateKey.value = value
        secretsChanged.value = true
        persistSecret(SecretKeys.PRIVATE_KEY, value)
    }

    fun setPassphrase(value: String) {
        _passphrase.value = value
        secretsChanged.value = true
        persistSecret(SecretKeys.PASSPHRASE, value)
    }

    private fun mutateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch {
            delay(300)
            graph.settingsStore.save(next)
        }
    }

    private fun persistSecret(key: String, value: String) {
        secretSaveJob?.cancel()
        secretSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            graph.secretStore.put(key, value)
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun start() {
        viewModelScope.launch {
            flushEdits()
            val error = graph.controller.start()
            if (error != null) {
                _message.value = error
            } else {
                secretsChanged.value = false
                // Worth one log line: this is the state that turns "跑一会儿就断"
                // from a mystery into an explanation.
                if (!refreshBatteryStatus()) {
                    append(LogSource.TUNNEL, "⚠ 本应用仍受电池优化限制：切到后台可能被系统冻结而断开隧道")
                }
            }
        }
    }

    /**
     * Pushes the edits into the running tunnel. Rule-only changes are hot-swapped
     * on the live SSH session; connection or credential changes reconnect.
     */
    fun apply() {
        viewModelScope.launch {
            flushEdits()
            val error = graph.controller.apply()
            if (error != null) {
                _message.value = error
            } else {
                secretsChanged.value = false
                _message.value = "已应用当前配置"
            }
        }
    }

    fun stop() = graph.controller.stop()

    fun retry() {
        viewModelScope.launch { graph.manager.retryNow() }
    }

    // ---------------------------------------------------- battery exemption

    /**
     * Re-reads the exemption state and shows the prompt if it is missing.
     *
     * Called once from the screen; [refreshBatteryStatus] is the one to call again
     * when the app resumes, because the user may have just granted it in Settings.
     */
    fun offerBatteryExemption() {
        if (batteryPromptOffered) return
        batteryPromptOffered = true
        if (!refreshBatteryStatus()) {
            _batteryDialogVisible.value = true
        }
    }

    /**
     * Re-reads the exemption from the platform.
     *
     * @return true when the app just became exempt (so the caller can confirm it).
     */
    fun refreshBatteryStatus(): Boolean {
        val exempt = BatteryOptimization.isExempt(graph.appContext)
        val gained = exempt && !_batteryExempt.value
        _batteryExempt.value = exempt
        if (exempt) _batteryDialogVisible.value = false
        return gained
    }

    fun dismissBatteryDialog() {
        _batteryDialogVisible.value = false
    }

    /**
     * Jumps to the system battery-optimisation page for this app.
     *
     * @return a message when no settings page could be opened, null otherwise.
     */
    fun openBatterySettings(): String? {
        _batteryDialogVisible.value = false
        val launched = BatteryOptimization.openSettings(graph.appContext)
        return if (launched == null) {
            "无法打开系统的电池优化设置，请到「设置 → 应用 → SSH Relay → 电池」手动允许后台运行"
        } else {
            null
        }
    }

    fun openAppDetails(): String? =
        if (BatteryOptimization.openAppDetails(graph.appContext)) {
            null
        } else {
            "无法打开应用详情页"
        }

    /** Flushes pending edits so the tunnel never starts from (or reloads stale) state. */
    private suspend fun flushEdits() {
        settingsSaveJob?.cancel()
        secretSaveJob?.cancel()
        withContext(Dispatchers.IO) {
            graph.settingsStore.save(_settings.value)
            graph.secretStore.put(SecretKeys.PASSWORD, _password.value)
            graph.secretStore.put(SecretKeys.PRIVATE_KEY, _privateKey.value)
            graph.secretStore.put(SecretKeys.PASSPHRASE, _passphrase.value)
        }
    }

    fun showMessage(text: String?) {
        _message.value = text
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun forgetKnownHost(key: KnownHostKey) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.hostKeyStore.remove(key.host, key.port, key.keyType)
            _knownHosts.value = graph.hostKeyStore.list()
        }
    }

    private fun append(source: LogSource, text: String) {
        val line = LogLine(timeFormat.format(Date()), source, text)
        _logs.value = (_logs.value + line).takeLast(MAX_LOG_LINES)
    }

    companion object {
        private const val MAX_LOG_LINES = 300

        fun factory(graph: AppGraph): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TunnelViewModel(graph) as T
        }
    }
}

/**
 * Whether the edited settings would produce the same tunnel as [running].
 *
 * Rule labels and order are presentation only; local port, remote target and the
 * enabled flag are what the forwarding engine actually uses.
 */
private fun AppSettings.matches(running: RunningSettings): Boolean {
    val authKindChanged = when (profile.authKind) {
        AuthKind.PASSWORD -> running.config.auth !is AuthMethod.Password
        AuthKind.PRIVATE_KEY -> running.config.auth !is AuthMethod.PrivateKey
    }
    if (authKindChanged) return false
    if (profile.host.trim() != running.config.host ||
        profile.port != running.config.port ||
        profile.username.trim() != running.config.username
    ) {
        return false
    }

    val edited = rules.filter { it.enabled }.map { it.toForwardRule() }
    if (edited.size != running.rules.size) return false
    return edited.all { rule ->
        running.rules.any { active ->
            active.id == rule.id &&
                active.localPort == rule.localPort &&
                active.remoteHost == rule.remoteHost &&
                active.remotePort == rule.remotePort
        }
    }
}
