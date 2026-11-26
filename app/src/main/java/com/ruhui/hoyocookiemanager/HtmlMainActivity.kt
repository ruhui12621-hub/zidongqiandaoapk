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
    private var todayGameStatuses: Map<String, Map<String, String>> = emptyMap()
    private var todaySummary = "等待读取今日签到状态"
    private var todayQuerying = false
    private var pageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountStore.clearLegacyLocalData(this)
        AccountStore.clearMemory()
        shell = FrameLayout(this).apply { setBackgroundColor(Color.rgb(5, 5, 5)) }
        ui = WebView(this).apply {
            setBackgroundColor(Color.rgb(5, 5, 5))
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

    override fun onCloudLoginSyncComplete() {
        refreshTodayStatuses(force = true)
        pushState()
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
        queryTodayCheckinStatusForAccounts { statuses, gameStatuses, summary ->
            todayStatuses = statuses
            todayGameStatuses = gameStatuses
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
                        value.contains("未连接华为云")
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
                    .put("cloudUpdateMessage", AppMessageBus.currentForAccount(this, account.id))
                    .put("todayStatus", todayStatuses[account.id] ?: "今日签到状态未知")
                    .put("gameTodayStatuses", JSONObject().apply { todayGameStatuses[account.id].orEmpty().forEach { (key, value) -> put(key, value) } })
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
            runOnUiThread { selectedAccountId = null; pushState() }
        }

        @JavascriptInterface fun refreshTodayStatuses() {
            runOnUiThread { this@HtmlMainActivity.refreshTodayStatuses(force = true) }
        }

        @JavascriptInterface fun openHoyoLogin(id: String, incognito: Boolean) {
            runOnUiThread {
                val account = AccountStore(this@HtmlMainActivity).find(id) ?: return@runOnUiThread
                HuaweiCloudSession.clearPendingHoyoCookie()
                pushState()
                startActivity(
                    Intent(this@HtmlMainActivity, MainActivity::class.java)
                        .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
                        .putExtra(AccountStore.EXTRA_PLATFORM, account.platform)
                        .putExtra("return_to_account_detail", true)
                        .putExtra("incognito_login", incognito)
                )
            }
        }

        @JavascriptInterface fun openSklandLogin(id: String) {
            runOnUiThread {
                AppMessageBus.post(this@HtmlMainActivity, AppMessageBus.SKLAND, "收到打开森空岛请求",
                    "账号 ID 已传入（${id.take(8)}…），正在查找账号并启动登录 Activity。", null)
                val account = AccountStore(this@HtmlMainActivity).find(id)
                if (account == null) {
                    AppMessageBus.post(this@HtmlMainActivity, AppMessageBus.SKLAND, "启动中止：账号不存在",
                        "AccountStore.find 未找到当前账号。", false)
                    pushState()
                    return@runOnUiThread
                }
                AppMessageBus.post(this@HtmlMainActivity, AppMessageBus.SKLAND, "正在启动森空岛 Activity",
                    "目标 Activity：SenkongdaoActivity\n账号平台：${account.platform}", null)
                runCatching {
                    startActivity(
                        Intent(this@HtmlMainActivity, SenkongdaoActivity::class.java)
                            .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
                            .putExtra(AccountStore.EXTRA_PLATFORM, account.platform)
                    )
                    AppMessageBus.post(this@HtmlMainActivity, AppMessageBus.SKLAND, "系统已接受启动请求",
                        "startActivity() 已正常返回；接下来检查 Activity 的 onCreate 与 WebView 初始化。", true)
                }.onFailure { error ->
                    val stack = android.util.Log.getStackTraceString(error).lineSequence().take(10).joinToString("\n")
                    AppMessageBus.post(this@HtmlMainActivity, AppMessageBus.SKLAND, "森空岛 Activity 启动失败",
                        "异常类型：${error.javaClass.name}\n异常信息：${error.message ?: "无"}\n堆栈：\n$stack", false)
                }
                pushState()
            }
        }

        @JavascriptInterface fun copyText(value: String) {
            runOnUiThread {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("错误消息", value))
                android.widget.Toast.makeText(this@HtmlMainActivity, "报错消息已复制", android.widget.Toast.LENGTH_SHORT).show()
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

        @JavascriptInterface fun getSavedCloudCredentials(): String {
            val pair = runCatching { AndroidCredentialVault(this@HtmlMainActivity).latest() }.getOrNull()
                ?: return JSONObject().toString()
            return JSONObject().put("ak", pair.first).put("sk", pair.second).toString()
        }

        @JavascriptInterface fun getMessages(channel: String): String =
            if (channel == "skland") AppMessageBus.current(this@HtmlMainActivity, AppMessageBus.SKLAND)
            else AppMessageBus.current(this@HtmlMainActivity, AppMessageBus.HOYOLAB)
    }
}
