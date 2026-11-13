package com.jmd.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class JmdroidCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("jmdroid_connection", Context.MODE_PRIVATE)

    fun load(): PairedJmd? {
        val encrypted = preferences.getString(ENCRYPTED_CREDENTIALS, null) ?: return null
        return try {
            val packed = Base64.decode(encrypted, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            PairedJmd(
                json.getString("baseUrl"), json.getString("certificateSha256"),
                json.getString("deviceId"), json.getString("accessToken")
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(paired: PairedJmd) {
        val json = JSONObject()
            .put("baseUrl", paired.baseUrl)
            .put("certificateSha256", paired.certificateSha256)
            .put("deviceId", paired.deviceId)
            .put("accessToken", paired.accessToken)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString(ENCRYPTED_CREDENTIALS, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun clear() {
        preferences.edit().remove(ENCRYPTED_CREDENTIALS).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "jmdroid_api_credentials"
        const val ENCRYPTED_CREDENTIALS = "encrypted_credentials"
        const val IV_LENGTH = 12
    }
}
