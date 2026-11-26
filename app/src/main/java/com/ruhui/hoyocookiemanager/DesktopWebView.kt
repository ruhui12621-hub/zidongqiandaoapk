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
     * Center the desktop page's main content in the visible WebView area.
     * This changes scroll position only; desktop UA, viewport and zoom are untouched.
     * A short bounded settling pass handles late layout shifts without polling.
     */
    fun centerDesktopContent(view: WebView) {
        val script = """
            (function() {
              if (window.__appCenteringInstalled) {
                if (window.__appCenterUserInteracted) return;
              } else {
                window.__appCenteringInstalled = true;
                window.__appCenterUserInteracted = false;
                var markInteraction = function() { window.__appCenterUserInteracted = true; };
                ['touchstart', 'pointerdown', 'wheel'].forEach(function(name) {
                  window.addEventListener(name, markInteraction, { passive: true, once: true, capture: true });
                });
              }

              function center() {
                if (window.__appCenterUserInteracted || !document.documentElement) return;
                var root = document.documentElement;
                var body = document.body;
                var vw = window.innerWidth || root.clientWidth;
                var vh = window.innerHeight || root.clientHeight;
                if (!vw || !vh) return;

                var candidates = [];
                var selectors = ['main', '[role="main"]', '#root', '#app', '#__next', 'article'];
                selectors.forEach(function(selector) {
                  try {
                    document.querySelectorAll(selector).forEach(function(el) {
                      var rect = el.getBoundingClientRect();
                      var style = window.getComputedStyle(el);
                      if (style.display === 'none' || style.visibility === 'hidden') return;
                      if (rect.width > vw * 0.25 && rect.height > 80) {
                        candidates.push({ el: el, area: rect.width * Math.min(rect.height, vh * 3) });
                      }
                    });
                  } catch (_) {}
                });

                var target = candidates.sort(function(a, b) { return b.area - a.area; })[0];
                var docWidth = Math.max(root.scrollWidth, body ? body.scrollWidth : 0, vw);
                var maxX = Math.max(0, docWidth - vw);
                var x = maxX / 2;
                var y = 0;

                if (target) {
                  var rect = target.el.getBoundingClientRect();
                  var pageTop = rect.top + (window.pageYOffset || root.scrollTop || 0);
                  var targetHeight = Math.min(rect.height, vh);
                  y = pageTop + rect.height / 2 - vh / 2;
                  // Keep the top of a short login panel visible rather than centering empty space.
                  if (rect.height <= vh) y = pageTop + rect.height / 2 - vh / 2;
                  else y = pageTop;
                } else {
                  var docHeight = Math.max(root.scrollHeight, body ? body.scrollHeight : 0, vh);
                  y = Math.max(0, (docHeight - vh) / 2);
                }

                var maxY = Math.max(0, (body ? body.scrollHeight : 0, root.scrollHeight) - vh);
                window.scrollTo({ left: Math.min(maxX, Math.max(0, x)), top: Math.min(maxY, Math.max(0, y)), behavior: 'instant' });
              }

              [250, 800, 1600, 2800].forEach(function(delay) {
                window.setTimeout(center, delay);
              });
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
