package com.ruhui.hoyocookiemanager

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AccountManagementActivity : Activity() {
    private lateinit var root: LinearLayout
    private val store by lazy { AccountStore(this) }
    private val gameLabels = linkedMapOf(
        "genshin" to "原神",
        "honkai2" to "崩坏2",
        "honkai3rd" to "崩坏3",
        "tears_of_themis" to "未定事件簿",
        "honkai_sr" to "崩坏：星穹铁道",
        "zzz" to "绝区零",
        "arknights" to "明日方舟"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        intent.getStringExtra(AccountStore.EXTRA_ACCOUNT_ID)?.let { id ->
            store.find(id)?.let { edit(it) }
        }
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹ 返回") { finish() }, LinearLayout.LayoutParams(-2, -2))
        header.addView(text("账号管理", 20, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        root.addView(header)
        root.addView(text("每个平台的账号独立保存；同一个游戏可以分配给不同平台的多个账号。", 13, false, 0xFF9BA7B8.toInt()).apply {
            setPadding(0, dp(12), 0, dp(14))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        store.all().forEach { account ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(12), dp(13))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF202631.toInt())
                    cornerRadius = dp(13).toFloat()
                }
                setOnClickListener { edit(account) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(account.name, 16, true))
            val games = account.games.mapNotNull { gameLabels[it] }
            info.addView(text(
                "${AccountStore.platformLabel(account.platform)} · ${if (games.isEmpty()) "尚未选择游戏" else games.joinToString("、")}",
                12, false, 0xFF9BA7B8.toInt()
            ).apply { setPadding(0, dp(5), 0, 0) })
            info.addView(text(
                if (account.platformUserId != null) "已识别平台账号 ID" else "等待登录后识别账号 ID",
                11, false, 0xFF8995A8.toInt()
            ).apply { setPadding(0, dp(4), 0, 0) })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("›", 25, false, 0xFF9BA7B8.toInt()))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("+ 添加账号") { addAccount() })
        setContentView(root)
    }

    private fun addAccount() {
        val labels = arrayOf("HoYoLAB（国际服）", "森空岛", "米游社（国服）")
        val values = arrayOf(AccountStore.HOYOLAB, AccountStore.SKLAND, AccountStore.MIYOUSHE_CN)
        AlertDialog.Builder(this)
            .setTitle("选择账号所属平台")
            .setItems(labels) { _, which ->
                val created = store.add(values[which])
                edit(created)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun edit(account: ManagedAccount) {
        val nameInput = android.widget.EditText(this).apply {
            setText(account.name)
            setSingleLine(true)
            hint = "账号备注名称"
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        panel.addView(TextView(this).apply {
            text = "账号名称"
            setTextColor(0xFFB8C1D1.toInt())
        })
        panel.addView(nameInput)
        panel.addView(TextView(this).apply {
            text = "选择此账号需要签到的游戏"
            setTextColor(0xFFB8C1D1.toInt())
            setPadding(0, dp(14), 0, dp(4))
        })
        val checks = linkedMapOf<String, CheckBox>()
        gameLabels.filterKeys { key ->
            account.platform != AccountStore.SKLAND || key == "arknights"
        }.forEach { (key, label) ->
            val check = CheckBox(this).apply {
                text = label
                setTextColor(0xFFECEFF5.toInt())
                isChecked = account.games.contains(key)
            }
            checks[key] = check
            panel.addView(check)
        }
        panel.addView(button("删除此账号") {
            AlertDialog.Builder(this)
                .setTitle("删除账号？")
                .setMessage("将删除「${account.name}」及其本地加密凭据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除") { _, _ ->
                    runCatching {
                        when (account.platform) {
                            AccountStore.SKLAND -> SklandTokenVault(this, account.id).delete()
                            AccountStore.HOYOLAB, AccountStore.MIYOUSHE_CN -> HoYoCookieVault(this, account.id).delete()
                        }
                    }
                    store.delete(account.id)
                    render()
                }.show()
        })
        val scroll = ScrollView(this).apply { addView(panel) }
        AlertDialog.Builder(this)
            .setTitle(AccountStore.platformLabel(account.platform))
            .setView(scroll)
            .setNeutralButton("登录 / 更新凭据", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { account.name }
                val games = checks.filterValues { it.isChecked }.keys
                runCatching {
                    store.update(account.copy(name = name, games = games))
                }.onSuccess { render() }.onFailure {
                    Toast.makeText(this, it.message ?: "保存失败", Toast.LENGTH_LONG).show()
                }
            }
            .show()
            .apply {
                getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val target = if (account.platform == AccountStore.SKLAND) {
                        SenkongdaoActivity::class.java
                    } else {
                        MainActivity::class.java
                    }
                    startActivity(android.content.Intent(this@AccountManagementActivity, target)
                        .putExtra(AccountStore.EXTRA_ACCOUNT_ID, account.id)
                        .putExtra(AccountStore.EXTRA_PLATFORM, account.platform))
                    dismiss()
                }
            }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(39, 46, 60))
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Int, bold: Boolean, color: Int = 0xFFECEFF5.toInt()) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
