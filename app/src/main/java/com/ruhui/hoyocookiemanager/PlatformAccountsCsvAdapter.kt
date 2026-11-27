package com.ruhui.hoyocookiemanager

/**
 * Shared RFC-4180 CSV schema used by the platform FunctionGraph packages.
 * Credential values stay in memory only; they are never written to Android storage.
 */
data class PlatformCsvAccount(
    val accountId: String,
    val platform: String,
    val server: String = "",
    val name: String,
    val userId: String,
    val credential: String,
    val stuid: String,
    val stoken: String,
    val mid: String,
    val games: Set<String>,
    val enabled: Boolean,
    val remark: String
)

object PlatformAccountsCsvAdapter {
    val header = listOf(
        "account_id", "platform", "server", "name", "user_id", "credential",
        "stuid", "stoken", "mid", "games", "enabled", "remark"
    )

    private val allowedGames = mapOf(
        AccountStore.HOYOLAB to setOf("genshin", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz"),
        AccountStore.MIYOUSHE_CN to setOf("genshin", "honkai2", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz"),
        AccountStore.SKLAND to setOf("arknights", "endfield")
    )

    fun parse(source: String, allowedPlatforms: Set<String>? = null): List<PlatformCsvAccount> {
        require(source.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
            "accounts.csv 超过 2 MB。"
        }
        val records = parseRecords(source.removePrefix("\uFEFF"))
        require(records.isNotEmpty() && records.first() == header) {
            "accounts.csv 表头不匹配，拒绝读取。"
        }
        val rows = records.drop(1).filter { row -> row.any { it.isNotEmpty() } }
        require(rows.size <= 100) { "accounts.csv 最多支持 100 个账号。" }
        val parsed = rows.mapIndexed { index, cells ->
            require(cells.size == header.size) { "accounts.csv 第 ${index + 2} 行列数不正确。" }
            val values = header.zip(cells).toMap()
            val csvPlatform = values.getValue("platform")
            val server = values.getValue("server")
            val platform = when {
                csvPlatform == "hoyolab" && server == "cn" -> AccountStore.MIYOUSHE_CN
                csvPlatform == "hoyolab" && server == "os" -> AccountStore.HOYOLAB
                csvPlatform == "skland" && server.isBlank() -> AccountStore.SKLAND
                else -> error("accounts.csv 第 ${index + 2} 行平台或 server 字段无效。")
            }
            val enabledValue = values.getValue("enabled").trim().lowercase()
            require(enabledValue in setOf("true", "false", "1", "0")) {
                "accounts.csv 第 ${index + 2} 行启用字段必须是 true/false。"
            }
            require(platform in allowedGames && (allowedPlatforms == null || platform in allowedPlatforms)) {
                "accounts.csv 第 ${index + 2} 行平台不属于当前函数。"
            }
            PlatformCsvAccount(
                accountId = values.getValue("account_id").trim(),
                platform = platform,
                server = server,
                name = values.getValue("name"),
                userId = values.getValue("user_id").trim(),
                credential = values.getValue("credential"),
                stuid = values.getValue("stuid"),
                stoken = values.getValue("stoken"),
                mid = values.getValue("mid"),
                games = values.getValue("games").split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                enabled = enabledValue == "true" || enabledValue == "1",
                remark = values.getValue("remark")
            )
        }
        validate(parsed, allowedPlatforms)
        return parsed
    }

    fun encode(accounts: List<PlatformCsvAccount>, allowedPlatforms: Set<String>? = null): String {
        validate(accounts, allowedPlatforms)
        return buildString {
            append(header.joinToString(",") { encodeCell(it) }).append("\r\n")
            accounts.forEach { row ->
                val cells = listOf(
                    row.accountId,
                    if (row.platform == AccountStore.HOYOLAB || row.platform == AccountStore.MIYOUSHE_CN) "hoyolab" else "skland",
                    if (row.platform == AccountStore.MIYOUSHE_CN) "cn" else if (row.platform == AccountStore.HOYOLAB) "os" else "",
                    row.name, row.userId, row.credential, row.stuid, row.stoken, row.mid,
                    row.games.sorted().joinToString("|"),
                    row.enabled.toString(), row.remark
                )
                append(cells.joinToString(",") { encodeCell(it) }).append("\r\n")
            }
        }
    }

    private fun validate(accounts: List<PlatformCsvAccount>, allowedPlatforms: Set<String>?) {
        require(accounts.size <= 100) { "accounts.csv 最多支持 100 个账号。" }
        val ids = mutableSetOf<String>()
        val credentials = mutableSetOf<String>()
        accounts.forEachIndexed { index, row ->
            require(row.accountId.isNotBlank() && ids.add(row.accountId)) {
                "账号表第 ${index + 1} 行账号 ID 为空或重复。"
            }
            require(row.platform in allowedGames && (allowedPlatforms == null || row.platform in allowedPlatforms)) {
                "账号表第 ${index + 1} 行平台不正确。"
            }
            val supported = allowedGames.getValue(row.platform)
            require(supported.containsAll(row.games)) {
                "账号「${row.name.ifBlank { row.accountId }}」的游戏选择包含不支持的游戏。"
            }
            require(!row.credential.contains('\n') && !row.credential.contains('\r')) {
                "账号「${row.name.ifBlank { row.accountId }}」的凭据包含换行符。"
            }
            require(listOf(row.accountId, row.platform, row.name, row.userId, row.stuid, row.stoken, row.mid, row.remark)
                .none { it.contains('\n') || it.contains('\r') }) {
                "账号「${row.name.ifBlank { row.accountId }}」的非凭据字段不能包含换行符。"
            }
            if (row.enabled) {
                require(row.credential.isNotBlank()) {
                    "启用账号「${row.name.ifBlank { row.accountId }}」尚未获取凭据。"
                }
                if (row.platform == AccountStore.MIYOUSHE_CN) {
                    require(row.stuid.isNotBlank() && row.stoken.isNotBlank() && row.mid.isNotBlank()) {
                        "国服账号「${row.name.ifBlank { row.accountId }}」缺少 stuid/stoken/mid。"
                    }
                }
            }
            if (row.credential.isNotBlank()) {
                require(credentials.add(row.credential)) { "账号表存在重复凭据，拒绝上传。" }
            }
            if (row.platform == AccountStore.SKLAND && row.credential.isNotBlank()) {
                require(!row.credential.any(Char::isWhitespace)) { "森空岛 Token 格式无效。" }
            }
        }
    }

    private fun parseRecords(source: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            when {
                quoted && ch == '"' && index + 1 < source.length && source[index + 1] == '"' -> {
                    field.append('"'); index += 2; continue
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    row.add(field.toString()); field.setLength(0)
                }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (ch == '\r' && index + 1 < source.length && source[index + 1] == '\n') index++
                    row.add(field.toString()); field.setLength(0)
                    result.add(row); row = mutableListOf()
                }
                else -> field.append(ch)
            }
            index++
        }
        require(!quoted) { "accounts.csv 包含未闭合的引号。" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            result.add(row)
        }
        return result
    }

    private fun encodeCell(value: String): String {
        val quote = value.contains(',') || value.contains('"') ||
            value.contains('\n') || value.contains('\r') ||
            value != value.trim()
        return if (quote) "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
