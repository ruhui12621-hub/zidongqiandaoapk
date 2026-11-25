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
        "zzz" to "绝区零", "arknights" to "明日方舟", "endfield" to "明日方舟：终末地"
    )
    private var accountId: String? = null
    private var firstResume = true
    private var nameInput: EditText? = null
    private val checks = linkedMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountId = intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)
        if (!CloudAccountSyncState.allowsAccountChanges(this)) {
            Toast.makeText(this, "请先成功读取华为云配置；当前不允许使用未同步的本地账号。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (accountId.isNullOrBlank()) {
            Toast.makeText(this, "请先从主界面选择要添加的平台", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) firstResume = false else if (accountId != null) render()
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
            addView(text(account.name, 16, false, 0xFFECEFF5.toInt()).apply {
                setPadding(0, dp(8), 0, dp(4))
            })
        })

        content.addView(sectionCard {
            addView(text("凭据获取状态", 14, true))
            if (account.platform == AccountStore.HOYOLAB || account.platform == AccountStore.MIYOUSHE_CN) {
                val isCn = account.platform == AccountStore.MIYOUSHE_CN
                val cookieReady = account.cookieReady
                val tokenReady = account.tokenReady
                val region = if (isCn) "国服（米游社）" else "国际服（HoYoLAB）"
                addView(text("账号游戏区：" + region, 13, true, 0xFFB9C9E5.toInt()).apply {
                    setPadding(0, dp(8), 0, dp(8))
                })
                addView(text("Cookie：" + if (cookieReady) "已获取" else "未获取", 14, false,
                    if (cookieReady) 0xFF8BD5A7.toInt() else 0xFFFFCC80.toInt()).apply { setPadding(0, dp(3), 0, dp(3)) })
                if (!account.games.contains("zzz")) {
                    addView(text("Token：" + if (tokenReady) "已获取" else "未获取", 14, false,
                        if (tokenReady) 0xFF8BD5A7.toInt() else 0xFFFFCC80.toInt()).apply { setPadding(0, dp(3), 0, dp(3)) })
                }
                addView(text("状态来自最近一次成功读取的云端配置，不会把另一游戏区的登录状态算作成功。", 12, false, 0xFF9BA7B8.toInt()).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                addView(button("打开 " + (if (isCn) "米游社" else "HoYoLAB") + " 网页") {
                    openHoyoLogin(account)
                })
            } else {
                val tokenReady = runCatching { !SklandTokenVault(this@AccountManagementActivity, account.id).latest().isNullOrBlank() }.getOrDefault(false)
                addView(text("森空岛 Token：" + if (tokenReady) "已获取" else "未获取", 14, false,
                    if (tokenReady) 0xFF8BD5A7.toInt() else 0xFFFFCC80.toInt()).apply { setPadding(0, dp(8), 0, dp(4)) })
                addView(button("打开森空岛网页") {
                    startActivity(Intent(this@AccountManagementActivity, SenkongdaoActivity::class.java)
                        .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
                        .putExtra(AccountStore.EXTRA_PLATFORM, account.platform))
                })
            }
        })

        content.addView(sectionCard {
            addView(text("签到游戏", 15, true))
            addView(text("当前云端配置中启用的签到游戏（只读）；账号配置不会保存在本机。", 12, false, 0xFF9BA7B8.toInt()).apply {
                setPadding(0, dp(5), 0, dp(8))
            })
            val allowed = gameLabels.filterKeys { key ->
                when (account.platform) {
                    AccountStore.HOYOLAB -> key != "honkai2" && key != "arknights"
                    AccountStore.MIYOUSHE_CN -> key != "arknights"
                    AccountStore.SKLAND -> key == "arknights" || key == "endfield"
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
                    isEnabled = false
                    setPadding(0, dp(5), 0, dp(5))
                }
                checks[key] = check
                addView(check)
            }
            if (allowed.isEmpty()) addView(text("此平台暂无可配置游戏。", 13, false, 0xFF9BA7B8.toInt()))
        })

        val deleteButton = button("云端删除同步尚未启用", danger = true) {
            AlertDialog.Builder(this)
                .setTitle("删除账号？")
                .setMessage("将删除「" + account.name + "」的账号记录。")
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
        }.apply {
            isEnabled = false
            alpha = 0.5f
        }
        root.addView(deleteButton)
        setContentView(root)
    }

    private fun openHoyoLogin(account: ManagedAccount) {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
            .putExtra(AccountStore.EXTRA_PLATFORM, account.platform)
            .putExtra("return_to_account_detail", true))
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
