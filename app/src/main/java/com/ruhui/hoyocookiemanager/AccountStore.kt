package com.ruhui.hoyocookiemanager

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * Local account registry. Account identity is scoped by platform; the same numeric UID
 * on HoYoLAB, Miyoushe and Skland never merges records.
 *
 * This registry stores metadata only. The cloud platform CSV is authoritative for credentials;
 * only the Skland local convenience copy is held in an encrypted credential vault.
 */
data class ManagedAccount(
    val id: String,
    val platform: String,
    val name: String,
    val platformUserId: String?,
    val games: Set<String>,
    val credentialReady: Boolean = false,
    val cookieReady: Boolean = credentialReady,
    val tokenReady: Boolean = false,
    val enabled: Boolean = true,
    val remark: String = ""
)

class AccountStore(context: Context) {
    // Account/config data is intentionally memory-only. Every app launch repopulates it
    // from the authoritative FunctionGraph package; no account YAML or Cookie is cached locally.
    @Synchronized
    fun all(): List<ManagedAccount> = synchronized(cache) { cache.toList() }

    @Synchronized
    fun add(platform: String, name: String = "", platformUserId: String? = null, games: Set<String> = emptySet(), enabled: Boolean = false, remark: String = ""): ManagedAccount {
        require(platform in PLATFORMS) { "不支持的账号平台" }
        require(games.all { GlobalModuleRegistry.isGameAllowed(platform, it) }) { "所选游戏不属于当前平台" }
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
            games = if (games.isNotEmpty()) games else GlobalModuleRegistry.defaultGamesFor(platform),
            enabled = enabled,
            remark = remark
        )
        synchronized(cache) { cache.add(account) }
        return account
    }

    @Synchronized
    fun reconcileCloudAccounts(platforms: Set<String>, incomingIds: Set<String>) {
        require(platforms.isNotEmpty() && platforms.all { it in PLATFORMS }) { "云端账号同步的平台范围无效" }
        synchronized(cache) {
            cache.removeAll { it.platform in platforms && it.id !in incomingIds }
        }
    }

    @Synchronized
    fun importCloudAccount(
        id: String,
        platform: String,
        name: String,
        platformUserId: String?,
        games: Set<String>,
        enabled: Boolean,
        remark: String,
        credentialReady: Boolean
    ): ManagedAccount {
        require(platform in PLATFORMS) { "不支持的账号平台" }
        require(id.isNotBlank()) { "云端账号缺少稳定 ID" }
        require(games.all { GlobalModuleRegistry.isGameAllowed(platform, it) }) { "云端账号包含不属于当前平台的游戏" }
        val normalized = normalizeUserId(platformUserId)
        val imported = ManagedAccount(
            id = id,
            platform = platform,
            name = name.trim().ifBlank { defaultName(platform, all().count { it.platform == platform } + 1) },
            platformUserId = normalized,
            games = games,
            credentialReady = credentialReady,
            cookieReady = if (platform == HOYOLAB || platform == MIYOUSHE_CN) credentialReady else false,
            tokenReady = platform == SKLAND && credentialReady,
            enabled = enabled,
            remark = remark
        )
        synchronized(cache) {
            val sameId = cache.firstOrNull { it.id == id }
            require(sameId == null || sameId.platform == platform) { "账号 ID 已被其他平台占用" }
            val duplicate = cache.firstOrNull { it.id != id && it.platform == platform && normalized != null && it.platformUserId == normalized }
            require(duplicate == null) { "该平台已存在相同账号 ID" }
            val index = cache.indexOfFirst { it.id == id }
            if (index >= 0) cache[index] = imported else cache.add(imported)
        }
        return imported
    }

    @Synchronized
    fun update(account: ManagedAccount) {
        require(account.platform in PLATFORMS) { "不支持的账号平台" }
        require(account.games.all { GlobalModuleRegistry.isGameAllowed(account.platform, it) }) { "账号启用了不属于当前平台的游戏" }
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
