package com.example.httpsbrowser

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import androidx.annotation.RequiresApi
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 端末外へ自動送信しない、クラッシュ原因を特定するための最小限の診断ログ。
 * API 30以降はOSが保持する直近のプロセス終了理由も次回起動時に記録する。
 */
object CrashDiagnostics {
    private const val FILE_NAME = "crash-diagnostics.txt"
    private const val MAX_FILE_CHARS = 48_000
    private const val MAX_EXIT_RECORDS = 5
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    private var applicationContext: Context? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(application: Application) {
        applicationContext = application.applicationContext
        recordSystemExitReasons(application)
        record("process_start", memorySummary())
        if (previousHandler == null) {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                record(
                    "uncaught_exception",
                    "thread=${thread.name}\n${throwable.javaClass.name}: ${throwable.message.orEmpty()}\n${stackTrace(throwable)}"
                )
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun record(event: String, detail: String = "") {
        val context = applicationContext ?: return
        runCatching {
            val entry = buildString {
                append('[').append(timestampFormat.format(Date())).append("] ")
                append(event)
                if (detail.isNotBlank()) append('\n').append(detail.trim())
                append("\n\n")
            }
            synchronized(this) {
                val file = File(context.filesDir, FILE_NAME)
                val existing = if (file.isFile) file.readText() else ""
                val next = (existing + entry).takeLast(MAX_FILE_CHARS)
                file.writeText(next)
            }
        }
    }

    fun recordWebViewNavigation(url: String) {
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host.isNotBlank()) record("webview_navigation", "host=$host\n${memorySummary()}")
    }

    fun recordWebViewRendererGone(didCrash: Boolean, rendererPriorityAtExit: Int) {
        record(
            "webview_renderer_gone",
            "didCrash=$didCrash\nrendererPriorityAtExit=$rendererPriorityAtExit\n${memorySummary()}"
        )
    }

    fun read(context: Context): String = runCatching {
        File(context.applicationContext.filesDir, FILE_NAME).takeIf(File::isFile)?.readText().orEmpty()
    }.getOrDefault("")

    fun share(context: Context) {
        val text = read(context).ifBlank { "ねこぶらうざのクラッシュ診断情報はまだありません。" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ねこぶらうざ crash diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "クラッシュ診断情報を共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun recordSystemExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            record("process_exit_reason_unavailable", "Android 11（API 30）未満ではOS終了理由を取得できません。")
            return
        }
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
            exits.forEach { info ->
                val key = "exit_${info.timestamp}_${info.pid}_${info.reason}"
                val preferences = context.getSharedPreferences("crash_diagnostics", Context.MODE_PRIVATE)
                if (!preferences.getBoolean(key, false)) {
                    preferences.edit().putBoolean(key, true).apply()
                    record("system_process_exit", exitDetail(info))
                }
            }
        }.onFailure { record("exit_reason_read_failure", "${it.javaClass.name}: ${it.message.orEmpty()}") }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitDetail(info: ApplicationExitInfo): String = buildString {
        append("reason=").append(reasonName(info.reason))
        append(" (").append(info.reason).append(')')
        append("\nstatus=").append(info.status)
        append("\npid=").append(info.pid)
        append("\nimportance=").append(info.importance)
        append("\npssKb=").append(info.pss)
        append("\nrssKb=").append(info.rss)
        append("\nprocess=").append(info.processName)
        info.description?.takeIf(String::isNotBlank)?.let { append("\ndescription=").append(it) }
        append("\nrecordedAt=").append(timestampFormat.format(Date(info.timestamp)))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH_JAVA"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        else -> "OTHER_OR_UNKNOWN"
    }

    private fun memorySummary(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L
        "heapUsedKb=${(runtime.totalMemory() - runtime.freeMemory()) / 1024L}\nheapMaxKb=${runtime.maxMemory() / 1024L}\nnativeHeapKb=$nativeHeapKb"
    }.getOrDefault("memory=unavailable")

    private fun stackTrace(throwable: Throwable): String = StringWriter().also { writer ->
        PrintWriter(writer).use { throwable.printStackTrace(it) }
    }.toString().take(16_000)
}
