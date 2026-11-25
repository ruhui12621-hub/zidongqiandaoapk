package com.ruhui.hoyocookiemanager

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())
                val stack = StringWriter().also { writer ->
                    throwable.printStackTrace(PrintWriter(writer))
                }.toString()
                val content = buildString {
                    appendLine("HoYoLAB Cookie Manager 崩溃记录")
                    appendLine("时间：${time}")
                    appendLine("线程：${thread.name}")
                    appendLine()
                    appendLine(stack)
                }
                FileOutputStream(File(context.filesDir, FILE_NAME), false).use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                val top = throwable.stackTrace.firstOrNull()
                val location = if (top == null) "位置未知" else
                    "${top.className}.${top.methodName}:${top.lineNumber}"
                AppMessageBus.post(
                    context,
                    "应用发生未捕获异常",
                    "异常类型：${throwable.javaClass.simpleName}；线程：${thread.name}；位置：$location。完整堆栈已保存到本地崩溃记录。",
                    false
                )
            } catch (_: Throwable) {
                // 崩溃记录写入失败时，仍交给系统原有处理器。
            }

            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun read(context: Context): String? =
        try {
            File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }
}
