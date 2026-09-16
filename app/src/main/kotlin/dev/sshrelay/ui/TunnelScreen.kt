package dev.sshrelay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sshrelay.data.AuthKind
import dev.sshrelay.data.Profile
import dev.sshrelay.data.RuleConfig
import dev.sshrelay.ssh.ForwardStatus
import dev.sshrelay.ssh.TunnelState
import dev.sshrelay.service.describe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen(
    viewModel: TunnelViewModel,
    onOpenInAppBrowser: (String) -> Unit,
    onOpenInSystemBrowser: (String) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val privateKey by viewModel.privateKey.collectAsStateWithLifecycle()
    val passphrase by viewModel.passphrase.collectAsStateWithLifecycle()
    val state by viewModel.tunnelState.collectAsStateWithLifecycle()
    val forwards by viewModel.forwards.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val knownHosts by viewModel.knownHosts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.showMessage(null)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SSH 端口转发") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(state, viewModel) }
            item {
                ConnectionCard(
                    profile = settings.profile,
                    password = password,
                    privateKey = privateKey,
                    passphrase = passphrase,
                    onProfileChange = viewModel::updateProfile,
                    onPasswordChange = viewModel::setPassword,
                    onPrivateKeyChange = viewModel::setPrivateKey,
                    onPassphraseChange = viewModel::setPassphrase,
                )
            }
            item {
                RulesCard(
                    rules = settings.rules,
                    onUpdate = viewModel::updateRules,
                )
            }
            if (forwards.isNotEmpty()) {
                item {
                    ForwardsCard(
                        forwards = forwards,
                        onOpenInAppBrowser = onOpenInAppBrowser,
                        onOpenInSystemBrowser = onOpenInSystemBrowser,
                    )
                }
            }
            if (knownHosts.isNotEmpty()) {
                item {
                    KnownHostsCard(knownHosts, onForget = viewModel::forgetKnownHost)
                }
            }
            item { LogCard(logs, onClear = viewModel::clearLogs) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ------------------------------------------------------------------ status

@Composable
private fun StatusCard(state: TunnelState, viewModel: TunnelViewModel) {
    val working = state.let {
        it is TunnelState.Connected || it is TunnelState.Connecting || it is TunnelState.Reconnecting
    }
    val accent = when (state) {
        is TunnelState.Connected -> MaterialTheme.colorScheme.secondary
        is TunnelState.Failed -> MaterialTheme.colorScheme.error
        is TunnelState.Connecting, is TunnelState.Reconnecting -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.background(accent).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(
                        text = when (state) {
                            is TunnelState.Connected -> "已连接"
                            is TunnelState.Connecting -> "连接中"
                            is TunnelState.Reconnecting -> "重连中"
                            is TunnelState.Failed -> "失败"
                            is TunnelState.Stopped -> "已停止"
                            is TunnelState.Idle -> "空闲"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(state.describe(), style = MaterialTheme.typography.bodyMedium)
            }

            (state as? TunnelState.Connected)?.serverVersion?.let {
                Text("服务端：$it", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (working) {
                    Button(onClick = viewModel::stop) { Text("停止隧道") }
                    OutlinedButton(onClick = viewModel::retry) { Text("立即重连") }
                } else {
                    Button(onClick = viewModel::start) { Text("启动隧道") }
                }
            }
        }
    }
}

// -------------------------------------------------------------- connection

@Composable
private fun ConnectionCard(
    profile: Profile,
    password: String,
    privateKey: String,
    passphrase: String,
    onProfileChange: ((Profile) -> Profile) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPrivateKeyChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
) {
    SectionCard("SSH 连接") {
        OutlinedTextField(
            value = profile.host,
            onValueChange = { value -> onProfileChange { it.copy(host = value) } },
            label = { Text("服务器地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = profile.port.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { port -> onProfileChange { it.copy(port = port) } }
                },
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp),
            )
            OutlinedTextField(
                value = profile.username,
                onValueChange = { value -> onProfileChange { it.copy(username = value) } },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = profile.authKind == AuthKind.PASSWORD,
                onClick = { onProfileChange { it.copy(authKind = AuthKind.PASSWORD) } },
                label = { Text("密码") },
            )
            FilterChip(
                selected = profile.authKind == AuthKind.PRIVATE_KEY,
                onClick = { onProfileChange { it.copy(authKind = AuthKind.PRIVATE_KEY) } },
                label = { Text("私钥") },
            )
        }

        if (profile.authKind == AuthKind.PASSWORD) {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = privateKey,
                onValueChange = onPrivateKeyChange,
                label = { Text("私钥内容（OpenSSH / PEM）") },
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = { Text("私钥口令（如无可留空）") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            "密码与私钥使用 Android Keystore（AES-GCM）加密保存，不会写入明文配置文件。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ------------------------------------------------------------------- rules

@Composable
private fun RulesCard(
    rules: List<RuleConfig>,
    onUpdate: ((List<RuleConfig>) -> List<RuleConfig>) -> Unit,
) {
    SectionCard("转发规则（本机 127.0.0.1:本地端口 → 远端目标）") {
        rules.forEachIndexed { index, rule ->
            if (index > 0) HorizontalDivider()
            RuleEditor(
                rule = rule,
                onChange = { updated ->
                    onUpdate { list -> list.map { if (it.id == rule.id) updated else it } }
                },
                onRemove = { onUpdate { list -> list.filterNot { it.id == rule.id } } },
                canRemove = rules.size > 1,
            )
        }
        OutlinedButton(onClick = { onUpdate { list ->
            val used = list.map { it.localPort }.toSet()
            val port = generateSequence(8080) { it + 1 }.first { it !in used }
            list + RuleConfig(
                id = java.util.UUID.randomUUID().toString().take(8),
                localPort = port,
                remoteHost = "127.0.0.1",
                remotePort = 80,
            )
        } }) { Text("添加规则") }
    }
}

@Composable
private fun RuleEditor(
    rule: RuleConfig,
    onChange: (RuleConfig) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rule.localPort.toString(),
                onValueChange = { value -> value.toIntOrNull()?.let { onChange(rule.copy(localPort = it)) } },
                label = { Text("本地端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            OutlinedTextField(
                value = rule.remoteHost,
                onValueChange = { value -> onChange(rule.copy(remoteHost = value)) },
                label = { Text("远端主机") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rule.remotePort.toString(),
                onValueChange = { value -> value.toIntOrNull()?.let { onChange(rule.copy(remotePort = it)) } },
                label = { Text("远端端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            OutlinedTextField(
                value = rule.label,
                onValueChange = { value -> onChange(rule.copy(label = value)) },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = rule.enabled,
                onCheckedChange = { checked -> onChange(rule.copy(enabled = checked)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("启用", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(16.dp))
            if (canRemove) {
                TextButton(onClick = onRemove) { Text("删除") }
            }
        }
        if (rule.localPort < 1024) {
            // Android refuses to bind privileged ports for a normal app; say so here
            // rather than letting it fail with EACCES at runtime.
            Text(
                "⚠ 本地端口必须 ≥ 1024：Android 不允许普通应用绑定 1024 以下端口。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------------------------------------------------------- forwards

@Composable
private fun ForwardsCard(
    forwards: List<ForwardStatus>,
    onOpenInAppBrowser: (String) -> Unit,
    onOpenInSystemBrowser: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    SectionCard("监听状态") {
        forwards.forEach { forward ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = (if (forward.running) "● " else "○ ") + forward.rule.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (forward.running) {
                    Text(
                        text = "浏览器打开：${forward.localUrl}   " +
                            "活动连接 ${forward.stats.activeConnections} / 累计 ${forward.stats.totalConnections}   " +
                            "↑${forward.stats.bytesToRemote / 1024}KB ↓${forward.stats.bytesToLocal / 1024}KB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenInAppBrowser(forward.localUrl) }) { Text("应用内打开") }
                        OutlinedButton(onClick = { onOpenInSystemBrowser(forward.localUrl) }) { Text("系统浏览器") }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(forward.localUrl))
                        }) { Text("复制地址") }
                    }
                } else {
                    Text(
                        text = "未监听：${forward.error ?: "未知原因"}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

// ------------------------------------------------------------- known hosts

@Composable
private fun KnownHostsCard(
    hosts: List<dev.sshrelay.ssh.KnownHostKey>,
    onForget: (dev.sshrelay.ssh.KnownHostKey) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    SectionCard("已知主机密钥（TOFU）") {
        Text(
            "首次连接会记录服务端密钥；若之后密钥变化，连接会被直接拒绝以防范中间人攻击。",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "展开（${hosts.size}）")
        }
        if (expanded) {
            hosts.forEach { host ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${host.host}:${host.port}  ${host.keyType}", style = MaterialTheme.typography.bodySmall)
                    Text(host.fingerprint, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    TextButton(onClick = { onForget(host) }) { Text("删除记录") }
                    HorizontalDivider()
                }
            }
        }
    }
}

// -------------------------------------------------------------------- logs

@Composable
private fun LogCard(logs: List<LogLine>, onClear: () -> Unit) {
    SectionCard("运行日志") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近 ${logs.size} 条", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onClear) { Text("清空") }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            logs.takeLast(200).forEach { line ->
                Text(
                    text = "${line.time} [${if (line.source == LogSource.SSH) "ssh" else "tun"}] ${line.text}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = if (line.text.contains("⚠") || line.text.contains("失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ shared

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
