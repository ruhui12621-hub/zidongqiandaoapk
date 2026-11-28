package com.ruhui.hoyocookiemanager

import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Shared public-API device metadata for all platform login flows. */
data class DeviceInfoSnapshot(
    val manufacturer: String = "",
    val model: String = "",
    val deviceCode: String = "",
    val androidVersion: String = "",
    val androidSdk: String = "",
    val fingerprint: String = "",
    val appInstallId: String = ""
) {
    companion object { val EMPTY = DeviceInfoSnapshot() }
}

object DeviceInfoProvider {
    private const val PREFS = "device_metadata"
    private const val KEY_INSTALL_ID = "app_install_id"
    private val loginSnapshots = ConcurrentHashMap<String, DeviceInfoSnapshot>()

    /** Capture once when either platform's in-app login page is opened. */
    fun capture(context: Context): DeviceInfoSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installId = synchronized(this) {
            prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString(KEY_INSTALL_ID, it).commit()) {
                    "无法保存 APK 安装实例标识"
                }
            }
        }
        return DeviceInfoSnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            deviceCode = Build.DEVICE.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT.toString(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
            appInstallId = installId
        )
    }

    /** Bind the snapshot only after the account authenticates successfully. */
    fun bind(accountId: String?, snapshot: DeviceInfoSnapshot) {
        val id = accountId?.trim().orEmpty()
        if (id.isNotEmpty()) loginSnapshots[id] = snapshot
    }

    fun forAccount(accountId: String): DeviceInfoSnapshot? =
        loginSnapshots[accountId.trim()]?.takeUnless { it == DeviceInfoSnapshot.EMPTY }

    fun clear(accountId: String) { loginSnapshots.remove(accountId.trim()) }
}
