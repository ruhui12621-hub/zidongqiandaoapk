package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class HuaweiCloudActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var region: EditText
    private lateinit var ak: EditText
    private lateinit var sk: EditText
    private lateinit var status: TextView
    private lateinit var functionSpinner: Spinner
    private var functions = listOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 28, 36, 24) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        content.addView(TextView(this).apply { text = "华为云 FunctionGraph"; textSize = 23f; setTextColor(0xff111827.toInt()) })
        content.addView(TextView(this).apply { text = "第一阶段：AK/SK 签名登录、自动识别项目并读取函数列表。"; textSize = 14f; setPadding(0, 10, 0, 18) })
        region = field(content, "Region", "cn-east-3")
        ak = field(content, "AK", "")
        sk = field(content, "SK", "", true)
        content.addView(Button(this).apply { text = "登录并读取函数列表"; isAllCaps = false; setOnClickListener { login() } })
        functionSpinner = Spinner(this)
        content.addView(functionSpinner)
        status = TextView(this).apply { text = "凭据仅在本次页面运行期间使用，不会写入配置文件。"; textSize = 13f; setPadding(0, 16, 0, 10) }
        content.addView(status)
        content.addView(Button(this).apply { text = "返回 Cookie 页面"; isAllCaps = false; setOnClickListener { finish() } })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun field(parent: LinearLayout, label: String, value: String, secret: Boolean = false): EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 14f; setPadding(0, 8, 0, 2) })
        return EditText(this).also { edit ->
            edit.setSingleLine(true); edit.setText(value)
            if (secret) edit.inputType = 129
            parent.addView(edit, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun login() {
        val r = region.text.toString().trim()
        val a = ak.text.toString().trim()
        val s = sk.text.toString()
        if (r.isBlank() || a.isBlank() || s.isBlank()) { status.text = "请填写 Region、AK 和 SK。"; return }
        status.text = "正在验证 AK/SK 并读取项目、函数列表…"
        worker.execute {
            try {
                val projects = signedGet("https://iam.$r.myhuaweicloud.com/v3/projects", a, s)
                val rows = projects.optJSONArray("projects") ?: error("IAM 未返回项目列表")
                var projectId: String? = null
                for (i in 0 until rows.length()) {
                    val p = rows.getJSONObject(i)
                    if (p.optString("name").equals(r, true)) { projectId = p.optString("id"); break }
                }
                val pid = projectId ?: error("AK/SK 认证已响应，但没有找到 Region=$r 对应的项目。")
                val endpoint = "https://functiongraph.$r.myhuaweicloud.com/v2/$pid/fgs/functions?maxitems=100"
                val result = signedGet(endpoint, a, s)
                val list = result.optJSONArray("functions")
                val found = mutableListOf<Pair<String, String>>()
                if (list != null) for (i in 0 until list.length()) {
                    val f = list.getJSONObject(i)
                    val name = f.optString("func_name")
                    val urn = f.optString("func_urn")
                    if (name.isNotBlank() && urn.isNotBlank()) found.add(name to urn)
                }
                runOnUiThread {
                    functions = found
                    val names = found.map { it.first }.distinct()
                    functionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
                    val preferred = names.indexOf("zzzzz")
                    if (preferred >= 0) functionSpinner.setSelection(preferred)
                    status.text = "登录成功\nRegion：$r\nProject ID：$pid\n读取到 ${found.size} 个函数。默认优先选择 zzzzz。"
                    ak.text.clear(); sk.text.clear()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "登录/查询失败：${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun signedGet(urlText: String, access: String, secret: String): JSONObject {
        val url = URL(urlText)
        val date = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        val host = url.host
        val canonicalUri = if (url.path.endsWith("/")) url.path else url.path + "/"
        val query = url.query.orEmpty().split("&").filter { it.isNotEmpty() }.map {
            val parts = it.split("=", limit = 2)
            encode(parts[0]) + "=" + encode(parts.getOrElse(1) { "" })
        }.sorted().joinToString("&")
        val signedHeaders = "host;x-sdk-date"
        val canonicalHeaders = "host:$host\nx-sdk-date:$date\n"
        val payloadHash = sha256("")
        val canonical = "GET\n$canonicalUri\n$query\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val canonicalHash = sha256(canonical)
        val toSign = "SDK-HMAC-SHA256\n$date\n$canonicalHash"
        val signature = hmac(secret, toSign)
        val authorization = "SDK-HMAC-SHA256 Access=$access, SignedHeaders=$signedHeaders, Signature=$signature"
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20000; readTimeout = 45000
            setRequestProperty("X-Sdk-Date", date); setRequestProperty("Authorization", authorization); setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code：$raw")
            return JSONObject(raw)
        } finally { conn.disconnect() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun hmac(key: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
