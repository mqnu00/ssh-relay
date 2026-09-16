package dev.sshrelay.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credential storage backed by the Android Keystore.
 *
 * Deliberately not `androidx.security:security-crypto` — that library has been
 * stuck in alpha and is deprecated, while the Keystore API it wraps is stable.
 *
 * The AES key never leaves the Keystore (hardware-backed where available); this
 * class only ever handles base64 `iv || ciphertext` blobs, which it keeps in a
 * private SharedPreferences file.
 */
class SecretStore(context: Context, private val alias: String = DEFAULT_ALIAS) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ssh_relay_secrets", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // A background service must be able to read credentials without a
                // user-authentication prompt.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    fun put(key: String, value: String) {
        if (value.isEmpty()) {
            remove(key)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ciphertext
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.onFailure {
            // e.g. the Keystore entry was invalidated; drop the unusable blob
            // rather than failing every future read.
            Log.w(TAG, "无法解密已保存的凭据($key)，将清除该条目", it)
            prefs.edit().remove(key).apply()
        }.getOrNull()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun has(key: String): Boolean = prefs.contains(key)

    private companion object {
        const val TAG = "SecretStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "ssh_relay_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
