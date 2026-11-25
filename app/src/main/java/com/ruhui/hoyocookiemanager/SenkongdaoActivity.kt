package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SenkongdaoActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 17, 23))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 12, 6)
        }
        header.addView(Button(this).apply {
            text = "‹ 主页"
            isAllCaps = false
            setOnClickListener { returnToMain() }
        })
        header.addView(TextView(this).apply {
            text = "森空岛"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(12, 0, 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url.startsWith("file:///android_asset/")) {
                        view.addJavascriptInterface(SklandTokenBridge(), "AndroidBridge")
                    } else {
                        view.removeJavascriptInterface("AndroidBridge")
                    }
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        webView.loadUrl("file:///android_asset/senkongdao/index.html")
    }


    private inner class SklandTokenBridge {
        @JavascriptInterface
        fun saveToken(value: String) {
            try {
                SklandTokenVault(this@SenkongdaoActivity).save(value)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@SenkongdaoActivity,
                        "森空岛 Token 已加密保存，部署时会自动写入云函数。",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@SenkongdaoActivity,
                        "Token 保存失败：" + (e.message ?: e.javaClass.simpleName),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else returnToMain()
    }

    private fun returnToMain() {
        startActivity(android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
