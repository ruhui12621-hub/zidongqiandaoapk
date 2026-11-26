package com.ruhui.hoyocookiemanager

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Skland-owned cloud configuration adapter.
 *
 * The template handles FunctionGraph upload/orchestration; this adapter owns the
 * Skland account schema, legacy migration, token replacement and game selection.
 */
object SklandCloudConfigAdapter {
    const val ACCOUNT_CONFIG_PATH = "code/INPUT_HYPERGRYPH_ACCOUNTS.json"
    const val LEGACY_TOKEN_PATH = "code/INPUT_HYPERGRYPH_TOKEN.txt"
    val supportedGames: Set<String> = linkedSetOf("arknights", "endfield")

    data class CloudAccount(
        val platformUserId: String,
        val token: String,
        val games: Set<String>
    )

    fun readAccounts(configJson: String?, legacyTokenText: String?): List<CloudAccount> {
        val raw = configJson?.trim().orEmpty()
        if (raw.isBlank()) return readLegacyAccounts(legacyTokenText.orEmpty())

        val document = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw IllegalStateException("森空岛账号配置不是有效 JSON。", error)
        }
        require(document.optInt("version", -1) == 1) { "森空岛账号配置版本不受支持。" }
        val rows = document.optJSONArray("accounts")
            ?: error("森空岛账号配置缺少 accounts 列表。")
        val result = mutableListOf<CloudAccount>()
        val seenTokens = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
                ?: error("森空岛账号配置第 ${index + 1} 项格式无效。")
            val token = row.optString("token").trim()
            require(token.isNotBlank() && !token.any(Char::isWhitespace)) {
                "森空岛账号配置包含无效 Token。"
            }
            val gameArray = row.optJSONArray("games")
                ?: error("森空岛账号配置缺少游戏列表。")
            val games = (0 until gameArray.length()).mapNotNull { gameArray.optString(it).takeIf(String::isNotBlank) }.toSet()
            require(games.isNotEmpty() && supportedGames.containsAll(games)) {
                "森空岛账号配置包含空游戏列表或不支持的游戏。"
            }
            val stableId = row.optString("platformUserId").takeIf { it.isNotBlank() } ?: sha256(token)
            require(seenTokens.add(token) && seenIds.add(stableId)) {
                "森空岛账号配置存在重复账号。"
            }
            result += CloudAccount(stableId, token, games)
        }
        return result
    }

    fun mergeAccount(
        configJson: String?,
        legacyTokenText: String?,
        token: String,
        previousPlatformUserId: String?,
        selectedGames: Set<String>
    ): String {
        require(token.isNotBlank() && !token.any(Char::isWhitespace)) { "森空岛 Token 无效。" }
        require(selectedGames.isNotEmpty()) { "至少选择一款森空岛游戏。" }
        require(supportedGames.containsAll(selectedGames)) { "游戏选择包含森空岛不支持的项目。" }

        val current = readAccounts(configJson, legacyTokenText)
        val newId = sha256(token)
        val previousId = previousPlatformUserId?.takeIf(String::isNotBlank)
        val merged = current.filterNot { account ->
            account.platformUserId == previousId ||
                account.platformUserId == newId ||
                account.token == token
        }.toMutableList()
        merged += CloudAccount(newId, token, selectedGames)

        val rows = JSONArray()
        merged.forEach { account ->
            rows.put(
                JSONObject()
                    .put("platformUserId", account.platformUserId)
                    .put("token", account.token)
                    .put("games", JSONArray(account.games.sorted()))
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("accounts", rows)
            .toString(2)
    }

    fun legacyTokenText(configJson: String): String =
        readAccounts(configJson, null).joinToString("\n") { it.token }.let {
            if (it.isBlank()) "" else "$it\n"
        }

    fun encodeAccounts(accounts: List<CloudAccount>): String {
        val rows = JSONArray()
        val seenTokens = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        accounts.forEach { account ->
            require(account.token.isNotBlank() && !account.token.any(Char::isWhitespace)) {
                "森空岛账号配置包含无效 Token。"
            }
            require(account.games.isNotEmpty() && supportedGames.containsAll(account.games)) {
                "森空岛账号配置包含空游戏列表或不支持的游戏。"
            }
            require(seenTokens.add(account.token) && seenIds.add(account.platformUserId)) {
                "森空岛账号配置存在重复账号。"
            }
            rows.put(
                JSONObject()
                    .put("platformUserId", account.platformUserId)
                    .put("token", account.token)
                    .put("games", JSONArray(account.games.sorted()))
            )
        }
        return JSONObject().put("version", 1).put("accounts", rows).toString(2)
    }

    private fun readLegacyAccounts(source: String): List<CloudAccount> {
        val tokens = source.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .distinct()
            .toList()
        return tokens.map { token ->
            CloudAccount(sha256(token), token, supportedGames)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
