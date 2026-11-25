package com.ruhui.hoyocookiemanager

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Independent Skland page: embedded login, direct token endpoint parsing,
 * and its own Huawei FunctionGraph deployment panel.
 */
class SenkongdaoActivity : HuaweiCloudActivity() {
    private lateinit var webView: WebView
    private lateinit var tokenStatus: TextView
    private var parsingTokenPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "‹ 主页"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "森空岛"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(header)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        content.addView(TextView(this).apply {
            text = "森空岛账号登录"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(5))
        })
        content.addView(TextView(this).apply {
            text = "在下方内置浏览器登录森空岛。登录后点击“自动获取 Token”，应用会直接读取官方 Token 页面并解析保存，无需手动复制 JSON。"
            textSize = 13f
            setTextColor(0xFFA7B0C0.toInt())
            setPadding(0, 0, 0, dp(10))
        })

        tokenStatus = TextView(this).apply {
            val ready = runCatching { SklandTokenVault(this@SenkongdaoActivity).latest() }.getOrNull().isNullOrBlank().not()
            text = if (ready) "Token 状态：已保存（加密）" else "Token 状态：尚未获取"
            textSize = 14f
            setTextColor(if (ready) 0xFF75D69C.toInt() else 0xFFB8C1D1.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFF202631.toInt())
        }
        content.addView(tokenStatus)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "打开森空岛登录"
            isAllCaps = false
            setOnClickListener {
                parsingTokenPage = false
                webView.loadUrl("https://www.skland.com/")
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "自动获取 Token"
            isAllCaps = false
            setOnClickListener {
                parsingTokenPage = true
                tokenStatus.text = "正在请求官方 Token 页面…"
                tokenStatus.setTextColor(0xFFE1E6EF.toInt())
                webView.loadUrl(TOKEN_URL)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (url.substringBefore('?').trimEnd('/') == TOKEN_URL.trimEnd('/') && parsingTokenPage) {
                        view.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") { encoded ->
                            parseAndSaveToken(encoded)
                        }
                    }
                }
            }
        }
        content.addView(webView, LinearLayout.LayoutParams(-1, dp(430)))
        webView.loadUrl("https://www.skland.com/")

        addHuaweiCloudPanel(content, "skland", { "" })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun parseAndSaveToken(encodedBody: String) {
        try {
            val body = JSONTokener(encodedBody).nextValue() as? String
                ?: error("无法读取 Token 页面正文。")
            val token = try {
                val json = JSONObject(body)
                if (json.has("code") && json.optInt("code") != 0) {
                    error("森空岛接口返回错误：" + json.optString("message", "未知错误"))
                }
                json.optJSONObject("data")?.optString("content").orEmpty()
                    .ifBlank { json.optString("content").orEmpty() }
            } catch (e: org.json.JSONException) {
                body.trim().takeIf { it.isNotBlank() && !it.any(Char::isWhitespace) }.orEmpty()
            }
            if (token.isBlank() || token.any(Char::isWhitespace)) {
                error("Token 页面没有返回有效的 data.content，请确认已在森空岛登录。")
            }
            SklandTokenVault(this).save(token)
            runOnUiThread {
                tokenStatus.text = "✓ Token 已自动解析并加密保存"
                tokenStatus.setTextColor(0xFF75D69C.toInt())
            }
        } catch (e: Exception) {
            runOnUiThread {
                tokenStatus.text = "Token 获取失败：" + (e.message ?: e.javaClass.simpleName)
                tokenStatus.setTextColor(0xFFFF8A80.toInt())
            }
        } finally {
            parsingTokenPage = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TOKEN_URL = "https://web-api.skland.com/account/info/hg"
    }
}
