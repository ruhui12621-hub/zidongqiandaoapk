package com.ruhui.hoyocookiemanager

import android.os.Bundle
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class HuaweiCloudSettingsActivity : HuaweiCloudActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundColor(Color.rgb(15, 17, 23))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(android.widget.Button(this).apply {
            text = "‹ 返回"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "华为云设置"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), 0, 0)
        })
        root.addView(header)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        scroll.addView(content)
        addHuaweiCloudPanel(content, "hoyolab", { HuaweiCloudSession.lastConfigYaml }, loginOnly = true)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
