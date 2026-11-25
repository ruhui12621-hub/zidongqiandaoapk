package com.ruhui.hoyocookiemanager

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

/**
 * Legacy cleanup only. New HoYoLAB Cookies are never persisted on this device.
 */
class HoYoCookieVault(context: Context, accountId: String) {
    private val keyId = MessageDigest.getInstance("SHA-256")
        .digest(accountId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(24)
    private val file = File(context.noBackupFilesDir, "hoyo_cookie_$keyId.enc")
    private val alias = "hoyo_cookie_key_$keyId"

    @Synchronized
    fun delete() {
        if (file.exists() && !file.delete()) error("无法删除旧的本地 Cookie 文件")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}
