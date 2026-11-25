package com.ruhui.hoyocookiemanager

import android.app.AlertDialog
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
import org.json.JSONObject

/** Skland login uses an in-app popup WebView; all WebViews share CookieManager. */
class SenkongdaoActivity : HuaweiCloudActivity() {
    private var loginDialog: AlertDialog? = null
    private var loginWebView: WebView? = null
    private var popupWebView: WebView? = null
    private lateinit var tokenStatus: TextView
    private var parsingTokenPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { text = "‹ 主页"; isAllCaps = false; setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = "森空岛"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0) })
        root.addView(header)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        tokenStatus = TextView(this).apply {
            val ready = !runCatching { SklandTokenVault(this@SenkongdaoActivity).latest() }.getOrNull().isNullOrBlank()
            text = if (ready) "Token：已保存（加密）" else "Token：尚未获取"
            textSize = 14f
            setTextColor(if (ready) 0xFF75D69C.toInt() else 0xFFB8C1D1.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFF202631.toInt())
        }
        content.addView(tokenStatus)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "打开森空岛登录窗口"; isAllCaps = false; setOnClickListener { showLoginWindow() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "自动获取 Token"; isAllCaps = false; setOnClickListener { fetchToken() } }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions)
        addHuaweiCloudPanel(content, "skland", { "" })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showLoginWindow() {
        if (loginDialog?.isShowing == true) return
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(15, 17, 23)) }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(Button(this).apply { text = "关闭"; isAllCaps = false; setOnClickListener { loginDialog?.dismiss() } })
        toolbar.addView(Button(this).apply {
            text = "返回"; isAllCaps = false
            setOnClickListener { val active = popupWebView ?: loginWebView; if (active?.canGoBack() == true) active.goBack() }
        })
        container.addView(toolbar)
        val main = WebView(this)
        loginWebView = main
        configureWebView(main, false)
        container.addView(main, LinearLayout.LayoutParams(-1, dp(520)))
        loginDialog = AlertDialog.Builder(this).setTitle("森空岛登录").setView(container).create()
        loginDialog?.setOnDismissListener {
            listOfNotNull(popupWebView, loginWebView).forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view); view.stopLoading(); view.destroy()
            }
            popupWebView = null; loginWebView = null; loginDialog = null
            CookieManager.getInstance().flush()
        }
        loginDialog?.show()
        main.loadUrl("https://www.skland.com/")
    }

    private fun configureWebView(view: WebView, isPopup: Boolean) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.loadsImagesAutomatically = true
        view.settings.javaScriptCanOpenWindowsAutomatically = true
        view.settings.setSupportMultipleWindows(!isPopup)
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(source: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val popup = WebView(this@SenkongdaoActivity)
                configureWebView(popup, true)
                popupWebView?.let { old -> (old.parent as? ViewGroup)?.removeView(old); old.destroy() }
                popupWebView = popup
                (source.parent as? ViewGroup)?.addView(popup, LinearLayout.LayoutParams(-1, dp(520)))
                source.visibility = android.view.View.GONE
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) {
                (window.parent as? ViewGroup)?.removeView(window); window.stopLoading(); window.destroy()
                if (popupWebView === window) popupWebView = null
                loginWebView?.visibility = android.view.View.VISIBLE
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                if (parsingTokenPage && url.substringBefore('?').trimEnd('/') == TOKEN_URL.trimEnd('/')) {
                    view.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") { encoded ->
                        parseAndSaveToken(encoded)
                        view.loadUrl("https://www.skland.com/")
                    }
                }
            }
        }
    }

    private fun fetchToken() {
        val active = popupWebView ?: loginWebView
        if (active == null) {
            tokenStatus.text = "请先打开森空岛登录窗口并完成登录。"
            tokenStatus.setTextColor(0xFFFFCC80.toInt())
            return
        }
        parsingTokenPage = true
        tokenStatus.text = "正在读取森空岛官方 Token…"
        tokenStatus.setTextColor(0xFFE1E6EF.toInt())
        active.loadUrl(TOKEN_URL)
    }

    private fun parseAndSaveToken(encodedBody: String) {
        try {
            val body = org.json.JSONTokener(encodedBody).nextValue() as? String ?: error("无法读取 Token 接口响应。")
            val json = JSONObject(body)
            if (json.has("code") && json.optInt("code") != 0) error("森空岛接口返回错误：" + json.optString("message", "未知错误"))
            val token = json.optJSONObject("data")?.optString("content").orEmpty().ifBlank { json.optString("content").orEmpty() }
            if (token.isBlank() || token.any(Char::isWhitespace)) error("接口没有返回有效 Token，请确认已登录森空岛。")
            SklandTokenVault(this).save(token)
            parsingTokenPage = false
            runOnUiThread { tokenStatus.text = "✓ Token 已解析并加密保存"; tokenStatus.setTextColor(0xFF75D69C.toInt()) }
        } catch (e: Exception) {
            parsingTokenPage = false
            runOnUiThread { tokenStatus.text = "Token 获取失败：" + (e.message ?: e.javaClass.simpleName); tokenStatus.setTextColor(0xFFFF8A80.toInt()) }
        }
    }

    override fun onDestroy() { loginDialog?.dismiss(); super.onDestroy() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    companion object { private const val TOKEN_URL = "https://web-api.skland.com/account/info/hg" }
}
