package dev.sshrelay.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * Bridges JSch's host-key hook to [TofuHostKeyPolicy].
 *
 * JSch formats the host as `[host]:port` whenever the port is not 22, so that is
 * unwrapped here before the policy sees it.
 */
internal class TofuHostKeyRepository(
    private val defaultPort: Int,
    private val policy: TofuHostKeyPolicy,
    private val onDecision: (HostKeyDecision) -> Unit,
) : HostKeyRepository {

    override fun check(host: String, key: ByteArray): Int {
        val (realHost, port) = parseJschHost(host, defaultPort)
        val decision = try {
            policy.evaluate(realHost, port, key)
        } catch (e: SshException) {
            onDecision(HostKeyDecision.Changed(
                expected = KnownHostKey(realHost, port, "unknown", ""),
                presented = KnownHostKey(realHost, port, "unknown", ""),
            ))
            return HostKeyRepository.CHANGED
        }
        onDecision(decision)
        return when (decision) {
            // TOFU: first sight is trusted and pinned; a later mismatch is fatal.
            is HostKeyDecision.FirstUse -> HostKeyRepository.OK
            is HostKeyDecision.Trusted -> HostKeyRepository.OK
            is HostKeyDecision.Changed -> HostKeyRepository.CHANGED
        }
    }

    /**
     * Only reached when JSch itself decides to persist a key (StrictHostKeyChecking
     * `no`/`ask`). We always run with `yes` and pin inside [check], so this stays a
     * no-op on purpose.
     */
    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit

    override fun remove(host: String, type: String) {
        val (realHost, port) = parseJschHost(host, defaultPort)
        policy.forget(realHost, port, type)
    }

    override fun remove(host: String, type: String, key: ByteArray) = remove(host, type)

    override fun getKnownHostsRepositoryID(): String = "ssh-relay-tofu"

    /** Not supported: we never write a known_hosts file, so JSch has nothing to enumerate. */
    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** `[10.0.0.5]:2222` -> ("10.0.0.5", 2222); plain `example.com` -> ("example.com", defaultPort). */
internal fun parseJschHost(raw: String, defaultPort: Int): Pair<String, Int> {
    if (raw.startsWith("[")) {
        val end = raw.indexOf(']')
        if (end > 0) {
            val host = raw.substring(1, end)
            val port = raw.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
    }
    return raw to defaultPort
}
