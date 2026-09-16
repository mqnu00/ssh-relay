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
import dev.sshrelay.ssh.ForwardStatus
import dev.sshrelay.ssh.KnownHostKey
import dev.sshrelay.ssh.TunnelState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    val tunnelState: StateFlow<TunnelState> = graph.manager.state
    val forwards: StateFlow<List<ForwardStatus>> = graph.manager.forwards

    private var settingsSaveJob: Job? = null
    private var secretSaveJob: Job? = null

    init {
        viewModelScope.launch {
            _settings.value = graph.settingsStore.settings.first()
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
        persistSecret(SecretKeys.PASSWORD, value)
    }

    fun setPrivateKey(value: String) {
        _privateKey.value = value
        persistSecret(SecretKeys.PRIVATE_KEY, value)
    }

    fun setPassphrase(value: String) {
        _passphrase.value = value
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
            // Flush pending edits so the tunnel never starts from stale state.
            settingsSaveJob?.cancel()
            secretSaveJob?.cancel()
            withContext(Dispatchers.IO) {
                graph.settingsStore.save(_settings.value)
                graph.secretStore.put(SecretKeys.PASSWORD, _password.value)
                graph.secretStore.put(SecretKeys.PRIVATE_KEY, _privateKey.value)
                graph.secretStore.put(SecretKeys.PASSPHRASE, _passphrase.value)
            }
            val error = graph.controller.start()
            if (error != null) _message.value = error
        }
    }

    fun stop() = graph.controller.stop()

    fun retry() {
        viewModelScope.launch { graph.manager.retryNow() }
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
