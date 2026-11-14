package com.example.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed Android Keystore wrapper solely for background 24/7 Auto-Hide persistence.
 *
 * When the user enables Background Auto-Hide, the 256-bit Master Vault Key is sealed with a
 * non-exportable Android Keystore AES-256-GCM key and persisted inside private internal preferences.
 * When Auto-Hide is turned off, both the sealed blob and Keystore key alias are wiped.
 * `vault.header` on external storage remains 100% PIN/Recovery-Phrase protected and untouched.
 */
object AutoHideKeyStoreHelper {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "local_vault_autohide_wrap_v1"
    private const val PREF_NAME = "local_vault_autohide_ks"
    private const val KEY_SEALED_MVK = "sealed_mvk_b64"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateWrapKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                generator.init(spec)
                generator.generateKey()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun sealMasterKeyForAutoHide(context: Context, masterKey: SecretKey) {
        try {
            val rawBytes = masterKey.encoded ?: return
            val wrapKey = getOrCreateWrapKey() ?: return
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
            val iv = cipher.iv
            if (iv == null || iv.size != GCM_IV_LEN) return
            val encrypted = cipher.doFinal(rawBytes)
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SEALED_MVK, encoded)
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun unsealMasterKeyForAutoHide(context: Context): SecretKey? {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val encoded = prefs.getString(KEY_SEALED_MVK, null) ?: return null
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LEN) return null

            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val wrapKey = ks.getKey(KEY_ALIAS, null) as? SecretKey ?: return null

            val iv = combined.copyOfRange(0, GCM_IV_LEN)
            val cipherText = combined.copyOfRange(GCM_IV_LEN, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plainMvk = cipher.doFinal(cipherText)
            SecretKeySpec(plainMvk, "AES")
        } catch (_: Throwable) {
            null
        }
    }

    fun clearAutoHideKey(context: Context) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SEALED_MVK)
                .apply()
        } catch (_: Throwable) {
        }
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Throwable) {
        }
    }
}
