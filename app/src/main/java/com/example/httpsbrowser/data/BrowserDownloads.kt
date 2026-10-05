package com.example.httpsbrowser.data

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** WebViewのDownloadListenerからUIへ渡す、保存に必要な最小限の直接ダウンロード情報。 */
data class BrowserDownloadRequest(
    val url: String,
    val fileName: String,
    val mimeType: String,
    val userAgent: String,
    val cookie: String?,
    val referer: String?
)

enum class BrowserDownloadMode { NORMAL, HIGH }

/**
 * DownloadManagerとWorkManagerの差を吸収した、アプリ内ダウンロード一覧用の読み取り専用状態。
 * 進捗はアプリのプロセスが生きている間に保持し、実ファイルは従来どおり公開Downloadsへ保存する。
 */
data class BrowserDownloadStatus(
    val id: String,
    val fileName: String,
    val mode: BrowserDownloadMode,
    val phase: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val isTerminal: Boolean = false,
    val isSuccessful: Boolean = false,
    /** 完了済み高速ダウンロードのMediaStore URI。通常モードはDownloadManagerが削除する。 */
    val contentUri: String? = null,
    val startedAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

/**
 * 通常はOS DownloadManagerへ委譲し、高速はRange対応を確認して最大4分割で取得する。
 * 起動時のダイアログ通知は返さず、すべての状態を下部メニューのアプリ内ダウンロード画面で確認できる。
 */
object BrowserDownloadDispatcher {
    private const val MAX_TRACKED_DOWNLOADS = 40
    private val trackedDownloads = ConcurrentHashMap<String, TrackedDownload>()

    private data class TrackedDownload(
        val id: String,
        val request: BrowserDownloadRequest,
        val requestedMode: BrowserDownloadMode,
        val startedAt: Long,
        @Volatile var downloadManagerId: Long? = null,
        @Volatile var workId: UUID? = null,
        @Volatile var fallbackToNormal: Boolean = false,
        @Volatile var contentUri: String? = null,
        @Volatile var cancelled: Boolean = false,
        @Volatile var deleted: Boolean = false
    )

    fun start(context: Context, request: BrowserDownloadRequest, mode: BrowserDownloadMode): BrowserDownloadStatus {
        pruneTrackedDownloads()
        val trackingId = UUID.randomUUID().toString()
        val tracked = TrackedDownload(
            id = trackingId,
            request = request,
            requestedMode = mode,
            startedAt = System.currentTimeMillis()
        )
        trackedDownloads[trackingId] = tracked
        if (mode == BrowserDownloadMode.HIGH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val work = OneTimeWorkRequestBuilder<FastDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(FastDownloadWorker.inputData(request, trackingId))
                .build()
            tracked.workId = work.id
            // 同URLでも別の保存操作として安全に追跡できるよう、WorkManagerの既存実行を置換しない。
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "neko_fast_download_$trackingId",
                ExistingWorkPolicy.KEEP,
                work
            )
            return BrowserDownloadStatus(trackingId, request.fileName, mode, "開始待ち", startedAt = tracked.startedAt)
        }
        tracked.downloadManagerId = enqueueNormal(context, request)
        return BrowserDownloadStatus(trackingId, request.fileName, BrowserDownloadMode.NORMAL, "開始待ち", startedAt = tracked.startedAt)
    }

    /** アプリ内進捗画面が定期取得する状態。UIスレッドを止めないようIOで呼び出す。 */
    suspend fun currentStatuses(context: Context): List<BrowserDownloadStatus> = withContext(Dispatchers.IO) {
        trackedDownloads.values.map { tracked ->
            val managerId = tracked.downloadManagerId
            when {
                managerId != null -> readDownloadManagerStatus(context, tracked, managerId)
                tracked.workId != null -> readFastWorkStatus(context, tracked)
                else -> BrowserDownloadStatus(tracked.id, tracked.request.fileName, tracked.requestedMode, "開始待ち", startedAt = tracked.startedAt)
            }
        }.sortedByDescending { it.startedAt }
    }

    /** 進行中の通常・高速ダウンロードを中止する。公開Downloadsの完了済みファイルは削除しない。 */
    fun cancel(context: Context, trackingId: String) {
        val tracked = trackedDownloads[trackingId] ?: return
        tracked.cancelled = true
        tracked.downloadManagerId?.let { id ->
            (context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        }
        tracked.workId?.let { id -> WorkManager.getInstance(context.applicationContext).cancelWorkById(id) }
    }

    /** 中止または完了済みの項目と公開Downloads上のファイルを削除する。 */
    fun delete(context: Context, trackingId: String) {
        val tracked = trackedDownloads[trackingId] ?: return
        cancel(context, trackingId)
        tracked.contentUri?.let { raw -> runCatching { context.applicationContext.contentResolver.delete(Uri.parse(raw), null, null) } }
        tracked.deleted = true
        trackedDownloads.remove(trackingId)
    }

    fun clearFinished() {
        trackedDownloads.entries.removeIf { (_, tracked) ->
            val managerId = tracked.downloadManagerId
            managerId == null && tracked.workId == null
        }
    }

    /** 高速モードの互換性fallbackでも、同じ行で通常DownloadManagerの進捗へ切り替える。 */
    internal fun switchToNormal(context: Context, trackingId: String?, request: BrowserDownloadRequest) {
        val managerId = enqueueNormal(context, request)
        trackingId?.let { id ->
            trackedDownloads[id]?.apply {
                downloadManagerId = managerId
                fallbackToNormal = true
            }
        }
    }

    fun enqueueNormal(context: Context, request: BrowserDownloadRequest): Long {
        val downloadRequest = DownloadManager.Request(Uri.parse(request.url)).apply {
            request.mimeType.takeIf { it.isNotBlank() }?.let(::setMimeType)
            setTitle(request.fileName)
            setDescription("ねこぶらうざからのダウンロード")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName)
            addRequestHeader("User-Agent", request.userAgent)
            request.cookie?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) }
            request.referer?.takeIf { it.startsWith("https://") }?.let { addRequestHeader("Referer", it) }
        }
        return (context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(downloadRequest)
    }

    private fun readDownloadManagerStatus(context: Context, tracked: TrackedDownload, managerId: Long): BrowserDownloadStatus {
        if (tracked.deleted) return BrowserDownloadStatus(tracked.id, tracked.request.fileName, tracked.requestedMode, "削除済み", isTerminal = true, startedAt = tracked.startedAt)
        if (tracked.cancelled) return BrowserDownloadStatus(tracked.id, tracked.request.fileName, tracked.requestedMode, "中止", isTerminal = true, startedAt = tracked.startedAt)
        val manager = context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(managerId))
        cursor.use {
            if (it == null || !it.moveToFirst()) {
                return BrowserDownloadStatus(
                    tracked.id,
                    tracked.request.fileName,
                    tracked.requestedMode,
                    "状態を確認中",
                    startedAt = tracked.startedAt
                )
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { bytes -> bytes > 0L }
            val contentUri = runCatching { manager.getUriForDownloadedFile(managerId)?.toString() }.getOrNull()
            if (contentUri != null) tracked.contentUri = contentUri
            val phase = when (status) {
                DownloadManager.STATUS_PENDING -> "待機中"
                DownloadManager.STATUS_RUNNING -> if (tracked.fallbackToNormal) "通常へ自動切替中" else "ダウンロード中"
                DownloadManager.STATUS_PAUSED -> "一時停止中"
                DownloadManager.STATUS_SUCCESSFUL -> "完了"
                DownloadManager.STATUS_FAILED -> "失敗"
                else -> "状態を確認中"
            }
            return BrowserDownloadStatus(
                id = tracked.id,
                fileName = tracked.request.fileName,
                mode = tracked.requestedMode,
                phase = phase,
                downloadedBytes = downloaded,
                totalBytes = total,
                isTerminal = status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED,
                isSuccessful = status == DownloadManager.STATUS_SUCCESSFUL,
                contentUri = contentUri,
                startedAt = tracked.startedAt
            )
        }
    }

    private fun readFastWorkStatus(context: Context, tracked: TrackedDownload): BrowserDownloadStatus {
        if (tracked.deleted) return BrowserDownloadStatus(tracked.id, tracked.request.fileName, tracked.requestedMode, "削除済み", isTerminal = true, startedAt = tracked.startedAt)
        if (tracked.cancelled) return BrowserDownloadStatus(tracked.id, tracked.request.fileName, tracked.requestedMode, "中止", isTerminal = true, startedAt = tracked.startedAt)
        val info = runCatching {
            WorkManager.getInstance(context.applicationContext).getWorkInfoById(tracked.workId!!).get()
        }.getOrNull()
        if (info == null) return BrowserDownloadStatus(tracked.id, tracked.request.fileName, tracked.requestedMode, "開始待ち", startedAt = tracked.startedAt)
        tracked.contentUri = info.outputData.getString(FastDownloadWorker.OUTPUT_CONTENT_URI) ?: tracked.contentUri
        val progress = info.progress
        val downloaded = progress.getLong(FastDownloadWorker.PROGRESS_DOWNLOADED, 0L)
        val total = progress.getLong(FastDownloadWorker.PROGRESS_TOTAL, -1L).takeIf { it > 0L }
        val phase = progress.getString(FastDownloadWorker.PROGRESS_PHASE) ?: when (info.state) {
            WorkInfo.State.ENQUEUED -> "ネットワーク待機中"
            WorkInfo.State.RUNNING -> "確認中"
            WorkInfo.State.BLOCKED -> "ネットワーク待機中"
            WorkInfo.State.SUCCEEDED -> "完了"
            WorkInfo.State.FAILED -> "失敗"
            WorkInfo.State.CANCELLED -> "中止"
        }
        return BrowserDownloadStatus(
            id = tracked.id,
            fileName = tracked.request.fileName,
            mode = tracked.requestedMode,
            phase = phase,
            downloadedBytes = downloaded,
            totalBytes = total,
            isTerminal = info.state.isFinished,
            isSuccessful = info.state == WorkInfo.State.SUCCEEDED,
            contentUri = tracked.contentUri,
            startedAt = tracked.startedAt
        )
    }

    private fun pruneTrackedDownloads() {
        if (trackedDownloads.size < MAX_TRACKED_DOWNLOADS) return
        trackedDownloads.values.sortedBy { it.startedAt }.take(trackedDownloads.size - MAX_TRACKED_DOWNLOADS + 1)
            .forEach { trackedDownloads.remove(it.id) }
    }

    private fun String.sha256Prefix(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
}

