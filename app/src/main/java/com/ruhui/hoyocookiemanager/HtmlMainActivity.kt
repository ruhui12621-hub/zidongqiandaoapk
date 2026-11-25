package com.ruhui.hoyocookiemanager

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/** Single HTML-driven application shell. All visible app UI lives in assets/ui. */
class HtmlMainActivity : HuaweiCloudActivity() {
    private lateinit var shell: FrameLayout
    private lateinit var ui: WebView
    private var selectedAccountId: String? = null
    private var todayStatuses: Map<String, String> = emptyMap()
    private var todaySummary = "等待读取今日签到状态"
    private var todayQuerying = false
    private var pageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountStore.clearLegacyLocalData(this)
        AccountStore.clearMemory()
        shell = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 15, 22)) }
        ui = WebView(this).apply {
            setBackgroundColor(Color.rgb(12, 15, 22))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.loadsImagesAutomatically = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(HtmlBridge(), "Android")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return !request.url.toString().startsWith("file:///android_asset/ui/")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == "file:///android_asset/ui/index.html") {
                        pageReady = true
                        pushState()
                    }
                }
            }
        }
        shell.addView(ui, FrameLayout.LayoutParams(-1, -1))
        prepareHtmlCloudBackend(shell)
        setContentView(shell)
        ui.loadUrl("file:///android_asset/ui/index.html")

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

    private fun refreshTodayStatuses(force: Boolean = true) {
        if (todayQuerying) return
        if (!force && todayStatuses.isNotEmpty()) return
        val sync = CloudAccountSyncState.get(this)
        if (sync != CloudAccountSyncState.SYNCED && sync != CloudAccountSyncState.EMPTY_CONFIRMED) return
        todayQuerying = true
        todaySummary = "正在读取今天的 FunctionGraph 运行日志…"
        pushState()
        queryTodayCheckinStatusForAccounts { statuses, summary ->
            todayStatuses = statuses
            todaySummary = summary
            todayQuerying = false
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
        val gamesLabels = mapOf(
            "genshin" to "原神", "honkai2" to "崩坏2", "honkai3rd" to "崩坏3",
            "tears_of_themis" to "未定事件簿", "honkai_sr" to "崩坏：星穹铁道",
            "zzz" to "绝区零", "arknights" to "明日方舟", "endfield" to "明日方舟：终末地"
        )
        AccountStore(this).all().forEach { account ->
            val games = JSONArray()
            account.games.forEach { key -> games.put(JSONObject().put("key", key).put("label", gamesLabels[key] ?: key)) }
            accountsJson.put(
                JSONObject()
                    .put("id", account.id)
                    .put("name", account.name)
                    .put("platform", account.platform)
                    .put("platformLabel", AccountStore.platformLabel(account.platform))
                    .put("userId", account.platformUserId.orEmpty())
                    .put("games", games)
                    .put("cookieReady", account.cookieReady)
                    .put("tokenReady", account.tokenReady)
                    .put("todayStatus", todayStatuses[account.id] ?: "今日签到状态未知")
            )
        }
        val sync = CloudAccountSyncState.get(this)
        val savedAkSk = runCatching { AndroidCredentialVault(this).latest() }.getOrNull() != null
        return JSONObject()
            .put("accounts", accountsJson)
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
            .put("cloudCredentialsSaved", savedAkSk)
            .put("region", HuaweiCloudSession.region)
            .put("todayQuerying", todayQuerying)
            .put("todaySummary", todaySummary)
            .put("hoyolabMessage", AppMessageBus.current(this, AppMessageBus.HOYOLAB))
            .put("sklandMessage", AppMessageBus.current(this, AppMessageBus.SKLAND))
            .put("cloudMessage", AppMessageBus.current(this, AppMessageBus.HOYOLAB))
            .put("logsText", htmlLogsText())
            .put("logsStatus", htmlLogsStatus())
    }

    private inner class HtmlBridge {
        @JavascriptInterface fun getState(): String = stateJson().toString()

        @JavascriptInterface fun refresh() {
            runOnUiThread {
                if (CloudAccountSyncState.get(this@HtmlMainActivity) == CloudAccountSyncState.SYNCED) {
                    refreshTodayStatuses()
                    pushState()
                } else {
                    synchronizeCloudAccounts { success, message ->
                        AppMessageBus.post(this@HtmlMainActivity, AppMessageBus.HOYOLAB, "云端账号同步", message, success)
                        pushState()
                        if (success) refreshTodayStatuses()
                    }
                }
            }
        }

        @JavascriptInterface fun syncCloud() {
            runOnUiThread {
                synchronizeCloudAccounts { success, message ->
                    AppMessageBus.post(this@HtmlMainActivity, AppMessageBus.HOYOLAB, "云端账号同步", message, success)
                    pushState()
                    if (success) refreshTodayStatuses()
                }
            }
        }

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

        @JavascriptInterface fun openAccount(id: String) { selectedAccountId = id; pushState() }
        @JavascriptInterface fun backToHome() { selectedAccountId = null; pushState() }

        @JavascriptInterface fun openHoyoLogin(id: String) {
            val account = AccountStore(this@HtmlMainActivity).find(id) ?: return
            startActivity(
                Intent(this@HtmlMainActivity, MainActivity::class.java)
                    .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
                    .putExtra(AccountStore.EXTRA_PLATFORM, account.platform)
                    .putExtra("return_to_account_detail", true)
            )
        }

        @JavascriptInterface fun openSklandLogin(id: String) {
            val account = AccountStore(this@HtmlMainActivity).find(id) ?: return
            startActivity(
                Intent(this@HtmlMainActivity, SenkongdaoActivity::class.java)
                    .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
                    .putExtra(AccountStore.EXTRA_PLATFORM, account.platform)
            )
        }

        @JavascriptInterface fun cloudLogin(region: String, ak: String, sk: String) {
            htmlCloudLogin(region, ak, sk)
            pushState()
        }

        @JavascriptInterface fun deploy(service: String) {
            htmlDeploy(service, "")
            pushState()
        }

        @JavascriptInterface fun fetchLogs() { htmlFetchLogs(); pushState() }
        @JavascriptInterface fun getMessages(channel: String): String =
            if (channel == "skland") AppMessageBus.current(this@HtmlMainActivity, AppMessageBus.SKLAND)
            else AppMessageBus.current(this@HtmlMainActivity, AppMessageBus.HOYOLAB)
    }
}
