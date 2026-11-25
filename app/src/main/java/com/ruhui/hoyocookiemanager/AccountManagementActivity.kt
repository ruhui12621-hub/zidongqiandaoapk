package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AccountManagementActivity : Activity() {
    private val store by lazy { AccountStore(this) }
    private val gameLabels = linkedMapOf(
        "genshin" to "原神", "honkai2" to "崩坏2", "honkai3rd" to "崩坏3",
        "tears_of_themis" to "未定事件簿", "honkai_sr" to "崩坏：星穹铁道",
        "zzz" to "绝区零", "arknights" to "明日方舟"
    )
    private var accountId: String? = null
    private var nameInput: EditText? = null
    private val checks = linkedMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountId = intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)
        if (accountId.isNullOrBlank()) {
            Toast.makeText(this, "请先从主界面选择要添加的平台", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (accountId != null) render()
    }

    private fun render() {
        val account = accountId?.let { store.find(it) }
        if (account == null) {
            Toast.makeText(this, "账号不存在或已删除", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        checks.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹ 返回", compact = true) { finish() })
        header.addView(text("账号设置", 21, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        root.addView(header)
        root.addView(text(AccountStore.platformLabel(account.platform), 13, false, 0xFF9BA7B8.toInt()).apply {
            setPadding(dp(4), dp(5), 0, dp(14))
        })

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(sectionCard {
            addView(text("账号备注名称", 14, true))
            nameInput = EditText(this@AccountManagementActivity).apply {
                setText(account.name)
                setSingleLine(true)
                hint = "例如：主账号 / 小号"
                setTextColor(0xFFECEFF5.toInt())
                setHintTextColor(0xFF778397.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF657187.toInt())
                textSize = 16f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        })

        val identity = when {
            account.credentialReady && account.platformUserId != null -> "凭据已保存 · 平台 ID 已识别"
            account.credentialReady -> "凭据已保存 · 使用本地唯一 ID"
            account.platformUserId != null -> "平台 ID 已识别 · 等待登录"
            else -> "尚未登录 · 使用本地唯一 ID"
        }
        content.addView(sectionCard {
            addView(text("登录凭据", 14, true))
            addView(text(identity, 13, false, if (account.credentialReady) 0xFF8BD5A7.toInt() else 0xFFFFCC80.toInt()).apply {
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(text(
                if (account.platform == AccountStore.SKLAND) "森空岛 Token 会按此账号独立加密保存。"
                else "HoYoLAB 与米游社目前仍共用 WebView 登录会话。登录前请确认网页中的账号正确。",
                12, false, 0xFF9BA7B8.toInt()
            ).apply { setPadding(0, dp(3), 0, 0) })
            addView(button("登录 / 更新凭据") {
                saveAccount(account, showToast = false)
                val target = if (account.platform == AccountStore.SKLAND) SenkongdaoActivity::class.java else MainActivity::class.java
                startActivity(Intent(this@AccountManagementActivity, target)
                    .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
                    .putExtra(AccountStore.EXTRA_PLATFORM, account.platform))
            })
        })

        content.addView(sectionCard {
            addView(text("签到游戏", 15, true))
            addView(text("勾选此账号需要执行签到的游戏。不同账号的选择互不影响。", 12, false, 0xFF9BA7B8.toInt()).apply {
                setPadding(0, dp(5), 0, dp(8))
            })
            val allowed = gameLabels.filterKeys { key ->
                when (account.platform) {
                    AccountStore.HOYOLAB -> key != "honkai2" && key != "arknights"
                    AccountStore.MIYOUSHE_CN -> key != "arknights"
                    AccountStore.SKLAND -> key == "arknights"
                    else -> false
                }
            }
            allowed.forEach { (key, label) ->
                val check = CheckBox(this@AccountManagementActivity).apply {
                    text = label
                    textSize = 15f
                    setTextColor(0xFFECEFF5.toInt())
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFF8EA4C7.toInt())
                    isChecked = account.games.contains(key)
                    setPadding(0, dp(5), 0, dp(5))
                }
                checks[key] = check
                addView(check)
            }
            if (allowed.isEmpty()) addView(text("此平台暂无可配置游戏。", 13, false, 0xFF9BA7B8.toInt()))
        })

        root.addView(button("保存账号设置") { saveAccount(account, showToast = true) })
        root.addView(button("删除此账号", danger = true) {
            AlertDialog.Builder(this)
                .setTitle("删除账号？")
                .setMessage("将删除「" + account.name + "」及其本地加密凭据，此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除") { _, _ ->
                    runCatching {
                        when (account.platform) {
                            AccountStore.SKLAND -> SklandTokenVault(this, account.id).delete()
                            AccountStore.HOYOLAB, AccountStore.MIYOUSHE_CN -> HoYoCookieVault(this, account.id).delete()
                        }
                    }
                    store.delete(account.id)
                    Toast.makeText(this, "账号已删除", Toast.LENGTH_SHORT).show()
                    finish()
                }.show()
        })
        setContentView(root)
    }

    private fun saveAccount(account: ManagedAccount, showToast: Boolean) {
        val name = nameInput?.text?.toString()?.trim().orEmpty().ifBlank { account.name }
        val games = checks.filterValues { it.isChecked }.keys
        runCatching { store.update(account.copy(name = name, games = games)) }
            .onSuccess { if (showToast) Toast.makeText(this, "账号设置已保存", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
    }

    private fun sectionCard(content: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = GradientDrawable().apply {
            setColor(0xFF202631.toInt())
            cornerRadius = dp(14).toFloat()
        }
        content()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    private fun button(label: String, compact: Boolean = false, danger: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = if (compact) 13f else 15f
            setTextColor(Color.WHITE)
            minHeight = if (compact) dp(40) else dp(48)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (danger) Color.rgb(125, 48, 55) else Color.rgb(39, 46, 60)
            )
            setOnClickListener { action() }
            if (!compact) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }

    private fun text(value: String, size: Int, bold: Boolean, color: Int = 0xFFECEFF5.toInt()) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
