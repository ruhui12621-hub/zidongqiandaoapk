package com.ruhui.hoyocookiemanager

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Separate, local diagnostics for the HoYoLAB and Skland workspaces. */
object AppMessageBus {
    const val HOYOLAB = "hoyolab"
    const val SKLAND = "skland"

    @Volatile private var activeChannel = HOYOLAB

    fun setActiveChannel(channel: String) {
        activeChannel = normalize(channel)
    }

    fun activeChannel(): String = activeChannel

    fun post(context: Context, channel: String, title: String, detail: String, success: Boolean? = null) {
        val key = normalize(channel)
        val safeTitle = sanitize(title)
        val safeDetail = sanitize(detail)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val prefix = when (success) { true -> "✓ "; false -> "✕ "; null -> "… " }
        val message = "$prefix[$time] $safeTitle\n$safeDetail"
        runCatching {
            context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
                .edit().putString("latest_$key", message).apply()
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

    private fun normalize(channel: String): String =
        if (channel.equals(SKLAND, true)) SKLAND else HOYOLAB

    private fun sanitize(value: String): String {
        var safe = value
        safe = Regex("(?i)(cookie|token|authorization|set-cookie|\\bAK\\b|\\bSK\\b)\\s*[:=]\\s*[^\\s,;]+")
            .replace(safe) { match -> match.value.substringBefore("=").substringBefore(":") + ": [已隐藏]" }
        safe = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*").replace(safe, "Bearer [已隐藏]")
        return safe.take(4000)
    }
}
