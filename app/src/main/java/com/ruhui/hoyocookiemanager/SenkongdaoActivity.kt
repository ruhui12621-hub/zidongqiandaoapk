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
    private var lastLayoutDiagnosticUrl = ""
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
                AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口启动失败", detail, false)
                status.text = "登录窗口启动失败，详情已记录到森空岛操作消息"
            }
        })
        val scroll = ScrollView(this)
        val cloud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        try {
            addHuaweiCloudPanel(cloud, "skland", { "" })
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛页面", "主页与华为云面板均已初始化。", true)
        } catch (e: Exception) {
            val detail = "华为云面板初始化失败：${e.javaClass.simpleName}: ${e.message ?: "无详细错误信息"}"
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛页面初始化失败", detail, false)
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
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "正在创建全屏桌面版 WebView…", null)
        loginMode = true
        // Keep the Activity's content edge-to-edge within the available window, but
        // allow adjustResize to make room for the soft keyboard during login.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        // 关键：切换到登录界面时必须把新根布局挂载到 Activity；否则 WebView 虽然创建并 loadUrl，屏幕仍显示旧主页。
        setContentView(root)
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "登录界面布局已挂载，正在创建 WebView。", null)
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
            text = if (hasToken()) "已保存" else "未登录"
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTextColor(0xFFB8C1D1.toInt())
            maxWidth = dp(76)
        }
        bar.addView(status, LinearLayout.LayoutParams(dp(76), -2))
        root.addView(bar)
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
                view.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") {
                    parseResponse(it)
                }
            }
        }
        browserFrame.addView(probe, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END))
        main.loadUrl(HOME_URL)
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "全屏桌面版登录页面已启动，正在加载森空岛。", true)
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
                DesktopWebView.enablePageZoom(view)
                CookieManager.getInstance().flush()
                if (isSklandHost(url)) {
                    diagnoseSklandLayout(view, url)
                    scheduleCheck()
                }
            }
        }
    }

    /**
     * Read-only layout diagnostics for the Skland desktop navigation.
     * No page styles are changed and no form values, cookies or tokens are collected.
     */
    private fun diagnoseSklandLayout(view: WebView, url: String) {
        if (!isSklandHost(url) || lastLayoutDiagnosticUrl == url) return
        lastLayoutDiagnosticUrl = url
        val script = """
            (function() {
              var doc = document.documentElement, body = document.body;
              var out = {
                url: location.origin + location.pathname,
                viewport: { innerWidth: innerWidth, clientWidth: doc ? doc.clientWidth : 0,
                  scrollWidth: doc ? doc.scrollWidth : 0, bodyClientWidth: body ? body.clientWidth : 0,
                  bodyScrollWidth: body ? body.scrollWidth : 0, scrollX: scrollX,
                  dpr: devicePixelRatio, visualWidth: visualViewport ? visualViewport.width : null,
                  visualScale: visualViewport ? visualViewport.scale : null },
                candidates: []
              };
              var els = Array.from(document.querySelectorAll('body *')).filter(function(el) {
                if (el.children.length > 4) return false;
                var t = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
                return t.length > 0 && t.length < 24 && /(首页|版区)/.test(t);
              }).slice(0, 8);
              els.forEach(function(el) {
                var chain = [], n = el;
                for (var i = 0; n && i < 5; i++, n = n.parentElement) {
                  var r = n.getBoundingClientRect(), s = getComputedStyle(n);
                  chain.push({
                    tag: n.tagName, id: n.id || '', className: (typeof n.className === 'string' ? n.className : '').slice(0,100),
                    text: (n.innerText || n.textContent || '').replace(/\\s+/g, ' ').trim().slice(0,32),
                    rect: {x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height), right: Math.round(r.right)},
                    clientWidth: n.clientWidth, scrollWidth: n.scrollWidth,
                    overflowX: s.overflowX, position: s.position, display: s.display,
                    whiteSpace: s.whiteSpace, transform: s.transform
                  });
                }
                out.candidates.push(chain);
              });
              return JSON.stringify(out);
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { encoded ->
            runCatching {
                val raw = org.json.JSONTokener(encoded).nextValue() as? String ?: encoded
                val json = JSONObject(raw)
                AppMessageBus.post(
                    this, AppMessageBus.SKLAND, "森空岛网页布局诊断",
                    "只读采样（未修改网页样式）：\\n" + json.toString(2), true
                )
            }.onFailure {
                AppMessageBus.post(
                    this, AppMessageBus.SKLAND, "森空岛网页布局诊断失败",
                    it.message ?: "无法解析网页布局数据", false
                )
            }
        }
    }

    private fun scheduleCheck() {
        val now = System.currentTimeMillis()
        if (!loginMode || saved || checking || now - lastCheck < 8000L) return
        lastCheck = now
        checking = true
        status.text = "检测中"
        probeView?.loadUrl(TOKEN_URL)
    }

    private fun parseResponse(encoded: String) {
        try {
            val body = org.json.JSONTokener(encoded).nextValue() as? String ?: error("not-json")
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                checking = false
                status.text = "未登录"
                return
            }
            val token = json.optJSONObject("data")?.optString("content").orEmpty()
                .ifBlank { json.optString("content").orEmpty() }
            if (token.isBlank() || token.any(Char::isWhitespace)) {
                checking = false
                status.text = "等待登录"
                return
            }
            SklandTokenVault(this).save(token)
            saved = true
            checking = false
            status.text = "已保存"
            AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录成功", "已自动解析登录状态并在本机加密保存 Token；未将凭据写入消息栏。", true)
            status.setTextColor(0xFF75D69C.toInt())
        } catch (e: Exception) {
            checking = false
            status.text = "等待登录"
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

    private fun hasToken() = !runCatching { SklandTokenVault(this).latest() }.getOrNull().isNullOrBlank()

    private fun leaveLogin() {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛登录窗口", "已返回森空岛服务主页。", null)
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
    }
}
