package com.ruhui.hoyocookiemanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores each account's Skland token encrypted with an Android Keystore AES-GCM key. */
class SklandTokenVault(context: Context, accountId: String? = null) {
    private val keyId = accountId?.let {
        MessageDigest.getInstance("SHA-256")
            .digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }.take(24)
    }
    // A null accountId intentionally preserves the legacy single-account storage location for migration.
    private val file = File(
        context.noBackupFilesDir,
        keyId?.let { "skland_token_$it.enc" } ?: "skland_token.enc"
    )
    private val alias = keyId?.let { "skland_token_key_$it" } ?: "skland_token_vault_key"

    @Synchronized
    fun save(value: String) {
        val token = value.trim()
        require(GlobalModuleRegistry.isValidCredential(AccountStore.SKLAND, token)) {
            "森空岛 Token 格式无效。"
        }
        val clear = token.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(clear)
            val outer = JSONObject()
                .put("version", 1)
                .put("nonce", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(outer.toString(), Charsets.UTF_8)
            if (file.exists() && !file.delete()) {
                temp.delete()
                error("无法替换森空岛 Token 保管文件。")
            }
            if (!temp.renameTo(file)) {
                temp.delete()
                error("无法保存森空岛 Token。")
            }
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    fun delete() {
        if (file.exists() && !file.delete()) error("无法删除森空岛 Token 保管文件。")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    @Synchronized
    fun latest(): String? {
        if (!file.isFile) return null
        val outer = JSONObject(file.readText(Charsets.UTF_8))
        if (outer.optInt("version") != 1) error("森空岛 Token 保管文件版本不受支持。")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(outer.getString("nonce"), Base64.NO_WRAP))
        )
        val clear = cipher.doFinal(Base64.decode(outer.getString("data"), Base64.NO_WRAP))
        return try {
            String(clear, Charsets.UTF_8)
        } finally {
            clear.fill(0)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
