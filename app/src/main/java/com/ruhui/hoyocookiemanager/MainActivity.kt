package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : HuaweiCloudActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var webView: WebView
    private var popupWebView: WebView? = null
    private var cookieValue = ""
    private var yamlValue = ""
    private var browserVisible = false
    private var showingLauncher = true
    private var showingBrowser = false
    private val webDiagnostics = mutableListOf<String>()

    private val cookieUrls = listOf(
        "https://www.hoyolab.com/",
        "https://act.hoyolab.com/",
        "https://bbs-api-os.hoyolab.com/"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        buildLauncher()
    }

    override fun onDestroy() {
        // 只销毁 WebView 实例，不清理 Cookie、缓存或站点存储；登录状态由独立数据目录持久保存。
        try {
            popupWebView?.let { popup ->
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.stopLoading()
                popup.destroy()
                popupWebView = null
            }
            CookieManager.getInstance().flush()
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.destroy()
            }
        } catch (_: Exception) {
            // 系统回收时，WebView 数据目录仍会保留登录状态。
        }
        super.onDestroy()
    }

    private fun buildLauncher() {
        showingLauncher = true
        showingBrowser = false
        if (::webView.isInitialized) (webView.parent as? ViewGroup)?.removeView(webView)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        root.addView(text("账号工具箱", 28, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(text("请选择要使用的服务", 14, false, Color.rgb(155, 164, 178)).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(34))
        })
        root.addView(button("HoYoLAB") {
            buildHome()
            refreshFromWebView()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(14) })
        root.addView(button("森空岛") {
            startActivity(android.content.Intent(this, SenkongdaoActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
    }

    private fun buildHome() {
        showingLauncher = false
        showingBrowser = false
        if (::webView.isInitialized) (webView.parent as? ViewGroup)?.removeView(webView)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹ 主页") { buildLauncher() }, LinearLayout.LayoutParams(-2, -2))
        header.addView(text("HoYoLAB", 19, true), LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(12)
        })
        root.addView(header)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(text("HoYoLAB Cookie Manager", 24, true).apply {
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(text("桌面版 HoYoLAB · Cookie 登录与云函数配置", 13, false, Color.rgb(160, 168, 182)))
        status = text("登录状态：尚未检测", 16, true).apply {
            setPadding(0, dp(22), 0, dp(8))
        }
        content.addView(status)
        content.addView(button("打开 HoYoLAB 登录页面") { showBrowser() })
        details = text("请使用上方按钮登录 HoYoLAB。登录完成后会自动检测 Cookie；获取成功即可在下方华为云区域更新配置。", 13, false, Color.rgb(160, 168, 182)).apply {
            setPadding(0, dp(8), 0, dp(18))
        }
        content.addView(details)
        addHuaweiCloudPanel(content, { yamlValue }) { showConfigSelection() }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingBrowser && ::webView.isInitialized) {
            if (webView.canGoBack()) webView.goBack() else buildHome()
        } else if (!showingLauncher) {
            buildLauncher()
        } else {
            super.onBackPressed()
        }
    }

    private fun showBrowser() {
        showingBrowser = true
        if (!::webView.isInitialized) {
            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                // 强制桌面站点布局。
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        recordWebDiagnostic(
                            "JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        )
                        return true
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message
                    ): Boolean {
                        recordWebDiagnostic("网站请求打开新窗口：userGesture=$isUserGesture，dialog=$isDialog")
                        val popup = createPopupWebView()
                        popupWebView?.let { old ->
                            (old.parent as? ViewGroup)?.removeView(old)
                            old.destroy()
                        }
                        popupWebView = popup
                        (webView.parent as? ViewGroup)?.let { container ->
                            container.addView(popup, LinearLayout.LayoutParams(-1, 0, 1f))
                            webView.visibility = android.view.View.GONE
                        }
                        val transport = resultMsg.obj as WebView.WebViewTransport
                        transport.webView = popup
                        resultMsg.sendToTarget()
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            recordWebDiagnostic(
                                "主页面加载失败：${request.url.host}，错误码 ${error.errorCode}，${error.description}"
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: android.webkit.WebResourceResponse
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request.isForMainFrame || errorResponse.statusCode >= 400) {
                            recordWebDiagnostic(
                                "HTTP ${errorResponse.statusCode}：${request.url.host}${request.url.encodedPath}"
                            )
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: android.webkit.RenderProcessGoneDetail
                    ): Boolean {
                        recordWebDiagnostic("WebView 渲染进程退出，是否被系统回收：${detail.didCrash()}")
                        return true
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        status.text = "页面已加载，正在检查 Cookie…"
                        recordWebDiagnostic("页面加载完成：${url.substringBefore('?')}")
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
        popupWebView?.let { popup ->
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.destroy()
            popupWebView = null
        }
        webView.visibility = android.view.View.VISIBLE
        if (!browserVisible || webView.url.isNullOrBlank()) {
            webView.loadUrl("https://www.hoyolab.com/")
        }
        browserVisible = true
    }


    // 为 window.open / target=_blank 创建 APK 内部子窗口；CookieManager 与主窗口共享。
    private fun createPopupWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = webView.settings.userAgentString
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    recordWebDiagnostic(
                        "弹窗 JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                            "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                    return true
                }

                override fun onCloseWindow(window: WebView) {
                    recordWebDiagnostic("网站关闭了验证子窗口")
                    (window.parent as? ViewGroup)?.removeView(window)
                    window.stopLoading()
                    window.destroy()
                    if (popupWebView === window) popupWebView = null
                    if (::webView.isInitialized) webView.visibility = android.view.View.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    recordWebDiagnostic("验证子窗口加载完成：${url.substringBefore('?')}")
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        recordWebDiagnostic(
                            "验证子窗口加载失败：${request.url.host}，错误码 ${error.errorCode}，${error.description}"
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame || errorResponse.statusCode >= 400) {
                        recordWebDiagnostic(
                            "验证子窗口 HTTP ${errorResponse.statusCode}：${request.url.host}${request.url.encodedPath}"
                        )
                    }
                }
            }
        }
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
            status.text = "Cookie 状态：已获取"
            details.text = "HoYoLAB 登录凭据有效。下方华为云区域已可使用此 Cookie 更新配置。"
            updateHuaweiCloudConfig(yamlValue)
            if (showToast) toast("已获取 Cookie")
        } else {
            yamlValue = ""
            status.text = "Cookie 状态：未获取"
            details.text = if (found.isBlank()) {
                "请点击上方 HoYoLAB 登录入口完成登录；登录后将自动检测 Cookie。"
            } else {
                "已读取到部分 Cookie，但缺少有效登录凭据。请完成 HoYoLAB 登录后重试。"
            }
            updateHuaweiCloudConfig("")
            if (showToast) toast("未发现登录 Cookie，请确认登录成功")
        }
    }

    private fun webViewEngineInfo(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "Android ${Build.VERSION.RELEASE}；系统 API 低于 26，无法通过公开接口读取 WebView 提供程序版本"
        }
        return try {
            val pkg = WebView.getCurrentWebViewPackage()
            if (pkg == null) "无法识别 WebView 提供程序"
            else "${pkg.packageName} · ${pkg.versionName ?: "版本未知"}"
        } catch (e: Exception) {
            "读取失败：${e.javaClass.simpleName}"
        }
    }

    private fun recordWebDiagnostic(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        webDiagnostics.add("$time  $message")
        while (webDiagnostics.size > 40) webDiagnostics.removeAt(0)
        if (::details.isInitialized && !browserVisible) {
            details.text = diagnosticsSummary()
        }
    }

    private fun diagnosticsSummary(): String =
        buildString {
            append("实际内核：")
            append(webViewEngineInfo())
            append("\\n网页诊断记录：")
            if (webDiagnostics.isEmpty()) append("\\n暂无错误或事件记录")
            else webDiagnostics.takeLast(12).forEach { append("\\n• "); append(it) }
        }

    private fun makeYaml(cookie: String): String {
        var yaml = assets.open("config-template.yaml").bufferedReader().use { it.readText() }
        val escaped = cookie.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "")
        yaml = yaml.replace("__HOYOLAB_COOKIE__", escaped)

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val server = prefs.getString("game_server", "os") ?: "os"
        val selected = prefs.getStringSet("selected_games", setOf("zzz")) ?: setOf("zzz")
        val gamesStart = yaml.indexOf("games:\n")
        val cloudStart = yaml.indexOf("\ncloud_games:", gamesStart + 1)
        if (gamesStart >= 0 && cloudStart > gamesStart) {
            var gamesSection = yaml.substring(gamesStart, cloudStart)
            gamesSection = gamesSection.replace("  cn:\n    enable: false", "  cn:\n    enable: " + (server == "cn"))
            gamesSection = gamesSection.replace("  os:\n    enable: true", "  os:\n    enable: " + (server == "os"))
            yaml = yaml.substring(0, gamesStart) + gamesSection + yaml.substring(cloudStart)
        }

        val supported = mapOf(
            "cn" to listOf("genshin", "honkai2", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz"),
            "os" to listOf("genshin", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz")
        )
        for (regionKey in listOf("cn", "os")) {
            val regionStart = yaml.indexOf("\n  " + regionKey + ":")
            if (regionStart < 0) continue
            val nextRegion = if (regionKey == "cn") yaml.indexOf("\n  os:", regionStart + 1) else yaml.indexOf("\ncloud_games:", regionStart + 1)
            val sectionEnd = if (nextRegion >= 0) nextRegion else yaml.length
            var section = yaml.substring(regionStart, sectionEnd)
            for (game in supported.getValue(regionKey)) {
                val gameStart = Regex("(?m)^    " + Regex.escape(game) + ":\\s*$").find(section)?.range?.first ?: continue
                val afterStart = section.indexOf("\n", gameStart).let { if (it < 0) section.length else it + 1 }
                val nextGame = Regex("(?m)^    [a-z0-9_]+:\\s*$").find(section, afterStart)?.range?.first
                val gameEnd = nextGame ?: section.length
                val block = section.substring(gameStart, gameEnd)
                val enabled = regionKey == server && selected.contains(game)
                val changed = Regex("(?m)^      checkin: (true|false)").replaceFirst(
                    block, "      checkin: " + enabled.toString()
                )
                section = section.substring(0, gameStart) + changed + section.substring(gameEnd)
            }
            yaml = yaml.substring(0, regionStart) + section + yaml.substring(sectionEnd)
        }
        return yaml
    }

    private fun showConfigSelection() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedServer = prefs.getString("game_server", "os") ?: "os"
        val savedGames = prefs.getStringSet("selected_games", setOf("zzz"))?.toMutableSet() ?: mutableSetOf("zzz")
        val labels = linkedMapOf(
            "genshin" to "原神",
            "honkai2" to "崩坏2",
            "honkai3rd" to "崩坏3",
            "tears_of_themis" to "未定事件簿",
            "honkai_sr" to "崩坏：星穹铁道",
            "zzz" to "绝区零"
        )
        val supported = mapOf(
            "cn" to listOf("genshin", "honkai2", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz"),
            "os" to listOf("genshin", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz")
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        panel.addView(TextView(this).apply {
            text = "服务器"
            textSize = 16f
            setTextColor(Color.WHITE)
        })
        val radios = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val cnRadio = RadioButton(this).apply {
            text = "国服"; setTextColor(Color.WHITE); id = android.view.View.generateViewId()
        }
        val osRadio = RadioButton(this).apply {
            text = "国际服"; setTextColor(Color.WHITE); id = android.view.View.generateViewId()
        }
        radios.addView(cnRadio)
        radios.addView(osRadio)
        radios.check(if (savedServer == "cn") cnRadio.id else osRadio.id)
        panel.addView(radios)
        panel.addView(TextView(this).apply {
            text = "游戏（可多选）"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(6))
        })
        val checks = linkedMapOf<String, CheckBox>()
        val draftGames = savedGames.toMutableSet()
        fun renderGames() {
            checks.forEach { (key, check) ->
                if (check.isChecked) draftGames.add(key) else draftGames.remove(key)
            }
            val server = if (radios.checkedRadioButtonId == cnRadio.id) "cn" else "os"
            if (panel.childCount > 3) panel.removeViews(3, panel.childCount - 3)
            checks.clear()
            supported.getValue(server).forEach { key ->
                val check = CheckBox(this).apply {
                    text = labels[key] ?: key
                    setTextColor(Color.rgb(230, 234, 242))
                    isChecked = draftGames.contains(key)
                    setPadding(0, dp(3), 0, dp(3))
                }
                checks[key] = check
                panel.addView(check)
            }
        }
        renderGames()
        radios.setOnCheckedChangeListener { _, _ -> renderGames() }
        val scroll = ScrollView(this).apply { addView(panel) }
        AlertDialog.Builder(this)
            .setTitle("配置选择")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存配置") { _, _ ->
                val server = if (radios.checkedRadioButtonId == cnRadio.id) "cn" else "os"
                val chosen = checks.filterValues { it.isChecked }.keys
                if (chosen.isEmpty()) {
                    toast("至少选择一个游戏")
                    return@setPositiveButton
                }
                prefs.edit().putString("game_server", server)
                    .putStringSet("selected_games", chosen.toSet()).apply()
                if (cookieValue.isNotBlank()) {
                    yamlValue = makeYaml(cookieValue)
                    updateHuaweiCloudConfig(yamlValue)
                }
                toast("配置选择已保存")
            }
            .show()
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
            popupWebView?.let { popup ->
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.stopLoading()
                popup.destroy()
                popupWebView = null
            }
            if (::webView.isInitialized) {
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                webView.visibility = android.view.View.VISIBLE
            }
            WebStorage.getInstance().deleteAllData()
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
            textSize = 15f
            setTextColor(Color.rgb(238, 241, 247))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(39, 46, 60))
            minHeight = dp(48)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(9)
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
        setTextColor(if (color == Color.BLACK) Color.rgb(235, 238, 245) else color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
