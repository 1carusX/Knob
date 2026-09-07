package cn.icarus.knob.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cn.icarus.knob.KnobApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获器：未捕获异常时把堆栈自动存到 Downloads/crash_{时间}.txt，
 * 作为无 adb 环境下抓崩溃日志的兜底（车机无 adb）。
 */
object CrashHandler {

    private var installed = false

    /** 安装全局未捕获异常处理器（应在 Application.onCreate 或 KnobActivity 调一次） */
    fun install() {
        if (installed) return
        installed = true
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(throwable, thread)
            } catch (ignored: Throwable) {
                // 存崩溃日志本身失败时忽略，避免二次崩溃
            }
            // 仍交给默认处理器（结束进程并提示）
            default?.uncaughtException(thread, throwable)
        }
    }

    /** 把崩溃信息写到 Downloads/crash_{时间}.txt */
    private fun saveCrash(throwable: Throwable, thread: Thread?) {
        val ctx = KnobApp.context
        val sb = StringBuilder()
        sb.append("===== Knob Crash Report =====\n")
        sb.append("时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date())).append("\n")
        sb.append("线程: ").append(thread?.name ?: "未知").append("\n")
        sb.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("\n").append("----- Stack Trace -----\n")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        sb.append(sw.toString())

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date())
        val fileName = "crash_$timestamp.txt"
        val content = sb.toString()

        // API 29+ 走 MediaStore（无需存储权限）；更早用公共 Downloads 目录
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            }
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            File(downloads, fileName).writeText(content)
        }
    }
}
