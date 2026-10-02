/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForwardingSecretStore @Inject constructor(
    private val preferences: SharedPreferences
) {
    companion object {
        private const val KEY_ALIAS = "quik_forward_smtp_password"
        private const val PREF_PASSWORD = "forwarding.smtp.password.encrypted"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    fun save(password: String) {
        if (password.isEmpty()) {
            preferences.edit().remove(PREF_PASSWORD).apply()
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
        val value = listOf(cipher.iv, encrypted)
            .joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
        preferences.edit().putString(PREF_PASSWORD, value).apply()
    }

    fun load(): String {
        val value = preferences.getString(PREF_PASSWORD, null) ?: return ""
        return try {
            val pieces = value.split(":", limit = 2)
            if (pieces.size != 2) return ""
            val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(pieces[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
