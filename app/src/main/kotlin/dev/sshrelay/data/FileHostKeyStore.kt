package dev.sshrelay.data

import dev.sshrelay.ssh.HostKeyStore
import dev.sshrelay.ssh.KnownHostKey
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Known-hosts persistence as a plain JSON file in the app's private storage.
 *
 * Host keys are public information, so they do not need Keystore encryption —
 * but they do need to survive restarts, because that is what turns TOFU into
 * real protection against a later man-in-the-middle.
 */
class FileHostKeyStore(private val file: File) : HostKeyStore {

    private val lock = Any()

    override fun find(host: String, port: Int, keyType: String): String? =
        synchronized(lock) { load().firstOrNull { it.matches(host, port, keyType) }?.keyBase64 }

    override fun store(key: KnownHostKey) {
        synchronized(lock) {
            val entries = load().filterNot { it.matches(key.host, key.port, key.keyType) } + key
            save(entries)
        }
    }

    override fun list(): List<KnownHostKey> = synchronized(lock) { load() }

    override fun remove(host: String, port: Int, keyType: String) {
        synchronized(lock) {
            save(load().filterNot { it.matches(host, port, keyType) })
        }
    }

    private fun KnownHostKey.matches(host: String, port: Int, keyType: String) =
        this.host == host && this.port == port && this.keyType == keyType

    private fun load(): List<KnownHostKey> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        KnownHostKey(
                            host = o.optString("host"),
                            port = o.optInt("port", 22),
                            keyType = o.optString("keyType"),
                            keyBase64 = o.optString("key"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(entries: List<KnownHostKey>) {
        val array = JSONArray()
        entries.forEach { key ->
            array.put(
                JSONObject().apply {
                    put("host", key.host)
                    put("port", key.port)
                    put("keyType", key.keyType)
                    put("key", key.keyBase64)
                    put("fingerprint", key.fingerprint)
                }
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(array.toString())
    }
}
