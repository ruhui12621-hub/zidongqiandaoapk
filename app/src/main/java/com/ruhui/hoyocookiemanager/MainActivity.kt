package com.ruhui.hoyocookiemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : HuaweiCloudActivity() {
    private lateinit var root: LinearLayout
    private var statusText = "登录状态：尚未检测"
    private var detailsText = "登录后自动检测 Cookie。"
    private lateinit var webView: WebView
    private lateinit var loginChrome: WebView
    private var popupWebView: WebView? = null
    private var cookieValue = ""
    private var yamlValue = ""
    private var detectedGameServer: String? = null
    private var activeAccountId: String? = null
    private var activePlatform: String? = null
    private var lastLoggedCookieAccountId: String? = null
    private var lastLoggedCookieServer: String? = null
    private var returnToAccountDetail = false
    private var browserVisible = false
    private var showingBrowser = false
    private val webDiagnostics = mutableListOf<String>()

    private val cookieUrls = listOf(
        "https://www.hoyolab.com/",
        "https://act.hoyolab.com/",
        "https://bbs-api-os.hoyolab.com/",
        "https://www.miyoushe.com/",
        "https://bbs.mihoyo.com/",
        "https://user.mihoyo.com/",
        "https://bbs-api.mihoyo.com/"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountStore.clearLegacyLocalData(this)
        CookieManager.getInstance().setAcceptCookie(true)
        activeAccountId = intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)
        activePlatform = intent.getStringExtra(AccountStore.EXTRA_PLATFORM)
        returnToAccountDetail = intent.getBooleanExtra("return_to_account_detail", false)
        restorePendingCookieSession()
        if (!returnToAccountDetail) AccountStore.clearMemory()
        if (activePlatform != AccountStore.HOYOLAB && activePlatform != AccountStore.MIYOUSHE_CN) {
            finish()
            return
        }
        buildHome()
        showBrowser()
    }

    override fun onDestroy() {
        // 登录 Cookie 只在本次运行期间存在；Activity 退出时清理 WebView 本地会话。
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
                webView.destroy()
            }
            if (::loginChrome.isInitialized) {
                loginChrome.stopLoading()
                loginChrome.removeJavascriptInterface("LoginUi")
                loginChrome.destroy()
            }
            // Activity recreation/navigation must not erase the process-wide login session.
            // CookieManager and WebStorage are cleared only by the user's explicit clear action.
        } catch (_: Exception) {
            // Ignore WebView teardown failures; no credential data is written by this Activity.
        }
        super.onDestroy()
    }

    private fun buildHome() {
        AppMessageBus.setActiveChannel(AppMessageBus.HOYOLAB)
        showingBrowser = false
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 5, 5))
        }
        if (!::loginChrome.isInitialized) {
            loginChrome = WebView(this).apply {
                setBackgroundColor(Color.rgb(5, 5, 5))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                addJavascriptInterface(LoginChromeBridge(), "LoginUi")
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !request.url.toString().startsWith("file:///android_asset/ui/")
                    override fun onPageFinished(view: WebView, url: String) { pushLoginChrome() }
                }
                loadUrl("file:///android_asset/ui/login.html")
            }
        }
        (loginChrome.parent as? ViewGroup)?.removeView(loginChrome)
        root.addView(loginChrome, LinearLayout.LayoutParams(-1, dp(112)))
        setContentView(root)
        pushLoginChrome()
    }

    private fun pushLoginChrome() {
        if (!::loginChrome.isInitialized) return
        val payload = org.json.JSONObject()
            .put("title", if (activePlatform == AccountStore.MIYOUSHE_CN) "米游社登录" else "HoYoLAB 登录")
            .put("status", statusText)
            .put("detail", detailsText)
            .put("message", AppMessageBus.current(this, AppMessageBus.HOYOLAB))
        loginChrome.post {
            loginChrome.evaluateJavascript(
                "window.updateLoginState && window.updateLoginState(" + org.json.JSONObject.quote(payload.toString()) + ")",
                null
            )
        }
    }

    private inner class LoginChromeBridge {
        @android.webkit.JavascriptInterface fun back() {
            runOnUiThread {
                refreshFromWebView()
                if (returnToAccountDetail) finish() else buildHome()
            }
        }
        @android.webkit.JavascriptInterface fun clear() { runOnUiThread { clearSession() } }
        @android.webkit.JavascriptInterface fun refresh() { runOnUiThread { refreshFromWebView(true); pushLoginChrome() } }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingBrowser && ::webView.isInitialized) {
            if (webView.canGoBack()) webView.goBack()
            else if (returnToAccountDetail) finish()
            else buildHome()
        } else if (returnToAccountDetail) {
            finish()
        } else {
            finish()
        }
    }

    private fun showBrowser() {
        showingBrowser = true
        if (!::webView.isInitialized) {
            webView = DesktopWebView.create(
                this,
                multiWindow = true,
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            ).apply {
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
                            DesktopWebView.attachPopup(
                                container, webView, popup, LinearLayout.LayoutParams(-1, 0, 1f)
                            )
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
                        if (HuaweiCloudSession.pendingHoyoCookie.isBlank()) {
                            statusText = "登录状态：等待检测"
                        } else {
                            restorePendingCookieSession()
                        }
                        pushLoginChrome()
                        recordWebDiagnostic("页面加载完成：${url.substringBefore('?')}")
                        DesktopWebView.enableDesktopViewport(view)
                        view.postDelayed({ refreshFromWebView() }, 900)
                    }
                }
            }
        }

        root.removeAllViews()
        (loginChrome.parent as? ViewGroup)?.removeView(loginChrome)
        (webView.parent as? ViewGroup)?.removeView(webView)
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(5, 5, 5)) }, LinearLayout.LayoutParams(-1, dp(36)))
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(loginChrome, LinearLayout.LayoutParams(-1, dp(42)))
        popupWebView?.let { popup ->
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.destroy()
            popupWebView = null
        }
        webView.visibility = android.view.View.VISIBLE
        if (!browserVisible || webView.url.isNullOrBlank()) {
            webView.loadUrl(if (activePlatform == AccountStore.MIYOUSHE_CN) "https://www.miyoushe.com/" else "https://www.hoyolab.com/")
        }
        browserVisible = true
    }


    // 为 window.open / target=_blank 创建 APK 内部子窗口；CookieManager 与主窗口共享。
    private fun createPopupWebView(): WebView {
        return DesktopWebView.create(
            this,
            multiWindow = false,
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        ).apply {
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
                    DesktopWebView.enableDesktopViewport(view)
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

    private fun restorePendingCookieSession() {
        val cached = HuaweiCloudSession.pendingHoyoCookie
        if (cached.isBlank()) return
        val server = HuaweiCloudSession.pendingHoyoCookieServer
        val cachedPlatform = if (server == "cn") AccountStore.MIYOUSHE_CN else AccountStore.HOYOLAB
        if (activePlatform != null && activePlatform != cachedPlatform) return
        cookieValue = cached
        detectedGameServer = server
        activeAccountId = HuaweiCloudSession.pendingHoyoCookieAccountId.ifBlank { activeAccountId.orEmpty() }
            .ifBlank { null }
        yamlValue = makeYaml(cached, server)
        statusText = "Cookie 状态：已获取（${if (server == "cn") "国服" else "国际服"}）"
        detailsText = "已保留本次应用运行期间最新获取的 Cookie。"
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
            .mapNotNull { it.trim().substringBefore('=').takeIf { name -> name.isNotBlank() } }
            .toSet()
        val cnMarkers = setOf("stoken", "stoken_v2", "stuid", "cookie_token", "ltoken")
        val osMarkers = setOf("ltoken_v2", "cookie_token_v2", "account_id_v2", "ltuid_v2")
        val hasCnToken = names.any { it in cnMarkers }
        val hasOsToken = names.any { it in osMarkers }
        val cnDomainHasToken = listOf(
            "https://www.miyoushe.com/", "https://bbs.mihoyo.com/",
            "https://user.mihoyo.com/", "https://bbs-api.mihoyo.com/"
        ).any { url ->
            manager.getCookie(url).orEmpty().split(';').any { part ->
                part.trim().substringBefore('=') in cnMarkers
            }
        }
        detectedGameServer = when {
            hasCnToken && !hasOsToken -> "cn"
            hasOsToken && !hasCnToken -> "os"
            hasCnToken && hasOsToken -> if (cnDomainHasToken) "cn" else "os"
            else -> null
        }

        if (detectedGameServer != null) {
            val platform = if (detectedGameServer == "cn") AccountStore.MIYOUSHE_CN else AccountStore.HOYOLAB
            val requested = activeAccountId?.let { AccountStore(this).find(it) }
            if (requested != null && requested.platform != platform) {
                statusText = "账号平台不匹配"
                detailsText = "当前登录凭据识别为${AccountStore.platformLabel(platform)}，但正在编辑的是${AccountStore.platformLabel(requested.platform)}。请返回并选择正确的平台账号。"
                HuaweiCloudSession.clearPendingHoyoCookie()
                pushLoginChrome()
                return
            }
            val identity = HoYoAccountIdentity.fromCookie(found, platform)
            val store = AccountStore(this)
            var account = requested
                ?: identity?.let { uid -> store.all().firstOrNull { it.platform == platform && it.platformUserId == uid } }
                ?: store.add(platform, platformUserId = identity)
            if (identity != null && account.platformUserId != null && account.platformUserId != identity) {
                statusText = "检测到不同账号"
                detailsText = "当前登录身份与此账号已绑定的 ID 不同。为避免覆盖其他账号，请新建一个账号后再登录。"
                HuaweiCloudSession.clearPendingHoyoCookie()
                return
            }
            val duplicate = identity?.let { uid ->
                store.all().firstOrNull { it.id != account.id && it.platform == platform && it.platformUserId == uid }
            }
            if (duplicate != null) {
                statusText = "账号已存在"
                detailsText = "该平台 ID 已关联到「${duplicate.name}」，请直接使用已有账号，避免重复添加。"
                HuaweiCloudSession.clearPendingHoyoCookie()
                return
            }
            val cookieFields = found.split(';').mapNotNull { part ->
                val key = part.trim().substringBefore('=').trim().lowercase()
                val value = part.trim().substringAfter('=', "").trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }.toMap()
            val tokenKeys = if (platform == AccountStore.MIYOUSHE_CN)
                setOf("stoken", "stoken_v2", "cookie_token", "ltoken")
            else setOf("ltoken_v2", "cookie_token_v2")
            account = account.copy(
                platformUserId = identity ?: account.platformUserId,
                credentialReady = true,
                cookieReady = true,
                tokenReady = tokenKeys.any { !cookieFields[it].isNullOrBlank() }
            )
            store.update(account)
            activeAccountId = account.id

            val server = detectedGameServer!!
            yamlValue = makeYaml(found, server)
            statusText = "Cookie 状态：已获取（${if (server == "cn") "国服" else "国际服"}）"
            detailsText = "已自动识别服务器，更新时只会写入对应服务器配置。"
            HuaweiCloudSession.pendingHoyoCookie = found
            HuaweiCloudSession.pendingHoyoCookieServer = server
            HuaweiCloudSession.pendingHoyoCookieAccountId = account.id
            if (lastLoggedCookieAccountId != account.id || lastLoggedCookieServer != server) {
                lastLoggedCookieAccountId = account.id
                lastLoggedCookieServer = server
                AppMessageBus.post(
                    this, AppMessageBus.HOYOLAB,
                    if (server == "cn") "米游社登录成功" else "HoYoLAB 登录成功",
                    "已识别当前登录账号并成功获取 Cookie（${if (server == "cn") "国服" else "国际服"}）。账号：${account.name}。凭据内容不会写入消息栏。",
                    true
                )
            }
            if (showToast) toast("已获取 Cookie，识别为${if (server == "cn") "国服" else "国际服"}")
        } else {
            // A transient empty scan (for example, while a login page is opening) is not
            // evidence that the user logged out. Keep the last valid process-session Cookie.
            if (HuaweiCloudSession.pendingHoyoCookie.isNotBlank()) {
                restorePendingCookieSession()
            } else {
                cookieValue = ""
                yamlValue = ""
                detectedGameServer = null
                statusText = "Cookie 状态：未获取"
                detailsText = if (found.isBlank()) {
                    "请登录 HoYoLAB 或米游社，登录后自动检测 Cookie 和服务器。"
                } else {
                    "已读取到 Cookie，但无法可靠判断服务器。请确认登录的是国服或国际服账号后重试。"
                }
            }
            if (showToast && HuaweiCloudSession.pendingHoyoCookie.isBlank()) {
                toast("未发现可识别的登录凭据")
            }
        }
        pushLoginChrome()
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
        if (!browserVisible) detailsText = diagnosticsSummary()
    }

    private fun diagnosticsSummary(): String =
        buildString {
            append("实际内核：")
            append(webViewEngineInfo())
            append("\\n网页诊断记录：")
            if (webDiagnostics.isEmpty()) append("\\n暂无错误或事件记录")
            else webDiagnostics.takeLast(12).forEach { append("\\n• "); append(it) }
        }

    private fun makeYaml(cookie: String, server: String): String {
        var yaml = assets.open("config-template.yaml").bufferedReader().use { it.readText() }
        val escaped = cookie.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "")
        yaml = yaml.replace("__HOYOLAB_COOKIE__", escaped)

        if (server == "cn") {
            val cookieFields = cookie.split(';').mapNotNull { part ->
                val key = part.trim().substringBefore('=')
                val value = part.trim().substringAfter('=', "")
                if (key.isBlank() || value.isBlank()) null else key to value
            }.toMap()
            fun yamlValue(value: String): String = "'" + value.replace("'", "''") + "'"
            val stuid = cookieFields["stuid"] ?: cookieFields["ltuid"] ?: ""
            val stoken = cookieFields["stoken"] ?: cookieFields["stoken_v2"] ?: ""
            val mid = cookieFields["mid"] ?: cookieFields["account_mid_v2"] ?: cookieFields["account_mid"] ?: ""
            if (stuid.isNotBlank()) yaml = yaml.replace("  stuid: ''", "  stuid: " + yamlValue(stuid))
            if (stoken.isNotBlank()) yaml = yaml.replace("  stoken: ''", "  stoken: " + yamlValue(stoken))
            if (mid.isNotBlank()) yaml = yaml.replace("  mid: ''", "  mid: " + yamlValue(mid))
        }

        val platform = if (server == "cn") AccountStore.MIYOUSHE_CN else AccountStore.HOYOLAB
        val registry = AccountStore(this)
        val selected = activeAccountId?.let { registry.find(it)?.games } ?: setOf("zzz")
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

    private fun copy(value: String, label: String) {
        if (value.isBlank()) {
            toast("请先登录并获取 Cookie")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("已复制 $label")
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
            statusText = "登录状态：已清除本地 WebView Cookie"
            detailsText = "登录会话已清除。"
            pushLoginChrome()
            toast("已清除登录会话")
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
