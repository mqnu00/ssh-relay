package dev.sshrelay.ssh

import java.security.MessageDigest
import java.util.Base64

/** One remembered server key. */
data class KnownHostKey(
    val host: String,
    val port: Int,
    /** e.g. `ssh-ed25519`, `rsa-sha2-256`, `ecdsa-sha2-nistp256`. */
    val keyType: String,
    val keyBase64: String,
) {
    /** `SHA256:...`, the format OpenSSH and `ssh-keygen -lf` print. */
    val fingerprint: String
        get() = fingerprintOf(Base64.getDecoder().decode(keyBase64))
}

/** Persistence for trusted host keys. The app backs this with a file; tests use [InMemoryHostKeyStore]. */
interface HostKeyStore {
    fun find(host: String, port: Int, keyType: String): String?
    fun store(key: KnownHostKey)
    fun list(): List<KnownHostKey>
    fun remove(host: String, port: Int, keyType: String)
}

class InMemoryHostKeyStore(initial: List<KnownHostKey> = emptyList()) : HostKeyStore {
    private val keys = LinkedHashMap<String, KnownHostKey>()

    init {
        initial.forEach { store(it) }
    }

    private fun id(host: String, port: Int, keyType: String) = "$host|$port|$keyType"

    override fun find(host: String, port: Int, keyType: String): String? =
        keys[id(host, port, keyType)]?.keyBase64

    override fun store(key: KnownHostKey) {
        keys[id(key.host, key.port, key.keyType)] = key
    }

    override fun list(): List<KnownHostKey> = keys.values.toList()

    override fun remove(host: String, port: Int, keyType: String) {
        keys.remove(id(host, port, keyType))
    }
}

/** Result of the trust decision for one connection attempt. */
sealed interface HostKeyDecision {
    /** Key seen before and unchanged. */
    data class Trusted(val key: KnownHostKey) : HostKeyDecision

    /** First time we see this server: remembered now (trust-on-first-use). */
    data class FirstUse(val key: KnownHostKey) : HostKeyDecision

    /** The server presented a different key than the one we pinned. */
    data class Changed(val expected: KnownHostKey, val presented: KnownHostKey) : HostKeyDecision
}

/**
 * Trust-on-first-use policy, evaluated by [TofuHostKeyRepository].
 *
 * Kept separate from the JSch adapter so the security decision is unit-testable
 * without standing up an SSH server.
 */
class TofuHostKeyPolicy(private val store: HostKeyStore) {

    fun evaluate(host: String, port: Int, keyBlob: ByteArray): HostKeyDecision {
        val keyType = sshStringAt(keyBlob, 0)
            ?: throw SshException("服务端主机密钥格式无法解析")
        val presented = KnownHostKey(host, port, keyType, Base64.getEncoder().encodeToString(keyBlob))
        val existingBase64 = store.find(host, port, keyType)
        return when {
            existingBase64 == null -> {
                store.store(presented)
                HostKeyDecision.FirstUse(presented)
            }
            existingBase64 == presented.keyBase64 -> HostKeyDecision.Trusted(presented)
            else -> HostKeyDecision.Changed(
                expected = KnownHostKey(host, port, keyType, existingBase64),
                presented = presented,
            )
        }
    }

    fun forget(host: String, port: Int, keyType: String) = store.remove(host, port, keyType)
}

/**
 * Reads the `name` field at [offset] of an SSH wire-format string
 * (`uint32 length || bytes`). Returns null when the blob is malformed.
 */
internal fun sshStringAt(blob: ByteArray, offset: Int): String? {
    if (offset + 4 > blob.size) return null
    val length = ((blob[offset].toInt() and 0xFF) shl 24) or
        ((blob[offset + 1].toInt() and 0xFF) shl 16) or
        ((blob[offset + 2].toInt() and 0xFF) shl 8) or
        (blob[offset + 3].toInt() and 0xFF)
    if (length <= 0 || offset + 4 + length > blob.size) return null
    return String(blob, offset + 4, length, Charsets.US_ASCII)
}

internal fun fingerprintOf(keyBlob: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
