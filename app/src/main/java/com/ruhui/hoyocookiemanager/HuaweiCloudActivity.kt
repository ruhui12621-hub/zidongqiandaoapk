package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.os.Bundle
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import com.obs.services.ObsClient
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.Executors

open class HuaweiCloudActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var accessKeyValue = ""
    private var secretKeyValue = ""
    private var connectionLabel = "尚未连接"
    private var lastResultMessage = ""
    private var authAk = ""
    private var authSk = ""
    private var regionValue = "cn-east-3"
    private val regionCodes = listOf("cn-east-3", "cn-east-2", "cn-north-4", "cn-north-1", "cn-south-1", "ap-southeast-1", "ap-southeast-3")
    private fun regionLabel(code: String): String = when (code) {
        "cn-east-3" -> "华东-上海一"
        "cn-east-2" -> "华东-上海二"
        "cn-north-4" -> "华北-北京四"
        "cn-north-1" -> "华北-北京一"
        "cn-south-1" -> "华南-广州"
        "ap-southeast-1" -> "中国-香港"
        "ap-southeast-3" -> "亚太-新加坡"
        else -> code
    }
    private var projectId = ""
    private var configYaml = ""
    private var configProvider: (() -> String)? = null
    private var panelService = "hoyolab"

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    /** The HTML interface owns every visible control. */
    protected fun prepareHtmlCloudBackend(host: android.widget.FrameLayout) {
        configProvider = { HuaweiCloudSession.lastConfigYaml }
        configYaml = HuaweiCloudSession.lastConfigYaml
        regionValue = getSharedPreferences("app_settings", MODE_PRIVATE).getString("cloud_region", "cn-east-3") ?: "cn-east-3"
        runCatching { AndroidCredentialVault(this).latest() }.getOrNull()?.let {
            accessKeyValue = it.first
            secretKeyValue = it.second
        }
        if (HuaweiCloudSession.isLoggedIn()) {
            authAk = HuaweiCloudSession.ak
            authSk = HuaweiCloudSession.sk
            regionValue = HuaweiCloudSession.region
            projectId = HuaweiCloudSession.projectId
            connectionLabel = "已连接"
        }
    }

    private fun setCloudConnectionState(state: String) { connectionLabel = state }
    protected fun cloudConnectionStatus(): String = connectionLabel

    /** Called after a successful cloud login and account synchronization. */
    protected open fun onCloudLoginSyncComplete() {}

    /** Notify the HTML account screen after cloud deployment messages or stages change. */
    protected open fun onCloudDeploymentProgress() {}

    /**
     * Read the existing FunctionGraph packages before account editing is enabled.
     * Only a successful function-list response with zero matching functions is an
     * explicitly empty cloud state. All other failures fail closed.
     */
    protected fun synchronizeCloudAccounts(onComplete: ((Boolean, String) -> Unit)? = null) {
        CloudAccountSyncState.set(this, CloudAccountSyncState.SYNCING)
        val saved = runCatching { AndroidCredentialVault(this).latest() }.getOrNull()
        if (saved == null && !HuaweiCloudSession.isLoggedIn()) {
            CloudAccountSyncState.set(this, CloudAccountSyncState.UNKNOWN)
            onComplete?.let { runOnUiThread { it(false, "请先连接华为云，读取云端账号配置。") } }
            return
        }
        worker.execute {
            try {
                if (saved != null) {
                    authAk = saved.first
                    authSk = saved.second
                } else {
                    authAk = HuaweiCloudSession.ak
                    authSk = HuaweiCloudSession.sk
                }
                regionValue = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getString("cloud_region", HuaweiCloudSession.region) ?: "cn-east-3"
                val projects = signedGet("https://iam.$regionValue.myhuaweicloud.com/v3/projects")
                val rows = projects.optJSONArray("projects") ?: error("IAM 未返回项目列表")
                projectId = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optString("name").equals(regionValue, true) }
                    ?.optString("id").orEmpty()
                if (projectId.isBlank()) error("未找到 $regionValue 对应的华为云项目")
                HuaweiCloudSession.ak = authAk
                HuaweiCloudSession.sk = authSk
                HuaweiCloudSession.region = regionValue
                HuaweiCloudSession.projectId = projectId

                fun findFunction(name: String): JSONObject? {
                    val url = "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions" +
                        "?func_name=" + enc(name) + "&precise_search=true&maxitems=10"
                    val list = signedGet(url).optJSONArray("functions")
                        ?: error("FunctionGraph 未返回函数列表，不能判断云端是否为空")
                    val matches = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                        .filter { it.optString("func_name") == name }
                    if (matches.size > 1) error("云端存在多个同名函数：$name，已停止同步")
                    return matches.singleOrNull()
                }

                fun downloadZip(function: JSONObject, label: String): ByteArray {
                    val urn = function.optString("func_urn")
                    if (urn.isBlank()) error("$label 函数缺少 URN")
                    val code = signedGet(
                        "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions/" + enc(urn) + "/code"
                    )
                    if (!code.optString("code_type").equals("zip", true))
                        error("$label 函数不是 ZIP 代码类型，无法安全读取")
                    val funcCode = code.optJSONObject("func_code")
                    val encoded = funcCode?.optString("file").orEmpty().trim()
                    val link = funcCode?.optString("link").orEmpty().trim()
                    val bytes = if (encoded.isNotBlank()) {
                        Base64.decode(encoded, Base64.DEFAULT)
                    } else {
                        // FunctionGraph may return an empty file and a temporary OBS download URL in link; the signed URL is fetched directly.
                        if (link.isBlank()) error("$label 函数代码既没有内联 file，也没有下载 link；不能判断配置为空")
                        val downloadUrl = runCatching { URL(link) }.getOrElse { error("$label 函数返回了无效的代码下载地址") }
                        if (downloadUrl.protocol != "https") error("$label 函数代码下载地址不是 HTTPS，已拒绝下载")
                        downloadObsObject(downloadUrl, authAk, authSk, label)
                    }
                    if (!isZip(bytes)) error("$label 函数返回的代码包不是有效 ZIP")
                    return bytes
                }

                fun readZipText(bytes: ByteArray, target: String, label: String): String {
                    var found: String? = null
                    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val path = entry.name.replace('\\', '/')
                            if (!entry.isDirectory && (path == target || path.endsWith("/$target"))) {
                                if (found != null) error("$label 配置文件出现多个匹配项")
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    if (total > 5 * 1024 * 1024) error("$label 配置文件超过 5 MB")
                                    output.write(buffer, 0, n)
                                }
                                found = output.toString("UTF-8")
                            }
                            zip.closeEntry()
                        }
                    }
                    return found ?: error("$label 函数包中找不到 $target；未将其视为无历史配置")
                }

                val store = AccountStore(this)
                var imported = 0
                val hoyoFunction = findFunction(HOYOLAB_FUNCTION)
                val sklandFunction = findFunction(SKLAND_FUNCTION)
                if (hoyoFunction == null) HuaweiCloudSession.lastConfigYaml = ""

                if (hoyoFunction != null) {
                    val yaml = readZipText(downloadZip(hoyoFunction, "HoYoLAB"), "config/config.yaml", "HoYoLAB")
                    HuaweiCloudSession.lastConfigYaml = yaml

                    // YAML structure and platform game rules belong to the HoYoLAB config adapter.
                    val parsedConfig = HoYoLabCloudConfigAdapter.read(yaml)
                    val cnCookie = parsedConfig.cnCookie
                    val osCookie = parsedConfig.osCookie
                    val enabledCnGames = parsedConfig.enabledCnGames
                    val enabledOsGames = parsedConfig.enabledOsGames

                    val cnValue = cnCookie.orEmpty()
                    if (cnValue.isNotBlank() && cnValue != "null" && cnValue != "~" &&
                        cnValue != "__HOYOLAB_COOKIE__") {
                        val identity = HoYoAccountIdentity.fromCookie(cnValue, AccountStore.MIYOUSHE_CN)
                        val existing = store.all().firstOrNull {
                            it.platform == AccountStore.MIYOUSHE_CN &&
                                (identity == null || it.platformUserId == identity)
                        }
                        val account = existing ?: store.add(
                            AccountStore.MIYOUSHE_CN,
                            platformUserId = identity,
                            games = enabledCnGames
                        )
                        store.update(account.copy(
                            platformUserId = identity ?: account.platformUserId,
                            games = enabledCnGames,
                            // Cloud configuration import is not a fresh login-page acquisition.
                            // Preserve the current app-session status (new accounts default to false).
                            credentialReady = true
                        ))
                        imported++
                    }

                    val cookie = osCookie.orEmpty()
                    if (cookie.isNotBlank() && cookie != "null" && cookie != "~" &&
                        cookie != "__HOYOLAB_COOKIE__") {
                        val identity = HoYoAccountIdentity.fromCookie(cookie, AccountStore.HOYOLAB)
                        val existing = store.all().firstOrNull {
                            it.platform == AccountStore.HOYOLAB &&
                                (identity == null || it.platformUserId == identity)
                        }
                        val account = existing ?: store.add(
                            AccountStore.HOYOLAB,
                            platformUserId = identity,
                            games = enabledOsGames
                        )
                        store.update(account.copy(
                            platformUserId = identity ?: account.platformUserId,
                            games = enabledOsGames,
                            // Cloud configuration import is not a fresh login-page acquisition.
                            // Preserve the current app-session status (new accounts default to false).
                            credentialReady = true
                        ))
                        imported++
                    }
                    // 国际服 Cookie 为空时仅跳过 HoYoLAB 凭据导入，不阻断后续森空岛 Token 同步。
                }

                if (sklandFunction != null) {
                    val archive = downloadZip(sklandFunction, "森空岛")
                    val accountConfig = readZipEntryTextOrNull(
                        archive, SklandCloudConfigAdapter.ACCOUNT_CONFIG_PATH
                    )
                    val legacyTokens = readZipEntryTextOrNull(
                        archive, SklandCloudConfigAdapter.LEGACY_TOKEN_PATH
                    )
                    if (accountConfig == null && legacyTokens == null) {
                        error("森空岛函数包中既没有账号配置，也没有旧版 Token 文件；已停止同步。")
                    }
                    val cloudAccounts = SklandCloudConfigAdapter.readAccounts(accountConfig, legacyTokens)
                    cloudAccounts.forEach { cloudAccount ->
                        val account = store.add(
                            AccountStore.SKLAND,
                            platformUserId = cloudAccount.platformUserId,
                            games = cloudAccount.games
                        )
                        SklandTokenVault(this, account.id).save(cloudAccount.token)
                        store.update(account.copy(
                            platformUserId = cloudAccount.platformUserId,
                            games = cloudAccount.games,
                            credentialReady = true
                        ))
                        imported++
                    }
                }

                val noFunctions = hoyoFunction == null && sklandFunction == null
                val state = if (noFunctions) CloudAccountSyncState.EMPTY_CONFIRMED else CloudAccountSyncState.SYNCED
                CloudAccountSyncState.set(this, state)
                val message = if (noFunctions) {
                    "华为云已明确确认两个签到函数均不存在历史配置，可以进行首次初始化。"
                } else {
                    "云端配置读取完成，已导入或核对 $imported 项凭据。"
                }
                runOnUiThread { onComplete?.invoke(true, message) }
            } catch (e: Exception) {
                CloudAccountSyncState.set(this, CloudAccountSyncState.FAILED)
                val message = "云端账号同步失败，已锁定账号变更以保护数据：" +
                    (e.message ?: e.javaClass.simpleName)
                runOnUiThread { onComplete?.invoke(false, message) }
            }
        }
    }

    @android.webkit.JavascriptInterface
    protected fun htmlCloudLogin(regionCode: String, accessKey: String, secretKey: String) {
        runOnUiThread {
            regionValue = regionCodes.firstOrNull { it == regionCode } ?: "cn-east-3"
            accessKeyValue = accessKey
            secretKeyValue = secretKey
            login(regionValue, accessKeyValue, secretKeyValue)
        }
    }

    protected fun htmlDeployHoyoAccount(accountId: String, selectedGamesJson: String) {
        runOnUiThread { deployHoyolabAccount(accountId, selectedGamesJson) }
    }

    protected fun htmlDeploySklandAccount(accountId: String, selectedGamesJson: String) {
        runOnUiThread { deploySklandAccount(accountId, selectedGamesJson) }
    }

    protected fun htmlCloudBackendReady(): Boolean = true

    protected fun updateHuaweiCloudConfig(value: String) {
        configYaml = value
        HuaweiCloudSession.lastConfigYaml = value

    }

    /**
     * FunctionGraph returns an OBS object URL for ZIP code packages. The URL is not
     * necessarily public; download it with the logged-in AK/SK through Huawei's OBS SDK.
     */
    private fun downloadObsObject(downloadUrl: URL, ak: String, sk: String, label: String): ByteArray {
        if (ak.isBlank() || sk.isBlank()) error("$label OBS 下载缺少华为云 AK/SK")
        val match = Regex("^([a-z0-9-]+)\\.(obs\\..+)$", RegexOption.IGNORE_CASE)
            .matchEntire(downloadUrl.host)
            ?: error("$label 无法识别 OBS 下载地址：${downloadUrl.host}")
        val bucket = match.groupValues[1]
        val endpoint = "https://" + match.groupValues[2]
        val encodedPath = downloadUrl.path.removePrefix("/")
        if (encodedPath.isBlank()) error("$label OBS 对象路径为空")
        val key = try {
            URLDecoder.decode(encodedPath.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            error("$label OBS 对象路径编码无效：${e.message}")
        }
        val client = ObsClient(ak, sk, endpoint)
        try {
            val objectResult = client.getObject(bucket, key)
                ?: error("$label OBS 未返回对象")
            val input = objectResult.objectContent ?: error("$label OBS 对象内容为空")
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                var total = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 100L * 1024L * 1024L) error("$label 函数代码包超过 100 MB")
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (!isZip(bytes)) error("$label OBS 下载内容不是有效 ZIP")
                return bytes
            }
        } catch (e: Exception) {
            val detail = e.message?.take(400).orEmpty()
            throw IllegalStateException("$label OBS 鉴权下载失败：$detail", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun login(regionInput: String = regionValue, accessKeyInput: String = accessKeyValue, secretKeyInput: String = secretKeyValue) {
        val r = regionCodes.firstOrNull { it == regionInput } ?: "cn-east-3"
        val a = accessKeyInput.trim()
        val s = secretKeyInput
        if (r.isBlank() || a.isBlank() || s.isBlank()) {
            val detail = "请填写 Region、AK 和 SK。"
            setCloudConnectionState("尚未连接")
            showOperationMessage("华为云登录失败", detail, false)
            return
        }
        regionValue = r; authAk = a; authSk = s
        HuaweiCloudSession.region = r
        getSharedPreferences("app_settings", MODE_PRIVATE).edit().putString("cloud_region", r).apply()
        setCloudConnectionState("连接中")
        showOperationMessage("华为云登录", "正在验证 AK/SK 并查询项目…", null)
        worker.execute {
            try {
                val projects = signedGet("https://iam." + r + ".myhuaweicloud.com/v3/projects")
                val rows = projects.optJSONArray("projects") ?: error("IAM 未返回项目列表")
                var pid = ""
                for (i in 0 until rows.length()) {
                    val p = rows.getJSONObject(i)
                    if (p.optString("name").equals(r, true)) { pid = p.optString("id"); break }
                }
                if (pid.isBlank()) error("认证成功，但没有找到当前 Region 对应的项目。")
                var vaultWarning = ""
                try {
                    AndroidCredentialVault(this).save(a, s)
                } catch (vaultError: Exception) {
                    vaultWarning = "\\n但本次凭据未能写入本地保管箱：" + (vaultError.message ?: vaultError.javaClass.simpleName)
                }
                runOnUiThread {
                    // 登录成功后才保存凭据；Toast 不再承担连接状态提示。
                    configYaml = configProvider?.invoke().orEmpty()
                    projectId = pid
                    HuaweiCloudSession.ak = authAk
                    HuaweiCloudSession.sk = authSk
                    HuaweiCloudSession.region = regionValue
                    HuaweiCloudSession.projectId = pid
                    setCloudConnectionState("已连接")
                    showOperationMessage("华为云连接成功", "区域：${regionLabel(r)}（$r）\nProject ID：$pid" + vaultWarning, true)
                    synchronizeCloudAccounts { success, message ->
                        showOperationMessage("云端账号同步", message, CloudAccountSyncState.allowsAccountChanges(this))
                        onCloudLoginSyncComplete()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val detail = "登录/查询失败：" + (e.message ?: e.javaClass.simpleName)
                    setCloudConnectionState("连接失败")
                    showOperationMessage("华为云登录失败", detail, false)
                }
            }
        }
    }

    /**
     * Read today's FunctionGraph/LTS logs and conservatively associate outcomes with
     * local accounts. With multiple accounts, a log must contain that account's stable
     * platform ID or explicit account name; otherwise the app does not guess.
     */
    /**
     * Query each platform through its own fixed FunctionGraph function and its own
     * LTS stream. A shared stream is treated as an isolation failure: never display
     * mixed logs as if they belonged to either platform.
     */
    protected fun queryTodayCheckinStatusForAccounts(
        onResult: (Map<String, String>, Map<String, Map<String, String>>, Map<String, String>, String) -> Unit
    ) {
        val accounts = AccountStore(this).all()
        val saved = runCatching { AndroidCredentialVault(this).latest() }.getOrNull()
        if (saved == null) {
            val empty = accounts.associate { a -> a.id to a.games.associateWith { "unknown" } }
            val reason = "未配置华为云 AK/SK"
            onResult(
                accounts.associate { it.id to "无法获取签到结果" },
                empty,
                accounts.associate { it.id to JSONObject().put("reason", reason).put("allRawMessages", reason).toString() },
                reason
            )
            return
        }
        worker.execute {
            val statuses = linkedMapOf<String, String>()
            val gameStatuses = linkedMapOf<String, Map<String, String>>()
            val details = linkedMapOf<String, String>()
            var summary = "今日运行日志读取完成"
            try {
                authAk = saved.first
                authSk = saved.second
                regionValue = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getString("cloud_region", "cn-east-3") ?: "cn-east-3"
                val projects = signedGet("https://iam.$regionValue.myhuaweicloud.com/v3/projects")
                val rows = projects.optJSONArray("projects") ?: error("IAM 未返回项目列表")
                projectId = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optString("name").equals(regionValue, true) }
                    ?.optString("id").orEmpty()
                if (projectId.isBlank()) error("未找到 $regionValue 对应的华为云项目")
                HuaweiCloudSession.ak = authAk
                HuaweiCloudSession.sk = authSk
                HuaweiCloudSession.region = regionValue
                HuaweiCloudSession.projectId = projectId

                data class PlatformLogResult(val state: String, val logs: List<String>, val sourceId: String, val groupName: String, val streamName: String)
                val functionUrns = linkedMapOf<String, String>()

                fun readTodayLogs(functionName: String): PlatformLogResult {
                    val listUrl = "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions?func_name=" +
                        enc(functionName) + "&precise_search=true&maxitems=10"
                    val functions = signedGet(listUrl).optJSONArray("functions")
                        ?: return PlatformLogResult("FunctionGraph 未返回函数列表", emptyList(), "", "", "")
                    val matches = (0 until functions.length()).mapNotNull { functions.optJSONObject(it) }
                        .filter { it.optString("func_name") == functionName }
                    if (matches.size != 1)
                        return PlatformLogResult("函数不存在或无法唯一识别：$functionName", emptyList(), "", "", "")
                    val urn = matches.single().optString("func_urn")
                    if (urn.isBlank())
                        return PlatformLogResult("函数缺少 URN：$functionName", emptyList(), "", "", "")
                    functionUrns[functionName] = urn
                    val detail = signedGet(
                        "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions/${enc(urn)}/lts-log-detail"
                    )
                    val groupId = detail.optString("group_id")
                    val streamId = detail.optString("stream_id")
                    if (groupId.isBlank() || streamId.isBlank())
                        return PlatformLogResult("函数未启用独立日志流或日志流不可用：$functionName", emptyList(), "", "", "")
                    // Resolve names from LTS metadata by the IDs returned by FunctionGraph.
                    // Do not hardcode platform/game names in the UI or infer names from labels.
                    val ltsBase = "https://lts.$regionValue.myhuaweicloud.com/v2/$projectId"
                    val groupRows = signedGet("$ltsBase/groups").optJSONArray("log_groups")
                        ?: error("LTS 未返回日志组列表，无法显示日志配置名称。")
                    val groupName = (0 until groupRows.length()).mapNotNull { groupRows.optJSONObject(it) }
                        .firstOrNull { it.optString("log_group_id") == groupId }
                        ?.optString("log_group_name").orEmpty()
                    if (groupName.isBlank()) error("无法根据函数返回的日志组 ID 定位日志组名称。")
                    val streamRows = signedGet("$ltsBase/groups/" + enc(groupId) + "/streams").optJSONArray("log_streams")
                        ?: error("LTS 未返回日志流列表，无法显示日志配置名称。")
                    val streamName = (0 until streamRows.length()).mapNotNull { streamRows.optJSONObject(it) }
                        .firstOrNull { it.optString("log_stream_id") == streamId }
                        ?.optString("log_stream_name").orEmpty()
                    if (streamName.isBlank()) error("无法根据函数返回的日志流 ID 定位日志流名称。")
                    val calendar = Calendar.getInstance()
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val startTime = calendar.timeInMillis
                    val endTime = System.currentTimeMillis()
                    val queryUrl = "https://lts.$regionValue.myhuaweicloud.com/v2/$projectId/groups/${enc(groupId)}/streams/${enc(streamId)}/content/query"
                    val logs = mutableListOf<String>()
                    val seen = mutableSetOf<String>()
                    var scrollId = ""
                    var lineNum = ""
                    var page = 0
                    var complete = false
                    while (page < 100 && !complete) {
                        val body = JSONObject()
                            .put("start_time", startTime.toString())
                            .put("end_time", endTime.toString())
                            .put("limit", 5000)
                            .put("is_desc", true)
                            .put("search_type", if (page == 0) "init" else "forwards")
                            .put("is_count", true)
                            .put("is_iterative", true)
                        if (scrollId.isNotBlank()) body.put("scroll_id", scrollId)
                        else if (lineNum.isNotBlank()) body.put("line_num", lineNum)
                        val response = signedRequest("POST", queryUrl, body.toString())
                        if (response.first !in 200..299)
                            error("LTS 查询失败（HTTP ${response.first}）：${response.second.take(500)}")
                        val result = JSONObject(response.second)
                        val pageLogs = result.optJSONArray("logs")
                        val count = pageLogs?.length() ?: 0
                        if (pageLogs != null) for (i in 0 until count) {
                            val item = pageLogs.optJSONObject(i) ?: continue
                            val content = item.optString("content").takeIf { it.isNotBlank() } ?: continue
                            val key = item.optString("line_num").ifBlank { content }
                            if (seen.add(key)) logs.add(content)
                        }
                        page++
                        val nextScroll = result.optString("scrollId")
                        if (nextScroll.isNotBlank()) scrollId = nextScroll
                        if (count > 0) lineNum = pageLogs?.optJSONObject(count - 1)?.optString("line_num").orEmpty()
                        complete = result.optBoolean("isQueryComplete", false) ||
                            count == 0 || (count < 5000 && nextScroll.isBlank())
                    }
                    return PlatformLogResult("OK", logs, "$groupId/$streamId", groupName, streamName)
                }

                // Always inspect both fixed functions, even if one platform currently
                // has no local account. This lets us detect accidental LTS stream sharing.
                val bindings = listOf(
                    AccountStore.HOYOLAB to HOYOLAB_FUNCTION,
                    AccountStore.SKLAND to SKLAND_FUNCTION
                )
                val logResults = linkedMapOf<String, PlatformLogResult?>()
                val logErrors = linkedMapOf<String, String>()
                for ((platform, functionName) in bindings) {
                    val result = runCatching { readTodayLogs(functionName) }
                    if (result.isSuccess) logResults[platform] = result.getOrThrow()
                    else logErrors[platform] = "日志查询失败：" +
                        (result.exceptionOrNull()?.message ?: "未知错误")
                }

                // Persist only the fingerprint; never store the raw HoYoLAB Cookie.
                val invalidCookieHash = logResults[AccountStore.HOYOLAB]?.logs
                    ?.asSequence()
                    ?.mapNotNull { Regex("""\[COOKIE_INVALID\]\[sha256=([0-9a-f]{64})\]""").find(it)?.groupValues?.get(1) }
                    ?.firstOrNull()
                if (invalidCookieHash != null) {
                    val urn = functionUrns[HOYOLAB_FUNCTION]
                    summary = if (urn == null) {
                        "已发现 HoYoLAB Cookie 失效记录，但无法取得函数 URN，未能写入永久作废标记。"
                    } else {
                        runCatching { persistHoyoLabInvalidCookieFingerprint(urn, invalidCookieHash) }
                            .getOrElse { error ->
                                "已发现 HoYoLAB Cookie 失效记录，但 FunctionGraph 作废标记写入失败：" +
                                    (error.message ?: error.javaClass.simpleName)
                            }
                    }
                }

                // LTS group/stream is the hard boundary. If Huawei returns the same
                // stream for both functions, fail closed rather than cross-contaminate.
                val sourceOwners = logResults.entries
                    .filter { it.value?.sourceId?.isNotBlank() == true }
                    .groupBy { it.value!!.sourceId }
                val sharedSourcePlatforms = sourceOwners.values
                    .filter { it.size > 1 }
                    .flatten()
                    .map { it.key }
                    .toSet()

                fun isAlreadyCheckedIn(line: String): Boolean =
                    line.contains("今日已签到") || line.contains("今天已经签到") ||
                    line.contains("已经签到过") || line.contains("已签到过") ||
                    line.contains("无需重复签到") || line.contains("请勿重复签到") ||
                    line.contains("already checked in", true) || line.contains("already signed in", true) ||
                    line.contains("already claimed", true)

                fun isOutcome(line: String): Boolean =
                    line.contains("签到成功") || line.contains("签到完成") || isAlreadyCheckedIn(line) ||
                    line.contains("签到失败") ||
                    line.contains("check-in success", true) || line.contains("checkin success", true) ||
                    line.contains("check-in failed", true) || line.contains("checkin failed", true)

                fun outcome(line: String): String = when {
                    isAlreadyCheckedIn(line) || line.contains("签到成功") || line.contains("签到完成") ||
                        line.contains("check-in success", true) || line.contains("checkin success", true) -> "success"
                    line.contains("签到失败") || line.contains("check-in failed", true) ||
                        line.contains("checkin failed", true) -> "failed"
                    else -> "unknown"
                }

                fun gameLogLines(displayName: String, aliases: List<String>, source: List<String>): List<String> {
                    val tagged = source.filter { it.contains("[签到结果][$displayName]") }
                    if (tagged.isNotEmpty()) return tagged
                    val searchTerms = (listOf(displayName) + aliases).distinct()
                    return source.filter { line ->
                        searchTerms.any { term -> line.contains(term, true) } &&
                            !line.contains("正在进行「$displayName」签到")
                    }
                }

                for ((platform, _) in bindings) {
                    val platformAccounts = accounts.filter { it.platform == platform }
                    if (platformAccounts.isEmpty()) continue
                    val reason = when {
                        platform in logErrors -> logErrors.getValue(platform)
                        platform in sharedSourcePlatforms ->
                            "隔离检查失败：HoYoLAB 与森空岛函数返回了相同的 LTS 日志流。为防止读取到另一平台日志，本平台今日日志已拒绝展示。"
                        logResults[platform]?.state != "OK" ->
                            logResults[platform]?.state ?: "未取得该平台函数日志"
                        else -> ""
                    }
                    if (reason.isNotBlank()) {
                        platformAccounts.forEach { account ->
                            statuses[account.id] = "无法获取签到结果"
                            gameStatuses[account.id] = account.games.associateWith { "unknown" }
                            details[account.id] = JSONObject()
                                .put("reason", reason)
                                .put("allRawMessages", reason)
                                .put("games", JSONObject())
                                .put("logGroupName", logResults[platform]?.groupName.orEmpty())
                                .put("logStreamName", logResults[platform]?.streamName.orEmpty())
                                .toString()
                        }
                        continue
                    }

                    // This account receives logs from exactly one platform-bound function.
                    val platformLogs = logResults.getValue(platform)!!.logs
                    for (account in platformAccounts) {
                        val matched = if (platformAccounts.size == 1) platformLogs else {
                            val ids = listOfNotNull(account.platformUserId, account.name)
                                .map { it.trim() }.filter { it.length >= 3 }
                            platformLogs.filter { line -> ids.any { id ->
                                if (id == account.name) line.contains(id, true)
                                else Regex("(?<![A-Za-z0-9])${Regex.escape(id)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
                                    .containsMatchIn(line)
                            } }
                        }
                        val perGame = linkedMapOf<String, String>()
                        for (game in account.games) {
                            val lines = gameLogLines(GameCatalog.label(game), GameCatalog.aliases(game), matched)
                            perGame[game] = lines.firstOrNull(::isOutcome)?.let(::outcome) ?: "unknown"
                        }
                        gameStatuses[account.id] = perGame
                        val accountStatus = when {
                            perGame.values.any { it == "failed" } -> "今日签到失败"
                            perGame.values.any { it == "success" } -> "今日签到成功"
                            perGame.isNotEmpty() -> "今日状态未知"
                            else -> "未配置"
                        }
                        statuses[account.id] = accountStatus
                        val gameDetails = JSONObject()
                        for (game in account.games) {
                            val lines = gameLogLines(GameCatalog.label(game), GameCatalog.aliases(game), matched)
                            val outcomeLine = lines.firstOrNull(::isOutcome)
                            gameDetails.put(game, JSONObject()
                                .put("status", when (perGame[game]) {
                                    "success" -> "今日签到成功"
                                    "failed" -> "今日签到失败"
                                    else -> "未获取到明确结果"
                                })
                                .put("reason", outcomeLine ?: if (lines.isEmpty())
                                    "没有找到包含此游戏名称的本平台签到日志，不能据此认定签到失败。"
                                else "已找到相关日志，但没有识别到明确的成功或失败标记。")
                            )
                        }
                        details[account.id] = JSONObject()
                            .put("status", accountStatus)
                            .put("reason", "日志仅从账号所属平台绑定的独立云函数读取；没有明确结果时显示未知。")
                            .put("games", gameDetails)
                            .put("logGroupName", logResults[platform]?.groupName.orEmpty())
                            .put("logStreamName", logResults[platform]?.streamName.orEmpty())
                            .put("allRawMessages", org.json.JSONArray(matched.take(300)))
                            .toString()
                    }
                }

                accounts.filter { it.platform == AccountStore.MIYOUSHE_CN }.forEach { account ->
                    gameStatuses[account.id] = account.games.associateWith { "unknown" }
                    statuses[account.id] = "无法获取签到结果"
                    val reason = "当前未接入此平台的云端签到函数"
                    details[account.id] = JSONObject().put("reason", reason)
                        .put("allRawMessages", reason).toString()
                }
            } catch (e: Exception) {
                summary = "今日签到日志读取失败：" + (e.message ?: e.javaClass.simpleName)
                accounts.filter { it.id !in statuses }.forEach { account ->
                    statuses[account.id] = "无法获取签到结果"
                    gameStatuses[account.id] = account.games.associateWith { "unknown" }
                    val reason = "日志查询失败：" + (e.message ?: e.javaClass.simpleName)
                    details[account.id] = JSONObject().put("reason", reason)
                        .put("allRawMessages", reason).toString()
                }
            }
            runOnUiThread { onResult(statuses, gameStatuses, details, summary) }
        }
    }

    private fun showAccountDeployMessage(accountId: String, title: String, detail: String, success: Boolean?) {
        showOperationMessage(title, detail, success)
        AppMessageBus.postForAccount(this, accountId, title, detail, success)
    }

    /** Detailed step-by-step messages stay in the app-wide message stream. */
    private fun logAccountDeployStep(accountId: String, detail: String, success: Boolean? = null) {
        showOperationMessage("HoYoLAB 部署进度", detail, success)
    }


    private fun showSklandDeployMessage(accountId: String, title: String, detail: String, success: Boolean?) {
        AppMessageBus.post(this, AppMessageBus.SKLAND, title, detail, success)
        AppMessageBus.postForAccount(this, accountId, title, detail, success)
        onCloudDeploymentProgress()
    }

    private fun logSklandDeployStep(detail: String, success: Boolean? = null) {
        AppMessageBus.post(this, AppMessageBus.SKLAND, "森空岛部署进度", detail, success)
        onCloudDeploymentProgress()
    }

    /**
     * Deploy the Skland function independently from HoYoLAB. The Skland package
     * stores one token per line in code/INPUT_HYPERGRYPH_TOKEN.txt; replace only
     * the token belonging to this account and preserve every other cloud token.
     */
    private fun deploySklandAccount(accountId: String, selectedGamesJson: String) {
        AppMessageBus.resetAccountStages(this, accountId)
        val title = "森空岛更新"
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            showSklandDeployMessage(accountId, "$title 失败", "华为云尚未连接，请先在华为云页面登录。", false)
            return
        }
        val account = AccountStore(this).find(accountId)
            ?: run { showSklandDeployMessage(accountId, "$title 失败", "当前账号已不存在，请重新读取云端信息。", false); return }
        if (account.platform != AccountStore.SKLAND) {
            showSklandDeployMessage(accountId, "$title 失败", "当前账号不是森空岛账号，已停止上传。", false)
            return
        }
        val selectedGames = try {
            val array = JSONArray(selectedGamesJson)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: Exception) {
            showSklandDeployMessage(accountId, "$title 失败", "游戏选择数据无效，已停止上传。", false)
            return
        }
        if (selectedGames.isEmpty() || !SklandCloudConfigAdapter.supportedGames.containsAll(selectedGames)) {
            showSklandDeployMessage(accountId, "$title 失败", "至少选择一款森空岛支持的游戏。", false)
            return
        }
        val token = try {
            SklandTokenVault(this, accountId).latest().orEmpty()
        } catch (e: Exception) {
            showSklandDeployMessage(accountId, "$title 失败", "读取本机加密 Token 失败：${e.message ?: e.javaClass.simpleName}", false)
            return
        }
        if (token.isBlank() || token.any(Char::isWhitespace)) {
            showSklandDeployMessage(accountId, "$title 失败", "当前账号尚未成功获取可用 Token。请先打开森空岛登录页面并完成登录。", false)
            return
        }

        AppMessageBus.setAccountStage(this, accountId, "function", "云函数", "进行中", "正在检查云端森空岛函数并准备上传。")
        showSklandDeployMessage(accountId, "$title 进行中", "正在合并当前账号 Token 与启用游戏；其他账号配置保持不变。", null)
        worker.execute {
            var deploymentReady = false
            try {
                logSklandDeployStep("正在读取 APK 内置函数包 cloud/skland.zip。")
                val packaged = readPackagedZip("cloud/skland.zip")
                logSklandDeployStep("森空岛内置函数包读取成功，大小：${packaged.size} 字节。", true)
                if (!CloudAccountSyncState.allowsAccountChanges(this)) {
                    error("云端账号尚未完成同步，无法安全合并森空岛账号配置。请先重新连接华为云。")
                }
                // AccountStore is populated from the authoritative cloud package before editing.
                // Rebuild the complete Skland account list from its independently encrypted vaults;
                // never start from the APK's blank template and accidentally drop other cloud accounts.
                val managedSklandAccounts = AccountStore(this).all()
                    .filter { it.platform == AccountStore.SKLAND }
                if (managedSklandAccounts.none { it.id == accountId }) {
                    error("当前森空岛账号不在已同步账号列表中，已停止上传。")
                }
                val cloudAccounts = managedSklandAccounts.map { managed ->
                    val managedToken = if (managed.id == accountId) token else {
                        SklandTokenVault(this, managed.id).latest().orEmpty()
                    }
                    if (managedToken.isBlank() || managedToken.any(Char::isWhitespace)) {
                        error("账号「${managed.name}」缺少有效的本机加密 Token，无法安全保留全部云端账号。")
                    }
                    SklandCloudConfigAdapter.CloudAccount(
                        platformUserId = if (managed.id == accountId) sha256(token)
                            else managed.platformUserId ?: sha256(managedToken),
                        token = managedToken,
                        games = if (managed.id == accountId) selectedGames else managed.games
                    )
                }
                val mergedConfig = SklandCloudConfigAdapter.encodeAccounts(cloudAccounts)
                val mergedLegacyTokens = SklandCloudConfigAdapter.legacyTokenText(mergedConfig)
                val tokenCount = cloudAccounts.size
                logSklandDeployStep("账号配置合并完成：共 $tokenCount 个账号；当前账号启用 ${selectedGames.size} 款游戏。未输出 Token 原文。", true)

                var archive = replaceZip(
                    packaged,
                    SklandCloudConfigAdapter.LEGACY_TOKEN_PATH,
                    mergedLegacyTokens,
                    allowCreate = true
                )
                archive = replaceZip(
                    archive,
                    SklandCloudConfigAdapter.ACCOUNT_CONFIG_PATH,
                    mergedConfig,
                    allowCreate = true
                )
                logSklandDeployStep("森空岛函数 ZIP 重新打包完成，大小：${archive.size} 字节。", true)

                val created = createOrUpdateFunction(SKLAND_FUNCTION, SKLAND_HANDLER, archive) { detail, success ->
                    logSklandDeployStep(detail, success)
                }
                logSklandDeployStep("函数代码提交完成，正在等待 FunctionGraph 函数就绪。")
                val function = waitForFunction(SKLAND_FUNCTION)
                val urn = function.optString("func_urn")
                if (urn.isBlank()) error("森空岛函数包已上传，但无法读取函数 URN，未执行测试或检查触发器。")
                // FunctionGraph has accepted the package and reports the function ready.
                // Commit the cloud-authoritative account metadata before later LTS/test checks.
                deploymentReady = true
                AppMessageBus.setAccountStage(this, accountId, "function", "云函数", "成功",
                    if (created) "森空岛函数不存在，已新建并确认就绪。" else "森空岛函数原已存在，代码覆盖更新并确认就绪。")
                AccountStore(this).update(account.copy(
                    platformUserId = sha256(token),
                    games = selectedGames,
                    credentialReady = true
                ))
                onCloudDeploymentProgress()

                AppMessageBus.setAccountStage(this, accountId, "lts", "LTS 日志隔离", "进行中", "正在检查并配置森空岛专属日志流。")
                val ltsSummary = ensureLtsIsolation(urn, "skland") { detail, success -> logSklandDeployStep(detail, success) }
                AppMessageBus.setAccountStage(this, accountId, "lts", "LTS 日志隔离", "成功", ltsSummary)

                AppMessageBus.setAccountStage(this, accountId, "test", "函数运行测试", "进行中", "正在使用 TIMER 定时触发器事件格式调用森空岛函数。")
                logSklandDeployStep("正在使用华为云 TIMER 事件格式测试森空岛函数。")
                val testResult = invokeFunctionOnce(urn)
                val testSummary = testResult.summary
                AppMessageBus.setAccountStage(this, accountId, "test", "函数运行测试", "成功", "森空岛函数调用成功。")
                AppMessageBus.setAccountStage(this, accountId, "raw_output", "原始输出结果", "成功", testResult.rawOutput)
                logSklandDeployStep("森空岛 TIMER 事件格式测试完成：$testSummary", true)

                AppMessageBus.setAccountStage(this, accountId, "trigger", "定时触发器", "进行中", "正在查询森空岛函数现有定时触发器。")
                logSklandDeployStep("正在查询森空岛函数现有定时触发器。")
                val timerSummary = ensureDailyTimerIfMissing(urn)
                val triggerExists = timerSummary.contains("检测到已有")
                AppMessageBus.setAccountStage(this, accountId, "trigger", "定时触发器",
                    if (triggerExists) "已存在" else "已创建",
                    if (triggerExists) "检测到已有定时触发器，未重复创建。" else timerSummary)
                logSklandDeployStep("森空岛定时触发器检查完成：$timerSummary", true)

                val action = if (created) "创建" else "更新"
                val gameLabels = selectedGames.map { GameCatalog.label(it) }.joinToString("、")
                runOnUiThread {
                    showSklandDeployMessage(accountId, "森空岛部署及检查完成",
                        "最终结果：森空岛函数${action}成功。\n当前账号启用游戏：$gameLabels。\n已保留其他森空岛账号配置。\n测试运行：$testSummary\n定时触发器：$timerSummary", true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val reason = e.message ?: e.javaClass.simpleName
                    val currentStages = AppMessageBus.currentAccountStages(this, accountId)
                    val activeStage = (0 until currentStages.length()).mapNotNull { currentStages.optJSONObject(it) }
                        .firstOrNull { it.optString("status") == "进行中" }?.optString("key").orEmpty()
                    if (activeStage.isNotBlank()) {
                        val label = when (activeStage) {
                            "function" -> "云函数"
                            "trigger" -> "定时触发器"
                            "lts" -> "LTS 日志隔离"
                            "test" -> "函数运行测试"
                            else -> "函数运行测试"
                        }
                        AppMessageBus.setAccountStage(this, accountId, activeStage, label, "失败", reason)
                    }
                    logSklandDeployStep(if (deploymentReady)
                        "森空岛函数代码已部署，但后续测试或定时触发器检查失败：$reason"
                        else "森空岛部署流程中止：$reason", false)
                    showSklandDeployMessage(accountId,
                        if (deploymentReady) "森空岛已部署，后续检查失败" else "$title 失败",
                        if (deploymentReady) "森空岛云函数代码已上传并确认就绪；失败仅发生在后续测试/触发器检查阶段。$reason"
                        else reason, false)
                }
            }
        }
    }


    private fun deployHoyolabAccount(accountId: String, selectedGamesJson: String) {
        AppMessageBus.resetAccountStages(this, accountId)
        val title = "HoYoLAB 更新"
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            showAccountDeployMessage(accountId, "$title 失败", "华为云尚未连接，请先在华为云页面登录。", false)
            return
        }
        val pendingCookie = HuaweiCloudSession.pendingHoyoCookie
        val pendingServer = HuaweiCloudSession.pendingHoyoCookieServer
        val pendingAccountId = HuaweiCloudSession.pendingHoyoCookieAccountId
        if (pendingCookie.isBlank() || pendingAccountId != accountId) {
            showAccountDeployMessage(accountId, "$title 失败", "当前账号没有本次网页会话获取的 Cookie。请重新打开登录网页并等待显示“已获取”。", false)
            return
        }
        if (pendingServer !in setOf("cn", "os")) {
            showAccountDeployMessage(accountId, "$title 失败", "无法识别 Cookie 对应的服务器区域，已停止上传。", false)
            return
        }
        val account = AccountStore(this).find(accountId)
            ?: run { showAccountDeployMessage(accountId, "$title 失败", "当前账号已不存在，请重新读取云端信息。", false); return }
        val expectedPlatform = if (pendingServer == "cn") AccountStore.MIYOUSHE_CN else AccountStore.HOYOLAB
        if (account.platform != expectedPlatform) {
            showAccountDeployMessage(accountId, "$title 失败", "Cookie 所属服务器与当前账号平台不一致，已停止上传。", false)
            return
        }

        AppMessageBus.setAccountStage(this, accountId, "function", "云函数", "进行中", "正在检查云端函数并准备上传。")
        showAccountDeployMessage(accountId, "$title 进行中", "正在读取 APK 内置 ZIP，并将当前 Cookie 合并到云端原配置；其他配置保持不变。", null)
        worker.execute {
            var deploymentReady = false
            try {
                val functionName = HOYOLAB_FUNCTION
                logAccountDeployStep(accountId, "正在读取 APK 内置函数包 cloud/hoyolab.zip。")
                val packaged = readPackagedZip("cloud/hoyolab.zip")
                logAccountDeployStep(accountId, "内置函数包读取成功，大小：${packaged.size} 字节。", true)
                val cloudYaml = HuaweiCloudSession.lastConfigYaml
                val baseYaml = if (cloudYaml.isNotBlank()) {
                    logAccountDeployStep(accountId, "已取得云端原配置，将在原配置上进行局部修改。", true)
                    cloudYaml
                } else {
                    logAccountDeployStep(accountId, "尚无已读取的云端配置，改用 APK 内置默认配置。")
                    readZipEntryText(packaged, "config/config.yaml")
                }
                val mergedCookieYaml = HoYoLabCloudConfigAdapter.replaceCookie(baseYaml, pendingCookie, pendingServer)
                logAccountDeployStep(accountId, "Cookie 已定位并合并到 ${if (pendingServer == "cn") "account.cookie" else "games.os.cookie"}；未输出 Cookie 内容。", true)
                val selectedGames = try {
                    val array = org.json.JSONArray(selectedGamesJson)
                    (0 until array.length()).map { array.getString(it) }.toSet()
                } catch (e: Exception) { error("游戏选择数据无效，已停止上传。") }
                val mergedYaml = HoYoLabCloudConfigAdapter.replaceGames(mergedCookieYaml, selectedGames, pendingServer)
                logAccountDeployStep(accountId, "游戏签到选项已按当前选择更新，正在重新打包 ZIP。", true)
                val archive = replaceZip(packaged, "config/config.yaml", mergedYaml)
                logAccountDeployStep(accountId, "函数 ZIP 重新打包完成，大小：${archive.size} 字节。", true)
                val created = createOrUpdateFunction(functionName, HOYOLAB_HANDLER, archive) { detail, success ->
                    logAccountDeployStep(accountId, detail, success)
                }
                logAccountDeployStep(accountId, "函数代码提交完成，正在等待 FunctionGraph 函数就绪。")
                val function = waitForFunction(functionName)
                logAccountDeployStep(accountId, "已查询到函数并取得函数 URN，函数已就绪。", true)
                val urn = function.optString("func_urn")
                if (urn.isBlank()) error("函数包已上传，但无法读取函数 URN，未执行测试或检查触发器。")
                // FunctionGraph has accepted the package and reports the function ready.
                // Keep the selected games and YAML in sync even if later checks fail.
                deploymentReady = true
                AppMessageBus.setAccountStage(this, accountId, "function", "云函数", "成功",
                    if (created) "函数不存在，已新建并确认就绪。" else "函数原已存在，代码覆盖更新并确认就绪。")
                AccountStore(this).update(account.copy(
                    games = selectedGames,
                    credentialReady = true
                ))
                onCloudDeploymentProgress()
                HuaweiCloudSession.lastConfigYaml = mergedYaml
                configYaml = mergedYaml

                AppMessageBus.setAccountStage(this, accountId, "lts", "LTS 日志隔离", "进行中", "正在检查并配置 HoYoLAB 专属日志流。")
                val ltsSummary = ensureLtsIsolation(urn, "hoyolab") { detail, success -> logAccountDeployStep(accountId, detail, success) }
                AppMessageBus.setAccountStage(this, accountId, "lts", "LTS 日志隔离", "成功", ltsSummary)
                logAccountDeployStep(accountId, "部署阶段已完成：函数已存在且就绪，账号配置已同步；现在开始独立测试。", true)
                AppMessageBus.setAccountStage(this, accountId, "test", "函数运行测试", "进行中", "正在使用 TIMER 定时触发器事件格式调用函数。")
                logAccountDeployStep(accountId, "正在使用华为云 TIMER 定时触发器事件格式测试函数（不再发送空事件 {}）。")
                val testResult = invokeFunctionOnce(urn)
                val testSummary = testResult.summary
                AppMessageBus.setAccountStage(this, accountId, "test", "函数运行测试", "成功", "函数调用成功。")
                AppMessageBus.setAccountStage(this, accountId, "raw_output", "原始输出结果", "成功", testResult.rawOutput)
                logAccountDeployStep(accountId, "TIMER 事件格式测试完成：$testSummary", true)
                AppMessageBus.setAccountStage(this, accountId, "trigger", "定时触发器", "进行中", "正在查询现有定时触发器。")
                logAccountDeployStep(accountId, "正在查询现有定时触发器。")
                val timerSummary = ensureDailyTimerIfMissing(urn)
                val triggerExists = timerSummary.contains("检测到已有")
                AppMessageBus.setAccountStage(this, accountId, "trigger", "定时触发器",
                    if (triggerExists) "已存在" else "已创建",
                    if (triggerExists) "检测到已有定时触发器，未重复创建。" else timerSummary)
                logAccountDeployStep(accountId, "定时触发器检查完成：$timerSummary", true)
                val action = if (created) "创建" else "更新"
                runOnUiThread {
                    HuaweiCloudSession.lastConfigYaml = mergedYaml
                    configYaml = mergedYaml
                    showAccountDeployMessage(accountId, "HoYoLAB 部署及检查完成",
                        "最终结果：函数${action}成功。\nCookie 已写入当前账号对应的配置位置。\n云端其余配置均保留。\n测试运行：$testSummary\n定时触发器：$timerSummary", true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val reason = e.message ?: e.javaClass.simpleName
                    val currentStages = AppMessageBus.currentAccountStages(this, accountId)
                    val activeStage = (0 until currentStages.length()).mapNotNull { currentStages.optJSONObject(it) }
                        .firstOrNull { it.optString("status") == "进行中" }?.optString("key").orEmpty()
                    if (activeStage.isNotBlank()) {
                        val label = when (activeStage) {
                            "function" -> "云函数"
                            "trigger" -> "定时触发器"
                            "test" -> "函数运行测试"
                            else -> "函数运行测试"
                        }
                        AppMessageBus.setAccountStage(this, accountId, activeStage, label, "失败", reason)
                    }
                    logAccountDeployStep(accountId, if (deploymentReady)
                        "部署已成功，但后续测试或定时触发器检查失败：$reason"
                        else "部署流程中止：$reason", false)
                    showAccountDeployMessage(accountId,
                        if (deploymentReady) "HoYoLAB 已部署，后续检查失败" else "$title 失败",
                        if (deploymentReady) "云函数代码已上传并确认就绪，账号配置已同步；失败仅发生在后续测试/触发器检查阶段。$reason"
                        else reason, false)
                }
            }
        }
    }

    private fun readZipEntryTextOrNull(source: ByteArray, wanted: String): String? {
        var found: String? = null
        ZipInputStream(ByteArrayInputStream(source)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val path = entry.name.replace('\\', '/')
                if (!entry.isDirectory && (path == wanted || path.endsWith("/$wanted"))) {
                    if (found != null) error("ZIP 中存在多个 $wanted，已停止。")
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 5 * 1024 * 1024) error("$wanted 超过 5 MB。")
                        out.write(buffer, 0, n)
                    }
                    found = out.toString("UTF-8")
                }
                input.closeEntry()
            }
        }
        return found
    }

    private fun readZipEntryText(source: ByteArray, wanted: String): String =
        readZipEntryTextOrNull(source, wanted) ?: error("APK 内置 ZIP 中不存在 $wanted。")

    private fun persistHoyoLabInvalidCookieFingerprint(functionUrn: String, fingerprint: String): String {
        if (!Regex("^[0-9a-f]{64}$").matches(fingerprint))
            error("HoYoLAB Cookie 指纹格式无效，拒绝写入。")
        val configUrl = "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions/" + enc(functionUrn) + "/config"
        val current = signedGet(configUrl)
        // Preserve existing user_data and writable settings; never echo immutable type.
        current.remove("type")
        val rawUserData = current.optString("user_data").trim()
        val userData = if (rawUserData.isBlank() || rawUserData == "null") JSONObject() else JSONObject(rawUserData)
        userData.put("HOYOLAB_OS_COOKIE_INVALID_SHA256", fingerprint)
        current.put("user_data", userData.toString())
        val update = signedRequest("PUT", configUrl, current.toString())
        if (update.first !in 200..299)
            error("FunctionGraph Cookie 作废标记更新失败（HTTP " + update.first + "）：" + update.second.take(600))
        val verify = signedGet(configUrl)
        val verifyRaw = verify.optString("user_data").trim()
        val verifyData = if (verifyRaw.isBlank() || verifyRaw == "null") JSONObject() else JSONObject(verifyRaw)
        if (verifyData.optString("HOYOLAB_OS_COOKIE_INVALID_SHA256") != fingerprint)
            error("FunctionGraph Cookie 作废标记回读不一致。")
        return "HoYoLAB Cookie 已确认过期；SHA-256 作废标记已写入 FunctionGraph 并回读验证，后续相同 Cookie 将不再请求 HoYoLAB。"
    }

    private fun ensureLtsIsolation(functionUrn: String, platform: String, onStep: (String, Boolean?) -> Unit = { _, _ -> }): String {
        require(platform == "hoyolab" || platform == "skland") { "未知日志平台：$platform" }
        val base = "https://lts.$regionValue.myhuaweicloud.com/v2/$projectId"
        val groupName = "zidongqiandao-log"
        onStep("正在检查 LTS 日志组 $groupName。", null)
        val groups = signedGet("$base/groups").optJSONArray("log_groups") ?: error("LTS 未返回日志组列表；请检查 LTS 查询权限。")
        val matches = (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }.filter { it.optString("log_group_name") == groupName }
        if (matches.size > 1) error("发现多个同名日志组 $groupName，已停止。")
        val group = matches.singleOrNull() ?: run {
            onStep("日志组不存在，正在创建 $groupName（保留 7 天）。", null)
            val response = signedRequest("POST", "$base/groups", JSONObject().put("log_group_name", groupName).put("ttl_in_days", 7).toString())
            if (response.first !in 200..299) error("创建日志组失败（HTTP ${response.first}）：${response.second.take(600)}")
            val refreshed = signedGet("$base/groups").optJSONArray("log_groups") ?: error("日志组创建后无法重新查询。")
            (0 until refreshed.length()).mapNotNull { refreshed.optJSONObject(it) }.singleOrNull { it.optString("log_group_name") == groupName } ?: error("日志组创建后回读失败。")
        }
        val groupId = group.optString("log_group_id").takeIf { it.isNotBlank() } ?: error("LTS 日志组缺少 ID。")
        onStep("日志组检查成功：$groupName。", true)
        val streamUrl = "$base/groups/${enc(groupId)}/streams"
        val streamRows = signedGet(streamUrl).optJSONArray("log_streams") ?: error("LTS 未返回日志流列表。")
        val streamMatches = (0 until streamRows.length()).mapNotNull { streamRows.optJSONObject(it) }.filter { it.optString("log_stream_name") == platform }
        if (streamMatches.size > 1) error("日志组中存在多个同名日志流 $platform。")
        val stream = streamMatches.singleOrNull() ?: run {
            onStep("日志流 $platform 不存在，正在创建（保留 7 天）。", null)
            val response = signedRequest("POST", streamUrl, JSONObject().put("log_stream_name", platform).put("ttl_in_days", 7).toString())
            if (response.first !in 200..299) error("创建日志流 $platform 失败（HTTP ${response.first}）：${response.second.take(600)}")
            val refreshed = signedGet(streamUrl).optJSONArray("log_streams") ?: error("日志流创建后无法重新查询。")
            (0 until refreshed.length()).mapNotNull { refreshed.optJSONObject(it) }.singleOrNull { it.optString("log_stream_name") == platform } ?: error("日志流创建后回读失败。")
        }
        val streamId = stream.optString("log_stream_id").takeIf { it.isNotBlank() } ?: error("日志流 $platform 缺少 ID。")
        if (stream.has("ttl_in_days") && stream.optInt("ttl_in_days", 7) != 7) error("日志流 $platform 保留期不是 7 天，请先在 LTS 控制台调整；未继续绑定。")
        onStep("日志流检查成功：$platform（保留期 7 天）。", true)
        val configUrl = "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions/${enc(functionUrn)}/config"
        onStep("正在读取函数配置并设置专属日志流。", null)
        val config = signedGet(configUrl)
        // FunctionGraph GET config may include response-only/immutable fields.
        // In particular, sending the top-level "type" back in UpdateFunctionConfig
        // is rejected by Huawei with FSS.0400 "Not allow modify type".
        // Keep the existing writable settings, but never echo this immutable field.
        config.remove("type")
        config.put("log_config", JSONObject().put("group_name", groupName).put("group_id", groupId).put("stream_name", platform).put("stream_id", streamId))
        val update = signedRequest("PUT", configUrl, config.toString())
        if (update.first !in 200..299) error("函数日志绑定失败（HTTP ${update.first}）：${update.second.take(800)}")
        // Read the binding through FunctionGraph's dedicated LTS detail endpoint.
        // The general function-config GET response does not reliably expose log_config.
        val verifyUrl = "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions/${enc(functionUrn)}/lts-log-detail"
        val verify = signedGet(verifyUrl)
        if (verify.optString("group_id") != groupId || verify.optString("stream_id") != streamId) {
            error("函数日志绑定回读不一致：期望日志组/流 ID 为 $groupId/$streamId，实际为 ${verify.optString("group_id")}/${verify.optString("stream_id")}。")
        }
        onStep("函数日志绑定成功并已回读验证：$groupName/$platform。", true)
        return "日志组：$groupName；日志流：$platform；保留期：7 天；函数绑定已验证。"
    }
    private fun waitForFunction(name: String): JSONObject {
        val base = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" + projectId + "/fgs/functions"
        var lastError = ""
        repeat(10) { attempt ->
            try {
                val rows = signedGet(base + "?func_name=" + enc(name) + "&precise_search=true&maxitems=10").optJSONArray("functions")
                val matches = if (rows == null) emptyList() else (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("func_name") == name }
                if (matches.size > 1) error("固定目标函数出现重名，已停止后续操作。")
                val found = matches.singleOrNull()
                if (found != null && found.optString("func_urn").isNotBlank()) return found
            } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName }
            if (attempt < 9) Thread.sleep(3000)
        }
        error("函数包已上传，但等待函数就绪超时。" + if (lastError.isBlank()) "" else " 最近错误：$lastError")
    }

    private data class FunctionInvokeResult(val summary: String, val rawOutput: String)

    private fun invokeFunctionOnce(functionUrn: String): FunctionInvokeResult {
        val url = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" + projectId + "/fgs/functions/" + enc(functionUrn) + "/invocations"
        // Match Huawei FunctionGraph's documented TIMER event shape instead of sending an empty object.
        val timerEvent = JSONObject()
            .put("version", "v2.0")
            .put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+08:00'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }.format(Date()))
            .put("trigger_type", "TIMER")
            .put("trigger_name", "APK_Timer_Test")
            .put("user_event", "APK 部署后的定时触发器格式测试")
        val responseHeaders = linkedMapOf<String, String>()
        val response = signedRequest(
            "POST", url, timerEvent.toString(),
            mapOf("x-cff-log-type" to "tail", "x-cff-request-version" to "v1"),
            responseHeaders
        )
        if (response.first !in 200..299) error("定时触发器格式测试请求失败（HTTP ${response.first}）：" + response.second.take(1600))
        val raw = response.second.trim()
        val encodedPrintLog = responseHeaders.entries.firstOrNull { it.key.equals("x-cff-function-log", true) }?.value.orEmpty()
        val bodyLog = runCatching { JSONObject(raw).optString("log").trim() }.getOrDefault("")
        val printLog = if (encodedPrintLog.isNotBlank()) runCatching {
            android.util.Base64.decode(encodedPrintLog, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrDefault("函数日志 Base64 解码失败：${encodedPrintLog}") else bodyLog
        val fullRawOutput = printLog.ifBlank {
            "华为云未返回函数自身打印日志。\n函数调用响应正文：\n$raw"
        }.take(12000)
        if (functionUrn.contains(HOYOLAB_FUNCTION)) {
            val invalidHash = Regex("""\[COOKIE_INVALID\]\[sha256=([0-9a-f]{64})\]""")
                .find(fullRawOutput)?.groupValues?.get(1)
            if (invalidHash != null) {
                persistHoyoLabInvalidCookieFingerprint(functionUrn, invalidHash)
            }
        }
        if (raw.isBlank()) error("定时触发器格式测试响应体为空（HTTP ${response.first}），无法确认函数返回结果。")
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result != null) {
            val statusCode = when {
                result.has("statusCode") -> result.optInt("statusCode", 200)
                result.has("status_code") -> result.optInt("status_code", 200)
                else -> 200
            }
            if (statusCode >= 400) error("定时触发器格式测试失败（状态码 $statusCode）：" + result.toString().take(1200))
            val statusText = result.optString("status").trim()
            if (statusText.equals("FAILED", true) || statusText.equals("ERROR", true) || statusText.equals("FAILURE", true))
                error("定时触发器格式测试失败（状态：$statusText）：" + result.toString().take(1200))
            val errorText = listOf("error", "errorMessage", "error_message", "function_error")
                .map { result.optString(it).trim() }.firstOrNull { it.isNotBlank() && it != "null" }
            if (errorText != null) error("定时触发器格式测试失败：$errorText")
            val requestId = result.optString("request_id")
            val summary = "已使用 TIMER 事件格式调用成功（HTTP ${response.first}" +
                if (requestId.isNotBlank()) "，请求 ID：$requestId）" else "）"
            return FunctionInvokeResult(summary, fullRawOutput)
        }
        if (raw.contains("Traceback", true) || raw.contains("Exception", true) ||
            raw.contains("FileNotFoundError", true) || raw.contains("error", true))
            error("定时触发器格式测试返回异常内容：" + raw.take(1200))
        // A raw scalar such as 0 is a function return value, not malformed JSON.
        return FunctionInvokeResult("已使用 TIMER 事件格式调用（HTTP ${response.first}）", fullRawOutput)
    }

    private fun listFunctionTriggers(functionUrn: String): org.json.JSONArray {
        val url = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" + projectId + "/fgs/triggers/" + enc(functionUrn)
        val response = signedGet(url)
        // FunctionGraph 的触发器列表接口在部分响应中直接返回 JSON 数组，
        // 另一些响应则使用 {"triggers": [...]} 包装；两种格式都安全识别。
        response.optJSONArray("triggers")?.let { return it }
        response.optJSONArray("_root_array")?.let { return it }
        error("华为云未返回可确认的触发器列表；为避免误覆盖，未创建任何触发器。")
    }

    private fun ensureDailyTimerIfMissing(functionUrn: String): String {
        val existing = listFunctionTriggers(functionUrn)
        val timers = (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }.filter { it.optString("trigger_type_code").equals("TIMER", ignoreCase = true) }
        if (timers.isNotEmpty()) {
            val names = timers.map { it.optJSONObject("event_data")?.optString("name")?.takeIf { name -> !name.isNullOrBlank() } ?: "未命名" }
            return "检测到已有 ${timers.size} 个定时触发器（${names.joinToString("、")}），按要求未作任何修改。"
        }
        // 00:02 至 03:30（含首尾）共 209 个分钟点；随机值只在本次创建时生成一次。
        val minuteOfDay = 2 + java.security.SecureRandom().nextInt(209)
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        val cron = "CRON_TZ=Asia/Shanghai 0 $minute $hour * * *"
        val triggerName = "AutoDaily_${System.currentTimeMillis()}"
        val body = JSONObject().put("trigger_type_code", "TIMER").put("trigger_status", "ACTIVE")
            .put("event_data", JSONObject().put("name", triggerName).put("schedule_type", "Cron").put("schedule", cron).put("user_event", "APK 自动创建的每日任务"))
        val url = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" + projectId + "/fgs/triggers/" + enc(functionUrn)
        val response = signedRequest("POST", url, body.toString())
        if (response.first !in 200..299) error("未发现已有定时触发器，但创建每日 Cron 触发器失败（HTTP ${response.first}）：" + response.second.take(1200))
        val verified = listFunctionTriggers(functionUrn)
        val created = (0 until verified.length()).mapNotNull { verified.optJSONObject(it) }.any { trigger ->
            trigger.optString("trigger_type_code").equals("TIMER", ignoreCase = true) &&
                trigger.optJSONObject("event_data")?.optString("name") == triggerName
        }
        if (!created) error("华为云已接受定时触发器创建请求，但回读未能确认创建结果；为避免重复，没有再次创建。")
        return "原先不存在定时触发器，已创建并确认每日执行：北京时间 %02d:%02d（Cron：%s）。".format(Locale.US, hour, minute, cron)
    }
    private fun messageChannel(): String =
        if (panelService == "skland") AppMessageBus.SKLAND else AppMessageBus.HOYOLAB

    protected fun showOperationMessage(title: String, detail: String, success: Boolean?) {
        AppMessageBus.post(this, messageChannel(), title, detail, success)
        lastResultMessage = AppMessageBus.current(this, messageChannel())
        onCloudDeploymentProgress()

    }

    private fun readPackagedZip(path: String): ByteArray {
        val bytes = assets.open(path).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > 50 * 1024 * 1024) error("APK 内置函数包超过 50 MB。")
            }
            out.toByteArray()
        }
        if (!isZip(bytes)) error("APK 内置部署包损坏：" + path)
        return bytes
    }

    private fun createOrUpdateFunction(
        name: String,
        handler: String,
        archive: ByteArray,
        onStep: (String, Boolean?) -> Unit = { _, _ -> }
    ): Boolean {
        val base = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" +
            projectId + "/fgs/functions"
        fun findExact(): JSONObject? {
            val response = signedGet(base + "?func_name=" + enc(name) + "&precise_search=true&maxitems=10")
            val rows = response.optJSONArray("functions")
                ?: error("FunctionGraph 函数查询未返回 functions 列表，无法判断函数是否存在；已停止上传。")
            val matches = (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.optString("func_name") == name }
            if (matches.size > 1) error("固定目标函数出现重名，已停止部署。")
            return matches.singleOrNull()
        }
        onStep("正在向华为云查询目标函数「$name」是否存在。", null)
        val existing = findExact()
        onStep(
            if (existing == null) "查询成功：云端不存在目标函数「$name」，本次将新建函数。"
            else "查询成功：云端已存在目标函数「$name」，本次将覆盖更新其代码（保留函数本身及其他设置）。",
            true
        )
        val encoded = Base64.encodeToString(archive, Base64.NO_WRAP)
        val code = JSONObject().put("code_filename", "index.zip")
            .put("code_type", "zip")
            .put("func_code", JSONObject().put("file", encoded))
        if (existing == null) {
            val body = JSONObject()
                .put("func_name", name)
                .put("package", "default")
                .put("runtime", "Python3.10")
                .put("handler", handler)
                .put("memory_size", 256)
                .put("timeout", 900)
                .put("code_filename", "index.zip")
                .put("code_type", "zip")
                .put("func_code", JSONObject().put("file", encoded))
            onStep("正在提交新建函数请求。", null)
            val response = signedRequest("POST", base, body.toString())
            if (response.first in 200..299) {
                onStep("新建函数请求成功（HTTP ${response.first}）。", true)
                return true
            }
            onStep("新建请求返回 HTTP ${response.first}，正在重新查询函数，确认是否发生并发创建。", null)
            val raced = findExact() ?: error("创建函数失败（HTTP ${response.first}）：" + response.second.take(1600))
            onStep("重新查询确认函数已由其他请求创建，改为更新该函数代码。", true)
            updateExistingCode(raced, code)
            onStep("并发创建后的代码覆盖更新成功。", true)
            return false
        }
        onStep("正在覆盖上传现有函数的代码包。", null)
        updateExistingCode(existing, code)
        onStep("覆盖上传成功：FunctionGraph 已接受代码更新请求。", true)
        return false
    }

    private fun updateExistingCode(function: JSONObject, payload: JSONObject) {
        val urn = function.optString("func_urn")
        if (urn.isBlank()) error("云端函数缺少 URN，已停止上传。")
        val url = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" +
            projectId + "/fgs/functions/" + enc(urn) + "/code"
        val response = signedRequest("PUT", url, payload.toString())
        if (response.first !in 200..299) {
            error("更新函数代码失败（HTTP ${response.first}）：" + response.second.take(1600))
        }
    }

    private fun replaceZip(source: ByteArray, wanted: String, yaml: String, allowCreate: Boolean = false): ByteArray {
        if (!isZip(source)) error("原函数代码包不是有效 ZIP。")
        val entries = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(source)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = ByteArrayOutputStream()
                if (!entry.isDirectory) {
                    val buffer = ByteArray(8192); var total = 0
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; if (total > 50 * 1024 * 1024) error("ZIP 单个文件超过 50 MB。")
                        out.write(buffer, 0, n)
                    }
                }
                entries.add(entry to out.toByteArray()); input.closeEntry()
            }
        }
        val hits = entries.filter {
            val p = it.first.name.replace('\\', '/')
            p == wanted || p.endsWith("/" + wanted)
        }
        if (hits.size > 1 || (hits.isEmpty() && !allowCreate)) {
            error(if (hits.isEmpty()) "ZIP 中不存在 " + wanted + "，未执行替换。" else "ZIP 中存在多个目标配置文件，已停止。")
        }
        val target = hits.singleOrNull()?.first
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { zip ->
            for ((entry, bytes) in entries) {
                val copy = ZipEntry(entry.name)
                if (entry.time >= 0) copy.time = entry.time
                if (entry.comment != null) copy.comment = entry.comment
                zip.putNextEntry(copy)
                if (!entry.isDirectory) zip.write(if (entry === target) yaml.toByteArray(Charsets.UTF_8) else bytes)
                zip.closeEntry()
            }
            if (target == null) {
                zip.putNextEntry(ZipEntry(wanted))
                zip.write(yaml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        val resultBytes = result.toByteArray()
        if (resultBytes.size > 50 * 1024 * 1024) error("重新打包后的 ZIP 超过 50 MB。")
        return resultBytes
    }

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 0x50.toByte() &&
        b[1] == 0x4b.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    private fun signedGet(url: String): JSONObject {
        val response = signedRequest("GET", url, "")
        if (response.first !in 200..299) error("华为云请求失败（HTTP " + response.first + "）：" + response.second)
        val body = response.second.trim()
        if (body.startsWith("[")) {
            // 统一封装根节点数组，避免 JSONObject(String) 因接口直接返回 [] 而崩溃。
            return JSONObject().put("_root_array", org.json.JSONArray(body))
        }
        if (body.isBlank()) error("华为云 GET 接口返回空响应。")
        return JSONObject(body)
    }

    private fun signedRequest(
        method: String,
        urlText: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
        responseHeaders: MutableMap<String, String>? = null
    ): Pair<Int, String> {
        val url = URL(urlText)
        val date = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val headers = sortedMapOf("host" to url.host, "x-sdk-date" to date)
        // APIGW requires Content-Type even on GET requests. Include it in the
        // canonical signed headers for every method so the signature matches
        // the actual HTTP request headers.
        headers["content-type"] = "application/json"
        extraHeaders.forEach { (key, value) -> headers[key.lowercase(Locale.ROOT)] = value }
        val names = headers.keys.toList()
        val canonicalHeaders = names.joinToString("") { it + ":" + headers[it] + "\n" }
        val signedHeaders = names.joinToString(";")
        val path = if (url.path.endsWith("/")) url.path else url.path + "/"
        val query = url.query.orEmpty().split("&").filter { it.isNotEmpty() }.map {
            val pair = it.split("=", limit = 2)
            enc(java.net.URLDecoder.decode(pair[0], "UTF-8")) + "=" +
                enc(java.net.URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8"))
        }.sorted().joinToString("&")
        val canonical = listOf(method, path, query, canonicalHeaders, signedHeaders, sha256(body)).joinToString("\n")
        val hash = sha256(canonical)
        val toSign = "SDK-HMAC-SHA256\n" + date + "\n" + hash
        val signature = hmac(authSk, toSign)
        val authorization = "SDK-HMAC-SHA256 Access=" + authAk + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 30000; readTimeout = if (method == "GET") 90000 else 180000
            setRequestProperty("X-Sdk-Date", date); setRequestProperty("Authorization", authorization)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", headers["content-type"])
            if (method != "GET") doOutput = true
            extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            if (method != "GET") conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            responseHeaders?.clear()
            conn.headerFields.forEach { (key, values) ->
                if (key != null && !values.isNullOrEmpty()) responseHeaders?.put(key.lowercase(Locale.ROOT), values.joinToString(","))
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally { conn.disconnect() }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun hmac(key: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val HOYOLAB_FUNCTION = "HOYOLAB20240704"
        private const val SKLAND_FUNCTION = "SKLand20260122"
        private const val HOYOLAB_HANDLER = "index.main_handler"
        private const val SKLAND_HANDLER = "index.handler"
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
