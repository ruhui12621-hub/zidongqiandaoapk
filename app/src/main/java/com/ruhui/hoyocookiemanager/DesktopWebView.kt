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
     * Skland's desktop header can exceed the WebView viewport while clipping its
     * own navigation row. Make the header/navigation row horizontally scrollable.
     */
    fun enableSklandHeaderScroll(view: WebView) {
        val script = """
            (function() {
              function fixHeader() {
                var viewportWidth = document.documentElement.clientWidth || window.innerWidth;
                var matches = Array.from(document.querySelectorAll('body *')).filter(function(el) {
                  if (!el.children.length && /首页|版区/.test(el.textContent || '')) return true;
                  return false;
                });
                matches.forEach(function(label) {
                  var node = label;
                  for (var depth = 0; node && depth < 6; depth++, node = node.parentElement) {
                    if (!node || node === document.body || node === document.documentElement) continue;
                    var rect = node.getBoundingClientRect();
                    var tooWide = rect.width > viewportWidth + 8;
                    var hasOverflow = node.scrollWidth > node.clientWidth + 8;
                    if (tooWide || hasOverflow) {
                      node.style.setProperty('max-width', '100vw', 'important');
                      node.style.setProperty('width', '100vw', 'important');
                      node.style.setProperty('overflow-x', 'auto', 'important');
                      node.style.setProperty('overscroll-behavior-x', 'contain', 'important');
                      node.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');
                      node.style.setProperty('touch-action', 'pan-x pan-y', 'important');
                      node.style.setProperty('scrollbar-width', 'none', 'important');
                      break;
                    }
                  }
                });
              }
              fixHeader();
              if (!window.__sklandHeaderScrollObserver && document.documentElement) {
                window.__sklandHeaderScrollObserver = new MutationObserver(fixHeader);
                window.__sklandHeaderScrollObserver.observe(document.documentElement, {
                  childList: true, subtree: true
                });
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
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

        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // Keep horizontal drags inside WebView when hosted by a scrolling parent.
        // Returning false preserves WebView's own pinch-zoom and page gestures.
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                    touched.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    touched.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
    }
}
