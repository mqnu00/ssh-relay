package dev.sshrelay.data

import dev.sshrelay.forward.ForwardRule
import org.json.JSONArray
import org.json.JSONObject

enum class AuthKind { PASSWORD, PRIVATE_KEY }

/** Non-secret part of the SSH connection settings. Credentials live in [SecretStore]. */
data class Profile(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authKind: AuthKind = AuthKind.PASSWORD,
)

/** Persisted form of a forwarding rule. */
data class RuleConfig(
    val id: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val label: String = "",
    val enabled: Boolean = true,
) {
    fun toForwardRule() = ForwardRule(
        id = id,
        localPort = localPort,
        remoteHost = remoteHost,
        remotePort = remotePort,
        label = label,
        enabled = enabled,
    )
}

data class AppSettings(
    val profile: Profile = Profile(),
    val rules: List<RuleConfig> = listOf(DEFAULT_RULE),
) {
    companion object {
        /** A browser-friendly default: `http://127.0.0.1:8080` is the obvious first thing to try. */
        val DEFAULT_RULE = RuleConfig(
            id = "default",
            localPort = 8080,
            remoteHost = "127.0.0.1",
            remotePort = 80,
            label = "示例：本机 8080 → 远端 80",
        )
    }
}

/**
 * Hand-rolled JSON (via Android's bundled org.json) instead of kotlinx.serialization:
 * it keeps the Gradle plugin surface minimal, which matters on a first build in a
 * brand-new, network-constrained environment.
 */
object SettingsCodec {

    fun encode(settings: AppSettings): String = JSONObject().apply {
        put("profile", JSONObject().apply {
            put("host", settings.profile.host)
            put("port", settings.profile.port)
            put("username", settings.profile.username)
            put("authKind", settings.profile.authKind.name)
        })
        put("rules", JSONArray().apply {
            settings.rules.forEach { rule ->
                put(JSONObject().apply {
                    put("id", rule.id)
                    put("localPort", rule.localPort)
                    put("remoteHost", rule.remoteHost)
                    put("remotePort", rule.remotePort)
                    put("label", rule.label)
                    put("enabled", rule.enabled)
                })
            }
        })
    }.toString()

    fun decode(json: String?): AppSettings {
        if (json.isNullOrBlank()) return AppSettings()
        return runCatching {
            val root = JSONObject(json)
            val profileJson = root.optJSONObject("profile") ?: JSONObject()
            val rulesJson = root.optJSONArray("rules") ?: JSONArray()
            val rules = buildList {
                for (i in 0 until rulesJson.length()) {
                    val r = rulesJson.optJSONObject(i) ?: continue
                    add(
                        RuleConfig(
                            id = r.optString("id").ifBlank { "rule-$i" },
                            localPort = r.optInt("localPort", 8080),
                            remoteHost = r.optString("remoteHost", "127.0.0.1"),
                            remotePort = r.optInt("remotePort", 80),
                            label = r.optString("label", ""),
                            enabled = r.optBoolean("enabled", true),
                        )
                    )
                }
            }
            AppSettings(
                profile = Profile(
                    host = profileJson.optString("host", ""),
                    port = profileJson.optInt("port", 22),
                    username = profileJson.optString("username", ""),
                    authKind = runCatching {
                        AuthKind.valueOf(profileJson.optString("authKind", AuthKind.PASSWORD.name))
                    }.getOrDefault(AuthKind.PASSWORD),
                ),
                rules = rules.ifEmpty { listOf(AppSettings.DEFAULT_RULE) },
            )
        }.getOrDefault(AppSettings())
    }
}

/** Keys used in [SecretStore]; secrets are never written to the settings file. */
object SecretKeys {
    const val PASSWORD = "ssh.password"
    const val PRIVATE_KEY = "ssh.privateKey"
    const val PASSPHRASE = "ssh.passphrase"
}
