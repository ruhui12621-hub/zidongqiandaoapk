package com.ruhui.hoyocookiemanager

import android.app.Application
import android.os.Build
import android.webkit.WebView

class CrashLoggingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = Application.getProcessName()
            val suffix = if (processName.endsWith(":skland")) "skland_profile" else "hoyolab_profile"
            WebView.setDataDirectorySuffix(suffix)
        }
        CrashLogger.install(this)
    }
}
