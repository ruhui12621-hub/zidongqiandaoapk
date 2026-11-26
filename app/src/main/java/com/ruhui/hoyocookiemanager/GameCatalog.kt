package com.ruhui.hoyocookiemanager

import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for game keys and the display names shown in account-detail tables.
 * The same display name is used when matching today's FunctionGraph log records.
 */
object GameCatalog {
    data class Game(val key: String, val label: String)

    val hoyolabCn = listOf(
        Game("genshin", "原神"),
        Game("honkai2", "崩坏2"),
        Game("honkai3rd", "崩坏3"),
        Game("tears_of_themis", "未定事件簿"),
        Game("honkai_sr", "崩坏：星穹铁道"),
        Game("zzz", "绝区零")
    )

    val hoyolabOs = listOf(
        Game("genshin", "原神"),
        Game("honkai3rd", "崩坏3"),
        Game("tears_of_themis", "未定事件簿"),
        Game("honkai_sr", "崩坏：星穹铁道"),
        Game("zzz", "绝区零")
    )

    val skland = listOf(
        Game("arknights", "明日方舟"),
        Game("endfield", "明日方舟：终末地")
    )

    private val labels = (hoyolabCn + skland).associate { it.key to it.label }

    fun label(key: String): String = labels[key] ?: key

    fun aliases(key: String): List<String> = when (key) {
        "genshin" -> listOf("Genshin Impact", "genshin")
        "honkai2" -> listOf("崩坏学园2", "Honkai 2", "Honkai Impact 2")
        "honkai3rd" -> listOf("崩坏3rd", "Honkai Impact 3", "Honkai 3rd")
        "tears_of_themis" -> listOf("Tears of Themis")
        "honkai_sr" -> listOf("星穹铁道", "Honkai: Star Rail", "Honkai Star Rail")
        "zzz" -> listOf("Zenless Zone Zero", "ZZZ")
        "arknights" -> listOf("Arknights")
        "endfield" -> listOf("终末地", "Endfield")
        else -> emptyList()
    }

    fun json(): JSONObject = JSONObject()
        .put("hoyolab_cn", toJson(hoyolabCn))
        .put("hoyolab_os", toJson(hoyolabOs))
        .put("skland", toJson(skland))

    private fun toJson(games: List<Game>): JSONArray = JSONArray().apply {
        games.forEach { put(JSONObject().put("key", it.key).put("label", it.label)) }
    }
}
