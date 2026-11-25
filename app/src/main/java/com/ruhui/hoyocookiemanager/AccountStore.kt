package com.ruhui.hoyocookiemanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local account registry. Account identity is scoped by platform; the same numeric UID
 * on HoYoLAB, Miyoushe and Skland never merges records.
 *
 * This registry stores metadata only. Secrets stay in the platform credential vaults.
 */
data class ManagedAccount(
    val id: String,
    val platform: String,
    val name: String,
    val platformUserId: String?,
    val games: Set<String>,
    val credentialReady: Boolean = false
)

class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("multi_account_registry", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<ManagedAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val platform = item.optString("platform").takeIf { it in PLATFORMS } ?: return@mapNotNull null
            val gamesJson = item.optJSONArray("games") ?: JSONArray()
            val games = (0 until gamesJson.length()).mapNotNull { gamesJson.optString(it).takeIf(String::isNotBlank) }.toSet()
            ManagedAccount(
                id = id,
                platform = platform,
                name = item.optString("name").ifBlank { defaultName(platform) },
                platformUserId = item.optString("platformUserId").takeIf { it.isNotBlank() },
                games = games,
                credentialReady = item.optBoolean("credentialReady", false)
            )
        }
    }

    @Synchronized
    fun add(platform: String, name: String = "", platformUserId: String? = null, games: Set<String> = emptySet()): ManagedAccount {
        require(platform in PLATFORMS) { "不支持的账号平台" }
        val normalized = normalizeUserId(platformUserId)
        val existing = if (normalized != null) all().firstOrNull {
            it.platform == platform && it.platformUserId == normalized
        } else null
        if (existing != null) return existing
        val account = ManagedAccount(
            id = UUID.randomUUID().toString(),
            platform = platform,
            name = name.trim().ifBlank { defaultName(platform, all().count { it.platform == platform } + 1) },
            platformUserId = normalized,
            games = if (games.isNotEmpty()) games else if (platform == SKLAND) setOf("endfield") else games
        )
        save(all() + account)
        return account
    }

    @Synchronized
    fun update(account: ManagedAccount) {
        require(account.platform in PLATFORMS) { "不支持的账号平台" }
        val rows = all().toMutableList()
        val index = rows.indexOfFirst { it.id == account.id }
        require(index >= 0) { "账号不存在" }
        val normalized = normalizeUserId(account.platformUserId)
        val duplicate = rows.any {
            it.id != account.id && it.platform == account.platform &&
                normalized != null && it.platformUserId == normalized
        }
        require(!duplicate) { "该平台已存在相同账号 ID" }
        rows[index] = account.copy(platformUserId = normalized)
        save(rows)
    }

    @Synchronized
    fun delete(id: String) {
        save(all().filterNot { it.id == id })
    }

    @Synchronized
    fun find(id: String): ManagedAccount? = all().firstOrNull { it.id == id }

    private fun save(rows: List<ManagedAccount>) {
        val array = JSONArray()
        rows.forEach { account ->
            val games = JSONArray()
            account.games.sorted().forEach(games::put)
            array.put(
                JSONObject()
                    .put("id", account.id)
                    .put("platform", account.platform)
                    .put("name", account.name)
                    .put("platformUserId", account.platformUserId ?: "")
                    .put("games", games)
                    .put("credentialReady", account.credentialReady)
            )
        }
        check(prefs.edit().putString(KEY_ACCOUNTS, array.toString()).commit()) { "账号列表保存失败" }
    }

    private fun normalizeUserId(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }

    companion object {
        const val HOYOLAB = "hoyolab"
        const val MIYOUSHE_CN = "miyoushe_cn"
        const val SKLAND = "skland"
        const val EXTRA_ACCOUNT_ID = "managed_account_id"
        const val EXTRA_PLATFORM = "managed_account_platform"
        const val KEY_ACCOUNTS = "accounts_json"
        val PLATFORMS = setOf(HOYOLAB, MIYOUSHE_CN, SKLAND)

        fun platformLabel(platform: String): String = when (platform) {
            HOYOLAB -> "HoYoLAB"
            MIYOUSHE_CN -> "米游社国服"
            SKLAND -> "森空岛"
            else -> "未知平台"
        }

        fun defaultName(platform: String, number: Int = 1): String =
            "${platformLabel(platform)}账号 $number"
    }
}

/**
 * Persisted cloud-authority state. Local account mutations must remain locked until
 * FunctionGraph has been queried successfully (including an explicit no-function result).
 */
object CloudAccountSyncState {
    const val UNKNOWN = "unknown"
    const val SYNCING = "syncing"
    const val SYNCED = "synced"
    const val EMPTY_CONFIRMED = "empty_confirmed"
    const val FAILED = "failed"

    private const val PREFS = "cloud_account_authority"
    private const val KEY = "state"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, UNKNOWN) ?: UNKNOWN

    fun set(context: Context, value: String) {
        require(value in setOf(UNKNOWN, SYNCING, SYNCED, EMPTY_CONFIRMED, FAILED))
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).commit()) { "云端同步状态保存失败" }
    }

    fun allowsAccountChanges(context: Context): Boolean =
        get(context) == SYNCED || get(context) == EMPTY_CONFIRMED
}

/**
 * Extracts only stable account identifiers from a Cookie header. Never returns token values.
 * Priority is explicit user/account IDs, not stoken/ltoken/cookie_token credentials.
 */
object HoYoAccountIdentity {
    private val priority = listOf(
        "ltuid_v2", "ltuid", "account_id_v2", "account_id",
        "stuid_v2", "stuid", "login_uid"
    )

    fun fromCookie(cookie: String, platform: String): String? {
        if (platform !in setOf(AccountStore.HOYOLAB, AccountStore.MIYOUSHE_CN)) return null
        val fields = cookie.split(';').mapNotNull { part ->
            val key = part.trim().substringBefore('=').trim().lowercase()
            val value = part.trim().substringAfter('=', "").trim()
            if (key.isBlank() || value.isBlank()) null else key to value
        }.toMap()
        val preferred = if (platform == AccountStore.MIYOUSHE_CN) {
            listOf("stuid_v2", "stuid", "ltuid_v2", "ltuid", "account_id_v2", "account_id", "login_uid")
        } else priority
        return preferred.firstNotNullOfOrNull { key ->
            fields[key]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        }
    }
}
