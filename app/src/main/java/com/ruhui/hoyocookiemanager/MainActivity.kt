package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : HuaweiCloudActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var webView: WebView
    private var popupWebView: WebView? = null
    private var cookieValue = ""
    private var yamlValue = ""
    private var detectedGameServer: String? = null
    private var activeAccountId: String? = null
    private var activePlatform: String? = null
    private var browserVisible = false
    private var showingLauncher = true
    private var showingBrowser = false
    private val webDiagnostics = mutableListOf<String>()

    private val cookieUrls = listOf(
        "https://www.hoyolab.com/",
        "https://act.hoyolab.com/",
        "https://bbs-api-os.hoyolab.com/",
        "https://www.miyoushe.com/",
        "https://bbs.mihoyo.com/",
        "https://user.mihoyo.com/",
        "https://bbs-api.mihoyo.com/"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        migrateLegacyAccounts()
        activeAccountId = intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)
        activePlatform = intent.getStringExtra(AccountStore.EXTRA_PLATFORM)
        buildLauncher()
        val cloudCredentials = runCatching { AndroidCredentialVault(this).latest() }.getOrNull()
        if (cloudCredentials != null) {
            synchronizeCloudAccounts { _, message ->
                if (showingLauncher && !isFinishing) buildLauncher()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } else {
            CloudAccountSyncState.set(this, CloudAccountSyncState.UNKNOWN)
        }
        if (activePlatform == AccountStore.HOYOLAB || activePlatform == AccountStore.MIYOUSHE_CN) {
            buildHome()
            showBrowser()
        }
    }

    override fun onDestroy() {
        // 只销毁 WebView 实例，不清理 Cookie、缓存或站点存储；登录状态由独立数据目录持久保存。
        try {
            popupWebView?.let { popup ->
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.stopLoading()
                popup.destroy()
                popupWebView = null
            }
            CookieManager.getInstance().flush()
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.destroy()
            }
        } catch (_: Exception) {
            // 系统回收时，WebView 数据目录仍会保留登录状态。
        }
        super.onDestroy()
    }

    /**
     * One-time migration of the previous single-account credentials into the new registry.
     * Existing encrypted credentials are copied before the legacy Skland vault is removed.
     */
    private fun migrateLegacyAccounts() {
        val store = AccountStore(this)
        val manager = CookieManager.getInstance()

        fun migrateHoyo(platform: String, urls: List<String>) {
            val cookie = urls.mapNotNull { manager.getCookie(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("; ")
            if (cookie.isBlank()) return
            val identity = HoYoAccountIdentity.fromCookie(cookie, platform)
            if (identity == null && store.all().any { it.platform == platform }) return
            val account = if (identity != null) store.add(platform, platformUserId = identity) else store.add(platform)
            runCatching {
                HoYoCookieVault(this, account.id).save(cookie)
                store.update(account.copy(platformUserId = identity ?: account.platformUserId, credentialReady = true))
            }
        }

        migrateHoyo(
            AccountStore.HOYOLAB,
            listOf("https://www.hoyolab.com/", "https://act.hoyolab.com/", "https://bbs-api-os.hoyolab.com/")
        )
        migrateHoyo(
            AccountStore.MIYOUSHE_CN,
            listOf("https://www.miyoushe.com/", "https://bbs.mihoyo.com/", "https://user.mihoyo.com/", "https://bbs-api.mihoyo.com/")
        )

        val legacySkland = runCatching { SklandTokenVault(this).latest() }.getOrNull()
        if (!legacySkland.isNullOrBlank() && store.all().none {
                it.platform == AccountStore.SKLAND && it.credentialReady
            }) {
            runCatching {
                val account = store.add(AccountStore.SKLAND)
                SklandTokenVault(this, account.id).save(legacySkland)
                store.update(account.copy(credentialReady = true))
                SklandTokenVault(this).delete()
            }
        }
    }

    private fun buildLauncher() {
        showingLauncher = true
        showingBrowser = false
        if (::webView.isInitialized) (webView.parent as? ViewGroup)?.removeView(webView)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        root.addView(text("自动签到管理", 25, true).apply {
            setPadding(0, 0, 0, dp(5))
        })
        root.addView(text("云服务与账号", 13, false, Color.rgb(155, 164, 178)).apply {
            setPadding(0, 0, 0, dp(18))
        })

        fun row(parent: LinearLayout, title: String, subtitle: String, statusText: String, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(12), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(32, 38, 49))
                    cornerRadius = dp(13).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(title, 16, true))
            info.addView(text(subtitle, 12, false, Color.rgb(155, 164, 178)).apply {
                setPadding(0, dp(5), 0, 0)
            })
            item.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(text(statusText, 11, false, Color.rgb(155, 164, 178)))
            item.addView(text("›", 24, false, Color.rgb(155, 164, 178)).apply {
                setPadding(dp(8), 0, 0, 0)
            })
            parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }

        val cloudSaved = runCatching { AndroidCredentialVault(this).latest() != null }.getOrDefault(false)
        val cloudStatus = when {
            HuaweiCloudSession.isLoggedIn() -> "已连接"
            cloudSaved -> "凭据已保存"
            else -> "未登录"
        }
        row(root, "华为云登录", "管理 AK / SK、Region 和云端连接", cloudStatus) {
            startActivity(android.content.Intent(this, HuaweiCloudSettingsActivity::class.java))
        }

        val cloudSyncState = CloudAccountSyncState.get(this)
        val cloudSyncLabel = when (cloudSyncState) {
            CloudAccountSyncState.SYNCING -> "正在读取华为云配置，账号操作暂时锁定"
            CloudAccountSyncState.SYNCED -> "云端配置已读取"
            CloudAccountSyncState.EMPTY_CONFIRMED -> "云端已确认没有历史函数配置，可进行首次初始化"
            CloudAccountSyncState.FAILED -> "云端读取失败，账号操作已锁定"
            else -> "请先连接华为云并读取现有配置"
        }
        root.addView(text(cloudSyncLabel, 12, false, Color.rgb(255, 204, 128)).apply {
            setPadding(0, dp(2), 0, dp(8))
        })
        root.addView(text("账号列表", 17, true).apply {
            setPadding(0, dp(8), 0, dp(10))
        })
        val accountScroll = ScrollView(this)
        val accountContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accountScroll.addView(accountContainer)
        root.addView(accountScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val accounts = AccountStore(this).all()
        if (accounts.isEmpty()) {
            accountContainer.addView(text("还没有添加账号。点击下方加号，选择 HoYoLAB、森空岛或米游社国服。", 13, false, Color.rgb(145, 155, 172)).apply {
                setPadding(dp(4), dp(4), dp(4), dp(12))
            })
        } else {
            accounts.forEach { account ->
                val gameNames = account.games.mapNotNull {
                    when (it) {
                        "genshin" -> "原神"
                        "honkai2" -> "崩坏2"
                        "honkai3rd" -> "崩坏3"
                        "tears_of_themis" -> "未定事件簿"
                        "honkai_sr" -> "星穹铁道"
                        "zzz" -> "绝区零"
                        "arknights" -> "明日方舟"
                        else -> null
                    }
                }
                val platformLabel = AccountStore.platformLabel(account.platform)
                val identityHint = when {
                account.credentialReady && account.platformUserId != null -> "凭据已保存 · ID 已识别"
                account.credentialReady -> "凭据已保存 · 本地 ID"
                account.platformUserId != null -> "ID 已识别 · 待登录"
                else -> "等待登录"
            }
                row(
                    accountContainer,
                    account.name,
                    "$platformLabel · ${if (gameNames.isEmpty()) "尚未选择游戏" else gameNames.joinToString("、")}",
                    identityHint
                ) {
                    if (!CloudAccountSyncState.allowsAccountChanges(this)) {
                        Toast.makeText(this, "请先完成云端配置同步，避免使用过期的本地账号。", Toast.LENGTH_LONG).show()
                        return@row
                    }
                    startActivity(android.content.Intent(this, AccountManagementActivity::class.java)
                        .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id))
                }
            }
        }

        val canChangeAccounts = CloudAccountSyncState.allowsAccountChanges(this)
        val add = button(if (canChangeAccounts) "+ 添加账号" else "等待云端配置同步") {
            if (!CloudAccountSyncState.allowsAccountChanges(this)) {
                Toast.makeText(this, "必须先成功读取华为云配置，才能添加账号。", Toast.LENGTH_LONG).show()
                return@button
            }
            val labels = arrayOf("HoYoLAB（国际服）", "森空岛", "米游社（国服）")
            val platforms = arrayOf(AccountStore.HOYOLAB, AccountStore.SKLAND, AccountStore.MIYOUSHE_CN)
            AlertDialog.Builder(this)
                .setTitle("选择账号平台")
                .setItems(labels) { _, which ->
                    val created = AccountStore(this).add(platforms[which])
                    startActivity(android.content.Intent(this, AccountManagementActivity::class.java)
                        .putExtra(AccountStore.EXTRA_ACCOUNT_ID, created.id))
                }
                .setNegativeButton("取消", null)
                .show()
        }.apply {
            textSize = 16f
            minHeight = dp(52)
            isEnabled = canChangeAccounts
            alpha = if (canChangeAccounts) 1f else 0.55f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(39, 46, 60))
        }
        root.addView(add, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(4)
        })
        root.addView(text("账号凭据独立于游戏选择；相同游戏可配置在不同平台账号中。", 11, false, Color.rgb(130, 141, 158)).apply {
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // AccountManagementActivity returns to this Activity; rebuild the launcher from
        // the latest local registry so newly added/edited/deleted accounts appear at once.
        if (showingLauncher && !isFinishing) {
            buildLauncher()
        }
    }

    private fun buildHome() {
        AppMessageBus.setActiveChannel(AppMessageBus.HOYOLAB)
        showingLauncher = false
        showingBrowser = false
        if (::webView.isInitialized) (webView.parent as? ViewGroup)?.removeView(webView)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹ 主页") { buildLauncher() }, LinearLayout.LayoutParams(-2, -2))
        header.addView(text("HoYoLAB", 19, true), LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(12)
        })
        root.addView(header)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        status = text("登录状态：尚未检测", 16, true).apply {
            setPadding(0, dp(22), 0, dp(8))
        }
        content.addView(status)
        content.addView(button(if (activePlatform == AccountStore.MIYOUSHE_CN) "打开米游社登录页面" else "打开 HoYoLAB 登录页面") { showBrowser() })
        details = text("登录后自动检测 Cookie。", 13, false, Color.rgb(160, 168, 182)).apply {
            setPadding(0, dp(4), 0, dp(12))
        }
        content.addView(details)
        content.addView(text("HoYoLAB 操作消息", 16, true).apply { setPadding(0, dp(14), 0, dp(6)) })
        val hoyoMessage = text(AppMessageBus.current(this, AppMessageBus.HOYOLAB), 12, false, Color.rgb(190, 199, 213)).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(32, 38, 49))
        }
        content.addView(hoyoMessage)
        content.addView(button("刷新消息") {
            hoyoMessage.text = AppMessageBus.current(this, AppMessageBus.HOYOLAB)
        })
        content.addView(text("登录成功后自动识别当前平台账号，并将 Cookie 加密保存在该账号名下。", 13, false, Color.rgb(160, 168, 182)).apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingBrowser && ::webView.isInitialized) {
            if (webView.canGoBack()) webView.goBack() else buildHome()
        } else if (!showingLauncher) {
            buildLauncher()
        } else {
            finishAffinity()
        }
    }

    private fun showBrowser() {
        showingBrowser = true
        if (!::webView.isInitialized) {
            webView = DesktopWebView.create(
                this,
                multiWindow = true,
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            ).apply {
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        recordWebDiagnostic(
                            "JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        )
                        return true
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message
                    ): Boolean {
                        recordWebDiagnostic("网站请求打开新窗口：userGesture=$isUserGesture，dialog=$isDialog")
                        val popup = createPopupWebView()
                        popupWebView?.let { old ->
                            (old.parent as? ViewGroup)?.removeView(old)
                            old.destroy()
                        }
                        popupWebView = popup
                        (webView.parent as? ViewGroup)?.let { container ->
                            container.addView(popup, LinearLayout.LayoutParams(-1, 0, 1f))
                            webView.visibility = android.view.View.GONE
                        }
                        val transport = resultMsg.obj as WebView.WebViewTransport
                        transport.webView = popup
                        resultMsg.sendToTarget()
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            recordWebDiagnostic(
                                "主页面加载失败：${request.url.host}，错误码 ${error.errorCode}，${error.description}"
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: android.webkit.WebResourceResponse
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request.isForMainFrame || errorResponse.statusCode >= 400) {
                            recordWebDiagnostic(
                                "HTTP ${errorResponse.statusCode}：${request.url.host}${request.url.encodedPath}"
                            )
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: android.webkit.RenderProcessGoneDetail
                    ): Boolean {
                        recordWebDiagnostic("WebView 渲染进程退出，是否被系统回收：${detail.didCrash()}")
                        return true
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        status.text = "页面已加载，正在检查 Cookie…"
                        recordWebDiagnostic("页面加载完成：${url.substringBefore('?')}")
                        DesktopWebView.enablePageZoom(view)
                        view.postDelayed({ refreshFromWebView() }, 900)
                    }
                }
            }
        }

        root.removeAllViews()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "返回"
            setOnClickListener {
                buildHome()
                refreshFromWebView()
            }
        }
        bar.addView(back)
        bar.addView(text(if (activePlatform == AccountStore.MIYOUSHE_CN) "米游社登录" else "HoYoLAB 登录", 18, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        root.addView(bar)
        (webView.parent as? ViewGroup)?.removeView(webView)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        popupWebView?.let { popup ->
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.destroy()
            popupWebView = null
        }
        webView.visibility = android.view.View.VISIBLE
        if (!browserVisible || webView.url.isNullOrBlank()) {
            webView.loadUrl(if (activePlatform == AccountStore.MIYOUSHE_CN) "https://www.miyoushe.com/" else "https://www.hoyolab.com/")
        }
        browserVisible = true
    }


    // 为 window.open / target=_blank 创建 APK 内部子窗口；CookieManager 与主窗口共享。
    private fun createPopupWebView(): WebView {
        return DesktopWebView.create(
            this,
            multiWindow = false,
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        ).apply {
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    recordWebDiagnostic(
                        "弹窗 JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                            "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                    return true
                }

                override fun onCloseWindow(window: WebView) {
                    recordWebDiagnostic("网站关闭了验证子窗口")
                    (window.parent as? ViewGroup)?.removeView(window)
                    window.stopLoading()
                    window.destroy()
                    if (popupWebView === window) popupWebView = null
                    if (::webView.isInitialized) webView.visibility = android.view.View.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    recordWebDiagnostic("验证子窗口加载完成：${url.substringBefore('?')}")
                    DesktopWebView.enablePageZoom(view)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        recordWebDiagnostic(
                            "验证子窗口加载失败：${request.url.host}，错误码 ${error.errorCode}，${error.description}"
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame || errorResponse.statusCode >= 400) {
                        recordWebDiagnostic(
                            "验证子窗口 HTTP ${errorResponse.statusCode}：${request.url.host}${request.url.encodedPath}"
                        )
                    }
                }
            }
        }
    }

    private fun refreshFromWebView(showToast: Boolean = false) {
        val manager = CookieManager.getInstance()
        val collected = linkedSetOf<String>()
        cookieUrls.forEach { url ->
            manager.getCookie(url)?.split(';')
                ?.map { it.trim() }
                ?.filter { it.contains('=') }
                ?.forEach { collected.add(it) }
        }
        val found = collected.joinToString("; ")
        cookieValue = found
        val names = found.split(';')
            .mapNotNull { it.trim().substringBefore('=').takeIf { name -> name.isNotBlank() } }
            .toSet()
        val cnMarkers = setOf("stoken", "stoken_v2", "stuid", "cookie_token", "ltoken")
        val osMarkers = setOf("ltoken_v2", "cookie_token_v2", "account_id_v2", "ltuid_v2")
        val hasCnToken = names.any { it in cnMarkers }
        val hasOsToken = names.any { it in osMarkers }
        val cnDomainHasToken = listOf(
            "https://www.miyoushe.com/", "https://bbs.mihoyo.com/",
            "https://user.mihoyo.com/", "https://bbs-api.mihoyo.com/"
        ).any { url ->
            manager.getCookie(url).orEmpty().split(';').any { part ->
                part.trim().substringBefore('=') in cnMarkers
            }
        }
        detectedGameServer = when {
            hasCnToken && !hasOsToken -> "cn"
            hasOsToken && !hasCnToken -> "os"
            hasCnToken && hasOsToken -> if (cnDomainHasToken) "cn" else "os"
            else -> null
        }

        if (detectedGameServer != null) {
            val platform = if (detectedGameServer == "cn") AccountStore.MIYOUSHE_CN else AccountStore.HOYOLAB
            val requested = activeAccountId?.let { AccountStore(this).find(it) }
            if (requested != null && requested.platform != platform) {
                status.text = "账号平台不匹配"
                details.text = "当前登录凭据识别为${AccountStore.platformLabel(platform)}，但正在编辑的是${AccountStore.platformLabel(requested.platform)}。请返回并选择正确的平台账号。"
                updateHuaweiCloudConfig("")
                return
            }
            val identity = HoYoAccountIdentity.fromCookie(found, platform)
            val store = AccountStore(this)
            var account = requested
                ?: identity?.let { uid -> store.all().firstOrNull { it.platform == platform && it.platformUserId == uid } }
                ?: store.add(platform, platformUserId = identity)
            if (identity != null && account.platformUserId != null && account.platformUserId != identity) {
                status.text = "检测到不同账号"
                details.text = "当前登录身份与此账号已绑定的 ID 不同。为避免覆盖其他账号，请新建一个账号后再登录。"
                updateHuaweiCloudConfig("")
                return
            }
            val duplicate = identity?.let { uid ->
                store.all().firstOrNull { it.id != account.id && it.platform == platform && it.platformUserId == uid }
            }
            if (duplicate != null) {
                status.text = "账号已存在"
                details.text = "该平台 ID 已关联到「${duplicate.name}」，请直接使用已有账号，避免重复添加。"
                updateHuaweiCloudConfig("")
                return
            }
            account = account.copy(platformUserId = identity ?: account.platformUserId, credentialReady = true)
            store.update(account)
            runCatching { HoYoCookieVault(this, account.id).save(found) }
                .onFailure { recordWebDiagnostic("账号 Cookie 加密保存失败：${it.javaClass.simpleName}") }
            activeAccountId = account.id
            getSharedPreferences("multi_account_registry", MODE_PRIVATE).edit()
                .putString("active_${platform}", account.id).apply()

            val server = detectedGameServer!!
            yamlValue = makeYaml(found, server)
            status.text = "Cookie 状态：已获取（${if (server == "cn") "国服" else "国际服"}）"
            details.text = "已自动识别服务器，更新时只会写入对应服务器配置。"
            updateHuaweiCloudConfig(yamlValue)
            if (showToast) toast("已获取 Cookie，识别为${if (server == "cn") "国服" else "国际服"}")
        } else {
            yamlValue = ""
            detectedGameServer = null
            status.text = "Cookie 状态：未获取"
            details.text = if (found.isBlank()) {
                "请登录 HoYoLAB 或米游社，登录后自动检测 Cookie 和服务器。"
            } else {
                "已读取到 Cookie，但无法可靠判断服务器。请确认登录的是国服或国际服账号后重试。"
            }
            updateHuaweiCloudConfig("")
            if (showToast) toast("未发现可识别的登录凭据")
        }
    }
    private fun webViewEngineInfo(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "Android ${Build.VERSION.RELEASE}；系统 API 低于 26，无法通过公开接口读取 WebView 提供程序版本"
        }
        return try {
            val pkg = WebView.getCurrentWebViewPackage()
            if (pkg == null) "无法识别 WebView 提供程序"
            else "${pkg.packageName} · ${pkg.versionName ?: "版本未知"}"
        } catch (e: Exception) {
            "读取失败：${e.javaClass.simpleName}"
        }
    }

    private fun recordWebDiagnostic(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        webDiagnostics.add("$time  $message")
        while (webDiagnostics.size > 40) webDiagnostics.removeAt(0)
        if (::details.isInitialized && !browserVisible) {
            details.text = diagnosticsSummary()
        }
    }

    private fun diagnosticsSummary(): String =
        buildString {
            append("实际内核：")
            append(webViewEngineInfo())
            append("\\n网页诊断记录：")
            if (webDiagnostics.isEmpty()) append("\\n暂无错误或事件记录")
            else webDiagnostics.takeLast(12).forEach { append("\\n• "); append(it) }
        }

    private fun makeYaml(cookie: String, server: String): String {
        var yaml = assets.open("config-template.yaml").bufferedReader().use { it.readText() }
        val escaped = cookie.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "")
        yaml = yaml.replace("__HOYOLAB_COOKIE__", escaped)

        if (server == "cn") {
            val cookieFields = cookie.split(';').mapNotNull { part ->
                val key = part.trim().substringBefore('=')
                val value = part.trim().substringAfter('=', "")
                if (key.isBlank() || value.isBlank()) null else key to value
            }.toMap()
            fun yamlValue(value: String): String = "'" + value.replace("'", "''") + "'"
            val stuid = cookieFields["stuid"] ?: cookieFields["ltuid"] ?: ""
            val stoken = cookieFields["stoken"] ?: cookieFields["stoken_v2"] ?: ""
            val mid = cookieFields["mid"] ?: cookieFields["account_mid_v2"] ?: cookieFields["account_mid"] ?: ""
            if (stuid.isNotBlank()) yaml = yaml.replace("  stuid: ''", "  stuid: " + yamlValue(stuid))
            if (stoken.isNotBlank()) yaml = yaml.replace("  stoken: ''", "  stoken: " + yamlValue(stoken))
            if (mid.isNotBlank()) yaml = yaml.replace("  mid: ''", "  mid: " + yamlValue(mid))
        }

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val platform = if (server == "cn") AccountStore.MIYOUSHE_CN else AccountStore.HOYOLAB
        val registry = AccountStore(this)
        val selectedAccountId = activeAccountId
            ?: getSharedPreferences("multi_account_registry", Context.MODE_PRIVATE).getString("active_${platform}", null)
        val selected = selectedAccountId?.let { registry.find(it)?.games }
            ?: prefs.getStringSet("selected_games", setOf("zzz"))
            ?: setOf("zzz")
        val gamesStart = yaml.indexOf("games:\n")
        val cloudStart = yaml.indexOf("\ncloud_games:", gamesStart + 1)
        if (gamesStart >= 0 && cloudStart > gamesStart) {
            var gamesSection = yaml.substring(gamesStart, cloudStart)
            gamesSection = gamesSection.replace("  cn:\n    enable: false", "  cn:\n    enable: " + (server == "cn"))
            gamesSection = gamesSection.replace("  os:\n    enable: true", "  os:\n    enable: " + (server == "os"))
            yaml = yaml.substring(0, gamesStart) + gamesSection + yaml.substring(cloudStart)
        }

        val supported = mapOf(
            "cn" to listOf("genshin", "honkai2", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz"),
            "os" to listOf("genshin", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz")
        )
        for (regionKey in listOf("cn", "os")) {
            val regionStart = yaml.indexOf("\n  " + regionKey + ":")
            if (regionStart < 0) continue
            val nextRegion = if (regionKey == "cn") yaml.indexOf("\n  os:", regionStart + 1) else yaml.indexOf("\ncloud_games:", regionStart + 1)
            val sectionEnd = if (nextRegion >= 0) nextRegion else yaml.length
            var section = yaml.substring(regionStart, sectionEnd)
            for (game in supported.getValue(regionKey)) {
                val gameStart = Regex("(?m)^    " + Regex.escape(game) + ":\\s*$").find(section)?.range?.first ?: continue
                val afterStart = section.indexOf("\n", gameStart).let { if (it < 0) section.length else it + 1 }
                val nextGame = Regex("(?m)^    [a-z0-9_]+:\\s*$").find(section, afterStart)?.range?.first
                val gameEnd = nextGame ?: section.length
                val block = section.substring(gameStart, gameEnd)
                val enabled = regionKey == server && selected.contains(game)
                val changed = Regex("(?m)^      checkin: (true|false)").replaceFirst(
                    block, "      checkin: " + enabled.toString()
                )
                section = section.substring(0, gameStart) + changed + section.substring(gameEnd)
            }
            yaml = yaml.substring(0, regionStart) + section + yaml.substring(sectionEnd)
        }
        return yaml
    }

    private fun showConfigSelection() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedGames = prefs.getStringSet("selected_games", setOf("zzz"))?.toMutableSet() ?: mutableSetOf("zzz")
        val labels = linkedMapOf(
            "genshin" to "原神",
            "honkai2" to "崩坏2",
            "honkai3rd" to "崩坏3",
            "tears_of_themis" to "未定事件簿",
            "honkai_sr" to "崩坏：星穹铁道",
            "zzz" to "绝区零"
        )
        val checks = linkedMapOf<String, CheckBox>()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        panel.addView(TextView(this).apply {
            text = "选择需要更新的游戏（可多选）"
            textSize = 14f
            setTextColor(Color.rgb(170, 190, 220))
        })
        labels.forEach { (key, label) ->
            val check = CheckBox(this).apply {
                text = label
                setTextColor(Color.rgb(230, 234, 242))
                isChecked = savedGames.contains(key)
                setPadding(0, dp(3), 0, dp(3))
            }
            checks[key] = check
            panel.addView(check)
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        AlertDialog.Builder(this)
            .setTitle("游戏选择")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存配置") { _, _ ->
                val chosen = checks.filterValues { it.isChecked }.keys
                if (chosen.isEmpty()) {
                    toast("至少选择一个游戏")
                    return@setPositiveButton
                }
                prefs.edit().putStringSet("selected_games", chosen.toSet()).apply()
                val server = detectedGameServer
                if (cookieValue.isNotBlank() && server != null) {
                    yamlValue = makeYaml(cookieValue, server)
                    updateHuaweiCloudConfig(yamlValue)
                }
                toast("游戏配置已保存")
            }
            .show()
    }
    private fun copy(value: String, label: String) {
        if (value.isBlank()) {
            toast("请先登录并获取 Cookie")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("已复制 $label")
    }

    private fun saveConfig() {
        if (yamlValue.isBlank()) {
            toast("请先获取 Cookie")
            return
        }
        openFileOutput("config.yaml", Context.MODE_PRIVATE).use {
            it.write(yamlValue.toByteArray(Charsets.UTF_8))
        }
        toast("已保存到应用内部 files/config.yaml")
    }

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            cookieValue = ""
            yamlValue = ""
            popupWebView?.let { popup ->
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.stopLoading()
                popup.destroy()
                popupWebView = null
            }
            if (::webView.isInitialized) {
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                webView.visibility = android.view.View.VISIBLE
            }
            WebStorage.getInstance().deleteAllData()
            browserVisible = false
            buildHome()
            status.text = "登录状态：已清除本地 WebView Cookie"
            details.text = "登录会话已清除。"
            toast("已清除登录会话")
        }
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.rgb(238, 241, 247))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(39, 46, 60))
            minHeight = dp(48)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(9)
            }
        }

    private fun text(
        value: String,
        size: Int,
        bold: Boolean,
        color: Int = Color.BLACK
    ): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(if (color == Color.BLACK) Color.rgb(235, 238, 245) else color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
