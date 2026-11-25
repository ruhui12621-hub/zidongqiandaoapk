package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.os.Bundle
import android.util.Base64
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
    private var logsStatusValue = "登录华为云后即可读取两个服务的运行日志。"
    private var logsOutputValue = "暂无日志"
    private var lastResultMessage = ""
    private var authAk = ""
    private var authSk = ""
    private var regionValue = "cn-east-3"
    private val regionCodes = listOf("cn-east-3", "cn-east-2", "cn-north-4", "cn-north-1", "cn-south-1", "ap-southeast-1", "ap-southeast-3")
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

                    // 国服凭据位于 account.cookie，允许为空；国际服凭据必须单独读取 games.os.cookie。
                    // 不能用全局 cookie 正则，否则会先命中国服空值并误判整个同步失败。
                    var inGames = false
                    var inOs = false
                    var inAccount = false
                    var cnCookie: String? = null
                    var osCookie: String? = null
                    yaml.lineSequence().forEach { line ->
                        val indent = line.takeWhile { it == ' ' }.length
                        val keyLine = line.trim()
                        if (indent == 0) {
                            inAccount = keyLine == "account:"
                            if (keyLine == "games:") {
                                inGames = true
                                inOs = false
                            } else {
                                inGames = false
                                inOs = false
                            }
                        }
                        if (inAccount && indent == 2 && keyLine.startsWith("cookie:")) {
                            val raw = keyLine.substringAfter(':').substringBefore(" #").trim()
                            cnCookie = raw.removeSurrounding("\"").removeSurrounding("'").trim()
                        }
                        if (inGames && indent == 2 && keyLine == "os:") {
                            inOs = true
                            return@forEach
                        }
                        if (inGames && indent == 2 && keyLine.endsWith(":") && keyLine != "os:") {
                            inOs = false
                        }
                        if (inGames && inOs && indent == 4 && keyLine.startsWith("cookie:")) {
                            val raw = keyLine.substringAfter(':').substringBefore(" #").trim()
                            osCookie = raw.removeSurrounding("\"").removeSurrounding("'").trim()
                        }
                    }

                    // Restore the enabled international game list from the cloud YAML.
                    // Without this, the imported account has an empty game set and makeYaml()
                    // later turns every game off (including the configured ZZZ check-in).
                    val enabledOsGames = linkedSetOf<String>()
                    val supportedOsGames = setOf("genshin", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz")
                    var inGamesSection = false
                    var inOsSection = false
                    var currentGame: String? = null
                    yaml.lineSequence().forEach { line ->
                        val indent = line.takeWhile { it == ' ' }.length
                        val keyLine = line.trim()
                        if (indent == 0) {
                            inGamesSection = keyLine == "games:"
                            inOsSection = false
                            currentGame = null
                            return@forEach
                        }
                        if (!inGamesSection) return@forEach
                        if (indent == 2) {
                            inOsSection = keyLine == "os:"
                            currentGame = null
                            return@forEach
                        }
                        if (!inOsSection) return@forEach
                        if (indent == 4 && keyLine.endsWith(":")) {
                            currentGame = keyLine.removeSuffix(":").takeIf { it in supportedOsGames }
                            return@forEach
                        }
                        if (indent == 6 && keyLine.startsWith("checkin:") &&
                            keyLine.substringAfter(':').trim().equals("true", true)) {
                            currentGame?.let(enabledOsGames::add)
                        }
                    }

                    val enabledCnGames = linkedSetOf<String>()
                    val supportedCnGames = setOf("genshin", "honkai2", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz")
                    var inCnGames = false
                    var currentCnGame: String? = null
                    yaml.lineSequence().forEach { line ->
                        val indent = line.takeWhile { it == ' ' }.length
                        val keyLine = line.trim()
                        if (indent == 0) {
                            inCnGames = keyLine == "games:"
                            currentCnGame = null
                            return@forEach
                        }
                        if (!inCnGames) return@forEach
                        if (indent == 2) {
                            inCnGames = keyLine == "cn:"
                            currentCnGame = null
                            return@forEach
                        }
                        if (!inCnGames) return@forEach
                        if (indent == 4 && keyLine.endsWith(":")) {
                            currentCnGame = keyLine.removeSuffix(":").takeIf { it in supportedCnGames }
                            return@forEach
                        }
                        if (indent == 6 && keyLine.startsWith("checkin:") &&
                            keyLine.substringAfter(':').trim().equals("true", true)) {
                            currentCnGame?.let(enabledCnGames::add)
                        }
                    }

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
                        val fields = cnValue.split(';').mapNotNull { part ->
                            val key = part.trim().substringBefore('=').trim().lowercase()
                            val value = part.trim().substringAfter('=', "").trim()
                            if (key.isBlank() || value.isBlank()) null else key to value
                        }.toMap()
                        val tokenReady = setOf("stoken", "stoken_v2", "cookie_token", "ltoken").any { !fields[it].isNullOrBlank() }
                        store.update(account.copy(
                            platformUserId = identity ?: account.platformUserId,
                            games = enabledCnGames,
                            credentialReady = true,
                            cookieReady = true,
                            tokenReady = tokenReady
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
                        val cookieFields = cookie.split(';').mapNotNull { part ->
                            val key = part.trim().substringBefore('=').trim().lowercase()
                            val value = part.trim().substringAfter('=', "").trim()
                            if (key.isBlank() || value.isBlank()) null else key to value
                        }.toMap()
                        val tokenReady = setOf("ltoken_v2", "cookie_token_v2").any { !cookieFields[it].isNullOrBlank() }
                        store.update(account.copy(
                            platformUserId = identity ?: account.platformUserId,
                            games = enabledOsGames,
                            credentialReady = true,
                            cookieReady = true,
                            tokenReady = tokenReady
                        ))
                        imported++
                    }
                    // 国际服 Cookie 为空时仅跳过 HoYoLAB 凭据导入，不阻断后续森空岛 Token 同步。
                }

                if (sklandFunction != null) {
                    val tokenText = readZipText(
                        downloadZip(sklandFunction, "森空岛"),
                        "code/INPUT_HYPERGRYPH_TOKEN.txt",
                        "森空岛"
                    )
                    val tokens = tokenText.lineSequence().map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }.distinct().toList()
                    tokens.forEach { token ->
                        val stableId = MessageDigest.getInstance("SHA-256")
                            .digest(token.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        val account = store.add(
                            AccountStore.SKLAND,
                            platformUserId = stableId,
                            games = setOf("endfield")
                        )
                        SklandTokenVault(this, account.id).save(token)
                        store.update(account.copy(
                            // Endfield is an additional Skland game; keep any existing
                            // game selections and ensure Endfield is checked as requested.
                            games = account.games + "endfield",
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

    @android.webkit.JavascriptInterface
    protected fun htmlDeploy(service: String, yaml: String) {
        runOnUiThread {
            if (service == "hoyolab" && yaml.isNotBlank()) updateHuaweiCloudConfig(yaml)
            deployPackages(if (service == "skland") "skland" else "hoyolab")
        }
    }

    protected fun htmlDeployHoyoAccount(accountId: String, selectedGamesJson: String) {
        runOnUiThread { deployHoyolabAccount(accountId, selectedGamesJson) }
    }

    @android.webkit.JavascriptInterface
    protected fun htmlFetchLogs() {
        runOnUiThread { fetchFunctionLogs() }
    }

    protected fun htmlLogsText(): String = logsOutputValue
    protected fun htmlLogsStatus(): String = logsStatusValue
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
                    showOperationMessage("华为云连接成功", "Region：$r\nProject ID：$pid" + vaultWarning, true)
                    synchronizeCloudAccounts { _, message ->
                        showOperationMessage("云端账号同步", message, CloudAccountSyncState.allowsAccountChanges(this))
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

    private fun fetchFunctionLogs() {
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            logsStatusValue = "请先登录华为云。"; return
        }
        logsStatusValue = "正在读取两个服务最近 7 天的运行日志…"
        logsOutputValue = "正在连接华为云日志服务…"
        worker.execute {
            try {
                val rendered = StringBuilder()
                val targets = listOf("HoYoLAB" to HOYOLAB_FUNCTION, "森空岛" to SKLAND_FUNCTION)
                for ((label, functionName) in targets) {
                    rendered.append("【").append(label).append("】\n")
                    val exactUrl = "https://functiongraph." + regionValue +
                        ".myhuaweicloud.com/v2/" + projectId + "/fgs/functions?func_name=" +
                        enc(functionName) + "&precise_search=true&maxitems=10"
                    val rows = signedGet(exactUrl).optJSONArray("functions")
                    val matches = if (rows == null) emptyList() else
                        (0 until rows.length()).map { rows.getJSONObject(it) }
                            .filter { it.optString("func_name") == functionName }
                    if (matches.size != 1) {
                        rendered.append("函数尚未创建或无法唯一识别。\n\n")
                        continue
                    }
                    val urn = matches.single().optString("func_urn")
                    val detailUrl = "https://functiongraph." + regionValue +
                        ".myhuaweicloud.com/v2/" + projectId + "/fgs/functions/" +
                        enc(urn) + "/lts-log-detail"
                    val detail = signedGet(detailUrl)
                    val groupId = detail.optString("group_id")
                    val streamId = detail.optString("stream_id")
                    if (groupId.isBlank() || streamId.isBlank()) {
                        rendered.append("尚未获取到日志组/日志流。\n\n")
                        continue
                    }
                    val now = System.currentTimeMillis()
                    val startTime = now - 7L * 24L * 60L * 60L * 1000L
                    val queryUrl = "https://lts." + regionValue + ".myhuaweicloud.com/v2/" +
                        projectId + "/groups/" + enc(groupId) + "/streams/" + enc(streamId) + "/content/query"
                    val logs = mutableListOf<JSONObject>()
                    val seen = mutableSetOf<String>()
                    var scrollId = ""
                    var lineNum = ""
                    var page = 0
                    var complete = false
                    while (page < 1000 && !complete) {
                        val body = JSONObject().put("start_time", startTime.toString())
                            .put("end_time", now.toString()).put("limit", 5000)
                            .put("is_desc", true).put("search_type", if (page == 0) "init" else "forwards")
                            .put("is_count", true).put("is_iterative", true)
                        if (scrollId.isNotBlank()) body.put("scroll_id", scrollId)
                        else if (lineNum.isNotBlank()) body.put("line_num", lineNum)
                        val response = signedRequest("POST", queryUrl, body.toString())
                        if (response.first !in 200..299) error("LTS 查询失败（HTTP ${response.first}）：" + response.second.take(1200))
                        val result = JSONObject(response.second)
                        val pageLogs = result.optJSONArray("logs")
                        val count = pageLogs?.length() ?: 0
                        if (pageLogs != null) for (i in 0 until pageLogs.length()) {
                            val item = pageLogs.optJSONObject(i) ?: continue
                            val key = item.optString("line_num")
                            if (key.isBlank() || seen.add(key)) logs.add(item)
                        }
                        page++
                        val nextScroll = result.optString("scrollId")
                        if (nextScroll.isNotBlank()) scrollId = nextScroll
                        if (count > 0) lineNum = pageLogs?.optJSONObject(count - 1)?.optString("line_num").orEmpty()
                        complete = result.optBoolean("isQueryComplete", false) || count == 0 ||
                            (count < 5000 && nextScroll.isBlank())
                    }
                    if (logs.isEmpty()) rendered.append("最近 7 天没有日志。\n")
                    else logs.forEach { rendered.append(it.optString("content", "(空日志)")).append("\n") }
                    rendered.append("\n")
                }
                runOnUiThread {
                    logsStatusValue = "已完成两个服务的最近 7 天日志查询。"
                    logsOutputValue = rendered.toString()
                    showOperationMessage("运行日志读取完成", rendered.toString(), true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logsStatusValue = "读取日志失败：" + (e.message ?: e.javaClass.simpleName)
                    logsOutputValue = "请检查 FunctionGraph 日志配置与 LTS 查询权限。"
                    showOperationMessage("运行日志读取失败", e.message ?: e.javaClass.simpleName, false)
                }
            }
        }
    }


    /**
     * Query only today's LTS output for the two scheduled check-in functions.
     * Credentials are read from the encrypted local vault.
     */
    protected fun queryTodayCheckinStatus(onResult: (String, String, String) -> Unit) {
        val saved = runCatching { AndroidCredentialVault(this).latest() }.getOrNull()
        if (saved == null) {
            onResult("未配置 AK/SK", "未配置 AK/SK", "请先在任一服务页连接华为云")
            return
        }
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val selectedRegion = prefs.getString("cloud_region", "cn-east-3") ?: "cn-east-3"
        worker.execute {
            var hoyo = "查询中"
            var skland = "查询中"
            var detail = ""
            try {
                authAk = saved.first
                authSk = saved.second
                regionValue = selectedRegion
                val projects = signedGet("https://iam.$regionValue.myhuaweicloud.com/v3/projects")
                val rows = projects.optJSONArray("projects") ?: error("IAM 未返回项目列表")
                projectId = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optString("name").equals(regionValue, true) }
                    ?.optString("id").orEmpty()
                if (projectId.isBlank()) error("AK/SK 已读取，但未找到 $regionValue 对应的项目")
                HuaweiCloudSession.ak = authAk
                HuaweiCloudSession.sk = authSk
                HuaweiCloudSession.region = regionValue
                HuaweiCloudSession.projectId = projectId

                fun queryOne(functionName: String): String {
                    val listUrl = "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions?func_name=" +
                        enc(functionName) + "&precise_search=true&maxitems=10"
                    val functions = signedGet(listUrl).optJSONArray("functions")
                        ?: return "函数不存在或无法查询"
                    val matches = (0 until functions.length()).mapNotNull { functions.optJSONObject(it) }
                        .filter { it.optString("func_name") == functionName }
                    if (matches.size != 1) return "函数不存在或无法唯一识别"
                    val urn = matches.single().optString("func_urn")
                    val logDetail = signedGet(
                        "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions/${enc(urn)}/lts-log-detail"
                    )
                    val groupId = logDetail.optString("group_id")
                    val streamId = logDetail.optString("stream_id")
                    if (groupId.isBlank() || streamId.isBlank()) return "未启用日志或日志流不可用"

                    val calendar = Calendar.getInstance()
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val start = calendar.timeInMillis
                    val end = System.currentTimeMillis()
                    val queryUrl = "https://lts.$regionValue.myhuaweicloud.com/v2/$projectId/groups/${enc(groupId)}/streams/${enc(streamId)}/content/query"
                    val body = JSONObject().put("start_time", start.toString())
                        .put("end_time", end.toString()).put("limit", 1000)
                        .put("is_desc", true).put("search_type", "init")
                        .put("is_count", true).put("is_iterative", true)
                    val response = signedRequest("POST", queryUrl, body.toString())
                    if (response.first !in 200..299) error("LTS HTTP ${response.first}: ${response.second.take(300)}")
                    val logs = JSONObject(response.second).optJSONArray("logs") ?: return "今天暂无运行记录"
                    if (logs.length() == 0) return "今天暂无运行记录"

                    val contents = (0 until logs.length()).mapNotNull { i ->
                        logs.optJSONObject(i)?.optString("content")?.takeIf { it.isNotBlank() }
                    }
                    // LTS is queried newest-first; use the newest explicit business result only.
                    val latestOutcome = contents.firstOrNull { line ->
                        line.contains("签到成功") || line.contains("签到完成") ||
                            line.contains("签到失败") || line.contains("check-in success", true) ||
                            line.contains("checkin success", true) || line.contains("check-in failed", true) ||
                            line.contains("checkin failed", true)
                    }.orEmpty()
                    return when {
                        latestOutcome.contains("签到成功") || latestOutcome.contains("签到完成") ||
                            latestOutcome.contains("check-in success", true) ||
                            latestOutcome.contains("checkin success", true) -> "签到成功"
                        latestOutcome.contains("签到失败") || latestOutcome.contains("check-in failed", true) ||
                            latestOutcome.contains("checkin failed", true) -> "签到失败（请查看函数运行消息）"
                        else -> "今天已运行，但日志未包含可确认的签到结果"
                    }
                }

                try { hoyo = queryOne(HOYOLAB_FUNCTION) }
                catch (e: Exception) { hoyo = "查询失败：${e.message ?: e.javaClass.simpleName}" }
                try { skland = queryOne(SKLAND_FUNCTION) }
                catch (e: Exception) { skland = "查询失败：${e.message ?: e.javaClass.simpleName}" }
            } catch (e: Exception) {
                detail = e.message ?: e.javaClass.simpleName
                if (hoyo == "查询中") hoyo = "查询失败：$detail"
                if (skland == "查询中") skland = "查询失败：$detail"
            }
            runOnUiThread { onResult(hoyo, skland, detail) }
        }
    }

    /**
     * Read today's FunctionGraph/LTS logs and conservatively associate outcomes with
     * local accounts. With multiple accounts, a log must contain that account's stable
     * platform ID or explicit account name; otherwise the app does not guess.
     */
    protected fun queryTodayCheckinStatusForAccounts(
        onResult: (Map<String, String>, String) -> Unit
    ) {
        val accounts = AccountStore(this).all()
        val saved = runCatching { AndroidCredentialVault(this).latest() }.getOrNull()
        if (saved == null) {
            onResult(accounts.associate { it.id to "未连接华为云" }, "未配置华为云 AK/SK")
            return
        }
        worker.execute {
            val statuses = linkedMapOf<String, String>()
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

                fun readTodayLogs(functionName: String): Pair<String, List<String>> {
                    val listUrl = "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions" +
                        "?func_name=" + enc(functionName) + "&precise_search=true&maxitems=10"
                    val functions = signedGet(listUrl).optJSONArray("functions")
                        ?: return "函数不存在或无法查询" to emptyList()
                    val matches = (0 until functions.length()).mapNotNull { functions.optJSONObject(it) }
                        .filter { it.optString("func_name") == functionName }
                    if (matches.size != 1) return "函数不存在或无法唯一识别" to emptyList()
                    val urn = matches.single().optString("func_urn")
                    val detail = signedGet(
                        "https://functiongraph.$regionValue.myhuaweicloud.com/v2/$projectId/fgs/functions/${enc(urn)}/lts-log-detail"
                    )
                    val groupId = detail.optString("group_id")
                    val streamId = detail.optString("stream_id")
                    if (groupId.isBlank() || streamId.isBlank()) return "未启用日志或日志流不可用" to emptyList()

                    val calendar = Calendar.getInstance()
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val start = calendar.timeInMillis
                    val end = System.currentTimeMillis()
                    val queryUrl = "https://lts.$regionValue.myhuaweicloud.com/v2/$projectId/groups/${enc(groupId)}/streams/${enc(streamId)}/content/query"
                    val logs = mutableListOf<String>()
                    val seen = mutableSetOf<String>()
                    var scrollId = ""
                    var lineNum = ""
                    var page = 0
                    var complete = false
                    while (page < 100 && !complete) {
                        val body = JSONObject().put("start_time", start.toString())
                            .put("end_time", end.toString()).put("limit", 5000)
                            .put("is_desc", true).put("search_type", if (page == 0) "init" else "forwards")
                            .put("is_count", true).put("is_iterative", true)
                        if (scrollId.isNotBlank()) body.put("scroll_id", scrollId)
                        else if (lineNum.isNotBlank()) body.put("line_num", lineNum)
                        val response = signedRequest("POST", queryUrl, body.toString())
                        if (response.first !in 200..299) {
                            error("LTS 查询失败（HTTP ${response.first}）：${response.second.take(500)}")
                        }
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
                        complete = result.optBoolean("isQueryComplete", false) || count == 0 ||
                            (count < 5000 && nextScroll.isBlank())
                    }
                    return "OK" to logs
                }

                fun isOutcome(line: String): Boolean =
                    line.contains("签到成功") || line.contains("签到完成") || line.contains("签到失败") ||
                        line.contains("check-in success", true) || line.contains("checkin success", true) ||
                        line.contains("check-in failed", true) || line.contains("checkin failed", true)

                fun outcome(line: String): String = when {
                    line.contains("签到成功") || line.contains("签到完成") ||
                        line.contains("check-in success", true) || line.contains("checkin success", true) -> "今日签到成功"
                    line.contains("签到失败") || line.contains("check-in failed", true) ||
                        line.contains("checkin failed", true) -> "今日签到失败"
                    else -> "今日状态未知"
                }

                val services = listOf(
                    AccountStore.HOYOLAB to HOYOLAB_FUNCTION,
                    AccountStore.SKLAND to SKLAND_FUNCTION
                )
                for ((platform, functionName) in services) {
                    val platformAccounts = accounts.filter { it.platform == platform }
                    if (platformAccounts.isEmpty()) continue
                    val logResult = runCatching { readTodayLogs(functionName) }
                    if (logResult.isFailure) {
                        val message = "日志查询失败：${logResult.exceptionOrNull()?.message ?: "未知错误"}"
                        platformAccounts.forEach { statuses[it.id] = message }
                        continue
                    }
                    val (readState, logs) = logResult.getOrThrow()
                    if (readState != "OK") {
                        platformAccounts.forEach { statuses[it.id] = readState }
                        continue
                    }
                    for (account in platformAccounts) {
                        val matchedLines = if (platformAccounts.size == 1) {
                            logs
                        } else {
                            val identifiers = listOfNotNull(account.platformUserId, account.name)
                                .map { it.trim() }.filter { it.length >= 3 }
                            logs.filter { line ->
                                identifiers.any { id ->
                                    if (id == account.name) line.contains(id, true)
                                    else Regex("(?<![A-Za-z0-9])${Regex.escape(id)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
                                        .containsMatchIn(line)
                                }
                            }
                        }
                        statuses[account.id] = when {
                            platformAccounts.size > 1 && matchedLines.isEmpty() ->
                                "日志未包含此账号标识，无法对应"
                            matchedLines.isEmpty() -> "今天暂无运行日志"
                            else -> matchedLines.firstOrNull(::isOutcome)?.let(::outcome)
                                ?: "已运行，但日志未明确记录签到结果"
                        }
                    }
                }
                accounts.filter { it.platform == AccountStore.MIYOUSHE_CN }
                    .forEach { statuses[it.id] = "当前未接入此平台的云端签到函数" }
            } catch (e: Exception) {
                summary = "今日签到日志读取失败：" + (e.message ?: e.javaClass.simpleName)
                accounts.filter { it.id !in statuses }.forEach { statuses[it.id] = "日志查询失败" }
            }
            runOnUiThread { onResult(statuses, summary) }
        }
    }

    private fun deployPackages(service: String) {
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            setCloudConnectionState("尚未连接")
            showOperationMessage(if (service == "hoyolab") "HoYoLAB 更新失败" else "森空岛更新失败", "华为云尚未连接，请先登录。", false)
            return
        }
        val isHoyolab = service == "hoyolab"
        if (isHoyolab) {
            configYaml = configProvider?.invoke().orEmpty()
            HuaweiCloudSession.lastConfigYaml = configYaml
            if (configYaml.isBlank() || !configYaml.contains("cookie:")) {
                setCloudConnectionState("尚未连接")
                showOperationMessage("HoYoLAB 更新失败", "HoYoLAB 尚未获取有效 Cookie，本次未执行更新。", false)
                return
            }
        }
        setCloudConnectionState("已连接")
        showOperationMessage(if (isHoyolab) "HoYoLAB 更新中" else "森空岛更新中", "正在上传函数包；先测试运行，确认成功后才检查定时触发器…", null)
        worker.execute {
            try {
                val functionName = if (isHoyolab) HOYOLAB_FUNCTION else SKLAND_FUNCTION
                val created = if (isHoyolab) {
                    val archive = replaceZip(readPackagedZip("cloud/hoyolab.zip"), "config/config.yaml", configYaml)
                    createOrUpdateFunction(functionName, HOYOLAB_HANDLER, archive)
                } else {
                    val token = SklandTokenVault(this).latest()?.takeIf { it.isNotBlank() }
                        ?: error("尚未获取森空岛 Token。请先登录森空岛并完成 Token 获取。")
                    val archive = replaceZip(readPackagedZip("cloud/skland.zip"), "code/INPUT_HYPERGRYPH_TOKEN.txt", token.trim() + "\n")
                    createOrUpdateFunction(functionName, SKLAND_HANDLER, archive)
                }
                val function = waitForFunction(functionName)
                val urn = function.optString("func_urn")
                if (urn.isBlank()) error("函数包已上传，但无法读取函数 URN，未执行测试或修改触发器。")
                // 必须先确认函数真实执行成功；任何异常都会在这里中止，不会查询或创建定时触发器。
                val testSummary = invokeFunctionOnce(urn)
                val timerSummary = ensureDailyTimerIfMissing(urn)
                val label = if (isHoyolab) "HoYoLAB" else "森空岛"
                val action = if (created) "创建" else "更新"
                val detail = "$label 函数${action}成功。\n测试运行：$testSummary\n定时触发器：$timerSummary\n本次没有触碰另一个服务。"
                runOnUiThread {
                    setCloudConnectionState("已连接")
                    showOperationMessage("$label 部署及检查完成", detail, true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setCloudConnectionState("已连接")
                    showOperationMessage(if (isHoyolab) "HoYoLAB 更新失败" else "森空岛更新失败", e.message ?: e.javaClass.simpleName, false)
                }
            }
        }
    }

    private fun deployHoyolabAccount(accountId: String, selectedGamesJson: String) {
        val title = "HoYoLAB 更新"
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            showOperationMessage("$title 失败", "华为云尚未连接，请先在华为云页面登录。", false)
            return
        }
        val pendingCookie = HuaweiCloudSession.pendingHoyoCookie
        val pendingServer = HuaweiCloudSession.pendingHoyoCookieServer
        val pendingAccountId = HuaweiCloudSession.pendingHoyoCookieAccountId
        if (pendingCookie.isBlank() || pendingAccountId != accountId) {
            showOperationMessage("$title 失败", "当前账号没有本次网页会话获取的 Cookie。请重新打开登录网页并等待显示“已获取”。", false)
            return
        }
        if (pendingServer !in setOf("cn", "os")) {
            showOperationMessage("$title 失败", "无法识别 Cookie 对应的服务器区域，已停止上传。", false)
            return
        }
        val account = AccountStore(this).find(accountId)
            ?: run { showOperationMessage("$title 失败", "当前账号已不存在，请重新读取云端信息。", false); return }
        val expectedPlatform = if (pendingServer == "cn") AccountStore.MIYOUSHE_CN else AccountStore.HOYOLAB
        if (account.platform != expectedPlatform) {
            showOperationMessage("$title 失败", "Cookie 所属服务器与当前账号平台不一致，已停止上传。", false)
            return
        }

        showOperationMessage("$title 进行中", "正在读取 APK 内置 ZIP，并将当前 Cookie 合并到云端原配置；其他配置保持不变。", null)
        worker.execute {
            try {
                val functionName = HOYOLAB_FUNCTION
                val packaged = readPackagedZip("cloud/hoyolab.zip")
                val cloudYaml = HuaweiCloudSession.lastConfigYaml
                val baseYaml = if (cloudYaml.isNotBlank()) cloudYaml
                    else readZipEntryText(packaged, "config/config.yaml")
                val mergedCookieYaml = replaceCloudCookie(baseYaml, pendingCookie, pendingServer)
                val selectedGames = try {
                    val array = org.json.JSONArray(selectedGamesJson)
                    (0 until array.length()).map { array.getString(it) }.toSet()
                } catch (e: Exception) { error("游戏选择数据无效，已停止上传。") }
                val mergedYaml = replaceCloudGames(mergedCookieYaml, selectedGames, pendingServer)
                val archive = replaceZip(packaged, "config/config.yaml", mergedYaml)
                val created = createOrUpdateFunction(functionName, HOYOLAB_HANDLER, archive)
                val function = waitForFunction(functionName)
                val urn = function.optString("func_urn")
                if (urn.isBlank()) error("函数包已上传，但无法读取函数 URN，未执行测试或检查触发器。")
                val testSummary = invokeFunctionOnce(urn)
                val timerSummary = ensureDailyTimerIfMissing(urn)
                val action = if (created) "创建" else "更新"
                runOnUiThread {
                    HuaweiCloudSession.lastConfigYaml = mergedYaml
                    configYaml = mergedYaml
                    showOperationMessage("HoYoLAB 部署及检查完成",
                        "函数" + action + "成功。\nCookie 已写入当前账号对应的配置位置。\n云端其余配置均保留。\n测试运行：$testSummary\n定时触发器：$timerSummary", true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showOperationMessage("$title 失败", e.message ?: e.javaClass.simpleName, false)
                }
            }
        }
    }

    private fun readZipEntryText(source: ByteArray, wanted: String): String {
        var found: String? = null
        ZipInputStream(ByteArrayInputStream(source)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val path = entry.name.replace('\\', '/')
                if (!entry.isDirectory && (path == wanted || path.endsWith("/$wanted"))) {
                    if (found != null) error("APK 内置 ZIP 中存在多个 $wanted，已停止。")
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
        return found ?: error("APK 内置 ZIP 中不存在 $wanted。")
    }

    /** Replace only account.cookie (CN) or games.os.cookie (global); preserve every other YAML line. */
    private fun replaceCloudCookie(source: String, cookie: String, server: String): String {
        val lines = source.split("\n").toMutableList()
        var inAccount = false
        var inGames = false
        var inOs = false
        val matches = mutableListOf<Int>()
        for (i in lines.indices) {
            val line = lines[i].removeSuffix("\r")
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' }.length
            if (server == "cn") {
                if (indent == 0) inAccount = trimmed == "account:"
                if (inAccount && indent == 2 && trimmed.startsWith("cookie:")) matches.add(i)
            } else {
                if (indent == 0) {
                    inGames = trimmed == "games:"
                    inOs = false
                } else if (inGames && indent == 2) {
                    inOs = trimmed == "os:"
                } else if (inGames && inOs && indent == 4 && trimmed.startsWith("cookie:")) {
                    matches.add(i)
                }
            }
        }
        if (matches.size != 1) {
            val path = if (server == "cn") "account.cookie" else "games.os.cookie"
            error("云端配置中无法唯一定位 " + path + "（匹配数：" + matches.size + "），为避免覆盖其他配置，已停止上传。")
        }
        val index = matches.single()
        val oldLine = lines[index]
        val lineEnding = if (oldLine.endsWith("\r")) "\r" else ""
        val indent = oldLine.takeWhile { it == ' ' }.length
        val escaped = cookie.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "").replace("\n", "")
        lines[index] = " ".repeat(indent) + "cookie: \"$escaped\"" + lineEnding
        return lines.joinToString("\n")
    }

    /** Update only each supported game's checkin flag inside games.cn or games.os. */
    private fun replaceCloudGames(source: String, selected: Set<String>, server: String): String {
        val supported = if (server == "cn") {
            linkedSetOf("genshin", "honkai2", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz")
        } else {
            linkedSetOf("genshin", "honkai3rd", "tears_of_themis", "honkai_sr", "zzz")
        }
        if (!supported.containsAll(selected)) error("游戏选择中包含当前服务器不支持的项目，已停止上传。")
        val lines = source.split("\n").toMutableList()
        var inGames = false
        var inServer = false
        var currentGame: String? = null
        val matches = mutableMapOf<String, MutableList<Int>>()
        supported.forEach { matches[it] = mutableListOf() }
        for (i in lines.indices) {
            val line = lines[i].removeSuffix("\r")
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' }.length
            if (indent == 0) {
                inGames = trimmed == "games:"
                inServer = false
                currentGame = null
                continue
            }
            if (!inGames) continue
            if (indent == 2) {
                inServer = trimmed == "$server:"
                currentGame = null
                continue
            }
            if (!inServer) continue
            if (indent == 4 && trimmed.endsWith(":")) {
                currentGame = trimmed.removeSuffix(":").takeIf { it in supported }
                continue
            }
            if (indent == 6 && trimmed.startsWith("checkin:")) {
                val game = currentGame ?: continue
                matches[game]?.add(i)
            }
        }
        val missing = supported.filter { matches[it]?.size != 1 }
        if (missing.isNotEmpty()) {
            error("云端配置中无法唯一定位游戏签到选项：" + missing.joinToString("、") +
                "。为避免改动错误配置，已停止上传。")
        }
        for (game in supported) {
            val index = matches.getValue(game).single()
            val old = lines[index]
            val ending = if (old.endsWith("\r")) "\r" else ""
            val indent = old.takeWhile { it == ' ' }.length
            val comment = old.removeSuffix("\r").substringAfter("checkin:", "").trim()
                .takeIf { it.startsWith("#") }.orEmpty()
            val suffix = if (comment.isNotEmpty()) " $comment" else ""
            lines[index] = " ".repeat(indent) + "checkin: " + (game in selected).toString() + suffix + ending
        }
        return lines.joinToString("\n")
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

    private fun invokeFunctionOnce(functionUrn: String): String {
        val url = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" + projectId + "/fgs/functions/" + enc(functionUrn) + "/invocations"
        val response = signedRequest("POST", url, "{}")
        if (response.first !in 200..299) error("函数测试运行请求失败（HTTP ${response.first}）：" + response.second.take(1600))
        if (response.second.isBlank()) error("函数测试运行没有返回执行结果；为避免误创建定时触发器，已停止后续操作。")

        val result = try {
            JSONObject(response.second)
        } catch (e: Exception) {
            error("函数测试运行返回无法识别的结果，未确认执行成功：" + response.second.take(1200))
        }

        fun checkStatus(obj: JSONObject, label: String) {
            val statusCode = when {
                obj.has("statusCode") -> obj.optInt("statusCode", 200)
                obj.has("status_code") -> obj.optInt("status_code", 200)
                obj.has("status") && obj.opt("status") is Number -> obj.optInt("status", 200)
                else -> 200
            }
            if (statusCode >= 400) error("${label}失败（状态码 $statusCode）：" + obj.toString().take(1200))
            val statusText = obj.optString("status").trim()
            if (statusText.equals("FAILED", true) || statusText.equals("ERROR", true) ||
                statusText.equals("FAILURE", true)) {
                error("${label}失败（状态：$statusText）：" + obj.toString().take(1200))
            }
            val errorText = listOf("error", "errorMessage", "error_message", "function_error")
                .map { obj.optString(it).trim() }.firstOrNull { it.isNotBlank() && it != "null" }
            if (errorText != null) error("${label}失败：$errorText")
        }

        checkStatus(result, "FunctionGraph 测试调用")
        val resultText = result.optString("result").trim()
        if (resultText.isBlank() && !result.has("statusCode") && !result.has("status_code")) {
            error("华为云已接受测试请求，但没有返回函数执行结果；为避免误创建定时触发器，已停止后续操作。")
        }
        if (resultText.isNotBlank()) {
            val inner = runCatching { JSONObject(resultText) }.getOrNull()
            if (inner != null) {
                checkStatus(inner, "森空岛函数实际执行")
                val bodyText = inner.optString("body").trim()
                if (bodyText.startsWith("{")) {
                    val bodyObject = runCatching { JSONObject(bodyText) }.getOrNull()
                    if (bodyObject != null) checkStatus(bodyObject, "森空岛函数业务结果")
                }
            } else if (resultText.contains("Traceback", true) ||
                resultText.contains("Exception", true) ||
                resultText.contains("FileNotFoundError", true)) {
                error("森空岛函数实际执行异常：" + resultText.take(1200))
            }
        }
        val requestId = result.optString("request_id")
        return if (requestId.isNotBlank()) "已执行一次且返回成功（请求 ID：$requestId）" else "已执行一次且返回成功（HTTP ${response.first}）"
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

    private fun createOrUpdateFunction(name: String, handler: String, archive: ByteArray): Boolean {
        val base = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" +
            projectId + "/fgs/functions"
        fun findExact(): JSONObject? {
            val rows = signedGet(base + "?func_name=" + enc(name) + "&precise_search=true&maxitems=10")
                .optJSONArray("functions") ?: return null
            val matches = (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.optString("func_name") == name }
            if (matches.size > 1) error("固定目标函数出现重名，已停止部署。")
            return matches.singleOrNull()
        }
        val encoded = Base64.encodeToString(archive, Base64.NO_WRAP)
        val code = JSONObject().put("code_filename", "index.zip")
            .put("code_type", "zip")
            .put("func_code", JSONObject().put("file", encoded))
        val existing = findExact()
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
            val response = signedRequest("POST", base, body.toString())
            if (response.first in 200..299) return true
            val raced = findExact() ?: error("创建函数失败（HTTP ${response.first}）：" + response.second.take(1600))
            updateExistingCode(raced, code)
            return false
        }
        updateExistingCode(existing, code)
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

    private fun replaceZip(source: ByteArray, wanted: String, yaml: String): ByteArray {
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
        if (hits.size != 1) error(if (hits.isEmpty()) "ZIP 中不存在 " + wanted + "，未执行替换。" else "ZIP 中存在多个目标配置文件，已停止。")
        val target = hits.single().first
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

    private fun signedRequest(method: String, urlText: String, body: String): Pair<Int, String> {
        val url = URL(urlText)
        val date = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val headers = sortedMapOf("host" to url.host, "x-sdk-date" to date)
        if (method != "GET") headers["content-type"] = if (method == "POST") "application/json;charset=utf8" else "application/json"
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
            if (method != "GET") { doOutput = true; setRequestProperty("Content-Type", headers["content-type"]) }
        }
        try {
            if (method != "GET") conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
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
