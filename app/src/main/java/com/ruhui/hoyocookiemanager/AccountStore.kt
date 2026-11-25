package com.ruhui.hoyocookiemanager

import android.content.Context
import org.json.JSONArray
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
    val credentialReady: Boolean = false,
    val cookieReady: Boolean = credentialReady,
    val tokenReady: Boolean = false
)

class AccountStore(context: Context) {
    // Account/config data is intentionally memory-only. Every app launch repopulates it
    // from the authoritative FunctionGraph package; no account YAML or Cookie is cached locally.
    @Synchronized
    fun all(): List<ManagedAccount> = synchronized(cache) { cache.toList() }

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
        synchronized(cache) { cache.add(account) }
        return account
    }

    @Synchronized
    fun update(account: ManagedAccount) {
        require(account.platform in PLATFORMS) { "不支持的账号平台" }
        synchronized(cache) {
            val index = cache.indexOfFirst { it.id == account.id }
            require(index >= 0) { "账号不存在" }
            val normalized = normalizeUserId(account.platformUserId)
            val duplicate = cache.any {
                it.id != account.id && it.platform == account.platform &&
                    normalized != null && it.platformUserId == normalized
            }
            require(!duplicate) { "该平台已存在相同账号 ID" }
            cache[index] = account.copy(platformUserId = normalized)
        }
    }

    @Synchronized
    fun delete(id: String) {
        synchronized(cache) { cache.removeAll { it.id == id } }
    }

    @Synchronized
    fun find(id: String): ManagedAccount? = all().firstOrNull { it.id == id }

    private fun normalizeUserId(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }

    companion object {
        private var legacyCleanupDone = false

        @Synchronized
        fun clearMemory() {
            synchronized(cache) { cache.clear() }
        }

        @Synchronized
        fun clearLegacyLocalData(context: Context) {
            if (legacyCleanupDone) return
            val prefs = context.getSharedPreferences("multi_account_registry", Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_ACCOUNTS, "[]").orEmpty()
            runCatching {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    if (item.optString("platform") == HOYOLAB || item.optString("platform") == MIYOUSHE_CN) {
                        runCatching { HoYoCookieVault(context, id).delete() }
                    }
                }
            }
            check(prefs.edit().clear().commit()) { "无法清理旧的本地账号配置" }
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .edit().remove("selected_games").commit()
            synchronized(cache) { cache.clear() }
            legacyCleanupDone = true
        }

        const val HOYOLAB = "hoyolab"
        const val MIYOUSHE_CN = "miyoushe_cn"
        const val SKLAND = "skland"
        const val EXTRA_ACCOUNT_ID = "managed_account_id"
        const val EXTRA_PLATFORM = "managed_account_platform"
        const val KEY_ACCOUNTS = "accounts_json"
        private val cache = mutableListOf<ManagedAccount>()
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
