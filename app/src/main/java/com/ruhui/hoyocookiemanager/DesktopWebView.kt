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
        cacheMode: Int = WebSettings.LOAD_DEFAULT
    ): WebView = WebView(context).apply {
        configure(this, multiWindow, cacheMode)
    }

    /**
     * Override restrictive viewport declarations used by some desktop login pages.
     * Native WebView zoom remains responsible for the actual pinch gesture.
     */
    fun enablePageZoom(view: WebView) {
        val script = """
            (function() {
              function allowZoom() {
                var head = document.head || document.getElementsByTagName('head')[0];
                if (!head) return;
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                  meta = document.createElement('meta');
                  meta.name = 'viewport';
                  head.appendChild(meta);
                }
                var parts = (meta.content || '').split(',').map(function(x) { return x.trim(); })
                  .filter(function(x) {
                    return !/^(user-scalable|maximum-scale|minimum-scale|initial-scale)\s*=/i.test(x);
                  });
                parts.push('user-scalable=yes', 'minimum-scale=0.5', 'maximum-scale=5');
                var nextContent = parts.join(', ');
                if (meta.content !== nextContent) meta.content = nextContent;
              }
              allowZoom();
              if (!window.__appZoomObserver && document.documentElement) {
                window.__appZoomObserver = new MutationObserver(function() { allowZoom(); });
                window.__appZoomObserver.observe(document.documentElement, {
                  childList: true, subtree: true, attributes: true,
                  attributeFilter: ['content', 'name']
                });
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    /**
     * Use a fixed desktop layout viewport for Skland pages that otherwise keep
     * a narrow mobile viewport despite the desktop user agent.
     *
     * The page itself is not restyled; native WebView overview/zoom handles
     * fitting and user interaction.
     */
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
     * Fit the desktop document into the available WebView width before centering.
     *
     * Never jump to the midpoint of an oversized document: that hides both
     * edges. First let Chromium zoom out in bounded steps until the full
     * document fits. Then keep the horizontal origin at zero; pages whose
     * own layout is narrower than the viewport are centered by their CSS.
     *
     * Re-check after layout settles, but stop as soon as the user touches the
     * page so automatic positioning never fights a drag or pinch gesture.
     */
    fun centerDesktopContent(view: WebView) {
        var userInteracted = false
        val touchGuard = android.view.View.OnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                event.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                userInteracted = true
            }
            false
        }
        view.setOnTouchListener(touchGuard)

        val attempts = intArrayOf(180, 650, 1400)
        attempts.forEach { delay ->
            view.postDelayed({
                if (userInteracted || !view.isAttachedToWindow || view.width <= 0) return@postDelayed
                view.evaluateJavascript(
                    "(function(){var d=document.documentElement,b=document.body;" +
                        "return Math.max(d?d.scrollWidth:0,b?b.scrollWidth:0);})()"
                ) { raw ->
                    if (userInteracted || !view.isAttachedToWindow) return@evaluateJavascript
                    val cssWidth = raw?.toDoubleOrNull() ?: return@evaluateJavascript
                    val visibleCssWidth = view.width / view.scale.coerceAtLeast(0.1f)
                    if (cssWidth > visibleCssWidth * 1.02) {
                        // A centered midpoint would crop both sides. Zoom out
                        // instead so the complete desktop page remains reachable.
                        var remaining = 8
                        while (remaining-- > 0 && view.width / view.scale < cssWidth * view.scale / view.scale) {
                            if (!view.zoomOut()) break
                        }
                    }
                    view.post {
                        if (!userInteracted && view.isAttachedToWindow) {
                            // Do not set scrollX to half the overflow. That is
                            // precisely what made the left and right edges disappear.
                            view.scrollTo(0, view.scrollY)
                        }
                    }
                }
            }, delay.toLong())
        }
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
        cacheMode: Int = WebSettings.LOAD_DEFAULT
    ) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(multiWindow)

            // Keep a consistent desktop layout viewport on both services.
            userAgentString = DESKTOP_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true

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
