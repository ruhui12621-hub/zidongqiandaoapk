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
        CookieManager.getInstance().setAcceptCookie(true)
        AppMessageBus.post(this, "森空岛页面", "Activity 已创建，正在初始化可见界面…", null)
        try {
            buildHome()
            AppMessageBus.post(this, "森空岛页面", "森空岛主页布局已初始化，正在显示界面。", true)
        } catch (e: Exception) {
            val detail = "主页初始化失败：${e.javaClass.simpleName}: ${e.message ?: "无详细错误信息"}"
            AppMessageBus.post(this, "森空岛页面初始化失败", detail, false)
            android.widget.Toast.makeText(this, "森空岛页面初始化失败，详情已写入消息栏", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!loginMode) {
            AppMessageBus.post(this, "森空岛页面", "森空岛主页已进入前台。", true)
        }
    }

    private fun desktop(view: WebView, multi: Boolean) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(multi)
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    private fun buildHome() {
        loginMode = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        // 先挂载最小可见根布局，避免后续华为云面板初始化异常时整个 Activity 看起来毫无反应。
        setContentView(root)
        root.addView(TextView(this).apply {
            text = "森空岛"
            textSize = 21f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(12))
        })
        status = TextView(this).apply {
            text = if (hasToken()) "Token：已加密保存" else "登录状态：等待检测"
            textSize = 14f
            setTextColor(if (hasToken()) 0xFF75D69C.toInt() else 0xFFB8C1D1.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF202631.toInt())
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "登录成功后自动检测并解析 Token，在本机加密保存。界面、日志和剪贴板均不会显示凭据原文。"
            textSize = 13f
            setTextColor(0xFF9BA7B8.toInt())
            setPadding(0, dp(10), 0, dp(8))
        })
        root.addView(button("打开森空岛") {
            try {
                showLogin()
            } catch (e: Exception) {
                val detail = "${e.javaClass.simpleName}: ${e.message ?: "无详细错误信息"}"
                AppMessageBus.post(this, "森空岛登录窗口启动失败", detail, false)
                status.text = "登录窗口启动失败，详情已记录到通用消息栏"
            }
        })
        val scroll = ScrollView(this)
        val cloud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        try {
            addHuaweiCloudPanel(cloud, "skland", { "" })
            AppMessageBus.post(this, "森空岛页面", "主页与华为云面板均已初始化。", true)
        } catch (e: Exception) {
            val detail = "华为云面板初始化失败：${e.javaClass.simpleName}: ${e.message ?: "无详细错误信息"}"
            AppMessageBus.post(this, "森空岛页面初始化失败", detail, false)
            cloud.addView(TextView(this).apply {
                text = detail
                textSize = 13f
                setTextColor(0xFFFF8A80.toInt())
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        scroll.addView(cloud)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showLogin() {
        AppMessageBus.post(this, "森空岛登录窗口", "正在创建全屏桌面版 WebView…", null)
        loginMode = true
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bar.addView(button("‹ 返回") { leaveLogin() })
        bar.addView(TextView(this).apply {
            text = "森空岛 · 桌面版"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        status = TextView(this).apply {
            text = if (hasToken()) "Token 已保存" else "等待登录后自动检测"
            textSize = 12f
            setTextColor(0xFFB8C1D1.toInt())
        }
        bar.addView(status)
        root.addView(bar)
        browserFrame = FrameLayout(this)
        root.addView(browserFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        val main = WebView(this)
        mainView = main
        configureLogin(main, false)
        browserFrame.addView(main, FrameLayout.LayoutParams(-1, -1))
        val probe = WebView(this)
        probeView = probe
        desktop(probe, false)
        probe.webChromeClient = WebChromeClient()
        probe.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.substringBefore('?').trimEnd('/') != TOKEN_URL.trimEnd('/')) return
                view.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") {
                    parseResponse(it)
                }
            }
        }
        browserFrame.addView(probe, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END))
        main.loadUrl(HOME_URL)
        AppMessageBus.post(this, "森空岛登录窗口", "全屏桌面版登录页面已启动，正在加载森空岛。", true)
        pollHandler.removeCallbacks(pollTask)
        pollHandler.postDelayed(pollTask, 2500L)
    }

    private fun configureLogin(view: WebView, popup: Boolean) {
        desktop(view, !popup)
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(source: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                return try {
                    val child = WebView(this@SenkongdaoActivity)
                    configureLogin(child, true)
                    popupView?.let { destroy(it) }
                    popupView = child
                    mainView?.visibility = View.GONE
                    browserFrame.addView(child, FrameLayout.LayoutParams(-1, -1))
                    (resultMsg.obj as WebView.WebViewTransport).webView = child
                    resultMsg.sendToTarget()
                    AppMessageBus.post(this@SenkongdaoActivity, "森空岛验证窗口", "网站弹出的新窗口已在应用内成功创建。", true)
                    true
                } catch (e: Exception) {
                    AppMessageBus.post(
                        this@SenkongdaoActivity,
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
                CookieManager.getInstance().flush()
                if (isSklandHost(url)) scheduleCheck()
            }
        }
    }

    private fun scheduleCheck() {
        val now = System.currentTimeMillis()
        if (!loginMode || saved || checking || now - lastCheck < 8000L) return
        lastCheck = now
        checking = true
        status.text = "正在安全检测登录状态…"
        probeView?.loadUrl(TOKEN_URL)
    }

    private fun parseResponse(encoded: String) {
        try {
            val body = org.json.JSONTokener(encoded).nextValue() as? String ?: error("not-json")
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                checking = false
                status.text = "尚未登录成功；完成登录后会自动继续检测"
                return
            }
            val token = json.optJSONObject("data")?.optString("content").orEmpty()
                .ifBlank { json.optString("content").orEmpty() }
            if (token.isBlank() || token.any(Char::isWhitespace)) {
                checking = false
                status.text = "等待有效登录状态…"
                return
            }
            SklandTokenVault(this).save(token)
            saved = true
            checking = false
            status.text = "✓ 已自动解析并加密保存 Token"
            AppMessageBus.post(this, "森空岛登录成功", "已自动解析登录状态并在本机加密保存 Token；未将凭据写入消息栏。", true)
            status.setTextColor(0xFF75D69C.toInt())
        } catch (e: Exception) {
            checking = false
            status.text = "等待登录完成；系统会自动重试"
            AppMessageBus.post(
                this,
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

    private fun hasToken() = !runCatching { SklandTokenVault(this).latest() }.getOrNull().isNullOrBlank()

    private fun leaveLogin() {
        AppMessageBus.post(this, "森空岛登录窗口", "已返回森空岛服务主页。", null)
        pollHandler.removeCallbacks(pollTask)
        destroyAll()
        buildHome()
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://www.skland.com/"
        private const val TOKEN_URL = "https://web-api.skland.com/account/info/hg"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
