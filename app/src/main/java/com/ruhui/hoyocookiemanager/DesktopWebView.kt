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

    fun enableDesktopViewport(view: WebView, onApplied: (() -> Unit)? = null) {
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
        view.evaluateJavascript(script) { onApplied?.invoke() }
    }

    /**
     * Single page-ready entry point shared by HoYoLAB and Skland.
     * It applies the desktop viewport, keeps wide fixed headers synchronized
     * with document panning, then positions the page using the shared centering logic.
     */
    fun prepareDesktopPage(view: WebView) {
        // Run the steps in order: viewport changes can trigger a layout pass, so
        // measuring/centering before Chromium applies it can produce a zero X range.
        enableDesktopViewport(view) {
            makeTopFixedHeadersFollowHorizontalScroll(view) {
                // Site detection and login-entry automation live in this shared
                // pipeline. The script itself is a no-op on every non-Skland host.
                autoClickSklandLoginEntry(view)
                centerDesktopContent(view)
            }
        }
    }

    /**
     * On the Skland home page only, automatically open the official top-right
     * "登录" entry. This deliberately does not run on login sub-pages/popups or
     * on HoYoLAB. The DOM observer handles delayed client-side rendering without
     * polling the page continuously.
     */
    private fun autoClickSklandLoginEntry(view: WebView) {
        val script = """
            (function() {
              var host = (location.hostname || '').toLowerCase();
              var path = (location.pathname || '/').replace(/\/+$/, '') || '/';
              if (!(host === 'skland.com' || host.endsWith('.skland.com')) || path !== '/') return;
              if (window.__sklandTopRightLoginAutoClickInstalled) return;
              window.__sklandTopRightLoginAutoClickInstalled = true;

              var observer = null;
              var finished = false;
              var scheduled = false;
              var startedAt = Date.now();

              function visible(el) {
                if (!el || !el.isConnected) return false;
                var style = getComputedStyle(el);
                var rect = el.getBoundingClientRect();
                return style.display !== 'none' &&
                  style.visibility !== 'hidden' &&
                  Number(style.opacity || 1) > 0 &&
                  rect.width > 0 && rect.height > 0 &&
                  rect.top >= -10 && rect.top < 220 &&
                  rect.right > window.innerWidth * 0.55 &&
                  rect.left < window.innerWidth;
              }

              function findLoginEntry() {
                var selector = 'a,button,[role="button"],[onclick],[class*="login"],header *,nav *,div,span';
                var nodes = document.querySelectorAll(selector);
                for (var i = 0; i < nodes.length; i++) {
                  var node = nodes[i];
                  var label = (node.innerText || node.textContent || '').replace(/\s+/g, '').trim();
                  if (label !== '登录' && label !== '立即登录' && label !== '登录/注册') continue;
                  if (!visible(node)) continue;

                  var target = node.closest('a,button,[role="button"],[onclick]');
                  if (!target) {
                    var parent = node;
                    for (var depth = 0; depth < 3 && parent.parentElement; depth++) {
                      parent = parent.parentElement;
                      var cursor = getComputedStyle(parent).cursor;
                      if (cursor === 'pointer') break;
                    }
                    target = parent;
                  }
                  if (!visible(target)) continue;
                  var targetText = (target.innerText || target.textContent || '').replace(/\s+/g, '').trim();
                  if (targetText.length > 18) continue;
                  return target;
                }
                return null;
              }

              function scan() {
                scheduled = false;
                if (finished) return;
                if (Date.now() - startedAt > 10000) {
                  finished = true;
                  if (observer) observer.disconnect();
                  return;
                }
                var button = findLoginEntry();
                if (!button) return;
                finished = true;
                if (observer) observer.disconnect();
                // Use the element's native click path so the site's own event
                // handlers/router receive the action; no credentials are entered.
                button.click();
              }

              function scheduleScan() {
                if (scheduled || finished) return;
                scheduled = true;
                requestAnimationFrame(scan);
              }

              scheduleScan();
              if (window.MutationObserver && document.documentElement) {
                observer = new MutationObserver(scheduleScan);
                observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
              }
              [250, 700, 1500, 3000, 6000, 9500].forEach(function(delay) {
                setTimeout(scheduleScan, delay);
              });
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    /**
     * Center the desktop document horizontally and vertically.
     * CSS document dimensions are converted to WebView pixels with the current
     * page scale; the fixed-header synchronizer compensates for horizontal pan.
     */
    fun centerDesktopContent(view) {
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
                    val maxScrollX = ((cssWidth * scale).toInt() - view.width).coerceAtLeast(0)
                    val maxScrollY = ((cssHeight * scale).toInt() - view.height).coerceAtLeast(0)
                    val targetX = maxScrollX / 2
                    val targetY = maxScrollY / 2
                    view.scrollTo(targetX, targetY)
                }
            }
        }
        // Wait for the site's responsive layout to settle before measuring.
        view.post { center() }
        view.postDelayed({ center() }, 500L)
        view.postDelayed({ center() }, 1100L)
    }

    /**
     * Keep desktop-site top navigation bars in the same horizontal coordinate space
     * as the document. Some sites use position:fixed for their top header; that
     * makes the header stay pinned to the WebView while the wide desktop page pans.
     * For wide, top-level fixed bars only, compensate their left edge by scrollX.
     */
    fun makeTopFixedHeadersFollowHorizontalScroll(view: WebView, onInstalled: (() -> Unit)? = null) {
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
        view.evaluateJavascript(script) { onInstalled?.invoke() }
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