/** HTTPSのRange対応ファイルだけを4本に分けてダウンロードする。 */
private object FastDownloadNotifications {
    private const val CHANNEL_ID = "fast_downloads"
    private const val NOTIFICATION_ID = 4217

    fun foregroundInfo(context: Context, fileName: String): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "高速ダウンロード", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("高速ダウンロード中")
            .setContentText(fileName)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}

/**
 * Range対応・2MB以上のHTTPSファイルだけを最大4接続で取得する。
 * 非対応・小容量・API 26-28ではDownloadManagerへ切り替え、部分ファイルを残さない。
 */
class FastDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val request = requestFromInput() ?: return@withContext Result.failure()
        setProgress(progressData("確認中"))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            BrowserDownloadDispatcher.switchToNormal(applicationContext, trackingId(), request)
            return@withContext Result.success()
        }

        val probe = runCatching { probeRange(request) }.getOrNull()
        if (probe == null || probe.totalBytes < MIN_PARALLEL_BYTES) {
            setProgress(progressData("通常へ自動切替中"))
            BrowserDownloadDispatcher.switchToNormal(applicationContext, trackingId(), request)
            return@withContext Result.success()
        }

        val parts = mutableListOf<File>()
        val transferred = AtomicLong(0L)
        val lastReported = AtomicLong(0L)
        try {
            setForeground(createForegroundInfo(request.fileName))
            setProgress(progressData("ダウンロード中", 0L, probe.totalBytes))
            val ranges = splitRanges(probe.totalBytes)
            coroutineScope {
                ranges.mapIndexed { index, range ->
                    async {
                        val part = File(applicationContext.cacheDir, "neko-fast-${id}-$index.part")
                        downloadRange(request, range, part) { bytes ->
                            val current = transferred.addAndGet(bytes.toLong())
                            val previous = lastReported.get()
                            if (current == probe.totalBytes || current - previous >= PROGRESS_STEP_BYTES) {
                                if (lastReported.compareAndSet(previous, current)) {
                                    setProgress(progressData("ダウンロード中", current, probe.totalBytes))
                                }
                            }
                        }
                        synchronized(parts) { parts += part }
                    }
                }.awaitAll()
            }
            if (parts.size != PARALLEL_CONNECTIONS) error("高速ダウンロードの分割結果が不足しています。")
            setProgress(progressData("保存中", probe.totalBytes, probe.totalBytes))
            val destination = publishToDownloads(request, parts.sortedBy { it.name })
            setProgress(progressData("完了", probe.totalBytes, probe.totalBytes))
            Result.success(Data.Builder().putString(OUTPUT_CONTENT_URI, destination.toString()).build())
        } catch (_: Throwable) {
            parts.forEach { runCatching { it.delete() } }
            setProgress(progressData("再試行中"))
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = withContext(Dispatchers.IO) {
        val name = inputData.getString(KEY_FILE_NAME) ?: "ダウンロード"
        createForegroundInfo(name)
    }

    private fun createForegroundInfo(fileName: String): ForegroundInfo =
        FastDownloadNotifications.foregroundInfo(applicationContext, fileName)

    private fun probeRange(request: BrowserDownloadRequest): RangeProbe {
        val connection = openConnection(request, "bytes=0-0")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return error("Range未対応")
            val contentRange = connection.getHeaderField("Content-Range") ?: return error("Content-Rangeなし")
            val total = contentRange.substringAfter('/').toLongOrNull() ?: return error("総サイズ不明")
            if (total <= 0L) return error("不正な総サイズ")
            return RangeProbe(total)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadRange(
        request: BrowserDownloadRequest,
        range: LongRange,
        target: File,
        onBytesDownloaded: suspend (Int) -> Unit
    ) {
        val connection = openConnection(request, "bytes=${range.first}-${range.last}")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) error("分割Rangeが拒否されました。")
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(target.outputStream()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onBytesDownloaded(read)
                    }
                }
            }
            if (target.length() != range.last - range.first + 1L) error("分割Rangeの長さが一致しません。")
        } finally {
            connection.disconnect()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToDownloads(request: BrowserDownloadRequest, parts: List<File>): Uri {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, request.fileName)
            put(MediaStore.Downloads.MIME_TYPE, request.mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Downloads保存先を作成できませんでした。")
        try {
            resolver.openOutputStream(destination)?.use { output ->
                BufferedOutputStream(output).use { buffered ->
                    parts.forEach { part -> part.inputStream().use { it.copyTo(buffered) } }
                }
            } ?: error("Downloads保存先を開けませんでした。")
            resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (throwable: Throwable) {
            resolver.delete(destination, null, null)
            throw throwable
        } finally {
            parts.forEach { runCatching { it.delete() } }
        }
        return destination
    }

    private fun openConnection(request: BrowserDownloadRequest, range: String): HttpURLConnection =
        (URL(request.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Range", range)
            setRequestProperty("User-Agent", request.userAgent)
            request.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            request.referer?.takeIf { it.startsWith("https://") }?.let { setRequestProperty("Referer", it) }
        }

    private fun splitRanges(totalBytes: Long): List<LongRange> = List(PARALLEL_CONNECTIONS) { index ->
        val start = totalBytes * index / PARALLEL_CONNECTIONS
        val end = totalBytes * (index + 1) / PARALLEL_CONNECTIONS - 1L
        start..end
    }

    private fun requestFromInput(): BrowserDownloadRequest? {
        val url = inputData.getString(KEY_URL) ?: return null
        return BrowserDownloadRequest(
            url = url,
            fileName = inputData.getString(KEY_FILE_NAME) ?: URLUtil.guessFileName(url, null, null),
            mimeType = inputData.getString(KEY_MIME_TYPE).orEmpty(),
            userAgent = inputData.getString(KEY_USER_AGENT).orEmpty(),
            cookie = inputData.getString(KEY_COOKIE),
            referer = inputData.getString(KEY_REFERER)
        )
    }

    private fun trackingId(): String? = inputData.getString(KEY_TRACKING_ID)

    private fun progressData(phase: String, downloaded: Long = 0L, total: Long = -1L): Data = Data.Builder()
        .putString(PROGRESS_PHASE, phase)
        .putLong(PROGRESS_DOWNLOADED, downloaded)
        .putLong(PROGRESS_TOTAL, total)
        .build()

    private data class RangeProbe(val totalBytes: Long)

    companion object {
        private const val PARALLEL_CONNECTIONS = 4
        private const val MIN_PARALLEL_BYTES = 2L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RETRIES = 2
        private const val PROGRESS_STEP_BYTES = 256L * 1024L
        private const val KEY_URL = "url"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_MIME_TYPE = "mimeType"
        private const val KEY_USER_AGENT = "userAgent"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_REFERER = "referer"
        private const val KEY_TRACKING_ID = "trackingId"
        const val PROGRESS_PHASE = "phase"
        const val PROGRESS_DOWNLOADED = "downloaded"
        const val PROGRESS_TOTAL = "total"
        const val OUTPUT_CONTENT_URI = "contentUri"

        fun inputData(request: BrowserDownloadRequest, trackingId: String): Data = Data.Builder()
            .putString(KEY_URL, request.url)
            .putString(KEY_FILE_NAME, request.fileName)
            .putString(KEY_MIME_TYPE, request.mimeType)
            .putString(KEY_USER_AGENT, request.userAgent)
            .putString(KEY_COOKIE, request.cookie)
            .putString(KEY_REFERER, request.referer)
            .putString(KEY_TRACKING_ID, trackingId)
            .build()
    }
}
