package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var webView: WebView
    private var cookieValue = ""
    private var yamlValue = ""
    private var browserVisible = false

    private val cookieUrls = listOf(
        "https://www.hoyolab.com/",
        "https://act.hoyolab.com/",
        "https://bbs-api-os.hoyolab.com/"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        buildHome()
        refreshFromWebView()
    }

    private fun buildHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(text("HoYoLAB Cookie Manager", 24, true).apply {
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(text("独立运行 · Cookie 仅保存在本机", 13, false, Color.DKGRAY))

        val previousCrash = CrashLogger.read(this)
        if (!previousCrash.isNullOrBlank()) {
            content.addView(text("检测到上次闪退记录", 18, true, Color.rgb(180, 45, 45)).apply {
                setPadding(0, dp(18), 0, dp(6))
            })
            content.addView(text(previousCrash, 12, false, Color.DKGRAY).apply {
                setTextIsSelectable(true)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(Color.rgb(235, 235, 235))
            })
            content.addView(button("复制闪退记录") {
                copy(previousCrash, "闪退记录")
            })
            content.addView(button("清除闪退记录") {
                CrashLogger.clear(this)
                buildHome()
                refreshFromWebView()
                toast("已清除闪退记录")
            })
        }

        status = text("登录状态：尚未检测", 16, true).apply {
            setPadding(0, dp(22), 0, dp(8))
        }
        content.addView(status)
        content.addView(button("打开 HoYoLAB 登录页面") { showBrowser() })
        content.addView(button("检测并获取 Cookie") { refreshFromWebView(true) })
        content.addView(button("复制纯 Cookie") { copy(cookieValue, "Cookie") })
        content.addView(button("复制完整 config.yaml") { copy(yamlValue, "config.yaml") })
        content.addView(button("保存 config.yaml 到应用内部") { saveConfig() })
        content.addView(button("清除登录会话") { clearSession() })
        details = text("尚未获取 Cookie。点击上方按钮登录。", 13, false, Color.DKGRAY).apply {
            setPadding(0, dp(18), 0, dp(10))
        }
        content.addView(details)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showBrowser() {
        if (!::webView.isInitialized) {
            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        status.text = "页面已加载，正在检查 Cookie…"
                        view.postDelayed({ refreshFromWebView() }, 900)
                    }
                }
            }
        }

        root.removeAllViews()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "返回"
            setOnClickListener {
                buildHome()
                refreshFromWebView()
            }
        }
        bar.addView(back)
        bar.addView(text("HoYoLAB 登录", 18, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        root.addView(bar)
        (webView.parent as? ViewGroup)?.removeView(webView)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!browserVisible || webView.url.isNullOrBlank()) {
            webView.loadUrl("https://www.hoyolab.com/")
        }
        browserVisible = true
    }

    private fun refreshFromWebView(showToast: Boolean = false) {
        val manager = CookieManager.getInstance()
        val collected = linkedSetOf<String>()
        cookieUrls.forEach { url ->
            manager.getCookie(url)?.split(';')
                ?.map { it.trim() }
                ?.filter { it.contains('=') }
                ?.forEach { collected.add(it) }
        }
        val found = collected.joinToString("; ")
        cookieValue = found
        val names = found.split(';')
            .mapNotNull { part ->
                part.trim().substringBefore('=').takeIf { it.isNotBlank() }
            }.toSet()
        val hasLoginToken = names.any {
            it == "ltoken_v2" || it == "ltoken" ||
                it == "cookie_token_v2" || it == "cookie_token"
        }

        if (hasLoginToken) {
            yamlValue = makeYaml(found)
            status.text = "登录状态：检测到登录凭据"
            details.text = "已读取 ${names.size} 个 Cookie 字段。\n关键字段：" +
                names.filter {
                    it.contains("token") || it.contains("account") || it.contains("uid")
                }.sorted().joinToString(", ") +
                "\n\nCookie 不会自动上传，复制操作由你主动触发。"
            if (showToast) toast("已获取 Cookie")
        } else {
            yamlValue = ""
            status.text = "登录状态：未检测到有效登录凭据"
            details.text = if (found.isBlank()) {
                "当前没有读取到 HoYoLAB Cookie。请在内置浏览器完成登录，并返回检测。"
            } else {
                "读取到 ${names.size} 个 Cookie 字段，但未发现 ltoken / cookie_token。请确认已经完成登录。"
            }
            if (showToast) toast("未发现登录 Cookie，请确认登录成功")
        }
    }

    private fun makeYaml(cookie: String): String {
        val template = assets.open("config-template.yaml").bufferedReader().use { it.readText() }
        val escaped = cookie.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "")
        return template.replace("__HOYOLAB_COOKIE__", escaped)
    }

    private fun copy(value: String, label: String) {
        if (value.isBlank()) {
            toast("请先登录并获取 Cookie")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("已复制 $label")
    }

    private fun saveConfig() {
        if (yamlValue.isBlank()) {
            toast("请先获取 Cookie")
            return
        }
        openFileOutput("config.yaml", Context.MODE_PRIVATE).use {
            it.write(yamlValue.toByteArray(Charsets.UTF_8))
        }
        toast("已保存到应用内部 files/config.yaml")
    }

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            cookieValue = ""
            yamlValue = ""
            if (::webView.isInitialized) webView.clearCache(true)
            browserVisible = false
            buildHome()
            status.text = "登录状态：已清除本地 WebView Cookie"
            details.text = "登录会话已清除。"
            toast("已清除登录会话")
        }
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(7)
            }
        }

    private fun text(
        value: String,
        size: Int,
        bold: Boolean,
        color: Int = Color.BLACK
    ): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
