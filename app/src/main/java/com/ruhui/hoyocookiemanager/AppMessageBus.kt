package com.ruhui.hoyocookiemanager

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Unified in-app operation history, with legacy per-service channels retained for compatibility. */
object AppMessageBus {
    const val HOYOLAB = "hoyolab"
    const val SKLAND = "skland"

    @Volatile private var activeChannel = HOYOLAB

    fun setActiveChannel(channel: String) {
        activeChannel = normalize(channel)
    }

    fun activeChannel(): String = activeChannel

    fun beginSession(context: Context, channel: String, title: String, detail: String) {
        // Keep the click/Activity-launch events already recorded by the calling screen.
        post(context, channel, title, detail, null)
    }

    fun clear(context: Context, channel: String) {
        val key = normalize(channel)
        runCatching {
            context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
                .edit().remove("latest_$key").apply()
        }
    }

    fun post(context: Context, channel: String, title: String, detail: String, success: Boolean? = null) {
        val key = normalize(channel)
        val safeTitle = sanitize(title)
        val safeDetail = sanitize(detail)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val prefix = when (success) { true -> "✓ "; false -> "✕ "; null -> "… " }
        val message = "$prefix[$time] $safeTitle\n$safeDetail"
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
            val previous = prefs.getString("latest_$key", "").orEmpty()
            val channelLabel = if (key == SKLAND) "森空岛" else "HoYoLAB / 米游社 / 华为云"
            val unifiedEntry = "【$channelLabel】\n$message"
            val allPrevious = prefs.getString("latest_all", "").orEmpty()
            val allCombined = (if (allPrevious.isBlank()) unifiedEntry else "$allPrevious\n\n$unifiedEntry")
                .split("\n\n").takeLast(120).joinToString("\n\n").takeLast(60000)
            val channelCombined = (if (previous.isBlank()) message else "$previous\n\n$message")
                .split("\n\n").takeLast(40).joinToString("\n\n").takeLast(24000)
            prefs.edit().putString("latest_$key", channelCombined).putString("latest_all", allCombined).apply()
        }
    }

    fun current(context: Context, channel: String): String {
        val key = normalize(channel)
        return runCatching {
            context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
                .getString("latest_$key", "暂无操作记录") ?: "暂无操作记录"
        }.getOrDefault("暂无操作记录")
    }

    fun currentActive(context: Context): String = current(context, activeChannel)

    /** All service events in one chronological stream for the app-wide message page. */
    fun currentAll(context: Context): String = runCatching {
        val prefs = context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
        val saved = prefs.getString("latest_all", "").orEmpty()
        if (saved.isNotBlank()) saved else {
            val hoyo = prefs.getString("latest_$HOYOLAB", "").orEmpty()
            val skland = prefs.getString("latest_$SKLAND", "").orEmpty()
            listOf(
                hoyo.takeIf { it.isNotBlank() }?.let { "【HoYoLAB / 米游社 / 华为云】\\n$it" },
                skland.takeIf { it.isNotBlank() }?.let { "【森空岛】\\n$it" }
            ).filterNotNull().joinToString("\\n\\n").ifBlank { "暂无运行消息" }
        }
    }.getOrDefault("暂无运行消息")

    private fun normalize(channel: String): String =
        if (channel.equals(SKLAND, true)) SKLAND else HOYOLAB

    private fun sanitize(value: String): String {
        var safe = value
        safe = Regex("(?i)(cookie|token|access_token|refresh_token|authorization|set-cookie|\\bAK\\b|\\bSK\\b|stoken|ltoken|session|ticket|\\bcode\\b)\\s*[:=]\\s*[^\\s,;&]+")
            .replace(safe) { match -> match.value.substringBefore("=").substringBefore(":") + ": [已隐藏]" }
        safe = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*").replace(safe, "Bearer [已隐藏]")
        return safe.take(4000)
    }
}
