package com.localmind.service

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获。**只落盘，不上传** —— 上不上传由用户在下次启动时决定。
 *
 * 设计取舍：
 *  - 崩溃回调里不能做任何耗时/可能再崩的事，所以只 writeText 一个几百字节的 JSON，
 *    然后立刻把异常交给原来的 handler（让系统正常弹"应用已停止"）。
 *  - 只保留最近一次：`pending.json` 单文件覆盖写，不堆历史、不吃空间。
 *  - 用户拒绝上传时调用 [dismiss] 删掉，等于没发生过。
 */
object CrashReporter {

    private lateinit var appContext: Context

    data class Report(val stack: String, val at: Long, val versionName: String, val versionCode: Int) {
        val timeText: String
            get() = runCatching {
                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))
            }.getOrDefault("")
    }

    private fun dir(context: Context) = File(context.filesDir, "crash").apply { mkdirs() }
    private fun file(context: Context) = File(dir(context), "pending.json")

    /** 在 Application.onCreate 里调一次。 */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(appContext, error) }
            // 交回系统默认处理，别把"应用停止"弹窗吞掉
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(context: Context, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().take(8000)
        val json = JSONObject().apply {
            put("stack", stack)
            put("at", System.currentTimeMillis())
            put("versionName", com.localmind.BuildConfig.VERSION_NAME)
            put("versionCode", com.localmind.BuildConfig.VERSION_CODE)
        }
        file(context).writeText(json.toString())
    }

    /** 上次启动是否有未处理的崩溃。读过不清除，由用户决定上传还是丢弃。 */
    fun pending(context: Context): Report? = runCatching {
        val f = file(context)
        if (!f.isFile) return null
        val j = JSONObject(f.readText())
        Report(
            stack = j.optString("stack"),
            at = j.optLong("at"),
            versionName = j.optString("versionName"),
            versionCode = j.optInt("versionCode"),
        )
    }.getOrNull()

    /** 用户选择「不上传」或已上传成功。 */
    fun dismiss(context: Context) {
        runCatching { file(context).delete() }
    }
}
