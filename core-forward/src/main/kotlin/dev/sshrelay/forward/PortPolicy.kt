package dev.sshrelay.forward

/**
 * Port rules that the Android platform and this app both care about.
 *
 * Android refuses to bind ports below 1024 for a non-privileged app (the app
 * sandbox has no CAP_NET_BIND_SERVICE), so a local port of e.g. 80/443 can never
 * work no matter what the UI says. We surface that as a validation error instead
 * of a mysterious "permission denied" at runtime.
 */
object PortPolicy {
    const val MIN_LOCAL_PORT = 1024
    const val MAX_PORT = 65535

    fun validateLocalPort(port: Int): Validation =
        when {
            port !in MIN_LOCAL_PORT..MAX_PORT ->
                Validation.Invalid(
                    "本地端口必须在 $MIN_LOCAL_PORT-$MAX_PORT 之间（Android 禁止普通应用绑定 <1024 端口），当前为 $port"
                )
            else -> Validation.Ok
        }

    fun validateRemotePort(port: Int): Validation =
        when {
            port !in 1..MAX_PORT -> Validation.Invalid("远端端口必须在 1-$MAX_PORT 之间，当前为 $port")
            else -> Validation.Ok
        }

    fun validateRemoteHost(host: String): Validation {
        val h = host.trim()
        return when {
            h.isEmpty() -> Validation.Invalid("远端主机不能为空")
            h.any { it.isWhitespace() } -> Validation.Invalid("远端主机不能包含空格")
            else -> Validation.Ok
        }
    }

    fun validate(rule: ForwardRule): Validation =
        listOf(
            validateLocalPort(rule.localPort),
            validateRemoteHost(rule.remoteHost),
            validateRemotePort(rule.remotePort),
        ).firstOrNull { it is Validation.Invalid } ?: Validation.Ok

    sealed interface Validation {
        data object Ok : Validation
        data class Invalid(val reason: String) : Validation
    }
}

fun PortPolicy.Validation.isOk(): Boolean = this is PortPolicy.Validation.Ok

fun PortPolicy.Validation.errorOrNull(): String? =
    (this as? PortPolicy.Validation.Invalid)?.reason
