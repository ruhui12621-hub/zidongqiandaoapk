package com.ruhui.hoyocookiemanager

import android.app.Application

class CrashLoggingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
