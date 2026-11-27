package com.ruhui.hoyocookiemanager

import org.json.JSONArray
import org.json.JSONObject

/**
 * Template-owned projection of account, platform and runtime data for the React home UI.
 *
 * React receives one stable account display model instead of reading AccountStore,
 * platform definitions, credential vaults or message stores independently.
 */
object HomeAccountDisplayAssembler {
    fun build(
        account: ManagedAccount,
        todayStatus: String,
        gameTodayStatuses: Map<String, String>,
        todayDetailsJson: String?,
        cloudUpdateStages: JSONArray
    ): JSONObject {
        val platform = GlobalModuleRegistry.gameModule(account.platform)
        val games = JSONArray().apply {
            account.games.forEach { key ->
                val label = platform?.games?.firstOrNull { it.key == key }?.label
                    ?: GameCatalog.label(key)
                put(JSONObject().put("key", key).put("label", label))
            }
        }
        val perGameStatuses = JSONObject().apply {
            gameTodayStatuses.forEach { (key, value) -> put(key, value) }
        }
        val details = runCatching {
            JSONObject(todayDetailsJson?.takeIf { it.isNotBlank() } ?: "{}")
        }.getOrDefault(JSONObject())

        return JSONObject()
            .put("id", account.id)
            .put("name", account.name)
            .put("platform", account.platform)
            .put("platformLabel", platform?.label ?: AccountStore.platformLabel(account.platform))
            .put("userId", account.platformUserId.orEmpty())
            .put("games", games)
            .put("enabled", account.enabled)
            .put("remark", account.remark)
            .put("cookieReady", account.cookieReady)
            .put("tokenReady", account.tokenReady)
            .put("cloudUpdateStages", cloudUpdateStages)
            .put("todayStatus", todayStatus)
            .put("gameTodayStatuses", perGameStatuses)
            .put("todayDetails", details)
    }
}
