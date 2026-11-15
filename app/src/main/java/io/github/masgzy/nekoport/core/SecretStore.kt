package io.github.masgzy.nekoport.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the secrets that used to sit in config.json as plain JSON
 * (FTP account passwords, cron shell commands). The AES-256-GCM key lives in
 * the Android Keystore and never leaves it - config.json only carries
 * `enc:v1:<iv>:<ciphertext>` blobs, so reading the app sandbox (FTP without
 * chroot, a confused file manager, an adb pull) no longer yields usable
 * credentials.
 *
 * Fail-soft contract, chosen over fail-hard because the config file must
 * stay loadable no matter what:
 *  - [encrypt] falls back to the plaintext when Keystore is unavailable
 *    (rare vendor breakage) and logs loudly - same behavior as before.
 *  - [decrypt] passes through anything without the `enc:v1:` marker (legacy
 *    plaintext configs keep working and are re-sealed on the next save) and
 *    returns "" for blobs that cannot be decrypted (e.g. the Keystore was
 *    factory-reset while config.json survived a backup restore) - the user
 *    re-enters the password instead of the app crashing.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val KEY_ALIAS = "nekoport-config-secrets"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PREFIX = "enc:v1:"

    @Volatile
    private var cachedKey: SecretKey? = null

    /** True when [stored] is a sealed blob produced by [encrypt]. */
    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    // Synchronized: two concurrent first-time encrypts would both call
    // generateKey() and the second would overwrite the key entry, making
    // everything encrypted by the first unreadable.
    @Synchronized
    private fun obtainKey(): SecretKey? {
        cachedKey?.let { return it }
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
                )
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generator.generateKey()
            }
        }.getOrNull()?.also { cachedKey = it }
    }

    /**
     * Seals [plain]. Returns the `enc:v1:...` blob, or the original string
     * when Keystore is unavailable (degraded but functional).
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        val key = obtainKey()
        if (key == null) {
            AppLog.e(TAG, "keystore unavailable; storing value unencrypted")
            return plain
        }
        // A fresh IV per encryption is mandatory for GCM; Cipher in
        // ENCRYPT_MODE generates one when no IvParameterSpec is supplied.
        val cipher = runCatching { Cipher.getInstance(TRANSFORMATION) }.getOrNull() ?: return plain
        return runCatching {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        }.getOrElse {
            AppLog.e(TAG, "encrypt failed: ${it.message}")
            plain
        }
    }

    /**
     * Unseals a blob written by [encrypt]. Legacy plaintext values are
     * returned unchanged (they get sealed on the next save); values that
     * cannot be decrypted return "" (the caller treats it like an empty
     * credential).
     */
    fun decrypt(stored: String): String {
        if (!isSealed(stored)) return stored
        val key = obtainKey() ?: run {
            AppLog.e(TAG, "keystore unavailable; cannot unseal a stored secret")
            return ""
        }
        val parts = stored.removePrefix(PREFIX).split(':')
        if (parts.size != 2) {
            AppLog.e(TAG, "malformed sealed blob; treating as empty")
            return ""
        }
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrElse {
            AppLog.e(TAG, "decrypt failed (${it.message}); the secret must be re-entered")
            ""
        }
    }
}
