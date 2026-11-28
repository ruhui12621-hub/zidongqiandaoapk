package com.ruhui.hoyocookiemanager

import java.util.concurrent.ConcurrentHashMap

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
    var lastHoyoFunctionExisted: Boolean = false
    var lastSklandFunctionExisted: Boolean = false
    var lastHoyoCsvPresent: Boolean = false
    var lastSklandCsvPresent: Boolean = false
    var lastSklandLegacySnapshot: String = ""

    // Current browser-session Cookie, never persisted. Kept separate from cloud YAML.
    @Volatile var pendingHoyoCookie: String = ""
    @Volatile var pendingHoyoCookieServer: String = ""
    @Volatile var pendingHoyoCookieAccountId: String = ""

    // Multiple newly acquired Cookies can coexist during one app process. This lets a
    // later update initiated from another account include every eligible local account.
    private val acquiredHoyoCookies = ConcurrentHashMap<String, Pair<String, String>>()

    fun recordHoyoCookie(accountId: String, cookie: String, server: String) {
        if (accountId.isNotBlank() && cookie.isNotBlank() && server in setOf("cn", "os")) {
            acquiredHoyoCookies[accountId] = cookie to server
        }
    }

    fun hoyoCookieFor(accountId: String): Pair<String, String>? =
        acquiredHoyoCookies[accountId]

    fun removeUploadedHoyoCookies(accountIds: Set<String>) {
        accountIds.forEach { acquiredHoyoCookies.remove(it) }
        if (pendingHoyoCookieAccountId in accountIds) clearPendingHoyoCookie()
    }

    fun clearPendingHoyoCookie() {
        pendingHoyoCookie = ""
        pendingHoyoCookieServer = ""
        pendingHoyoCookieAccountId = ""
    }

    fun isLoggedIn(): Boolean =
        ak.isNotBlank() && sk.isNotBlank() &&
            region.isNotBlank() && projectId.isNotBlank()
}
