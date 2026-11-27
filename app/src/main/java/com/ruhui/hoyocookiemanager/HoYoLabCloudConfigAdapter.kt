package com.ruhui.hoyocookiemanager

/**
 * HoYoLAB/Miyoushe-owned YAML configuration adapter.
 *
 * The shared template coordinates cloud reads and FunctionGraph deployment.
 * This adapter understands only the HoYoLAB function's config.yaml schema.
 */
object HoYoLabCloudConfigAdapter {
    fun cookieFields(cookie: String): Map<String, String> =
        cookie.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val split = trimmed.indexOf('=')
            if (split <= 0) null else trimmed.substring(0, split).trim() to trimmed.substring(split + 1).trim()
        }.toMap()

    /** Preserve all non-account YAML settings while removing legacy plaintext credentials. */
    fun clearAccountCredentials(source: String): String {
        val lines = source.split("\n").toMutableList()
        var inAccount = false
        var inGames = false
        var inOs = false
        for (index in lines.indices) {
            val original = lines[index]
            val line = original.removeSuffix("\r")
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' }.length
            if (indent == 0) {
                inAccount = trimmed == "account:"
                inGames = trimmed == "games:"
                inOs = false
                continue
            }
            var key: String? = null
            if (inAccount && indent == 2) {
                key = trimmed.substringBefore(':').takeIf { it in setOf("cookie", "stuid", "stoken", "mid") }
            } else if (inGames && indent == 2) {
                inOs = trimmed == "os:"
            } else if (inGames && inOs && indent == 4) {
                key = trimmed.substringBefore(':').takeIf { it == "cookie" }
            }
            if (key != null) {
                val ending = if (original.endsWith("\r")) "\r" else ""
                lines[index] = " ".repeat(indent) + key + ": \"\"" + ending
            }
        }
        return lines.joinToString("\n")
    }

    /** Replace only account.cookie (CN) or games.os.cookie (global); preserve every other YAML line. */
    fun replaceCookie(source: String, cookie: String, server: String): String {
        require(server == "cn" || server == "os") { "未知 HoYoLAB 服务器：$server" }
        val lines = source.split("\n").toMutableList()
        var inAccount = false
        var inGames = false
        var inOs = false
        val matches = mutableListOf<Int>()
        for (i in lines.indices) {
            val line = lines[i].removeSuffix("\r")
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' }.length
            if (server == "cn") {
                if (indent == 0) inAccount = trimmed == "account:"
                if (inAccount && indent == 2 && trimmed.startsWith("cookie:")) matches.add(i)
            } else {
                if (indent == 0) {
                    inGames = trimmed == "games:"
                    inOs = false
                } else if (inGames && indent == 2) {
                    inOs = trimmed == "os:"
                } else if (inGames && inOs && indent == 4 && trimmed.startsWith("cookie:")) {
                    matches.add(i)
                }
            }
        }
        if (matches.size != 1) {
            val path = if (server == "cn") "account.cookie" else "games.os.cookie"
            error("云端配置中无法唯一定位 " + path + "（匹配数：" + matches.size + "），为避免覆盖其他配置，已停止上传。")
        }
        val index = matches.single()
        val oldLine = lines[index]
        val lineEnding = if (oldLine.endsWith("\r")) "\r" else ""
        val indent = oldLine.takeWhile { it == ' ' }.length
        val escaped = cookie.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "").replace("\n", "")
        lines[index] = " ".repeat(indent) + "cookie: \"$escaped\"" + lineEnding
        return lines.joinToString("\n")
    }

    /** Update only each supported game's checkin flag inside games.cn or games.os. */
    fun replaceGames(source: String, selected: Set<String>, server: String): String {
        val supported = when (server) {
            "cn" -> supportedCnGames
            "os" -> supportedOsGames
            else -> error("未知 HoYoLAB 服务器：$server")
        }
        if (!supported.containsAll(selected)) error("游戏选择中包含当前服务器不支持的项目，已停止上传。")
        val lines = source.split("\n").toMutableList()
        var inGames = false
        var inServer = false
        var currentGame: String? = null
        val matches = mutableMapOf<String, MutableList<Int>>()
        supported.forEach { matches[it] = mutableListOf() }
        for (i in lines.indices) {
            val line = lines[i].removeSuffix("\r")
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' }.length
            if (indent == 0) {
                inGames = trimmed == "games:"
                inServer = false
                currentGame = null
                continue
            }
            if (!inGames) continue
            if (indent == 2) {
                inServer = trimmed == "$server:"
                currentGame = null
                continue
            }
            if (!inServer) continue
            if (indent == 4 && trimmed.endsWith(":")) {
                currentGame = trimmed.removeSuffix(":").takeIf { it in supported }
                continue
            }
            if (indent == 6 && trimmed.startsWith("checkin:")) {
                val game = currentGame ?: continue
                matches[game]?.add(i)
            }
        }
        val missing = supported.filter { matches[it]?.size != 1 }
        if (missing.isNotEmpty()) {
            error("云端配置中无法唯一定位游戏签到选项：" + missing.joinToString("、") +
                "。为避免改动错误配置，已停止上传。")
        }
        for (game in supported) {
            val index = matches.getValue(game).single()
            val old = lines[index]
            val ending = if (old.endsWith("\r")) "\r" else ""
            val content = old.removeSuffix("\r")
            val match = Regex("^(\\s*checkin:\\s*)(true|false)(.*)$", RegexOption.IGNORE_CASE)
                .matchEntire(content) ?: error("游戏 $game 的 checkin 字段格式无法识别，已停止上传。")
            lines[index] = match.groupValues[1] + (game in selected).toString() +
                match.groupValues[3] + ending
        }
        return lines.joinToString("\n")
    }
}
