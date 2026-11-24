package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.webkit.WebStorage
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
    private val webDiagnostics = mutableListOf<String>()

    private val cookieUrls = listOf(
        "https://www.hoyolab.com/",
        "https://act.hoyolab.com/",
        "https://bbs-api-os.hoyolab.com/"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 每次进程启动使用独立 WebView 数据目录，避免复用上一次的登录会话。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("hoyo_private_" + Process.myPid())
        }
        CookieManager.getInstance().setAcceptCookie(true)
        buildHome()
        refreshFromWebView()
    }

    override fun onDestroy() {
        // 离开应用时尽力清理；下次启动也会使用全新的 WebView 数据目录。
        try {
            popupWebView?.let { popup ->
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.stopLoading()
                popup.destroy()
                popupWebView = null
            }
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.clearCache(true)
                webView.clearHistory()
                webView.destroy()
            }
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
            // 系统强制回收时，下一次启动仍会切换到独立数据目录。
        }
        super.onDestroy()
    }

    private fun buildHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(text("HoYoLAB Cookie Manager", 24, true).apply {
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(text("桌面版 HoYoLAB · 临时无痕会话 · Cookie 仅在本次运行期间保留", 13, false, Color.rgb(160, 168, 182)))
        status = text("登录状态：尚未检测", 16, true).apply {
            setPadding(0, dp(22), 0, dp(8))
        }
        content.addView(status)
        content.addView(button("打开 HoYoLAB 登录页面") { showBrowser() })
        details = text("请使用上方按钮登录 HoYoLAB。登录完成后会自动检测 Cookie；获取成功即可在下方华为云区域更新配置。", 13, false, Color.rgb(160, 168, 182)).apply {
            setPadding(0, dp(8), 0, dp(18))
        }
        content.addView(details)

        content.addView(text("华为云 FunctionGraph", 19, true, Color.rgb(235, 238, 245)).apply {
            setPadding(0, dp(18), 0, dp(6))
        })
        content.addView(text("在此直接登录华为云、选择函数并更新配置，无需跳转页面。", 13, false, Color.rgb(160, 168, 182)))
        addHuaweiCloudPanel(content)
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
