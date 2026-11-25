package com.ruhui.hoyocookiemanager

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
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
import org.json.JSONObject

class SenkongdaoActivity : HuaweiCloudActivity() {
    private lateinit var root: LinearLayout
    private var statusText = "登录状态：等待检测"
    private lateinit var browserFrame: FrameLayout
    private lateinit var chromeView: WebView
    private var mainView: WebView? = null
    private var popupView: WebView? = null
    private var probeView: WebView? = null
    private var loginMode = true
    private var checking = false
    private var saved = false
    private var lastCheck = 0L
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollTask = object : Runnable {
        override fun run() {
            if (!loginMode || saved) return
            scheduleCheck()
            pollHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppMessageBus.setActiveChannel(AppMessageBus.SKLAND)
        CookieManager.getInstance().setAcceptCookie(true)
        AppMessageBus.beginSession(this, AppMessageBus.SKLAND, "森空岛启动", "已进入森空岛登录 Activity，开始初始化 WebView。")
        try {
            initializeChromeView()
            AppMessageBus.post(this, AppMessageBus.SKLAND, "状态栏初始化", "状态栏 WebView 已创建，正在加载本地界面资源。", null)
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

    private fun initializeChromeView() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "初始化状态栏 WebView", "正在创建承载森空岛窗口状态和操作按钮的本地 WebView。", null)
        chromeView = WebView(this).apply {
            setBackgroundColor(Color.rgb(5, 5, 5))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            addJavascriptInterface(SklandChromeBridge(), "SklandUi")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        AppMessageBus.post(
                            this@SenkongdaoActivity, AppMessageBus.SKLAND, "森空岛界面 JavaScript 错误",
                            "${message.message()}\n来源：${message.sourceId()}\n行号：${message.lineNumber()}", false
                        )
                        pushSklandUi()
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "状态栏页面开始加载", "本地界面：$url", null)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "状态栏页面加载完成", "本地界面资源已加载：$url", true)
                    pushSklandUi()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "状态栏页面加载失败",
                            "错误码：${error.errorCode}\n原因：${error.description}\n地址：${request.url}", false)
                    }
                }
            }
            loadUrl("file:///android_asset/ui/skland.html")
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

    private fun desktop(view: WebView, multi: Boolean) {
        DesktopWebView.configure(
            view,
            multiWindow = multi,
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        )
    }

    private fun pushSklandUi() {
        if (!::chromeView.isInitialized) return
        val payload = JSONObject().put("loginMode", loginMode)
            .put("status", statusText)
            .put("tokenSaved", hasToken())
            .put("message", AppMessageBus.current(this, AppMessageBus.SKLAND))
        chromeView.post {
            chromeView.evaluateJavascript(
                "window.updateSklandState && window.updateSklandState(" + JSONObject.quote(payload.toString()) + ")", null
            )
        }
    }

    private inner class SklandChromeBridge {
        @android.webkit.JavascriptInterface fun back() { runOnUiThread { leaveLogin() } }
        @android.webkit.JavascriptInterface fun refresh() { runOnUiThread { if (loginMode) scheduleCheck(); pushSklandUi() } }
    }

    private fun showLogin() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "构建森空岛登录窗口", "开始创建页面容器、主 WebView 和 Token 检测 WebView。", null)
        loginMode = true
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(5, 5, 5)) }
        setContentView(root)
        (chromeView.parent as? ViewGroup)?.removeView(chromeView)
        root.addView(chromeView, LinearLayout.LayoutParams(-1, dp(166)))
        browserFrame = FrameLayout(this)
        root.addView(browserFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        val main = DesktopWebView.create(this, multiWindow = true)
        mainView = main
        configureLogin(main, false)
        browserFrame.addView(main, FrameLayout.LayoutParams(-1, -1))
        val probe = DesktopWebView.create(this, multiWindow = false)
        probeView = probe
        desktop(probe, false)
        probe.webChromeClient = WebChromeClient()
        probe.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "Token 检测请求开始", "请求地址：$url", null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "Token 检测请求完成", "响应页面地址：$url", null)
                if (url.substringBefore('?').trimEnd('/') != TOKEN_URL.trimEnd('/')) return
                view.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") { parseResponse(it) }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "Token 检测网络失败",
                    "主框架：${request.isForMainFrame}\n错误码：${error.errorCode}\n原因：${error.description}\n地址：${request.url}", false)
                pushSklandUi()
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (response.statusCode >= 400) {
                    AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "Token 检测 HTTP 异常",
                        "HTTP ${response.statusCode} ${response.reasonPhrase.orEmpty()}\n主框架：${request.isForMainFrame}\n地址：${request.url}", false)
                    pushSklandUi()
                }
            }
        }
        browserFrame.addView(probe, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END))
        AppMessageBus.post(this, AppMessageBus.SKLAND, "主 WebView 已创建", "桌面 UA：${main.settings.userAgentString}\n多窗口：${main.settings.supportMultipleWindows}\nJavaScript：${main.settings.javaScriptEnabled}\nDOM Storage：${main.settings.domStorageEnabled}\nWebView 内核：${runCatching { if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName ?: "未知" else "系统版本未提供" }.getOrDefault("读取失败")}", true)
        AppMessageBus.post(this, AppMessageBus.SKLAND, "请求加载森空岛首页", "即将调用 WebView.loadUrl。\n目标地址：$HOME_URL", null)
        main.loadUrl(HOME_URL)
        AppMessageBus.post(this, AppMessageBus.SKLAND, "已调用首页加载", "WebView.loadUrl 已执行；是否真正加载成功，以后续页面回调为准。", null)
        pushSklandUi()
        pollHandler.removeCallbacks(pollTask)
        pollHandler.postDelayed(pollTask, 2500L)
    }

    private fun configureLogin(view: WebView, popup: Boolean) {
        desktop(view, !popup)
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(source: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                return try {
                    val child = DesktopWebView.create(this@SenkongdaoActivity, multiWindow = false)
                    configureLogin(child, true)
                    popupView?.let { destroy(it) }
                    popupView = child
                    mainView?.visibility = View.GONE
                    browserFrame.addView(child, FrameLayout.LayoutParams(-1, -1))
                    (resultMsg.obj as WebView.WebViewTransport).webView = child
                    resultMsg.sendToTarget()
                    AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "森空岛验证窗口", "网站弹出的新窗口已在应用内成功创建。", true)
                    true
                } catch (e: Exception) {
                    AppMessageBus.post(
                        this@SenkongdaoActivity, AppMessageBus.SKLAND,
                        "森空岛验证窗口创建失败",
                        "${e.javaClass.simpleName}: ${e.message ?: "无详细错误信息"}",
                        false
                    )
                    false
                }
            }
            override fun onCloseWindow(window: WebView) {
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
                pushSklandUi()
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页已提交可见内容",
                    "地址：$url\nWebView 已开始呈现页面内容。", null)
                pushSklandUi()
            }
            override fun onPageFinished(view: WebView, url: String) {
                AppMessageBus.post(this@SenkongdaoActivity, AppMessageBus.SKLAND, "网页加载回调完成",
                    "地址：$url\n标题：${view.title.orEmpty()}\n此回调本身不代表页面业务内容一定正常。", true)
                if (isSklandHost(url)) {
                    DesktopWebView.enableDesktopViewport(view)
                    scheduleCheck()
                }
                CookieManager.getInstance().flush()
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
        probeView?.loadUrl(TOKEN_URL)
    }

    private fun parseResponse(encoded: String) {
        try {
            val body = org.json.JSONTokener(encoded).nextValue() as? String ?: error("not-json")
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                checking = false
                statusText = "未登录"
                AppMessageBus.post(this, AppMessageBus.SKLAND, "Token 接口返回未登录",
                    "业务状态码：${json.optInt("code", -1)}\n服务端消息：${json.optString("msg", json.optString("message", "未提供消息"))}", false)
                pushSklandUi()
                return
            }
            val token = json.optJSONObject("data")?.optString("content").orEmpty()
                .ifBlank { json.optString("content").orEmpty() }
            if (token.isBlank() || token.any(Char::isWhitespace)) {
                checking = false
                statusText = "等待登录"
                AppMessageBus.post(this, AppMessageBus.SKLAND, "Token 接口未返回可用凭据",
                    "接口状态成功，但 content 字段为空或格式不符合预期；不会记录 Token 原文。", false)
                pushSklandUi()
                return
            }
            val selectedAccountId = intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)
            SklandTokenVault(this, selectedAccountId).save(token)
            selectedAccountId?.let { id ->
                val store = AccountStore(this)
                store.find(id)?.let { account ->
                    store.update(account.copy(credentialReady = true, tokenReady = true))
                }
            }
            saved = true
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
        listOfNotNull(popupView, mainView, probeView).distinct().forEach { destroy(it) }
        popupView = null
        mainView = null
        probeView = null
        checking = false
        CookieManager.getInstance().flush()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "Activity onDestroy", "森空岛登录窗口正在销毁；isFinishing=$isFinishing", null)
        pollHandler.removeCallbacks(pollTask)
        destroyAll()
        if (::chromeView.isInitialized) { chromeView.stopLoading(); chromeView.removeJavascriptInterface("SklandUi"); chromeView.destroy() }
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://www.skland.com/"
        private const val TOKEN_URL = "https://web-api.skland.com/account/info/hg"
    }
}
