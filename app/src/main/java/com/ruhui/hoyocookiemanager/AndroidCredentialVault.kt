package com.ruhui.hoyocookiemanager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Offline credential vault. The data key is wrapped independently by:
 * 1) a PBKDF2-derived key from the user's vault password, and
 * 2) an AES key held by Android Keystore.
 * Both wrappers must decrypt and match before credentials are returned.
 */
class AndroidCredentialVault(private val activity: android.content.Context) {
    private val file = File(activity.noBackupFilesDir, "cloud_credentials.vault")
    private val alias = "cloud_credentials_vault_key"
    private val random = SecureRandom()

    fun exists(): Boolean = file.isFile

    @Synchronized
    fun save(password: CharArray, ak: String, sk: String) {
        require(password.size >= 10) { "保管箱密码至少需要 10 个字符。" }
        require(ak.isNotBlank() && sk.isNotBlank()) { "AK 和 SK 不能为空。" }
        val salt = ByteArray(16).also(random::nextBytes)
        val dataKey = ByteArray(32).also(random::nextBytes)
        val passwordKey = derive(password, salt)
        val passwordWrap = encrypt(SecretKeySpec(passwordKey, "AES"), dataKey)
        val systemWrap = encrypt(getOrCreateKeystoreKey(), dataKey)
        val payload = JSONObject().put("ak", ak).put("sk", sk).toString().toByteArray(Charsets.UTF_8)
        val encryptedPayload = encrypt(SecretKeySpec(dataKey, "AES"), payload)
        val json = JSONObject()
            .put("version", 1)
            .put("iterations", ITERATIONS)
            .put("salt", b64(salt))
            .put("passwordWrapNonce", b64(passwordWrap.first))
            .put("passwordWrap", b64(passwordWrap.second))
            .put("systemWrapNonce", b64(systemWrap.first))
            .put("systemWrap", b64(systemWrap.second))
            .put("dataNonce", b64(encryptedPayload.first))
            .put("data", b64(encryptedPayload.second))
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (file.exists() && !file.delete()) throw IllegalStateException("无法替换旧保管箱文件。")
        if (!temp.renameTo(file)) throw IllegalStateException("无法安全保存保管箱文件。")
        passwordKey.fill(0); dataKey.fill(0); payload.fill(0)
    }

    @Synchronized
    fun unlock(password: CharArray): Pair<String, String> {
        if (!file.isFile) error("尚未创建密码保管箱。")
        val j = JSONObject(file.readText(Charsets.UTF_8))
        if (j.optInt("version") != 1) error("保管箱版本不受支持。")
        val passwordKey = derive(password, decode(j.getString("salt")), j.optInt("iterations", ITERATIONS))
        val fromPassword = decrypt(
            SecretKeySpec(passwordKey, "AES"),
            decode(j.getString("passwordWrapNonce")),
            decode(j.getString("passwordWrap"))
        )
        val fromSystem = decrypt(
            getExistingKeystoreKey(),
            decode(j.getString("systemWrapNonce")),
            decode(j.getString("systemWrap"))
        )
        passwordKey.fill(0)
        if (!java.security.MessageDigest.isEqual(fromPassword, fromSystem)) {
            fromPassword.fill(0); fromSystem.fill(0)
            error("保管箱密钥校验失败。")
        }
        val clear = decrypt(
            SecretKeySpec(fromPassword, "AES"),
            decode(j.getString("dataNonce")),
            decode(j.getString("data"))
        )
        fromPassword.fill(0); fromSystem.fill(0)
        try {
            val credentials = JSONObject(String(clear, Charsets.UTF_8))
            credentials.getString("ak") to credentials.getString("sk")
        } finally { clear.fill(0) }
    }

    @Synchronized
    fun delete() {
        if (file.exists()) file.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun getExistingKeystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey
            ?: error("系统加密密钥不存在，保管箱可能无法恢复。")
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

    companion object { private const val ITERATIONS = 310000 }
}
