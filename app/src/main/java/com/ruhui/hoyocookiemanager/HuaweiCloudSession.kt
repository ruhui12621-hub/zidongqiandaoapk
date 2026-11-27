package com.ruhui.hoyocookiemanager

/**
 * Process-only Huawei Cloud session.
 * Values are intentionally not persisted to disk; Android clears them when the app process ends.
 */
object HuaweiCloudSession {
    var ak: String = ""
    var sk: String = ""
    var region: String = "cn-east-3"
    var projectId: String = ""
    var lastConfigYaml: String = ""
    var lastHoyoAccountsCsv: String = ""
    var lastSklandAccountsCsv: String = ""

    // Current browser-session Cookie, never persisted. Kept separate from cloud YAML.
    @Volatile var pendingHoyoCookie: String = ""
    @Volatile var pendingHoyoCookieServer: String = ""
    @Volatile var pendingHoyoCookieAccountId: String = ""

    fun clearPendingHoyoCookie() {
        pendingHoyoCookie = ""
        pendingHoyoCookieServer = ""
        pendingHoyoCookieAccountId = ""
    }

    fun isLoggedIn(): Boolean =
        ak.isNotBlank() && sk.isNotBlank() &&
            region.isNotBlank() && projectId.isNotBlank()
}
