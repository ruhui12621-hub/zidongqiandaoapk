package com.ruhui.hoyocookiemanager

import android.webkit.WebView

/**
 * Shared login-page presentation owned by the single global template.
 *
 * Platform modules select a presentation profile by ID; they do not implement
 * their own centering or viewport rules. Skland retains its established vertical offset.
 */
object TemplateLoginLayout {
    private const val DEFAULT_VERTICAL_SCROLL_CSS_PX = 600f
    private const val SKLAND_VERTICAL_SCROLL_CSS_PX = 800f

    fun centerDesktopContent(view: WebView, platformId: String?) {
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
                    // Keep horizontal centering. Skland gets an additional
                    // 100 CSS-pixel vertical offset; HoYoLAB keeps the original value.
                    // Login-page positioning is a template presentation rule, selected by platform ID.
                    val verticalOffset = if (platformId == AccountStore.SKLAND) {
                        SKLAND_VERTICAL_SCROLL_CSS_PX
                    } else {
                        DEFAULT_VERTICAL_SCROLL_CSS_PX
                    }
                    val targetY = (verticalOffset * scale)
                        .toInt()
                        .coerceIn(0, maxScrollY)
                    view.scrollTo(targetX, targetY)
                }
            }
        }
        // Re-measure after the site's responsive layout has settled.
        view.post { center() }
        view.postDelayed({ center() }, 500L)
        view.postDelayed({ center() }, 1100L)
    }
}
