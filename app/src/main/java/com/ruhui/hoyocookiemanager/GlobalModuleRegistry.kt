package com.ruhui.hoyocookiemanager

/**
 * Global three-layer registry.
 *
 * TemplateModule is the single shared implementation contract.
 * Platform modules own credential parsing and the games they expose.
 * Account records reference a platform and game keys; credentials remain in their vaults.
 */
object GlobalModuleRegistry {
    data class TemplateDefinition(
        val id: String,
        val label: String,
        val version: Int,
        val responsibilities: List<String>
    )

    data class GameModule(
        val id: String,
        val label: String,
        val credentialType: String,
        val parserDescription: String,
        val credentialMarkers: Set<String> = emptySet(),
        val credentialDomains: List<String> = emptyList(),
        val games: List<GameCatalog.Game>
    )

    data class AccountDefinition(
        val id: String,
        val label: String,
        val fields: List<String>,
        val credentialStorage: String
    )

    /** There is exactly one shared template; platform modules must not fork common flows. */
    val template = TemplateDefinition(
        id = "default",
        label = "通用签到模板",
        version = 2,
        responsibilities = listOf(
            "账号注册与云端同步",
            "统一首页账号展示模型组装",
            "统一登录网页布局与平台呈现策略",
            "通用签到状态和结果展示",
            "华为云函数部署与配置更新",
            "日志组、日志流及运行消息管理"
        )
    )

    /** Platform-specific parsing rules and game catalogs live at this boundary. */
    val gameModules = listOf(
        GameModule(
            id = AccountStore.HOYOLAB,
            label = AccountStore.platformLabel(AccountStore.HOYOLAB),
            credentialType = "Cookie",
            parserDescription = "识别国际服登录凭据，并按国际服规则提取账号身份",
            credentialMarkers = setOf("ltoken_v2", "cookie_token_v2"),
            credentialDomains = listOf(
                "https://www.hoyolab.com/",
                "https://act.hoyolab.com/",
                "https://bbs-api-os.hoyolab.com/"
            ),
            games = GameCatalog.hoyolabOs
        ),
        GameModule(
            id = AccountStore.MIYOUSHE_CN,
            label = AccountStore.platformLabel(AccountStore.MIYOUSHE_CN),
            credentialType = "Cookie",
            parserDescription = "识别国服登录凭据，并按国服规则提取账号身份",
            credentialMarkers = setOf("stoken", "stoken_v2", "cookie_token", "ltoken"),
            credentialDomains = listOf(
                "https://www.miyoushe.com/",
                "https://bbs.mihoyo.com/",
                "https://user.mihoyo.com/",
                "https://bbs-api.mihoyo.com/"
            ),
            games = GameCatalog.hoyolabCn
        ),
        GameModule(
            id = AccountStore.SKLAND,
            label = AccountStore.platformLabel(AccountStore.SKLAND),
            credentialType = "Token",
            parserDescription = "解析森空岛 Token 接口响应、校验凭据并交由独立保管箱加密保存",
            games = GameCatalog.skland
        )
    )

    /** Account records contain only these references and selections, never credential values. */
    val account = AccountDefinition(
        id = "accounts",
        label = "账号",
        fields = listOf("内部唯一 ID", "所属游戏模块 ID", "平台用户 ID", "启用的游戏 ID"),
        credentialStorage = "按内部账号 ID 独立加密保存 Cookie 或 Token"
    )

    fun gameModule(platformId: String): GameModule? =
        gameModules.firstOrNull { it.id == platformId }

    fun gamesFor(platformId: String): List<GameCatalog.Game> =
        gameModule(platformId)?.games.orEmpty()

    fun isGameAllowed(platformId: String, gameId: String): Boolean =
        gamesFor(platformId).any { it.key == gameId }

    fun isValidCredential(platformId: String, value: String): Boolean {
        val module = gameModule(platformId) ?: return false
        return when (module.credentialType) {
            "Token" -> value.isNotBlank() && value.length <= 65536 && !value.any(Char::isWhitespace)
            "Cookie" -> value.split(';').any { part ->
                val key = part.trim().substringBefore('=').trim().lowercase()
                val fieldValue = part.trim().substringAfter('=', "").trim()
                key in module.credentialMarkers && fieldValue.isNotBlank()
            }
            else -> false
        }
    }
}
