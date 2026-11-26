package com.ruhui.hoyocookiemanager

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Shared desktop-browser setup for HoYoLAB and Skland.
 *
 * This class only configures browser presentation and platform behavior.
 * Site-specific navigation, popup handling, cookie parsing and token extraction
 * remain owned by each Activity.
 */
object DesktopWebView {
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun create(
        context: Context,
        multiWindow: Boolean = true,
        cacheMode: Int = WebSettings.LOAD_DEFAULT,
        fitToScreen: Boolean = true
    ): WebView = WebView(context).apply {
        configure(this, multiWindow, cacheMode, fitToScreen)
    }

    fun enableDesktopViewport(view: WebView) {
        val script = """
            (function() {
              var head = document.head || document.getElementsByTagName('head')[0];
              if (!head) return;
              var meta = document.querySelector('meta[name="viewport"]');
              if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                head.appendChild(meta);
              }
              var desktopViewport = 'width=980, initial-scale=1, minimum-scale=0.25, maximum-scale=5, user-scalable=yes';
              // Avoid rewriting the viewport meta on every same-site navigation:
              // changing it forces Chromium to recalculate the page layout.
              if (meta.getAttribute('content') !== desktopViewport) {
                meta.setAttribute('content', desktopViewport);
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    /**
     * Horizontally center an oversized desktop page using WebView's native scroll
     * position. This does not rewrite the site's DOM or viewport metadata.
     */
    fun centerDesktopContent(view: WebView) {
        fun center() {
            if (view.width <= 0 || view.height <= 0) return
            view.evaluateJavascript(
                "(function(){var d=document.documentElement,b=document.body;return Math.max(d?d.scrollWidth:0,b?b.scrollWidth:0);})()"
            ) { result ->
                val cssWidth = result?.trim()?.trim('"')?.toFloatOrNull() ?: return@evaluateJavascript
                view.post {
                    if (view.width <= 0) return@post
                    val contentWidthPx = (cssWidth * view.scale).toInt()
                    val maxScrollX = (contentWidthPx - view.width).coerceAtLeast(0)
                    view.scrollTo(maxScrollX / 2, view.scrollY)
                }
            }
        }
        view.post { center() }
        view.postDelayed({ center() }, 650L)
    }

    /** Attach a popup in the same layout slot as its source WebView. */
    fun attachPopup(
        container: android.view.ViewGroup,
        source: android.view.View,
        popup: android.view.View,
        layoutParams: android.view.ViewGroup.LayoutParams
    ) {
        val sourceIndex = container.indexOfChild(source).takeIf { it >= 0 } ?: container.childCount
        (popup.parent as? android.view.ViewGroup)?.removeView(popup)
        container.addView(popup, sourceIndex, layoutParams)
        source.visibility = android.view.View.GONE
    }

    fun configure(
        view: WebView,
        multiWindow: Boolean = true,
        cacheMode: Int = WebSettings.LOAD_DEFAULT,
        fitToScreen: Boolean = true
    ) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(multiWindow)

            // Keep a consistent desktop layout viewport on both services.
            userAgentString = DESKTOP_USER_AGENT
            // Match Chromium's documented browser-style WebView configuration:
            // wide desktop viewport + overview fit + text autosizing.
            useWideViewPort = true
            loadWithOverviewMode = fitToScreen
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

            // Allow pinch zoom without placing intrusive +/- controls over the page.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100

            this.cacheMode = cacheMode
        }

        // Keep the WebView on the window's hardware-accelerated render path.
        // LAYER_TYPE_NONE avoids forcing an extra cached hardware layer over a
        // frequently changing, scroll-heavy page.
        view.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // Do not install a custom touch listener: let Chromium handle drag,
        // fling and pinch gestures directly without an extra app-side event path.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
    }
}
