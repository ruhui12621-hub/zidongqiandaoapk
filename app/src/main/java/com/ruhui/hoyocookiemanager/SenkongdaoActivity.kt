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
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class SenkongdaoActivity : HuaweiCloudActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var browserFrame: FrameLayout
    private lateinit var chromeView: WebView
    private var mainView: WebView? = null
    private var popupView: WebView? = null
    private var probeView: WebView? = null
    private var loginMode = false
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
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛页面", "Activity 已创建，正在初始化可见界面…", null)
        try {
            buildHome()
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛页面", "森空岛主页布局已初始化，正在显示界面。", true)
        } catch (e: Exception) {
            val detail = "主页初始化失败：${e.javaClass.simpleName}: ${e.message ?: "无详细错误信息"}"
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛页面初始化失败", detail, false)
            android.widget.Toast.makeText(this, "森空岛页面初始化失败，详情已写入消息栏", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!loginMode) {
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛页面", "森空岛主页已进入前台。", true)
        }
    }

    private fun desktop(view: WebView, multi: Boolean) {
        DesktopWebView.configure(
            view,
            multiWindow = multi,
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        )
    }

    private fun buildHome() {
        loginMode = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 15, 22)) }
        status = TextView(this).apply { text = if (hasToken()) "Token：已加密保存" else "登录状态：等待检测" }
        if (!::chromeView.isInitialized) {
            chromeView = WebView(this).apply {
                setBackgroundColor(Color.rgb(12, 15, 22))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                addJavascriptInterface(SklandChromeBridge(), "SklandUi")
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                        !request.url.toString().startsWith("file:///android_asset/ui/")
                    override fun onPageFinished(view: WebView, url: String) { pushSklandUi() }
                }
                loadUrl("file:///android_asset/ui/skland.html")
            }
        }
        (chromeView.parent as? ViewGroup)?.removeView(chromeView)
        root.addView(chromeView, LinearLayout.LayoutParams(-1, dp(166)))
        setContentView(root)
        pushSklandUi()
    }

    private fun pushSklandUi() {
        if (!::chromeView.isInitialized) return
        val payload = JSONObject().put("loginMode", loginMode)
            .put("status", if (::status.isInitialized) status.text.toString() else "等待检测")
            .put("tokenSaved", hasToken())
            .put("message", AppMessageBus.current(this, AppMessageBus.SKLAND))
        chromeView.post {
            chromeView.evaluateJavascript(
                "window.updateSklandState && window.updateSklandState(" + JSONObject.quote(payload.toString()) + ")", null
            )
        }
    }

    private inner class SklandChromeBridge {
        @android.webkit.JavascriptInterface fun openLogin() { runOnUiThread { showLogin() } }
        @android.webkit.JavascriptInterface fun back() { runOnUiThread { leaveLogin() } }
        @android.webkit.JavascriptInterface fun refresh() { runOnUiThread { if (loginMode) scheduleCheck(); pushSklandUi() } }
    }

    private fun showLogin() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "正在打开桌面版登录网页…", null)
        loginMode = true
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 15, 22)) }
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
            override fun onPageFinished(view: WebView, url: String) {
                if (url.substringBefore('?').trimEnd('/') != TOKEN_URL.trimEnd('/')) return
                view.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") { parseResponse(it) }
            }
        }
        browserFrame.addView(probe, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END))
        main.loadUrl(HOME_URL)
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "桌面版登录页面已启动，正在加载森空岛。", true)
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
            override fun onPageFinished(view: WebView, url: String) {
                if (isSklandHost(url)) {
                    DesktopWebView.enableDesktopViewport(view)
                    scheduleCheck()
                }
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun scheduleCheck() {
        val now = System.currentTimeMillis()
        if (!loginMode || saved || checking || now - lastCheck < 8000L) return
        lastCheck = now
        checking = true
        status.text = "检测中"
        pushSklandUi()
        probeView?.loadUrl(TOKEN_URL)
    }

    private fun parseResponse(encoded: String) {
        try {
            val body = org.json.JSONTokener(encoded).nextValue() as? String ?: error("not-json")
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                checking = false
                status.text = "未登录"
                pushSklandUi()
                return
            }
            val token = json.optJSONObject("data")?.optString("content").orEmpty()
                .ifBlank { json.optString("content").orEmpty() }
            if (token.isBlank() || token.any(Char::isWhitespace)) {
                checking = false
                status.text = "等待登录"
                pushSklandUi()
                return
            }
            val selectedAccountId = intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)
            SklandTokenVault(this, selectedAccountId).save(token)
            selectedAccountId?.let { id ->
                val store = AccountStore(this)
                store.find(id)?.let { account ->
                    store.update(account.copy(credentialReady = true))
                }
            }
            saved = true
            checking = false
            status.text = "已保存"
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录成功", "已自动解析登录状态并在本机加密保存 Token；未将凭据写入消息栏。", true)
            status.setTextColor(0xFF75D69C.toInt())
            pushSklandUi()
        } catch (e: Exception) {
            checking = false
            status.text = "等待登录"
            pushSklandUi()
            AppMessageBus.post(
                this, AppMessageBus.SKLAND,
                "森空岛登录检测异常",
                "${e.javaClass.simpleName}: ${e.message ?: "解析或本地加密保存失败"}",
                false
            )
        }
    }

    private fun isSklandHost(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "skland.com" || host.endsWith(".skland.com") ||
            host == "hypergryph.com" || host.endsWith(".hypergryph.com")
    }

    private fun hasToken() = !runCatching { SklandTokenVault(this, intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)).latest() }.getOrNull().isNullOrBlank()

    private fun leaveLogin() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "已返回森空岛服务主页。", null)
        pollHandler.removeCallbacks(pollTask)
        destroyAll()
        buildHome()
        pushSklandUi()
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

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(39, 46, 60))
        setOnClickListener { action() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
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
