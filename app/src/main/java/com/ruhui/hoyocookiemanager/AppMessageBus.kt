package com.ruhui.hoyocookiemanager

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide, non-sensitive operation/diagnostic message shared by all Activities.
 * Credentials and cookie/token values are never intentionally written here.
 */
object AppMessageBus {
    @Volatile private var latest = "暂无操作记录"

    fun post(context: Context, title: String, detail: String, success: Boolean? = null) {
        val safeTitle = sanitize(title)
        val safeDetail = sanitize(detail)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val prefix = when (success) {
            true -> "✓ "
            false -> "✕ "
            null -> "… "
        }
        latest = "$prefix[$time] $safeTitle\n$safeDetail"
        runCatching {
            context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
                .edit().putString("latest", latest).apply()
        }
    }

    fun current(context: Context): String {
        if (latest == "暂无操作记录") {
            latest = runCatching {
                context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
                    .getString("latest", latest) ?: latest
            }.getOrDefault(latest)
        }
        return latest
    }

    private fun sanitize(value: String): String {
        var safe = value
        safe = Regex("(?i)(cookie|token|authorization|set-cookie|\\bAK\\b|\\bSK\\b)\\s*[:=]\\s*[^\\s,;]+")
            .replace(safe) { match -> match.value.substringBefore(Regex("[:=]")) + ": [已隐藏]" }
        safe = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*").replace(safe, "Bearer [已隐藏]")
        return safe.take(4000)
    }
}
