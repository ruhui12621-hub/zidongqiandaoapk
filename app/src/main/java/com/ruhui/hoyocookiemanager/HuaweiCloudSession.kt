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

    fun isLoggedIn(): Boolean =
        ak.isNotBlank() && sk.isNotBlank() &&
            region.isNotBlank() && projectId.isNotBlank()
}
