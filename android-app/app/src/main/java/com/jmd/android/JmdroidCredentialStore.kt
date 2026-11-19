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

    fun load(): PairedJmd? = active()

    fun active(): PairedJmd? {
        if (preferences.getBoolean(NO_ACTIVE_COMPUTER, false)) return null
        val computers = loadAll()
        val activeId = preferences.getString(ACTIVE_COMPUTER, null)
        return computers.firstOrNull { id(it) == activeId } ?: computers.firstOrNull()
    }

    fun loadAll(): List<PairedJmd> {
        val encrypted = preferences.getString(ENCRYPTED_CREDENTIALS, null) ?: return emptyList()
        return try {
            val packed = Base64.decode(encrypted, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            if (json.has("computers")) {
                val array = json.getJSONArray("computers")
                (0 until array.length()).mapNotNull { index ->
                    runCatching { jsonToPaired(array.getJSONObject(index)) }.getOrNull()
                }.distinctBy(::id)
            } else listOf(jsonToPaired(json))
        } catch (_: Exception) {
            clear()
            emptyList()
        }
    }

    fun save(paired: PairedJmd) {
        val computers = loadAll().filterNot { id(it) == id(paired) } + paired
        saveAll(computers, paired)
    }

    fun select(paired: PairedJmd?) {
        if (paired == null) preferences.edit().remove(ACTIVE_COMPUTER).putBoolean(NO_ACTIVE_COMPUTER, true).apply()
        else {
            save(paired)
            preferences.edit().putString(ACTIVE_COMPUTER, id(paired)).putBoolean(NO_ACTIVE_COMPUTER, false).apply()
        }
    }

    fun remove(paired: PairedJmd) {
        val currentActive = active()
        val remaining = loadAll().filterNot { id(it) == id(paired) }
        if (remaining.isEmpty()) clear()
        else saveAll(remaining, if (currentActive?.let(::id) == id(paired)) remaining.first() else currentActive)
    }

    fun clear() {
        preferences.edit().remove(ENCRYPTED_CREDENTIALS).remove(ACTIVE_COMPUTER).remove(NO_ACTIVE_COMPUTER).apply()
    }

    private fun saveAll(computers: List<PairedJmd>, active: PairedJmd?) {
        val json = JSONObject().put("computers", org.json.JSONArray().apply {
            computers.forEach { put(pairedToJson(it)) }
        })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val edit = preferences.edit().putString(ENCRYPTED_CREDENTIALS, Base64.encodeToString(packed, Base64.NO_WRAP))
        if (active != null) edit.putString(ACTIVE_COMPUTER, id(active)).putBoolean(NO_ACTIVE_COMPUTER, false)
        edit.apply()
    }

    private fun pairedToJson(paired: PairedJmd) = JSONObject()
        .put("baseUrl", paired.baseUrl)
        .put("certificateSha256", paired.certificateSha256)
        .put("deviceId", paired.deviceId)
        .put("accessToken", paired.accessToken)

    private fun jsonToPaired(json: JSONObject) = PairedJmd(
        json.getString("baseUrl"), json.getString("certificateSha256"),
        json.getString("deviceId"), json.getString("accessToken")
    )

    private fun id(paired: PairedJmd) = "${paired.baseUrl.trimEnd('/')}|${paired.certificateSha256.lowercase()}"

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
        const val ACTIVE_COMPUTER = "active_computer"
        const val NO_ACTIVE_COMPUTER = "no_active_computer"
        const val IV_LENGTH = 12
    }
}
