const fs = require('fs');

const downloads = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/data/BrowserDownloads.kt', 'utf8');
const web = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const screen = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt', 'utf8');
const sheets = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserSheets.kt', 'utf8');
const models = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/data/BrowserModels.kt', 'utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');
const adblock = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/data/AdBlocker.kt', 'utf8');
const assets = fs.readdirSync('app/src/main/assets/adblock');

function requireText(source, text, label) {
  if (!source.includes(text)) throw new Error(`${label}: missing ${text}`);
}

for (const required of [
  'enum class BrowserDownloadMode { NORMAL, HIGH }',
  'DownloadManager.Request(Uri.parse(request.url))',
  'setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName)',
  'setRequestProperty("Range", range)',
  'private const val PARALLEL_CONNECTIONS = 4',
  'HttpURLConnection.HTTP_PARTIAL',
  'BrowserDownloadDispatcher.switchToNormal(applicationContext, trackingId(), request)',
  'MediaStore.Downloads.IS_PENDING',
  'ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC',
  'data class BrowserDownloadStatus(',
  'suspend fun currentStatuses(context: Context)',
  'DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR',
  'setProgress(progressData("ダウンロード中"',
  'fun cancel(context: Context, trackingId: String)',
  'fun delete(context: Context, trackingId: String)',
  'WorkManager.getInstance(context.applicationContext).cancelWorkById(id)',
  'OUTPUT_CONTENT_URI'
]) requireText(downloads, required, 'download dispatch safety');

requireText(web, 'entry.callbacks.onDownloadRequested(', 'WebView download handoff');
requireText(web, 'referer = entry.webView.url?.takeIf(::isHttps)', 'HTTPS Referer handoff');
requireText(screen, 'Text("通常")', 'normal download option');
requireText(screen, 'Text("高速")', 'high download option');
requireText(screen, 'BrowserDownloadMode.HIGH', 'high dispatch choice');
requireText(screen, 'viewModel.openSettings(SettingsPage.DOWNLOADS)', 'bottom menu opens in-app downloads');
requireText(screen, 'BrowserDownloadDispatcher.start(context, request, BrowserDownloadMode.NORMAL)', 'normal start without blocking notice');
requireText(sheets, 'SettingsPage.DOWNLOADS -> DownloadsPage(onBack)', 'downloads settings page routing');
requireText(sheets, 'BrowserDownloadDispatcher.currentStatuses(context)', 'in-app download progress polling');
requireText(sheets, 'LinearProgressIndicator(progress = { fraction }', 'download progress indicator');
requireText(sheets, 'Text("停止")', 'in-app download cancel action');
requireText(sheets, 'Text("削除")', 'in-app download delete action');
requireText(models, 'DOWNLOADS', 'downloads page type');
requireText(manifest, 'android.permission.FOREGROUND_SERVICE_DATA_SYNC', 'data sync permission');
requireText(manifest, 'android:foregroundServiceType="dataSync"', 'data sync service type');
requireText(adblock, 'PeriodicWorkRequestBuilder<AdBlockUpdateWorker>(1, TimeUnit.DAYS)', 'daily adblock update cadence');
requireText(adblock, 'NetworkType.UNMETERED', 'Wi-Fi-only adblock update constraint');
if (adblock.includes('adguard_android_101_optimized') || adblock.includes('https://filters.adtidy.org/android/filters/101_optimized.txt')) {
  throw new Error('obsolete AdGuard Android filter 101 must remain removed');
}
if (assets.some((name) => name.includes('adguard_android_101_optimized'))) {
  throw new Error('obsolete AdGuard Android filter 101 snapshot must remain removed');
}

if (downloads.includes('return "高速ダウンロードを準備しています')) {
  throw new Error('download start must not show intrusive preparing notice');
}
console.log('Download link handoff, normal/high mode safeguards, and in-app progress UI: OK');
