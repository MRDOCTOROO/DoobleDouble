package com.dooble.space

import android.app.Application
import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DoobleApp : Application() {

    /**
     * 虚拟化容器的进程伪装必须在这里完成——这是系统给应用的最后一次机会，
     * 晚于此刻，进程身份就已经固化成宿主了。详见 [DoobleContainer.bootstrapHost]。
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            DoobleContainer.bootstrapHost(this, base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 宿主、容器服务（:black）与各虚拟应用进程（:pN）的未捕获异常都落盘到
        // filesDir/dooble/crash/，供真机黑屏/闪退排查使用。诊断产物仅本地留存，
        // 不随包分发、不提交仓库。
        installCrashLog()
        // 引擎探测 + native 加载 + 供给层装配，全部在这里异步完成。
        // 不要在 onCreate 里同步等待，否则冷启动会被 native 加载拖慢。
        DoobleContainer.bootstrap(this)
    }

    private fun installCrashLog() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(filesDir, "dooble/crash")
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val safeThread = thread.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val out = File(dir, "crash_${stamp}_${safeThread}_p${Process.myPid()}.log")
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("Process: pid=${Process.myPid()} package=$packageName")
                pw.println("Thread: ${thread.name}")
                pw.println("Time: ${Date()}")
                pw.println()
                throwable.printStackTrace(pw)
                pw.close()
                out.writeText(sw.toString())
            } catch (ignored: Exception) {
                // 崩溃日志写入失败不能影响主流程
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
