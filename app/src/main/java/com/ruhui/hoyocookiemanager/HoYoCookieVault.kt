package com.ruhui.hoyocookiemanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyGenerator
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted per-account storage for HoYoLAB / Miyoushe Cookie headers. */
class HoYoCookieVault(context: Context, accountId: String) {
    private val keyId = MessageDigest.getInstance("SHA-256")
        .digest(accountId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(24)
    private val file = File(context.noBackupFilesDir, "hoyo_cookie_$keyId.enc")
    private val alias = "hoyo_cookie_key_$keyId"

    @Synchronized
    fun save(cookie: String) {
        require(cookie.isNotBlank()) { "Cookie 不能为空" }
        val clear = cookie.toByteArray(Charsets.UTF_8)
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
                error("无法替换账号 Cookie 保管文件")
            }
            if (!temp.renameTo(file)) {
                temp.delete()
                error("无法保存账号 Cookie")
            }
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    fun latest(): String? {
        if (!file.isFile) return null
        val outer = JSONObject(file.readText(Charsets.UTF_8))
        require(outer.optInt("version") == 1) { "账号 Cookie 文件版本不受支持" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(outer.getString("nonce"), Base64.NO_WRAP))
        )
        val clear = cipher.doFinal(Base64.decode(outer.getString("data"), Base64.NO_WRAP))
        return try { String(clear, Charsets.UTF_8) } finally { clear.fill(0) }
    }

    @Synchronized
    fun delete() {
        if (file.exists() && !file.delete()) error("无法删除账号 Cookie")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
