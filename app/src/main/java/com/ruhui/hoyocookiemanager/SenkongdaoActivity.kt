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
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛页面", "Activity 已创建，正在初始化可见界面…", null)
        try {
            showLogin()
        } catch (e: Exception) {
            val detail = "主页初始化失败：${e.javaClass.simpleName}: ${e.message ?: "无详细错误信息"}"
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛页面初始化失败", detail, false)
            android.widget.Toast.makeText(this, "森空岛页面初始化失败，详情已写入消息栏", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
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
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "正在打开桌面版登录网页…", null)
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
                pushSklandUi()
                return
            }
            val token = json.optJSONObject("data")?.optString("content").orEmpty()
                .ifBlank { json.optString("content").orEmpty() }
            if (token.isBlank() || token.any(Char::isWhitespace)) {
                checking = false
                statusText = "等待登录"
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
