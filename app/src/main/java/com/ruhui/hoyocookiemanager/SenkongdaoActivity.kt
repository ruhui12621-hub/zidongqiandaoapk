package com.ruhui.hoyocookiemanager

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SafeBrowsingResponse
import android.webkit.RenderProcessGoneDetail
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SenkongdaoActivity : HuaweiCloudActivity() {
    private lateinit var root: LinearLayout
    private var statusText = "登录状态：等待检测"
    private lateinit var browserFrame: FrameLayout
    private var mainView: WebView? = null
    private var popupView: WebView? = null
    private val tokenExecutor = Executors.newSingleThreadExecutor()
    private var loginMode = true
    private var checking = false
    private var saved = false
    private var tokenSavedCached = false
    private lateinit var statusBar: WebView
    private lateinit var statusAssetLoader: WebViewAssetLoader
    private var statusPageReady = false
    private var lastCheck = 0L
    private var loginProfileName = ""
    private lateinit var loginDeviceInfo: DeviceInfoSnapshot
    private var sessionCookieManager: CookieManager? = null
    private var sessionWebStorage: android.webkit.WebStorage? = null
    private var profilePrepared = false
    private var profileCleared = false
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollTask = object : Runnable {
        override fun run() {
            if (!loginMode || saved) return
            scheduleCheck()
            pollHandler.postDelayed(this, 8000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppMessageBus.setActiveChannel(AppMessageBus.SKLAND)
        loginProfileName = LoginWebSession.profileName(intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID) ?: "unassigned")
        loginDeviceInfo = DeviceInfoProvider.capture(this)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            android.widget.Toast.makeText(this, "当前系统 WebView 不支持独立登录空间，请更新 Android System WebView 后重试。", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // The bottom status belongs to this login-page session, not an older saved token.
        tokenSavedCached = false
        AppMessageBus.beginSession(this, AppMessageBus.SKLAND, "森空岛启动", "已进入森空岛登录 Activity，开始初始化 WebView。")
        try {
            initializeStatusBar()
            AppMessageBus.post(this, AppMessageBus.SKLAND, "状态栏初始化", "React 状态栏 WebView 已创建，正在加载本地 React 页面。", null)
            showLogin()
        } catch (e: Throwable) {
            recordException("森空岛页面初始化失败", e)
            val fallback = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(20), dp(20), dp(20), dp(20))
                text = "森空岛登录窗口初始化失败。请返回账号详情页，复制消息栏中的完整诊断记录。"
                setBackgroundColor(Color.rgb(5, 5, 5))
            }
            setContentView(fallback)
            android.widget.Toast.makeText(this, "森空岛窗口初始化失败，详细记录已保存", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        AppMessageBus.post(this, AppMessageBus.SKLAND, "Activity onStart", "森空岛登录窗口进入启动阶段。", null)
    }

    override fun onResume() {
        super.onResume()
        AppMessageBus.post(this, AppMessageBus.SKLAND, "Activity onResume", "森空岛登录窗口已进入前台。", null)
        pushSklandUi()
    }

    override fun onPause() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "Activity onPause", "森空岛登录窗口暂时失去前台焦点。", null)
        super.onPause()
    }

    override fun onStop() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "Activity onStop", "森空岛登录窗口已不可见。", null)
        super.onStop()
    }

    private fun initializeStatusBar() {
        statusAssetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        statusBar = WebView(this).apply {
            setBackgroundColor(Color.rgb(8, 8, 7))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.loadsImagesAutomatically = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(SklandStatusBridge(), "AndroidSkland")
            webChromeClient = android.webkit.WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return statusAssetLoader.shouldInterceptRequest(request.url)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == SKLAND_STATUS_URL) {
                        statusPageReady = true
                        updateReactStatusBar()
                    }
                }
            }
        }
        statusBar.loadUrl(SKLAND_STATUS_URL)
        AppMessageBus.post(this, AppMessageBus.SKLAND, "React 状态栏初始化",
            "底部状态栏使用独立 React 页面渲染，返回操作通过 AndroidSkland 桥接。", true)
    }

    private fun updateReactStatusBar() {
        if (!::statusBar.isInitialized || !statusPageReady) return
        val acquired = tokenSavedCached
        statusBar.post {
            statusBar.evaluateJavascript(
                "window.setSklandTokenState && window.setSklandTokenState($acquired)", null
            )
        }
    }

    private inner class SklandStatusBridge {
        @JavascriptInterface
        fun back() {
            runOnUiThread { leaveLogin() }
        }
    }

    private fun recordException(title: String, error: Throwable) {
        val stack = Log.getStackTraceString(error).lineSequence().take(12).joinToString("\n")
        AppMessageBus.post(
            this, AppMessageBus.SKLAND, title,
            "异常类型：${error.javaClass.name}\n异常信息：${error.message ?: "无"}\n堆栈（前 12 行）：\n$stack",
            false
        )
        pushSklandUi()
    }

    private fun recordNavigation(view: WebView, label: String, url: String) {
        val current = runCatching { view.url.orEmpty() }.getOrDefault("")
        AppMessageBus.post(
            this, AppMessageBus.SKLAND, label,
            "目标地址：$url\n当前地址：${current.ifBlank { "尚未分配" }}\nWebView 标题：${view.title.orEmpty().ifBlank { "暂无" }}",
            null
        )
        pushSklandUi()
    }

    private fun pushSklandUi() {
        runOnUiThread { updateReactStatusBar() }
    }

    private fun showLogin() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "构建森空岛登录窗口", "开始创建登录网页和本地状态栏。", null)
        loginMode = true
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(5, 5, 5)) }
        setContentView(root)
        (statusBar.parent as? ViewGroup)?.removeView(statusBar)
        browserFrame = FrameLayout(this)
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(5, 5, 5)) }, LinearLayout.LayoutParams(-1, dp(36)))
        root.addView(browserFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(statusBar, LinearLayout.LayoutParams(-1, dp(42)))
        val main = DesktopWebView.create(this, multiWindow = true, fitToScreen = false, profileName = loginProfileName)
        mainView = main
        configureLogin(main, false)
        val profile = WebViewCompat.getProfile(main)
        sessionCookieManager = profile.cookieManager
        sessionWebStorage = profile.webStorage
        sessionCookieManager?.setAcceptCookie(true)
        sessionCookieManager?.setAcceptThirdPartyCookies(main, true)
        browserFrame.addView(main, FrameLayout.LayoutParams(-1, -1))
        AppMessageBus.post(this, AppMessageBus.SKLAND, "主 WebView 已创建", "桌面 UA：${main.settings.userAgentString}\n多窗口：${main.settings.supportMultipleWindows()}\nJavaScript：${main.settings.javaScriptEnabled}\nDOM Storage：${main.settings.domStorageEnabled}\nWebView 内核：${runCatching { if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName ?: "未知" else "系统版本未提供" }.getOrDefault("读取失败")}", true)
        AppMessageBus.post(this, AppMessageBus.SKLAND, "请求加载森空岛首页", "即将调用 WebView.loadUrl。\n目标地址：$SklandLoginAdapter.HOME_URL", null)
        if (!profilePrepared) {
            profilePrepared = true
            sessionWebStorage?.deleteAllData()
            sessionCookieManager?.removeAllCookies {
                sessionCookieManager?.flush()
                if (!isFinishing && !isDestroyed) main.loadUrl(SklandLoginAdapter.HOME_URL)
            }
        } else main.loadUrl(SklandLoginAdapter.HOME_URL)
        AppMessageBus.post(this, AppMessageBus.SKLAND, "已调用首页加载", "WebView.loadUrl 已执行；是否真正加载成功，以后续页面回调为准。", null)
        pushSklandUi()
        pollHandler.removeCallbacks(pollTask)
        pollHandler.postDelayed(pollTask, 2500L)
    }

    private fun configureLogin(view: WebView, popup: Boolean) {
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "森空岛网页 JavaScript 错误",
                        "错误：${message.message()}\n来源：${message.sourceId()}\n行号：${message.lineNumber()}\n视图：${if (popup) "验证弹窗" else "主登录窗口"}", false)
                    pushSklandUi()
                }
                return true
            }
            override fun onCreateWindow(source: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网站请求打开新窗口",
                    "是否对话框：$isDialog\n是否由用户手势触发：$isUserGesture\n来源地址：${source.url.orEmpty()}", null)
                return try {
                    val child = DesktopWebView.create(this@SenkongdaoActivity, multiWindow = false, fitToScreen = false, profileName = loginProfileName)
                    configureLogin(child, true)
                    popupView?.let { destroy(it) }
                    popupView = child
                    mainView?.let { source ->
                        DesktopWebView.attachPopup(
                            browserFrame, source, child, FrameLayout.LayoutParams(-1, -1)
                        )
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = child
                    resultMsg.sendToTarget()
                    AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "森空岛验证窗口", "网站弹出的新窗口已在应用内成功创建。", true)
                    true
                } catch (e: Exception) {
                    recordException("森空岛验证窗口创建失败", e)
                    false
                }
            }
            override fun onCloseWindow(window: WebView) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网站关闭验证窗口",
                    "被关闭窗口地址：${window.url.orEmpty()}", null)
                destroy(window)
                if (popupView === window) popupView = null
                mainView?.visibility = View.VISIBLE
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) recordNavigation(view, "网页主框架跳转", request.url.toString())
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页开始加载",
                    "地址：$url\n视图：${if (popup) "验证弹窗" else "主登录窗口"}", null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页加载回调完成",
                    "地址：$url\n标题：${view.title.orEmpty()}\n此回调本身不代表页面业务内容一定正常。", true)
                // HoYoLAB and Skland use the same unconditional two-axis centering pipeline.
                DesktopWebView.prepareDesktopPage(view, AccountStore.SKLAND)
                SklandLoginAdapter.onPageReady(view)
                AppMessageBus.post(
                    this@SenkongdaoActivity,
                    AppMessageBus.SKLAND,
                    "已触发网页双轴居中",
                    "森空岛页面已调用统一的 DesktopWebView.prepareDesktopPage；横向和纵向居中均已启用，页面尺寸稳定后会再次校正位置。",
                    true
                )
                if (isSklandHost(url)) {
                    scheduleCheck()
                }
                pushSklandUi()
            }
            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页资源加载错误",
                    "回调：旧版 onReceivedError\n错误码：$errorCode\n原因：$description\n地址：$failingUrl", false)
                pushSklandUi()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                val url = request.url.toString()
                if (!request.isForMainFrame && !isSklandHost(url)) return
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页网络加载错误",
                    "回调：onReceivedError\n主框架：${request.isForMainFrame}\n方法：${request.method}\n错误码：${error.errorCode}\n原因：${error.description}\n地址：$url", false)
                pushSklandUi()
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                super.onReceivedHttpError(view, request, response)
                val url = request.url.toString()
                if (response.statusCode < 400 || (!request.isForMainFrame && !isSklandHost(url))) return
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页服务器响应错误",
                    "HTTP 状态：${response.statusCode} ${response.reasonPhrase.orEmpty()}\n主框架：${request.isForMainFrame}\n方法：${request.method}\nMIME：${response.mimeType.orEmpty()}\n地址：$url", false)
                pushSklandUi()
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val cert = error.certificate
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页 SSL 证书错误",
                    "SSL/TLS 校验失败\n错误类型：${error.primaryError}\n地址：${error.url}\n证书主体：${cert?.issuedTo?.dName.orEmpty()}\n证书颁发者：${cert?.issuedBy?.dName.orEmpty()}\n有效期：${cert?.validNotBeforeDate} 至 ${cert?.validNotAfterDate}\n处理：已安全取消连接，未忽略证书错误。", false)
                handler.cancel()
                pushSklandUi()
            }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页要求 HTTP 身份验证",
                    "主机：$host\n认证域：$realm\n未自动提交任何凭据。", false)
                handler.cancel()
                pushSklandUi()
            }
            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页要求客户端证书",
                    "主机：${request.host}\n端口：${request.port}\n当前应用没有配置客户端证书，已取消该请求。", false)
                request.cancel()
                pushSklandUi()
            }
            override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "WebView 安全浏览拦截",
                    "威胁类型：$threatType\n地址：${request.url}\n已返回安全页面。", false)
                callback.backToSafety(true)
                pushSklandUi()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "WebView 渲染进程异常退出",
                    "是否崩溃：${detail.didCrash()}\n渲染器优先级：${detail.rendererPriorityAtExit()}\n视图：${if (popup) "验证弹窗" else "主登录窗口"}", false)
                if (popupView === view) popupView = null
                if (mainView === view) mainView = null
                runCatching { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
                pushSklandUi()
                return true
            }
        }
    }

    private fun scheduleCheck() {
        val now = System.currentTimeMillis()
        if (!loginMode || saved || checking || now - lastCheck < 8000L) return
        lastCheck = now
        checking = true
        statusText = "检测中"
        pushSklandUi()

        // Read WebView cookies on the UI thread; do the HTTP request in the background.
        // This replaces the hidden third WebView, leaving the login page free to render.
        val cookie = sessionCookieManager?.getCookie(SklandLoginAdapter.TOKEN_URL).orEmpty()
        if (cookie.isBlank()) {
            checking = false
            statusText = "等待登录"
            pushSklandUi()
            return
        }
        tokenExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val request = (URL(SklandLoginAdapter.TOKEN_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    useCaches = true
                    setRequestProperty("Cookie", cookie)
                    setRequestProperty("User-Agent", DesktopWebView.DESKTOP_USER_AGENT)
                    setRequestProperty("Referer", SklandLoginAdapter.HOME_URL)
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                }
                connection = request
                val code = request.responseCode
                val stream = if (code in 200..299) request.inputStream else request.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (code !in 200..299) {
                            checking = false
                            statusText = "检测失败"
                            AppMessageBus.post(this, AppMessageBus.SKLAND, "Token 检测 HTTP 异常",
                                "HTTP 状态：$code；接口未返回成功响应。", false)
                            pushSklandUi()
                        } else {
                            parseResponse(body)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        checking = false
                        statusText = "等待登录"
                        AppMessageBus.post(this, AppMessageBus.SKLAND, "Token 后台检测失败",
                            "请求异常：${e.javaClass.simpleName}；${e.message ?: "无详细信息"}", false)
                        pushSklandUi()
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun parseResponse(body: String) {
        try {
            val parsed = SklandLoginAdapter.parseTokenResponse(body)
            if (parsed.businessCode != 0) {
                checking = false
                statusText = "未登录"
                AppMessageBus.post(this, AppMessageBus.SKLAND, "Token 接口返回未登录",
                    "业务状态码：${parsed.businessCode}\n服务端消息：${parsed.message}", false)
                pushSklandUi()
                return
            }
            val token = parsed.token.orEmpty()
            if (!SklandLoginAdapter.isValidToken(token)) {
                checking = false
                statusText = "等待登录"
                AppMessageBus.post(this, AppMessageBus.SKLAND, "Token 接口未返回可用凭据",
                    "接口状态成功，但 content 字段为空或格式不符合预期；不会记录 Token 原文。", false)
                pushSklandUi()
                return
            }
            val selectedAccountId = intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)
            DeviceInfoProvider.bind(selectedAccountId, loginDeviceInfo)
            SklandTokenVault(this, selectedAccountId).save(token)
            selectedAccountId?.let { HtmlMainActivity.markSessionTokenAcquired(it) }
            selectedAccountId?.let { id ->
                val store = AccountStore(this)
                store.find(id)?.let { account ->
                    store.update(account.copy(credentialReady = true, cookieReady = true, tokenReady = true, enabled = true))
                }
            }
            saved = true
            tokenSavedCached = true
            checking = false
            statusText = "已保存"
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录成功", "已自动解析登录状态并在本机加密保存 Token；未将凭据写入消息栏。", true)
            pushSklandUi()
        } catch (e: Exception) {
            checking = false
            statusText = "等待登录"
            recordException("森空岛登录检测异常", e)
        }
    }

    private fun isSklandHost(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "skland.com" || host.endsWith(".skland.com") ||
            host == "hypergryph.com" || host.endsWith(".hypergryph.com")
    }

    private fun hasToken() = !runCatching { SklandTokenVault(this, intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)).latest() }.getOrNull().isNullOrBlank()

    private fun leaveLogin() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "已返回账号详情页面。", null)
        pollHandler.removeCallbacks(pollTask)
        destroyAll()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (loginMode) {
            val active = popupView ?: mainView
            if (active?.canGoBack() == true) active.goBack() else leaveLogin()
        } else super.onBackPressed()
    }

    private fun destroy(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun destroyAll() {
        listOfNotNull(popupView, mainView).distinct().forEach { destroy(it) }
        if (::statusBar.isInitialized) {
            statusPageReady = false
            runCatching { statusBar.stopLoading(); statusBar.removeJavascriptInterface("AndroidSkland"); statusBar.destroy() }
        }
        popupView = null
        mainView = null
        checking = false
        clearLoginProfile()
    }

    private fun clearLoginProfile() {
        if (profileCleared) return
        profileCleared = true
        sessionWebStorage?.deleteAllData()
        sessionCookieManager?.removeAllCookies { sessionCookieManager?.flush() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "Activity onDestroy", "森空岛登录窗口正在销毁；isFinishing=$isFinishing", null)
        pollHandler.removeCallbacks(pollTask)
        destroyAll()
        tokenExecutor.shutdownNow()
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        super.onDestroy()
    }

    companion object {
        private const val SKLAND_STATUS_URL = "https://appassets.androidplatform.net/assets/ui/skland-status.html"
    }
}
