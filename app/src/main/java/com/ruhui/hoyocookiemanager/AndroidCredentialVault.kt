package com.ruhui.hoyocookiemanager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwordless local credential vault.
 * Only the latest successfully authenticated AK/SK pair is retained, encrypted at rest
 * with an AES-GCM key held by Android Keystore.
 */
class AndroidCredentialVault(private val context: android.content.Context) {
    private val file = File(context.noBackupFilesDir, "cloud_credentials.vault")
    private val encryptionAlias = "cloud_credentials_vault_key"
    private val fingerprintAlias = "cloud_credentials_fingerprint_key"
    private val random = SecureRandom()

    fun exists(): Boolean = file.isFile

    @Synchronized
    fun latest(): Pair<String, String>? {
        val entries = readEntries()
        val first = entries.optJSONObject(0) ?: return null
        return first.getString("ak") to first.getString("sk")
    }

    /**
     * Replace the single stored pair only after a successful Huawei Cloud login.
     */
    @Synchronized
    fun save(ak: String, sk: String) {
        require(ak.isNotBlank() && sk.isNotBlank()) { "AK 和 SK 不能为空。" }
        val fingerprint = fingerprint(ak, sk)
        val entries = try {
            readEntries()
        } catch (e: Exception) {
            // A legacy password-protected vault cannot be silently decrypted by this version.
            // After a successful fresh cloud login, replace it with the new passwordless vault.
            if (e.message?.contains("旧版带密码保管箱") == true) JSONArray() else throw e
        }
        val newest = JSONArray().put(entry(ak, sk, fingerprint))
        var kept = 0
        for (i in 0 until entries.length()) {
            val old = entries.getJSONObject(i)
            val same = MessageDigest.isEqual(
                fingerprint.toByteArray(Charsets.UTF_8),
                old.optString("fingerprint").toByteArray(Charsets.UTF_8)
            )
            if (!same && kept < MAX_ENTRIES - 1) {
                newest.put(old)
                kept++
            }
        }
        writeEntries(newest)
    }

    @Synchronized
    fun delete() {
        if (file.exists() && !file.delete()) error("无法删除保管箱文件。")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(encryptionAlias)) store.deleteEntry(encryptionAlias)
        if (store.containsAlias(fingerprintAlias)) store.deleteEntry(fingerprintAlias)
    }

    private fun entry(ak: String, sk: String, fingerprint: String) =
        JSONObject().put("ak", ak).put("sk", sk).put("fingerprint", fingerprint)

    private fun readEntries(): JSONArray {
        if (!file.isFile) return JSONArray()
        val outer = JSONObject(file.readText(Charsets.UTF_8))
        if (outer.optInt("version") != VERSION) {
            error("检测到旧版带密码保管箱。请先用旧版本解锁并确认 AK/SK 可用，再升级保存；新版不会擅自覆盖旧数据。")
        }
        val clear = decrypt(
            getEncryptionKey(),
            decode(outer.getString("nonce")),
            decode(outer.getString("data"))
        )
        return try {
            val parsed = JSONObject(String(clear, Charsets.UTF_8)).optJSONArray("entries") ?: JSONArray()
            JSONArray().also { result ->
                for (i in 0 until minOf(parsed.length(), MAX_ENTRIES)) {
                    val item = parsed.optJSONObject(i) ?: continue
                    if (item.has("ak") && item.has("sk") && item.has("fingerprint")) result.put(item)
                }
            }
        } finally {
            clear.fill(0)
        }
    }

    private fun writeEntries(entries: JSONArray) {
        val clear = JSONObject().put("entries", entries).toString().toByteArray(Charsets.UTF_8)
        try {
            val encrypted = encrypt(getEncryptionKey(), clear)
            val outer = JSONObject().put("version", VERSION)
                .put("nonce", b64(encrypted.first)).put("data", b64(encrypted.second))
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(outer.toString(), Charsets.UTF_8)
            // Rename over the existing file atomically where supported.
            if (file.exists() && !file.delete()) {
                temp.delete()
                error("无法替换旧保管箱文件。")
            }
            if (!temp.renameTo(file)) {
                temp.delete()
                error("无法保存保管箱文件。")
            }
        } finally {
            clear.fill(0)
        }
    }

    private fun fingerprint(ak: String, sk: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(getFingerprintKey())
        val bytes = (ak + "\u0000" + sk).toByteArray(Charsets.UTF_8)
        return try { b64(mac.doFinal(bytes)) } finally { bytes.fill(0) }
    }

    private fun getEncryptionKey(): SecretKey = getOrCreateKey(
        encryptionAlias,
        KeyProperties.KEY_ALGORITHM_AES,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )

    private fun getFingerprintKey(): SecretKey = getOrCreateKey(
        fingerprintAlias,
        KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    )

    private fun getOrCreateKey(alias: String, algorithm: String, purposes: Int): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(algorithm, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(alias, purposes)
        if (algorithm == KeyProperties.KEY_ALGORITHM_AES) {
            builder.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
        } else {
            builder.setDigests(KeyProperties.DIGEST_SHA256)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun encrypt(key: SecretKey, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv to cipher.doFinal(plain)
    }

    private fun decrypt(key: SecretKey, nonce: ByteArray, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(encrypted)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val VERSION = 2
        private const val MAX_ENTRIES = 1
    }
}
