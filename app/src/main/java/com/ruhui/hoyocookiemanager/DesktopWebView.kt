package com.ruhui.hoyocookiemanager

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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
        fitToScreen: Boolean = true,
        profileName: String? = null
    ): WebView = WebView(context).apply {
        configure(this, multiWindow, cacheMode, fitToScreen, profileName)
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
     * Center the full desktop document in both axes using WebView's native scroll.
     * The JavaScript result is a simple comma-separated string so Android can
     * parse it reliably (evaluateJavascript JSON-escapes returned JS strings).
     */
    fun centerDesktopContent(view: WebView, centerVertical: Boolean = true) {
        fun center() {
            if (view.width <= 0 || view.height <= 0) return
            view.evaluateJavascript(
                "(function(){var d=document.documentElement,b=document.body;" +
                    "var w=Math.max(d?d.scrollWidth:0,b?b.scrollWidth:0);" +
                    "var h=Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0);" +
                    "return w+','+h;})()"
            ) { result ->
                val dimensions = result?.trim()?.trim('"')?.split(',') ?: return@evaluateJavascript
                if (dimensions.size != 2) return@evaluateJavascript
                val cssWidth = dimensions[0].toFloatOrNull() ?: return@evaluateJavascript
                val cssHeight = dimensions[1].toFloatOrNull() ?: return@evaluateJavascript
                view.post {
                    if (view.width <= 0 || view.height <= 0) return@post
                    val scale = view.scale
                    val maxScrollY = ((cssHeight * scale).toInt() - view.height).coerceAtLeast(0)
                    // Never center by scrolling into a wide document: that cuts off its
                    // left-side navigation/header (as seen on Skland). Keep the document's
                    // leading edge visible; native pinch zoom remains enabled in configure().
                    // Vertical centering is retained only when explicitly requested.
                    val targetY = if (centerVertical) maxScrollY / 2 else 0
                    view.scrollTo(0, targetY)
                }
            }
        }
        view.post { center() }
        view.postDelayed({ center() }, 650L)
    }

    /**
     * Keep desktop-site top navigation bars in the same horizontal coordinate space
     * as the document. Some sites use position:fixed for their top header; that
     * makes the header stay pinned to the WebView while the wide desktop page pans.
     * For wide, top-level fixed bars only, compensate their left edge by scrollX.
     */
    fun makeTopFixedHeadersFollowHorizontalScroll(view: WebView) {
        val script = """
            (function() {
              if (window.__desktopHeaderPanSyncInstalled) return;
              window.__desktopHeaderPanSyncInstalled = true;
              var headers = new Set();
              function isTopFixed(el) {
                var s = getComputedStyle(el), r = el.getBoundingClientRect();
                if (s.position !== 'fixed' ||
                    r.top < -2 || r.top >= Math.min(window.innerHeight * 0.4, 260) ||
                    r.height < 20 || r.width < window.innerWidth * 0.45) return false;
                for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) {
                  if (getComputedStyle(p).position === 'fixed') return false;
                }
                return true;
              }
              function scan() {
                document.querySelectorAll('body *').forEach(function(el) {
                  if (!headers.has(el) && isTopFixed(el)) headers.add(el);
                });
              }
              function sync() {
                scan();
                var x = window.scrollX || document.documentElement.scrollLeft || 0;
                headers.forEach(function(el) {
                  if (!el.isConnected) { headers.delete(el); return; }
                  // Use CSS individual translate so the site's own transform is preserved.
                  // Unlike changing left/right, this does not alter the header's layout width.
                  el.style.setProperty('translate', (-x) + 'px 0', 'important');
                });
              }
              scan();
              window.addEventListener('scroll', sync, {passive:true});
              window.addEventListener('resize', sync, {passive:true});
              if (window.MutationObserver && document.body) {
                new MutationObserver(function() { scan(); sync(); })
                  .observe(document.body, {childList:true, subtree:true});
              }
              sync();
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
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
        fitToScreen: Boolean = true,
        profileName: String? = null
    ) {
        if (profileName != null) {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                "当前系统 WebView 不支持多配置文件"
            }
            WebViewCompat.setProfile(view, profileName)
        }
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
        val cookies = if (profileName == null) {
            CookieManager.getInstance()
        } else {
            WebViewCompat.getProfile(view).cookieManager
        }
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
    }
}
