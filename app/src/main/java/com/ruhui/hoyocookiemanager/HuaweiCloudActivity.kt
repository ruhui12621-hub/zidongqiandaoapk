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
    private lateinit var logsButton: Button
    private lateinit var logsStatus: TextView
    private lateinit var logsOutput: TextView
    private var functions = listOf<Pair<String, String>>()
    private var authAk = ""
    private var authSk = ""
    private var regionValue = "cn-east-3"
    private val regionCodes = listOf("cn-east-3", "cn-east-2", "cn-north-4", "cn-north-1", "cn-south-1", "ap-southeast-1", "ap-southeast-3")
    private val regionLabels = listOf("华东-上海一（默认）", "华东-上海二", "华北-北京四", "华北-北京一", "华南-广州", "中国-香港", "亚太-新加坡")
    private var projectId = ""
    private var configYaml = ""
    private var configProvider: (() -> String)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    protected fun addHuaweiCloudPanel(parent: LinearLayout, configProvider: () -> String, onConfigure: () -> Unit) {
        this.configProvider = configProvider
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
            text = "直接使用 APK 获取的 Cookie 更新云端配置，无需复制粘贴。"
            textSize = 14f; setTextColor(0xFFA7B0C0.toInt()); setPadding(0, dp(8), 0, dp(16))
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
                    HuaweiCloudSession.functions = emptyList()
                    projectId = ""
                    functions = emptyList()
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
        content.addView(Button(this).apply {
            text = "登录并读取函数列表"; isAllCaps = false; setOnClickListener { login() }
        })
        content.addView(Button(this).apply {
            text = "本地保管箱 · 自动保存 / 管理"
            isAllCaps = false
            setOnClickListener { openCredentialVault() }
        })
        content.addView(TextView(this).apply {
            text = if (configYaml.isBlank()) "等待上方 HoYoLAB 登录完成并获取 Cookie。"
            else "已检测到 Cookie，可以更新云端配置。"
            setTextColor(0xFFA7B0C0.toInt())
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(Button(this).apply {
            text = "配置选择"; isAllCaps = false
            setOnClickListener { onConfigure() }
        })
        updateButton = Button(this).apply {
            text = "一键部署 / 更新云函数"; isAllCaps = false; isEnabled = false
            setOnClickListener { deployPackages() }
        }
        content.addView(updateButton)
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
        cloudStatus = TextView(this).apply {
            text = "尚未连接华为云。AK/SK 仅保留在当前进程内存中。"
            textSize = 13f; setTextColor(0xFFB8C1D1.toInt()); setPadding(0, dp(16), 0, dp(10))
        }
        content.addView(cloudStatus)
        if (HuaweiCloudSession.isLoggedIn()) {
            projectId = HuaweiCloudSession.projectId
            regionValue = HuaweiCloudSession.region
            region.setSelection(regionCodes.indexOf(regionValue).takeIf { it >= 0 } ?: 0)
            authAk = HuaweiCloudSession.ak
            authSk = HuaweiCloudSession.sk
            functions = HuaweiCloudSession.functions
            cloudStatus.text = "华为云登录状态：已保持\nRegion：$regionValue\nProject ID：$projectId"
            updateButton.isEnabled = configYaml.isNotBlank()
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


    private fun openCredentialVault() {
        val vault = AndroidCredentialVault(this)
        val count = runCatching { vault.latest(); if (vault.exists()) "已保存，最多保留 10 组 AK/SK。" else "尚未保存；首次登录成功后会自动创建。" }
            .getOrElse { "保管箱读取异常：" + (it.message ?: "未知错误") }
        val builder = AlertDialog.Builder(this)
            .setTitle("本地 AK/SK 保管箱")
            .setMessage(count + "\n\n凭据仅保存在本机应用私有目录，使用 Android Keystore + AES-256-GCM 加密。每次打开页面自动填入最近使用的一组，SK 始终以星号显示。")
            .setPositiveButton("知道了", null)
        if (vault.exists()) builder.setNegativeButton("删除全部凭据") { _, _ -> confirmDeleteVault(vault) }
        builder.show()
    }

    private fun confirmDeleteVault(vault: AndroidCredentialVault) {
        AlertDialog.Builder(this)
            .setTitle("删除本地密码保管箱？")
            .setMessage("将删除加密凭据文件并销毁 Android Keystore 对应密钥。此操作不可撤销，不会删除当前已登录的内存会话。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                try {
                    vault.delete()
                    Toast.makeText(this, "本地密码保管箱已删除。", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "删除失败：" + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun login() {
        val r = regionCodes.getOrElse(region.selectedItemPosition) { "cn-east-3" }
        val a = ak.text.toString().trim()
        val s = sk.text.toString()
        if (r.isBlank() || a.isBlank() || s.isBlank()) { cloudStatus.text = "请填写 Region、AK 和 SK。"; return }
        regionValue = r; authAk = a; authSk = s
        HuaweiCloudSession.region = r
        getSharedPreferences("app_settings", MODE_PRIVATE).edit().putString("cloud_region", r).apply()
        cloudStatus.text = "正在验证 AK/SK 并连接华为云…"
        updateButton.isEnabled = false
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
                val found = mutableListOf<Pair<String, String>>()
                var vaultWarning = ""
                try {
                    AndroidCredentialVault(this).save(a, s)
                } catch (vaultError: Exception) {
                    vaultWarning = "\\n但本次凭据未能写入本地保管箱：" + (vaultError.message ?: vaultError.javaClass.simpleName)
                }
                runOnUiThread {
                    projectId = pid; functions = found
                    HuaweiCloudSession.ak = authAk
                    HuaweiCloudSession.sk = authSk
                    HuaweiCloudSession.region = regionValue
                    HuaweiCloudSession.projectId = pid
                    HuaweiCloudSession.functions = emptyList()
                    cloudStatus.text = "华为云连接成功\nRegion：" + r + "\nProject ID：" + pid +
                        (if (configYaml.isBlank()) "\n等待获取 HoYoLAB Cookie。" else "\n已接收当前 Cookie，可以一键部署两个服务。") + vaultWarning
                    updateButton.isEnabled = configYaml.isNotBlank()
                    logsButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread { cloudStatus.text = "登录/查询失败：" + (e.message ?: e.javaClass.simpleName) }
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
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logsStatus.text = "读取日志失败：" + (e.message ?: e.javaClass.simpleName)
                    logsOutput.text = "请检查 FunctionGraph 日志配置与 LTS 查询权限。"
                    logsButton.isEnabled = true
                }
            }
        }
    }

    private fun deployPackages() {
        configYaml = configProvider?.invoke().orEmpty()
        HuaweiCloudSession.lastConfigYaml = configYaml
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            cloudStatus.text = "请先登录华为云。"; return
        }
        if (configYaml.isBlank() || !configYaml.contains("cookie:")) {
            cloudStatus.text = "没有有效的 HoYoLAB Cookie 配置，请先完成登录。"; return
        }
        updateButton.isEnabled = false
        cloudStatus.text = "正在准备 APK 内置部署包，并写入当前 Cookie…"
        worker.execute {
            try {
                val hoyolabOriginal = readPackagedZip("cloud/hoyolab.zip")
                val sklandArchive = readPackagedZip("cloud/skland.zip")
                val hoyolabArchive = replaceZip(hoyolabOriginal, "config/config.yaml", configYaml)
                runOnUiThread { cloudStatus.text = "正在直接上传两个服务，请不要关闭页面…" }
                val hoyolabCreated = createOrUpdateFunction(HOYOLAB_FUNCTION, HOYOLAB_HANDLER, hoyolabArchive)
                runOnUiThread { cloudStatus.text = "HoYoLAB 部署完成，正在部署森空岛…" }
                val sklandCreated = createOrUpdateFunction(SENKONGDAO_FUNCTION, SENKONGDAO_HANDLER, sklandArchive)
                runOnUiThread {
                    cloudStatus.text = "两个服务部署完成。\nHoYoLAB：" +
                        (if (hoyolabCreated) "已创建" else "已更新") +
                        "\n森空岛：" + (if (sklandCreated) "已创建" else "已更新") +
                        "\n当前 Cookie 已写入 HoYoLAB 配置。"
                    updateButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    cloudStatus.text = "部署失败：" + (e.message ?: e.javaClass.simpleName) +
                        "\n已完成的服务不会回滚；再次点击会继续更新。"
                    updateButton.isEnabled = true
                }
            }
        }
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
                .put("timeout", 300)
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
            requestMethod = method; connectTimeout = 30000; readTimeout = if (method == "PUT") 180000 else 90000
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
        private const val SENKONGDAO_FUNCTION = "SENKONGDAO20260122"
        private const val HOYOLAB_HANDLER = "index.handler"
        private const val SENKONGDAO_HANDLER = "index.handler"
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
