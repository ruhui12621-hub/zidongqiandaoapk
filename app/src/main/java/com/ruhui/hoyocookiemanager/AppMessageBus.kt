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
            val channelCombined = (if (previous.isBlank()) message else "$previous\n\n$message")
                .split("\n\n").takeLast(40).joinToString("\n\n").takeLast(24000)
            prefs.edit().putString("latest_$key", channelCombined).apply()
        }
    }

    /** Persist cloud-function deployment results separately for each account. */
    fun postForAccount(context: Context, accountId: String, title: String, detail: String, success: Boolean? = null) {
        if (accountId.isBlank()) return
        val key = "latest_account_" + accountId.take(120)
        val safeTitle = sanitize(title)
        val safeDetail = sanitize(detail)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val prefix = when (success) { true -> "✓ "; false -> "✕ "; null -> "… " }
        val message = "$prefix[$time] $safeTitle\n$safeDetail"
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
            val previous = prefs.getString(key, "").orEmpty()
            val combined = (if (previous.isBlank()) message else "$previous\n\n$message")
                .split("\n\n").takeLast(30).joinToString("\n\n").takeLast(18000)
            prefs.edit().putString(key, combined).apply()
        }
    }

    /** Compact structured deployment stages shown in account details. */
    fun resetAccountStages(context: Context, accountId: String) {
        setAccountStage(context, accountId, "function", "云函数", "未执行", "")
        setAccountStage(context, accountId, "lts", "LTS 日志隔离", "未执行", "")
        setAccountStage(context, accountId, "trigger", "定时触发器", "未执行", "")
        setAccountStage(context, accountId, "test", "函数运行测试", "未执行", "")
        setAccountStage(context, accountId, "output", "测试输出结果", "未执行", "")
        setAccountStage(context, accountId, "raw_output", "原始输出结果", "未执行", "")
    }

    fun setAccountStage(context: Context, accountId: String, stage: String, label: String, status: String, detail: String) {
        if (accountId.isBlank()) return
        val key = "account_stages_" + accountId.take(120)
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
            val json = org.json.JSONObject(prefs.getString(key, "{}").orEmpty())
            json.put(stage, org.json.JSONObject()
                .put("key", stage).put("label", label).put("status", status)
                .put("detail", sanitize(detail, if (stage == "raw_output") 12000 else 500))
                .put("updatedAt", System.currentTimeMillis()))
            prefs.edit().putString(key, json.toString()).apply()
        }
    }

    fun currentAccountStages(context: Context, accountId: String): org.json.JSONArray {
        val key = "account_stages_" + accountId.take(120)
        val saved = runCatching {
            context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
                .getString(key, "{}").orEmpty()
        }.getOrDefault("{}")
        val json = runCatching { org.json.JSONObject(saved) }.getOrDefault(org.json.JSONObject())
        val order = listOf("function", "lts", "trigger", "test", "output", "raw_output")
        val rows = org.json.JSONArray()
        order.forEach { stage ->
            val item = json.optJSONObject(stage) ?: org.json.JSONObject()
                .put("key", stage)
                .put("label", when(stage) {
                    "function" -> "云函数"
                    "lts" -> "LTS 日志隔离"
                    "trigger" -> "定时触发器"
                    "test" -> "函数运行测试"
                    "output" -> "测试输出结果"
                    else -> "原始输出结果"
                }).put("status", "未执行").put("detail", "")
            rows.put(item)
        }
        return rows
    }

    fun currentForAccount(context: Context, accountId: String): String {
        if (accountId.isBlank()) return "暂无云函数更新结果"
        val key = "latest_account_" + accountId.take(120)
        return runCatching {
            context.applicationContext.getSharedPreferences("app_messages", Context.MODE_PRIVATE)
                .getString(key, "暂无云函数更新结果") ?: "暂无云函数更新结果"
        }.getOrDefault("暂无云函数更新结果")
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

    private fun sanitize(value: String, limit: Int = 4000): String {
        var safe = value
        safe = Regex("(?i)(cookie|token|access_token|refresh_token|authorization|set-cookie|\\bAK\\b|\\bSK\\b|stoken|ltoken|session|ticket|\\bcode\\b)\\s*[:=]\\s*[^\\s,;&]+")
            .replace(safe) { match -> match.value.substringBefore("=").substringBefore(":") + ": [已隐藏]" }
        safe = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*").replace(safe, "Bearer [已隐藏]")
        return safe.take(limit)
    }
}
