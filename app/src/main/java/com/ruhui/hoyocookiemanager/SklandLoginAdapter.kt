package com.ruhui.hoyocookiemanager

import android.webkit.WebView
import org.json.JSONObject

/**
 * Skland-only login entry behavior.
 *
 * This adapter opens the site's visible login entry on the home page. It does not
 * own generic WebView presentation or account/session storage.
 */
object SklandLoginAdapter {
    const val HOME_URL = "https://www.skland.com/"
    const val TOKEN_URL = "https://web-api.skland.com/account/info/hg"

    data class TokenResponse(
        val businessCode: Int,
        val message: String,
        val token: String?
    )

    fun parseTokenResponse(body: String): TokenResponse {
        val json = JSONObject(body)
        return TokenResponse(
            businessCode = json.optInt("code", -1),
            message = json.optString("msg", json.optString("message", "未提供消息")),
            token = json.optJSONObject("data")?.optString("content").orEmpty()
                .ifBlank { json.optString("content").orEmpty() }
        )
    }

    fun isValidToken(value: String): Boolean = SklandCredentialAdapter.isValidToken(value)

    fun onPageReady(view: WebView) {
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
}
