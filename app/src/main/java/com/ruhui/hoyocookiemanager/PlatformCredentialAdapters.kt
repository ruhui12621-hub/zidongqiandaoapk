package com.ruhui.hoyocookiemanager

/**
 * Platform-owned credential parsing contract.
 *
 * The shared login Activity coordinates WebView/session lifecycle only. Each
 * platform adapter decides which credential fields authenticate a session and
 * how a stable platform identity is extracted.
 */
interface PlatformCredentialAdapter {
    val platformId: String
    val credentialDomains: List<String>
    fun hasAuthenticationCredential(cookie: String): Boolean
    fun resolveIdentity(cookie: String): String?
}

abstract class CookiePlatformCredentialAdapter(
    final override val platformId: String,
    private val identityPriority: List<String>
) : PlatformCredentialAdapter {
    final override val credentialDomains: List<String>
        get() = GlobalModuleRegistry.gameModule(platformId)?.credentialDomains.orEmpty()

    private fun fields(cookie: String): Map<String, String> = cookie.split(';').mapNotNull { part ->
        val key = part.trim().substringBefore('=').trim().lowercase()
        val value = part.trim().substringAfter('=', "").trim()
        if (key.isBlank() || value.isBlank()) null else key to value
    }.toMap()

    final override fun hasAuthenticationCredential(cookie: String): Boolean {
        val markers = GlobalModuleRegistry.gameModule(platformId)?.credentialMarkers.orEmpty()
        return fields(cookie).any { (key, value) -> key in markers && value.isNotBlank() }
    }

    final override fun resolveIdentity(cookie: String): String? {
        val values = fields(cookie)
        return identityPriority.firstNotNullOfOrNull { key ->
            values[key]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        }
    }
}

/** HoYoLAB international account parser. */
object HoYoLabCredentialAdapter : CookiePlatformCredentialAdapter(
    platformId = AccountStore.HOYOLAB,
    identityPriority = listOf("ltuid_v2", "ltuid", "account_id_v2", "account_id", "stuid_v2", "stuid", "login_uid")
)

/** Mainland China Miyoushe account parser; stuid takes precedence over ltuid. */
object MiyousheCnCredentialAdapter : CookiePlatformCredentialAdapter(
    platformId = AccountStore.MIYOUSHE_CN,
    identityPriority = listOf("stuid_v2", "stuid", "ltuid_v2", "ltuid", "account_id_v2", "account_id", "login_uid")
)

/** Token validation remains owned by the Skland platform adapter. */
object SklandCredentialAdapter {
    fun isValidToken(value: String): Boolean =
        GlobalModuleRegistry.isValidCredential(AccountStore.SKLAND, value)
}

object PlatformCredentialAdapters {
    private val adapters: Map<String, PlatformCredentialAdapter> = listOf(
        HoYoLabCredentialAdapter,
        MiyousheCnCredentialAdapter
    ).associateBy { it.platformId }

    fun forPlatform(platformId: String): PlatformCredentialAdapter? = adapters[platformId]

    fun cookieDomains(): List<String> =
        adapters.values.flatMap { it.credentialDomains }.distinct()
}

/**
 * Compatibility facade for older call sites. The actual parsing belongs to the
 * platform adapters above, not the shared account store.
 */
object HoYoAccountIdentity {
    fun fromCookie(cookie: String, platform: String): String? =
        PlatformCredentialAdapters.forPlatform(platform)?.resolveIdentity(cookie)
}
