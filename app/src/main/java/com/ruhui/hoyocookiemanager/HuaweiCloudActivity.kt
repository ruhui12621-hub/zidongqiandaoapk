package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Base64
import android.widget.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
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
    private lateinit var region: Spinner
    private lateinit var ak: EditText
    private lateinit var sk: EditText
    private lateinit var cloudStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var resultMessage: TextView
    private lateinit var copyResultButton: Button
    private var lastResultMessage = ""
    private lateinit var logsButton: Button
    private lateinit var logsStatus: TextView
    private lateinit var logsOutput: TextView
    private var authAk = ""
    private var authSk = ""
    private var regionValue = "cn-east-3"
    private val regionCodes = listOf("cn-east-3", "cn-east-2", "cn-north-4", "cn-north-1", "cn-south-1", "ap-southeast-1", "ap-southeast-3")
    private val regionLabels = listOf("华东-上海一（默认）", "华东-上海二", "华北-北京四", "华北-北京一", "华南-广州", "中国-香港", "亚太-新加坡")
    private var projectId = ""
    private var configYaml = ""
    private var configProvider: (() -> String)? = null
    private var panelService = "hoyolab"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    protected fun addHuaweiCloudPanel(parent: LinearLayout, service: String, configProvider: () -> String, onConfigure: () -> Unit = {}) {
        this.configProvider = configProvider
        this.panelService = service
        configYaml = HuaweiCloudSession.lastConfigYaml
        val content = parent
        content.addView(TextView(this).apply {
            text = "华为云 FunctionGraph"
            textSize = 24f
            setTextColor(0xFFECEFF5.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(TextView(this).apply {
            text = "华为云地区"
            setTextColor(0xFFDCE2EC.toInt())
            setPadding(0, dp(10), 0, dp(4))
        })
        region = Spinner(this)
        region.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, regionLabels)
        val savedRegion = getSharedPreferences("app_settings", MODE_PRIVATE).getString("cloud_region", "cn-east-3") ?: "cn-east-3"
        region.setSelection(regionCodes.indexOf(savedRegion).takeIf { it >= 0 } ?: 0)
        region.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val code = regionCodes.getOrElse(position) { "cn-east-3" }
                getSharedPreferences("app_settings", MODE_PRIVATE).edit().putString("cloud_region", code).apply()
                if (regionValue != code && HuaweiCloudSession.isLoggedIn()) {
                    HuaweiCloudSession.region = code
                    HuaweiCloudSession.projectId = ""
                    projectId = ""
                    cloudStatus.text = "地区已切换为 $code，请重新登录并读取该地区的函数列表。"
                    updateButton.isEnabled = false
                    logsButton.isEnabled = false
                }
            }
        }
        content.addView(region, LinearLayout.LayoutParams(-1, -2))
        val savedCredentials = runCatching { AndroidCredentialVault(this).latest() }.getOrNull()
        ak = field(content, "AK", savedCredentials?.first ?: HuaweiCloudSession.ak)
        sk = field(content, "SK", savedCredentials?.second ?: HuaweiCloudSession.sk, true)
        content.addView(TextView(this).apply {
            text = "提示：AK/SK 不是常规账号密码。请登录华为云官网，在“我的凭证”中创建并获取访问密钥（AK/SK），再填入下方。"
            textSize = 13f
            setTextColor(0xFFFFCC80.toInt())
            setPadding(0, dp(10), 0, dp(8))
        })
        content.addView(Button(this).apply {
            text = "登录并连接华为云"; isAllCaps = false; setOnClickListener { login() }
        })
        cloudStatus = TextView(this).apply {
            text = "华为云状态：尚未连接"
            textSize = 14f
            setTextColor(0xFFB8C1D1.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF202631.toInt())
        }
        content.addView(cloudStatus)
        if (service == "hoyolab") {
            content.addView(Button(this).apply {
                text = "配置选择"; isAllCaps = false
                setOnClickListener { onConfigure() }
            })
        }
        updateButton = Button(this).apply {
            text = if (service == "hoyolab") "更新 HoYoLAB 云函数" else "更新森空岛云函数"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { deployPackages(service) }
        }
        content.addView(updateButton)
        content.addView(TextView(this).apply {
            text = "操作消息"
            textSize = 19f
            setTextColor(0xFFECEFF5.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(6))
        })
        lastResultMessage = AppMessageBus.current(this, messageChannel())
        resultMessage = TextView(this).apply {
            text = lastResultMessage
            textSize = 13f
            setTextColor(0xFFE1E6EF.toInt())
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFF202631.toInt())
            minHeight = dp(72)
        }
        content.addView(resultMessage)
        copyResultButton = Button(this).apply {
            text = "复制消息内容"
            isAllCaps = false
            isEnabled = lastResultMessage != "暂无操作记录"
            setOnClickListener {
                if (lastResultMessage.isNotBlank() && lastResultMessage != "暂无操作记录") {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("操作消息", lastResultMessage))
                    Toast.makeText(this@HuaweiCloudActivity, "消息内容已复制", Toast.LENGTH_SHORT).show()
                }
            }
        }
        content.addView(copyResultButton)
        content.addView(TextView(this).apply {
            text = "函数运行日志"
            textSize = 20f
            setTextColor(0xFFECEFF5.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(26), 0, dp(6))
        })
        content.addView(TextView(this).apply {
            text = "读取 HoYoLAB 与森空岛两个服务最近一周的运行输出。"
            textSize = 13f
            setTextColor(0xFFA7B0C0.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(TextView(this).apply {
            text = "固定查询最近 7 天，自动分页读取全部日志。"
            textSize = 13f
            setTextColor(0xFFA7B0C0.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        logsButton = Button(this).apply {
            text = "读取运行日志"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { fetchFunctionLogs() }
        }
        content.addView(logsButton)
        logsStatus = TextView(this).apply {
            text = "登录华为云后即可读取两个服务的运行日志。"
            textSize = 13f
            setTextColor(0xFFA7B0C0.toInt())
            setPadding(0, dp(8), 0, dp(6))
        }
        content.addView(logsStatus)
        logsOutput = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFE1E6EF.toInt())
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFF202631.toInt())
            minHeight = dp(90)
            text = "暂无日志"
        }
        val logsScroll = ScrollView(this).apply { addView(logsOutput) }
        content.addView(logsScroll, LinearLayout.LayoutParams(-1, dp(260)))
        if (HuaweiCloudSession.isLoggedIn()) {
            projectId = HuaweiCloudSession.projectId
            regionValue = HuaweiCloudSession.region
            region.setSelection(regionCodes.indexOf(regionValue).takeIf { it >= 0 } ?: 0)
            authAk = HuaweiCloudSession.ak
            authSk = HuaweiCloudSession.sk
            cloudStatus.text = "● 华为云已连接\nRegion：$regionValue\nProject ID：$projectId"
            updateButton.isEnabled = service == "skland" || configYaml.isNotBlank()
            logsButton.isEnabled = true
        }
    }

    protected fun updateHuaweiCloudConfig(value: String) {
        configYaml = value
        HuaweiCloudSession.lastConfigYaml = value
        if (::updateButton.isInitialized) {
            updateButton.isEnabled = value.isNotBlank() && HuaweiCloudSession.isLoggedIn()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun field(parent: LinearLayout, label: String, value: String, secret: Boolean = false): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFDCE2EC.toInt())
            setPadding(0, dp(10), 0, dp(4))
        })
        return EditText(this).also {
            it.setSingleLine(true)
            it.setText(value)
            it.setTextColor(0xFFECEFF5.toInt())
            it.setHintTextColor(0xFF7F899A.toInt())
            it.setHint(if (secret) "输入 SK" else "请输入 $label")
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF65718A.toInt())
            if (secret) it.inputType = 129
            parent.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
    }


    private fun login() {
        val r = regionCodes.getOrElse(region.selectedItemPosition) { "cn-east-3" }
        val a = ak.text.toString().trim()
        val s = sk.text.toString()
        if (r.isBlank() || a.isBlank() || s.isBlank()) {
            val detail = "请填写 Region、AK 和 SK。"
            cloudStatus.text = detail
            showOperationMessage("华为云登录失败", detail, false)
            return
        }
        regionValue = r; authAk = a; authSk = s
        HuaweiCloudSession.region = r
        getSharedPreferences("app_settings", MODE_PRIVATE).edit().putString("cloud_region", r).apply()
        cloudStatus.text = "正在验证 AK/SK 并连接华为云…"
        updateButton.isEnabled = false
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
                    val sklandReady = !runCatching { SklandTokenVault(this).latest() }.getOrNull().isNullOrBlank()
                    cloudStatus.text = "● 华为云已连接\nRegion：" + r + "\nProject ID：" + pid +
                        (if (panelService == "hoyolab") {
                            if (configYaml.isBlank()) "\nHoYoLAB：等待 Cookie" else "\nHoYoLAB：Cookie 已就绪"
                        } else {
                            if (sklandReady) "\n森空岛：Token 已就绪" else "\n森空岛：等待 Token"
                        }) + vaultWarning
                    updateButton.isEnabled = panelService == "skland" || configYaml.isNotBlank()
                    logsButton.isEnabled = true
                    showOperationMessage("华为云连接成功", "Region：$r\nProject ID：$pid" + vaultWarning, true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val detail = "登录/查询失败：" + (e.message ?: e.javaClass.simpleName)
                    cloudStatus.text = detail
                    showOperationMessage("华为云登录失败", detail, false)
                }
            }
        }
    }

    private fun fetchFunctionLogs() {
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            logsStatus.text = "请先登录华为云。"; return
        }
        logsButton.isEnabled = false
        logsStatus.text = "正在读取两个服务最近 7 天的运行日志…"
        logsOutput.text = "正在连接华为云日志服务…"
        worker.execute {
            try {
                val rendered = StringBuilder()
                val targets = listOf("HoYoLAB" to HOYOLAB_FUNCTION, "森空岛" to SENKONGDAO_FUNCTION)
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
                    logsStatus.text = "已完成两个服务的最近 7 天日志查询。"
                    logsOutput.text = rendered.toString()
                    logsButton.isEnabled = true
                    showOperationMessage("运行日志读取完成", rendered.toString(), true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logsStatus.text = "读取日志失败：" + (e.message ?: e.javaClass.simpleName)
                    logsOutput.text = "请检查 FunctionGraph 日志配置与 LTS 查询权限。"
                    logsButton.isEnabled = true
                    showOperationMessage("运行日志读取失败", e.message ?: e.javaClass.simpleName, false)
                }
            }
        }
    }

    private fun deployPackages(service: String) {
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            val detail = "华为云尚未连接，请先登录。"
            cloudStatus.text = detail
            showOperationMessage(if (service == "hoyolab") "HoYoLAB 更新失败" else "森空岛更新失败", detail, false)
            return
        }
        val isHoyolab = service == "hoyolab"
        if (isHoyolab) {
            configYaml = configProvider?.invoke().orEmpty()
            HuaweiCloudSession.lastConfigYaml = configYaml
            if (configYaml.isBlank() || !configYaml.contains("cookie:")) {
                val detail = "HoYoLAB 尚未获取有效 Cookie，本次未执行更新。"
                cloudStatus.text = detail
                showOperationMessage("HoYoLAB 更新失败", detail, false)
                return
            }
        }
        updateButton.isEnabled = false
        cloudStatus.text = if (isHoyolab) "正在单独更新 HoYoLAB 函数…" else "正在单独更新森空岛函数…"
        showOperationMessage(if (isHoyolab) "HoYoLAB 更新中" else "森空岛更新中", "正在准备部署包并提交到华为云…", null)
        worker.execute {
            try {
                val created = if (isHoyolab) {
                    val archive = replaceZip(
                        readPackagedZip("cloud/hoyolab.zip"),
                        "config/config.yaml",
                        configYaml
                    )
                    createOrUpdateFunction(HOYOLAB_FUNCTION, HOYOLAB_HANDLER, archive)
                } else {
                    val token = SklandTokenVault(this).latest()
                        ?.takeIf { it.isNotBlank() }
                        ?: error("尚未获取森空岛 Token。请先登录森空岛并完成 Token 获取。")
                    val archive = replaceZip(
                        readPackagedZip("cloud/skland.zip"),
                        "code/INPUT_HYPERGRYPH_TOKEN.txt",
                        token.trim() + "\n"
                    )
                    createOrUpdateFunction(SENKONGDAO_FUNCTION, SENKONGDAO_HANDLER, archive)
                }
                runOnUiThread {
                    val label = if (isHoyolab) "HoYoLAB" else "森空岛"
                    val action = if (created) "创建" else "更新"
                    cloudStatus.text = label + " 函数" + action + "成功。\n本次仅提交了 " + label + " 函数，没有触碰另一个服务。"
                    updateButton.isEnabled = true
                    showOperationMessage("$label 函数${action}成功", "本次仅提交了 $label 函数，没有触碰另一个服务。", true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    cloudStatus.text = (if (isHoyolab) "HoYoLAB" else "森空岛") +
                        " 更新失败：" + (e.message ?: e.javaClass.simpleName) +
                        "\n另一个服务不会受到影响。"
                    updateButton.isEnabled = true
                    showOperationMessage(if (isHoyolab) "HoYoLAB 更新失败" else "森空岛更新失败", e.message ?: e.javaClass.simpleName, false)
                }
            }
        }
    }

    private fun messageChannel(): String =
        if (panelService == "skland") AppMessageBus.SKLAND else AppMessageBus.HOYOLAB

    protected fun showOperationMessage(title: String, detail: String, success: Boolean?) {
        AppMessageBus.post(this, messageChannel(), title, detail, success)
        lastResultMessage = AppMessageBus.current(this, messageChannel())
        if (::resultMessage.isInitialized) {
            resultMessage.text = (when (success) { true -> "✓ "; false -> "✕ "; else -> "… " }) + lastResultMessage
            resultMessage.setTextColor(when (success) {
                true -> 0xFF75D69C.toInt()
                false -> 0xFFFF8A80.toInt()
                else -> 0xFFE1E6EF.toInt()
            })
        }
        if (::copyResultButton.isInitialized) copyResultButton.isEnabled = true
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
                .put("enable_lts_log", true)
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
        return JSONObject(response.second)
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
        private const val SENKONGDAO_FUNCTION = "SENKONGDAO"
        private const val HOYOLAB_HANDLER = "index.main_handler"
        private const val SENKONGDAO_HANDLER = "index.handler"
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
