package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Base64
import android.widget.*
import com.obs.services.ObsClient
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
    private lateinit var region: EditText
    private lateinit var ak: EditText
    private lateinit var sk: EditText
    private lateinit var cloudStatus: TextView
    private lateinit var spinner: Spinner
    private lateinit var updateButton: Button
    private lateinit var logsButton: Button
    private lateinit var logsStatus: TextView
    private lateinit var logsOutput: TextView
    private lateinit var logsRange: Spinner
    private var functions = listOf<Pair<String, String>>()
    private var authAk = ""
    private var authSk = ""
    private var regionValue = "cn-east-3"
    private var projectId = ""
    private var configYaml = ""
    private var configProvider: (() -> String)? = null
    private var preparedArchive: ByteArray? = null
    private var preparedCode = JSONObject()
    private var preparedUrn = ""
    private var preparedName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    protected fun addHuaweiCloudPanel(parent: LinearLayout, configProvider: () -> String) {
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
        region = field(content, "Region", HuaweiCloudSession.region.ifBlank { "cn-east-3" })
        ak = field(content, "AK", HuaweiCloudSession.ak)
        sk = field(content, "SK", HuaweiCloudSession.sk, true)
        content.addView(Button(this).apply {
            text = "登录并读取函数列表"; isAllCaps = false; setOnClickListener { login() }
        })
        content.addView(TextView(this).apply {
            text = "目标云函数（默认 zzzzz）"
            setTextColor(0xFFE1E6EF.toInt())
            setPadding(0, dp(18), 0, dp(6))
        })
        spinner = Spinner(this)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                spinner.selectedItem?.toString()?.let { HuaweiCloudSession.selectedFunction = it }
            }
        }
        content.addView(spinner)
        content.addView(TextView(this).apply {
            text = if (configYaml.isBlank()) "等待上方 HoYoLAB 登录完成并获取 Cookie。"
            else "已检测到 Cookie，可以更新云端配置。"
            setTextColor(0xFFA7B0C0.toInt())
            setPadding(0, dp(12), 0, dp(8))
        })
        updateButton = Button(this).apply {
            text = "一键更新云函数配置"; isAllCaps = false; isEnabled = false
            setOnClickListener { prepareUpdate() }
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
            text = "读取当前选中函数的运行输出和错误信息，不会修改云函数。"
            textSize = 13f
            setTextColor(0xFFA7B0C0.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        logsRange = Spinner(this)
        logsRange.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("最近 1 小时", "最近 24 小时", "最近 3 天"))
        content.addView(logsRange)
        logsButton = Button(this).apply {
            text = "读取运行日志"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { fetchFunctionLogs() }
        }
        content.addView(logsButton)
        logsStatus = TextView(this).apply {
            text = "登录华为云后，选择函数即可读取对应运行日志。"
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
        content.addView(logsOutput, LinearLayout.LayoutParams(-1, dp(260)))
        cloudStatus = TextView(this).apply {
            text = "尚未连接华为云。AK/SK 仅保留在当前进程内存中。"
            textSize = 13f; setTextColor(0xFFB8C1D1.toInt()); setPadding(0, dp(16), 0, dp(10))
        }
        content.addView(cloudStatus)
        if (HuaweiCloudSession.isLoggedIn()) {
            projectId = HuaweiCloudSession.projectId
            regionValue = HuaweiCloudSession.region
            authAk = HuaweiCloudSession.ak
            authSk = HuaweiCloudSession.sk
            functions = HuaweiCloudSession.functions
            val names = functions.map { it.first }.distinct()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            val preferred = names.indexOf(HuaweiCloudSession.selectedFunction).takeIf { it >= 0 }
                ?: names.indexOf("zzzzz")
            if (preferred >= 0) spinner.setSelection(preferred)
            cloudStatus.text = "华为云登录状态：已保持\nRegion：$regionValue\nProject ID：$projectId\n读取到 ${functions.size} 个函数。"
            updateButton.isEnabled = names.isNotEmpty() && configYaml.isNotBlank()
            logsButton.isEnabled = names.isNotEmpty()
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
        val r = region.text.toString().trim().lowercase(Locale.ROOT)
        val a = ak.text.toString().trim()
        val s = sk.text.toString()
        if (r.isBlank() || a.isBlank() || s.isBlank()) { cloudStatus.text = "请填写 Region、AK 和 SK。"; return }
        regionValue = r; authAk = a; authSk = s
        cloudStatus.text = "正在验证 AK/SK 并读取项目和函数列表…"
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
                val data = signedGet("https://functiongraph." + r + ".myhuaweicloud.com/v2/" + pid + "/fgs/functions?maxitems=100")
                val list = data.optJSONArray("functions")
                val found = mutableListOf<Pair<String, String>>()
                if (list != null) for (i in 0 until list.length()) {
                    val f = list.getJSONObject(i)
                    val n = f.optString("func_name"); val u = f.optString("func_urn")
                    if (n.isNotBlank() && u.isNotBlank()) found.add(n to u)
                }
                runOnUiThread {
                    projectId = pid; functions = found
                    HuaweiCloudSession.ak = authAk
                    HuaweiCloudSession.sk = authSk
                    HuaweiCloudSession.region = regionValue
                    HuaweiCloudSession.projectId = pid
                    HuaweiCloudSession.functions = found
                    val names = found.map { it.first }.distinct()
                    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
                    val preferred = names.indexOf("zzzzz")
                    if (preferred >= 0) spinner.setSelection(preferred)
                    cloudStatus.text = "华为云连接成功\nRegion：" + r + "\nProject ID：" + pid +
                        "\n读取到 " + found.size + " 个函数。" +
                        if (configYaml.isBlank()) "\n但未检测到 Cookie 配置，请返回首页重新获取。" else "\n已自动接收 Cookie 配置，可以一键更新。"
                    updateButton.isEnabled = names.isNotEmpty() && configYaml.isNotBlank()
                    logsButton.isEnabled = names.isNotEmpty()
                }
            } catch (e: Exception) {
                runOnUiThread { cloudStatus.text = "登录/查询失败：" + (e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun fetchFunctionLogs() {
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) {
            logsStatus.text = "请先登录华为云。"
            return
        }
        val name = spinner.selectedItem?.toString().orEmpty()
        val selected = functions.firstOrNull { it.first == name }
        if (selected == null) { logsStatus.text = "请先从上方函数列表选择函数。"; return }
        logsButton.isEnabled = false
        logsStatus.text = "正在查询 $name 的运行日志…"
        logsOutput.text = "正在连接华为云 LTS…"
        val hours = when (logsRange.selectedItemPosition) { 1 -> 24; 2 -> 72; else -> 1 }
        worker.execute {
            try {
                val detailUrl = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" + projectId + "/fgs/functions/" + enc(selected.second) + "/lts-log-detail"
                val detail = signedGet(detailUrl)
                val groupId = detail.optString("group_id")
                val streamId = detail.optString("stream_id")
                if (groupId.isBlank() || streamId.isBlank()) error("华为云没有返回该函数的 LTS 日志组/日志流。请检查函数是否已开启日志记录。")
                val now = System.currentTimeMillis()
                val body = JSONObject().put("start_time", (now - hours * 60L * 60L * 1000L).toString()).put("end_time", now.toString()).put("limit", 50).put("is_desc", true).put("search_type", "init").put("is_count", true)
                val queryUrl = "https://lts." + regionValue + ".myhuaweicloud.com/v2/" + projectId + "/groups/" + enc(groupId) + "/streams/" + enc(streamId) + "/content/query"
                val response = signedRequest("POST", queryUrl, body.toString())
                if (response.first !in 200..299) error("LTS 查询失败（HTTP " + response.first + "）：" + response.second.take(1600))
                val rows = JSONObject(response.second).optJSONArray("logs")
                val rendered = StringBuilder()
                if (rows == null || rows.length() == 0) { rendered.append("所选时间范围内没有查询到日志。\n提示：函数日志上报可能有几分钟延迟。") }
                else for (i in 0 until rows.length()) {
                    val item = rows.optJSONObject(i) ?: continue
                    if (rendered.isNotEmpty()) rendered.append("\n\n")
                    rendered.append(item.optString("content", "(空日志)"))
                }
                val count = rows?.length() ?: 0
                runOnUiThread {
                    logsStatus.text = "函数：$name    时间范围：最近 ${hours} 小时    本次读取：$count 条"
                    logsOutput.text = rendered.toString()
                    logsButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logsStatus.text = "读取日志失败：" + (e.message ?: e.javaClass.simpleName)
                    logsOutput.text = "请检查 LTS 是否已启用，以及当前 AK/SK 是否具有 FunctionGraph 日志配置读取和 LTS 日志查询权限。"
                    logsButton.isEnabled = true
                }
            }
        }
    }

    private fun prepareUpdate() {
        configYaml = configProvider?.invoke().orEmpty()
        HuaweiCloudSession.lastConfigYaml = configYaml
        spinner.selectedItem?.toString()?.let { HuaweiCloudSession.selectedFunction = it }
        if (projectId.isBlank() || authAk.isBlank() || authSk.isBlank()) { cloudStatus.text = "请先登录华为云。"; return }
        if (configYaml.isBlank() || !configYaml.contains("cookie:")) { cloudStatus.text = "没有有效的 Cookie 配置，请先在上方 HoYoLAB 登录。"; return }
        val name = spinner.selectedItem?.toString().orEmpty()
        val selected = functions.firstOrNull { it.first == name }
        if (selected == null) { cloudStatus.text = "请从云端函数列表选择目标函数。"; return }
        updateButton.isEnabled = false
        cloudStatus.text = "正在精确核对函数、下载原 ZIP 并替换目标配置…"
        worker.execute {
            try {
                val q = "func_name=" + enc(name) + "&precise_search=true&maxitems=10"
                val exact = signedGet("https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" + projectId + "/fgs/functions?" + q)
                    .optJSONArray("functions") ?: error("精确查询没有返回函数列表")
                val matches = (0 until exact.length()).map { exact.getJSONObject(it) }.filter { it.optString("func_name") == name }
                if (matches.size != 1) error("函数名精确匹配结果不是唯一项，已停止更新。")
                val urn = matches[0].optString("func_urn")
                if (urn.isBlank() || urn != selected.second) error("函数 URN 与云端列表不一致，已停止更新。")
                val code = signedGet("https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" +
                    projectId + "/fgs/functions/" + enc(urn) + "/code")
                val original = getArchive(code)
                val updated = replaceZip(original, "config/config.yaml", configYaml)
                runOnUiThread {
                    preparedArchive = updated; preparedCode = code; preparedUrn = urn; preparedName = name
                    confirmUpdate(original.size, updated.size)
                }
            } catch (e: Exception) {
                runOnUiThread { cloudStatus.text = "准备更新失败：" + (e.message ?: e.javaClass.simpleName); updateButton.isEnabled = true }
            }
        }
    }

    private fun confirmUpdate(oldSize: Int, newSize: Int) {
        val density = resources.displayMetrics.density
        val previewHeight = (300 * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(),
                (20 * density).toInt(), 0)
        }
        panel.addView(TextView(this).apply {
            text = "目标函数：" + preparedName +
                "\n目标文件：config/config.yaml" +
                "\n原代码包：" + oldSize + " bytes" +
                "\n更新后代码包：" + newSize + " bytes" +
                "\n\n确认后将上传完整 ZIP；ZIP 中只有 config/config.yaml 会被替换。"
            textSize = 14f
            setPadding(0, 0, 0, (10 * density).toInt())
        })
        val preview = ScrollView(this).apply {
            isFillViewport = true
            addView(TextView(this@HuaweiCloudActivity).apply {
                text = configYaml
                textSize = 12f
                setTextIsSelectable(true)
                setPadding((12 * density).toInt(), (12 * density).toInt(),
                    (12 * density).toInt(), (12 * density).toInt())
            })
        }
        panel.addView(preview, LinearLayout.LayoutParams(-1, previewHeight))
        AlertDialog.Builder(this)
            .setTitle("确认更新云函数？")
            .setView(panel)
            .setNegativeButton("取消") { _, _ ->
                preparedArchive = null
                cloudStatus.text = "已取消，云端没有修改。"
                updateButton.isEnabled = true
            }
            .setPositiveButton("确认上传并更新") { _, _ -> uploadPrepared() }
            .show()
    }

    private fun uploadPrepared() {
        val archive = preparedArchive ?: run { cloudStatus.text = "待上传代码包已失效，请重新准备。"; updateButton.isEnabled = true; return }
        updateButton.isEnabled = false; cloudStatus.text = "正在上传更新 FunctionGraph，请不要关闭页面…"
        worker.execute {
            try {
                val payload = JSONObject()
                payload.put("code_filename", preparedCode.optString("code_filename").ifBlank { "index.zip" })
                payload.put("code_type", "zip")
                payload.put("func_code", JSONObject().put("file", Base64.encodeToString(archive, Base64.NO_WRAP)))
                if (preparedCode.has("depend_list")) payload.put("depend_list", preparedCode.optJSONArray("depend_list"))
                if (preparedCode.has("depend_version_list")) payload.put("depend_version_list", preparedCode.optJSONArray("depend_version_list"))
                val url = "https://functiongraph." + regionValue + ".myhuaweicloud.com/v2/" + projectId +
                    "/fgs/functions/" + enc(preparedUrn) + "/code"
                val response = signedRequest("PUT", url, payload.toString())
                if (response.first !in 200..299) error("FunctionGraph 更新失败（HTTP " + response.first + "）：" + response.second.take(1800))
                runOnUiThread {
                    cloudStatus.text = "更新成功！\n函数：" + preparedName +
                        "\n文件：config/config.yaml\n只替换了该配置文件，ZIP 其他内容已保留。"
                    preparedArchive = null; updateButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread { cloudStatus.text = "上传更新失败：" + (e.message ?: e.javaClass.simpleName); updateButton.isEnabled = true }
            }
        }
    }

    private fun getArchive(code: JSONObject): ByteArray {
        val inline = code.optJSONObject("func_code")?.optString("file").orEmpty()
        if (inline.isNotBlank()) {
            val bytes = Base64.decode(inline, Base64.DEFAULT)
            if (isZip(bytes)) return bytes
        }
        val link = code.optJSONObject("func_code")?.optString("link")
            ?.takeIf { it.isNotBlank() } ?: code.optString("code_url")
        if (link.isBlank()) error("华为云没有返回 ZIP 文件或下载链接，代码类型：" + code.optString("code_type"))
        val uri = URL(link)
        if (uri.protocol != "https") error("代码包下载链接不是 HTTPS，已拒绝访问。")
        val match = Regex("^([a-z0-9-]+)\\.(obs\\..+)$", RegexOption.IGNORE_CASE).matchEntire(uri.host)
            ?: error("无法识别 OBS 代码包地址：" + uri.host)
        val bucket = match.groupValues[1]
        val endpoint = "https://" + match.groupValues[2]
        val key = java.net.URLDecoder.decode(uri.path.removePrefix("/"), "UTF-8")
        if (key.isBlank()) error("OBS 对象路径为空。")
        val client = ObsClient(authAk, authSk, endpoint)
        try {
            val input = client.getObject(bucket, key).objectContent
            val out = ByteArrayOutputStream()
            input.use { stream ->
                val buffer = ByteArray(8192); var total = 0
                while (true) {
                    val n = stream.read(buffer); if (n < 0) break
                    total += n; if (total > 50 * 1024 * 1024) error("函数代码包超过 50 MB 限制。")
                    out.write(buffer, 0, n)
                }
            }
            val bytes = out.toByteArray()
            if (!isZip(bytes)) error("OBS 下载内容不是有效 ZIP 文件。")
            return bytes
        } finally { try { client.close() } catch (_: Exception) {} }
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

    override fun onDestroy() {
        worker.shutdownNow()
        preparedArchive = null
        super.onDestroy()
    }
}
