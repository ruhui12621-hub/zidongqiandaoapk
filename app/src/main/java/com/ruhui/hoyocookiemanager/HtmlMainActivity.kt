package com.ruhui.hoyocookiemanager

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

/** Single React-driven application shell. All visible app UI lives in bundled assets/ui. */
class HtmlMainActivity : HuaweiCloudActivity() {
    private lateinit var shell: FrameLayout
    private lateinit var ui: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var selectedAccountId: String? = null
    private var todayStatuses: Map<String, String> = emptyMap()
    private var todayGameStatuses: Map<String, Map<String, String>> = emptyMap()
    private var todayDetails: Map<String, String> = emptyMap()
    private var todaySummary = "等待读取今日签到状态"
    private var todayQuerying = false
    private var pageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountStore.clearLegacyLocalData(this)
        AccountStore.clearMemory()
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        shell = FrameLayout(this).apply { setBackgroundColor(Color.rgb(33, 33, 33)) }
        ui = WebView(this).apply {
            setBackgroundColor(Color.rgb(33, 33, 33))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.loadsImagesAutomatically = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(HtmlBridge(), "Android")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return request.url.host != "appassets.androidplatform.net"
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == "https://appassets.androidplatform.net/assets/ui/index.html") {
                        pageReady = true
                        pushState()
                    }
                }
            }
        }
        shell.addView(ui, FrameLayout.LayoutParams(-1, -1))
        prepareHtmlCloudBackend(shell)
        setContentView(shell)
        ui.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")

        if (runCatching { AndroidCredentialVault(this).latest() }.getOrNull() != null) {
            synchronizeCloudAccounts { success, message ->
                AppMessageBus.post(this, AppMessageBus.HOYOLAB, "云端账号同步", message, success)
                pushState()
                if (success) refreshTodayStatuses()
            }
        } else {
            CloudAccountSyncState.set(this, CloudAccountSyncState.UNKNOWN)
            pushState()
        }
    }

    override fun onResume() {
        super.onResume()
        // Record successful Cookie acquisition for this Android process only.
        // This survives CSV upload clearing the pending Cookie, but is never persisted.
        val pendingAccountId = HuaweiCloudSession.pendingHoyoCookieAccountId
        if (HuaweiCloudSession.pendingHoyoCookie.isNotBlank() && pendingAccountId.isNotBlank()) {
            sessionCookieAcquiredAccountIds.add(pendingAccountId)
        }
        pushState()
        if (CloudAccountSyncState.get(this) == CloudAccountSyncState.SYNCED) refreshTodayStatuses(force = false)
    }

    override fun onDestroy() {
        pageReady = false
        runCatching {
            ui.stopLoading()
            ui.removeJavascriptInterface("Android")
            ui.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedAccountId != null) {
            selectedAccountId = null
            pushState()
        } else {
            ui.evaluateJavascript("window.nativeBack && window.nativeBack()", null)
        }
    }

    override fun onCloudLoginSyncComplete() {
        refreshTodayStatuses(force = true)
        pushState()
    }

    override fun onCloudDeploymentProgress() {
        runOnUiThread { pushState() }
    }

    private fun refreshTodayStatuses(force: Boolean = true) {
        if (todayQuerying) return
        if (!force && todayStatuses.isNotEmpty()) return
        val sync = CloudAccountSyncState.get(this)
        if (sync != CloudAccountSyncState.SYNCED && sync != CloudAccountSyncState.EMPTY_CONFIRMED && !HuaweiCloudSession.isLoggedIn()) return
        todayQuerying = true
        todaySummary = "正在读取今天的 FunctionGraph 运行日志…"
        AppMessageBus.post(this, AppMessageBus.HOYOLAB, "今日签到信息读取开始",
            "正在从华为云 FunctionGraph / LTS 下载并分析今天的运行日志。", null)
        pushState()
        queryTodayCheckinStatusForAccounts { statuses, gameStatuses, details, summary ->
            todayStatuses = statuses
            todayGameStatuses = gameStatuses
            todayDetails = details
            todaySummary = summary
            todayQuerying = false
            val accountNames = AccountStore(this).all().associate { it.id to it.name }
            val resultLines = statuses.entries.joinToString("\n") { (id, status) ->
                "${accountNames[id] ?: "账号"}：$status"
            }
            val downloadFailed = summary.contains("失败") || summary.contains("未配置华为云") ||
                statuses.values.any { value ->
                    value.contains("日志查询失败") || value.contains("函数不存在") ||
                        value.contains("无法唯一识别") || value.contains("未启用日志") ||
                        value.contains("未连接华为云") || value.contains("无法获取签到结果")
                }
            val completed = !downloadFailed
            AppMessageBus.post(this, AppMessageBus.HOYOLAB, "今日签到信息下载${if (completed) "完成" else "失败"}",
                "日志读取结果：$summary" + if (resultLines.isNotBlank()) "\n账号状态：\n$resultLines" else "\n没有可展示的账号状态。", completed)
            pushState()
        }
    }

    private fun pushState() {
        if (!pageReady || isFinishing) return
        val payload = stateJson().toString()
        ui.post {
            ui.evaluateJavascript("window.onNativeState && window.onNativeState(" +
                JSONObject.quote(payload) + ")", null)
        }
    }

    private fun stateJson(): JSONObject {
        val accountsJson = JSONArray()
        AccountStore(this).all().forEach { account ->
            val display = HomeAccountDisplayAssembler.build(
                account = account,
                todayStatus = todayStatuses[account.id] ?: "今日签到状态未知",
                gameTodayStatuses = todayGameStatuses[account.id].orEmpty(),
                todayDetailsJson = todayDetails[account.id],
                cloudUpdateStages = AppMessageBus.currentAccountStages(this, account.id)
            ).put("sessionCookieAcquired", sessionCookieAcquiredAccountIds.contains(account.id))
            accountsJson.put(display)
        }
        val sync = CloudAccountSyncState.get(this)
        val savedAkSk = runCatching { AndroidCredentialVault(this).latest() }.getOrNull() != null
        return JSONObject()
            .put("accounts", accountsJson)
            .put("gameCatalog", GameCatalog.json())
            .put("selectedAccountId", selectedAccountId ?: JSONObject.NULL)
            .put("syncState", sync)
            .put("syncLabel", when (sync) {
                CloudAccountSyncState.SYNCING -> "正在读取云端配置"
                CloudAccountSyncState.SYNCED -> "云端配置已同步"
                CloudAccountSyncState.EMPTY_CONFIRMED -> "云端尚无账号配置"
                CloudAccountSyncState.FAILED -> "云端读取失败，账号操作已锁定"
                else -> "等待连接华为云"
            })
            .put("canAddAccount", CloudAccountSyncState.allowsAccountChanges(this))
            .put("cloudConnected", HuaweiCloudSession.isLoggedIn())
            .put("cloudConnectionLabel", cloudConnectionStatus())
            .put("cloudCredentialsSaved", savedAkSk)
            .put("hoyoPageCookieReady", HuaweiCloudSession.pendingHoyoCookie.isNotBlank())
            .put("hoyoPageCookieAccountId", HuaweiCloudSession.pendingHoyoCookieAccountId)
            .put("hoyoPageCookieServer", HuaweiCloudSession.pendingHoyoCookieServer)
            .put("region", HuaweiCloudSession.region)
            .put("todayQuerying", todayQuerying)
            .put("todaySummary", todaySummary)
            .put("hoyolabMessage", AppMessageBus.current(this, AppMessageBus.HOYOLAB))
            .put("sklandMessage", AppMessageBus.current(this, AppMessageBus.SKLAND))
            .put("allMessages", AppMessageBus.currentAll(this))
            .put("sklandError", AppMessageBus.current(this, AppMessageBus.SKLAND).takeIf { it.startsWith("✕") } ?: "")
            .put("cloudMessage", AppMessageBus.current(this, AppMessageBus.HOYOLAB))
    }

    private inner class HtmlBridge {
        @JavascriptInterface fun getState(): String = stateJson().toString()

        @JavascriptInterface fun addAccount(platform: String) {
            runOnUiThread {
                if (!CloudAccountSyncState.allowsAccountChanges(this@HtmlMainActivity)) return@runOnUiThread
                runCatching { AccountStore(this@HtmlMainActivity).add(platform) }
                    .onSuccess { selectedAccountId = it.id; pushState() }
                    .onFailure {
                        AppMessageBus.post(this@HtmlMainActivity, AppMessageBus.HOYOLAB, "添加账号失败", it.message ?: "未知错误", false)
                        pushState()
                    }
            }
        }

        @JavascriptInterface fun openAccount(id: String) {
            runOnUiThread { selectedAccountId = id; pushState() }
        }
        @JavascriptInterface fun backToHome() {
            // This bridge only synchronizes native back-button state.
            // Do not call pushState() here: it rebuilds the full account snapshot
            // (including AccountStore reads) on the UI thread and can freeze WebView navigation.
            runOnUiThread { selectedAccountId = null }
        }

        @JavascriptInterface fun refreshTodayStatuses() {
            runOnUiThread { this@HtmlMainActivity.refreshTodayStatuses(force = true) }
        }

        @JavascriptInterface fun openLogin(id: String) {
            runOnUiThread {
                val account = AccountStore(this@HtmlMainActivity).find(id) ?: return@runOnUiThread
                if (account.platform == AccountStore.HOYOLAB || account.platform == AccountStore.MIYOUSHE_CN) {
                    HuaweiCloudSession.clearPendingHoyoCookie()
                }
                pushState()
                val target = when (account.platform) {
                    AccountStore.HOYOLAB, AccountStore.MIYOUSHE_CN -> MainActivity::class.java
                    AccountStore.SKLAND -> SenkongdaoActivity::class.java
                    else -> return@runOnUiThread
                }
                startActivity(
                    Intent(this@HtmlMainActivity, target)
                        .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
                        .putExtra(AccountStore.EXTRA_PLATFORM, account.platform)
                        .putExtra("return_to_account_detail", true)
                )
            }
        }

        @JavascriptInterface fun copyText(value: String) {
            runOnUiThread {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("复制内容", value))
            }
        }

        @JavascriptInterface fun cloudLogin(region: String, ak: String, sk: String) {
            htmlCloudLogin(region, ak, sk)
            pushState()
        }

        @JavascriptInterface fun deployHoyoAccount(id: String, selectedGamesJson: String) {
            htmlDeployHoyoAccount(id, selectedGamesJson)
            pushState()
        }

        @JavascriptInterface fun deploySklandAccount(id: String, selectedGamesJson: String) {
            htmlDeploySklandAccount(id, selectedGamesJson)
            pushState()
        }

        @JavascriptInterface fun savePlatformAccounts(platform: String, rowsJson: String, anchorAccountId: String) {
            htmlSavePlatformAccounts(platform, rowsJson, anchorAccountId)
            pushState()
        }

        @JavascriptInterface fun deletePlatformAccount(id: String) {
            htmlDeletePlatformAccount(id)
        }

        @JavascriptInterface fun getSavedCloudCredentials(): String {
            val pair = runCatching { AndroidCredentialVault(this@HtmlMainActivity).latest() }.getOrNull()
                ?: return JSONObject().toString()
            return JSONObject().put("ak", pair.first).put("sk", pair.second).toString()
        }

        @JavascriptInterface fun getMessages(channel: String): String =
            if (channel == "skland") AppMessageBus.current(this@HtmlMainActivity, AppMessageBus.SKLAND)
            else AppMessageBus.current(this@HtmlMainActivity, AppMessageBus.HOYOLAB)
    }

    companion object {
        // Process-memory only: reset automatically when Android kills the APK process.
        private val sessionCookieAcquiredAccountIds = mutableSetOf<String>()
    }
}
