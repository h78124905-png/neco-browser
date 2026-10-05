package com.example.httpsbrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.example.httpsbrowser.CrashDiagnostics
import androidx.compose.ui.unit.dp
import com.example.httpsbrowser.data.Bookmark
import com.example.httpsbrowser.data.BrowserDownloadDispatcher
import com.example.httpsbrowser.data.BrowserDownloadStatus
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.BrowserUiState
import com.example.httpsbrowser.data.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

object BrowserSheets {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TabSheet(
        tabs: List<BrowserTab>,
        selectedTabId: String?,
        onSelect: (String) -> Unit,
        onClose: (String) -> Unit,
        onNewTab: (Boolean) -> Unit,
        onPrivateModeChanged: (Boolean) -> Unit,
        onDismiss: () -> Unit
    ) {
        val selectedTab = tabs.firstOrNull { it.id == selectedTabId }
        var privateMode by remember(selectedTabId, selectedTab?.isPrivate) { mutableStateOf(selectedTab?.isPrivate == true) }
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("タブ一覧", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onNewTab(privateMode) }) { Icon(Icons.Default.Add, null); Text("新しいタブ") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text("シークレットタブ")
                    Text("履歴・タブ復元に残さない", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = privateMode, onCheckedChange = { enabled ->
                    privateMode = enabled
                    onPrivateModeChanged(enabled)
                })
            }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(tab.id) },
                        headlineContent = { Text(tab.title.ifBlank { "ホーム" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (tab.isPrivate) "シークレット・履歴を保存しない"
                                else if (tab.isHome) "独自ホーム" else tab.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = {
                            Row {
                                if (tab.id == selectedTabId) Text("選択中", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                                IconButton(onClick = { onClose(tab.id) }) { Icon(Icons.Default.Delete, "タブを閉じる") }
                            }
                        }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsSheet(
        state: BrowserUiState,
        onSettings: ((BrowserSettings) -> BrowserSettings) -> Unit,
        onOpenUrl: (String) -> Unit,
        onOpenPage: (SettingsPage) -> Unit,
        onBack: () -> Unit,
        onDismiss: () -> Unit,
        onSaveBookmark: (String, String) -> Boolean,
        onUpdateBookmark: (String, String, String) -> Boolean,
        onDeleteBookmark: (String) -> Unit,
        onDeleteHistory: (String) -> Unit,
        onClear: () -> Unit,
        onDownloads: () -> Unit,
        onShareDiagnostics: () -> Unit,
        onNotice: (String) -> Unit
    ) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (state.settingsPage) {
                SettingsPage.ROOT -> SettingsRoot(state, onSettings, onOpenPage, onDownloads, onDismiss)
                SettingsPage.BOOKMARKS -> BookmarkPage(state.bookmarks, onOpenUrl, onSaveBookmark, onUpdateBookmark, onDeleteBookmark, onBack, onNotice)
                SettingsPage.HISTORY -> HistoryPage(state.history, onOpenUrl, onDeleteHistory, onBack)
                SettingsPage.DOWNLOADS -> DownloadsPage(onBack)
                SettingsPage.DARK_EXCLUSIONS -> DarkExclusionsPage(state.settings, onSettings, onBack)
                // 旧フィルターリスト画面は削除済み。保存された古い状態から来てもrootへ戻す。
                SettingsPage.DATA -> DataPage(onClear, onBack)
                SettingsPage.DIAGNOSTICS -> DiagnosticsPage(onBack, onShareDiagnostics)
                SettingsPage.OPEN_SOURCE_LICENSES -> OpenSourceLicensesPage(onBack)
            }
        }
    }

    @Composable
    private fun SettingsRoot(
        state: BrowserUiState,
        onSettings: ((BrowserSettings) -> BrowserSettings) -> Unit,
        onOpenPage: (SettingsPage) -> Unit,
        onDownloads: () -> Unit,
        onDismiss: () -> Unit
    ) {
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            item { SheetTitle("設定") }
            item {
                SettingSwitch("暗色化", state.settings.forceDarkPages) { enabled ->
                    onSettings { setting -> setting.copy(forceDarkPages = enabled) }
                }
            }
            item {
                SettingSwitch("広告ブロック", state.settings.adBlockingEnabled) { enabled ->
                    onSettings { setting -> setting.copy(adBlockingEnabled = enabled) }
                }
            }
            item { NavigationItem("暗色化の例外", Icons.Default.Security) { onOpenPage(SettingsPage.DARK_EXCLUSIONS) } }
            item { SettingSwitch("JavaScript を有効化", state.settings.javascriptEnabled) { onSettings { setting -> setting.copy(javascriptEnabled = it) } } }
            item {
                VideoControlHostSetting(
                    hosts = state.settings.videoControlHosts,
                    onHostsChanged = { hosts -> onSettings { setting -> setting.copy(videoControlHosts = hosts) } }
                )
            }
            item {
                VideoPlaybackRateSetting(
                    rate = state.settings.videoPlaybackRate,
                    onRateChanged = { rate -> onSettings { setting -> setting.copy(videoPlaybackRate = rate) } }
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SheetTitle("管理") }
            item { NavigationItem("ブックマーク", Icons.Default.Bookmark) { onOpenPage(SettingsPage.BOOKMARKS) } }
            item { NavigationItem("閲覧履歴", Icons.Default.History) { onOpenPage(SettingsPage.HISTORY) } }
            item { NavigationItem("ダウンロード", Icons.Default.Download, onDownloads) }
            item { NavigationItem("クラッシュ診断", Icons.Default.Security) { onOpenPage(SettingsPage.DIAGNOSTICS) } }
            item { NavigationItem("オープンソースライセンス", Icons.Default.Security) { onOpenPage(SettingsPage.OPEN_SOURCE_LICENSES) } }
            item { NavigationItem("閲覧データの消去", Icons.Default.Delete) { onOpenPage(SettingsPage.DATA) } }
            item { TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Text("閉じる") } }
        }
    }

    @Composable
    private fun VideoControlHostSetting(hosts: List<String>, onHostsChanged: (List<String>) -> Unit) {
        var input by remember { mutableStateOf("") }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("通常画面の動画操作サイト", style = MaterialTheme.typography.titleSmall)
            Text("全画面動画では常に表示され、通常画面ではここに登録したサイトだけ表示します。", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("例: youtube.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val host = input.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
                    if (host.isNotBlank()) onHostsChanged((hosts + host).distinct())
                    input = ""
                }) { Text("追加") }
            }
            hosts.forEach { host ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(host, modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(onClick = { onHostsChanged(hosts - host) }) { Text("削除") }
                }
            }
        }
    }

    @Composable
    private fun VideoPlaybackRateSetting(rate: Float, onRateChanged: (Float) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("動画の再生速度", style = MaterialTheme.typography.titleSmall)
            Text("動画を切り替えても選択した速度を維持します。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.0f, 1.5f, 2.0f).forEach { value ->
                    TextButton(onClick = { onRateChanged(value) }) {
                        Text(if (rate == value) "✓×${value}" else "×${value}")
                    }
                }
            }
        }
    }

    @Composable
    private fun BookmarkPage(
        bookmarks: List<Bookmark>,
        onOpenUrl: (String) -> Unit,
        onSaveBookmark: (String, String) -> Boolean,
        onUpdateBookmark: (String, String, String) -> Boolean,
        onDeleteBookmark: (String) -> Unit,
        onBack: () -> Unit,
        onNotice: (String) -> Unit
    ) {
        var creating by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Bookmark?>(null) }
        PageHeader("ブックマーク", onBack, actionLabel = "追加") { creating = true }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (bookmarks.isEmpty()) item { EmptyRow("ブックマークはまだありません。") }
            items(bookmarks, key = { it.id }) { bookmark ->
                ListItem(
                    modifier = Modifier.clickable { onOpenUrl(bookmark.url) },
                    headlineContent = { Text(bookmark.title.ifBlank { bookmark.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(bookmark.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        BookmarkFavicon(
                            url = bookmark.url,
                            title = bookmark.title.ifBlank { bookmark.url },
                            modifier = Modifier.size(40.dp).padding(3.dp)
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = bookmark }) { Icon(Icons.Default.Edit, "ブックマークを編集") }
                            IconButton(onClick = { onDeleteBookmark(bookmark.id) }) { Icon(Icons.Default.Delete, "ブックマークを削除") }
                        }
                    }
                )
            }
        }
        if (creating) BookmarkEditorDialog(
            title = "ブックマークを追加", initialTitle = "", initialUrl = "",
            onConfirm = { title, url ->
                if (onSaveBookmark(title, url)) creating = false else onNotice("HTTPS URL または検索語を入力してください。")
            }, onDismiss = { creating = false }
        )
        editing?.let { bookmark ->
            BookmarkEditorDialog(
                title = "ブックマークを編集", initialTitle = bookmark.title, initialUrl = bookmark.url,
                onConfirm = { title, url ->
                    if (onUpdateBookmark(bookmark.id, title, url)) editing = null else onNotice("HTTPS URL または検索語を入力してください。")
                }, onDismiss = { editing = null }
            )
        }
    }

    @Composable
    private fun HistoryPage(
        history: List<com.example.httpsbrowser.data.HistoryEntry>,
        onOpenUrl: (String) -> Unit,
        onDelete: (String) -> Unit,
        onBack: () -> Unit
    ) {
        PageHeader("閲覧履歴", onBack)
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (history.isEmpty()) item { EmptyRow("閲覧履歴はまだありません。") }
            items(history.take(200), key = { it.id }) { entry ->
                ListItem(
                    modifier = Modifier.clickable { onOpenUrl(entry.url) },
                    headlineContent = { Text(entry.query ?: entry.title.ifBlank { entry.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(entry.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                            IconButton(onClick = { onDelete(entry.id) }) { Icon(Icons.Default.Delete, "この履歴を削除") }
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun DarkExclusionsPage(
        settings: BrowserSettings,
        onSettings: ((BrowserSettings) -> BrowserSettings) -> Unit,
        onBack: () -> Unit
    ) {
        var hostInput by remember { mutableStateOf("") }
        PageHeader("暗色化の例外", onBack)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("ここに追加したサイトでは、highを含む追加暗色化を行いません。example.com を追加するとサブドメインも対象です。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("サイトURL またはホスト名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    normalizeDarkExclusionHost(hostInput)?.let { host ->
                        onSettings { current -> current.copy(darkModeExcludedHosts = (current.darkModeExcludedHosts + host).distinct()) }
                        hostInput = ""
                    }
                }) { Text("追加") }
            }
        }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (settings.darkModeExcludedHosts.isEmpty()) item { EmptyRow("暗色化の例外はまだありません。") }
            items(settings.darkModeExcludedHosts, key = { it }) { host ->
                ListItem(
                    headlineContent = { Text(host) },
                    supportingContent = { Text("このサイトとサブドメインでは追加暗色化をしない", style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        IconButton(onClick = {
                            onSettings { current -> current.copy(darkModeExcludedHosts = current.darkModeExcludedHosts - host) }
                        }) { Icon(Icons.Default.Delete, "$host を除外リストから削除") }
                    }
                )
            }
        }
    }

    private fun normalizeDarkExclusionHost(input: String): String? = runCatching {
        val normalized = input.trim().lowercase()
        val uri = URI(if ("://" in normalized) normalized else "https://$normalized")
        uri.host?.removePrefix("www.")?.takeIf { host -> host.matches(Regex("[a-z0-9.-]+")) }
    }.getOrNull()

    @Composable
    private fun DownloadsPage(onBack: () -> Unit) {
        val context = LocalContext.current
        var downloads by remember { mutableStateOf<List<BrowserDownloadStatus>>(emptyList()) }
        LaunchedEffect(Unit) {
            while (isActive) {
                downloads = BrowserDownloadDispatcher.currentStatuses(context)
                delay(700)
            }
        }
        PageHeader("ダウンロード", onBack)
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (downloads.isEmpty()) {
                item { EmptyRow("進行中またはこの起動中に開始したダウンロードはここに表示されます。") }
            }
            items(downloads, key = { it.id }) { download ->
                DownloadStatusRow(
                    download = download,
                    onCancel = { BrowserDownloadDispatcher.cancel(context, download.id) },
                    onDelete = { BrowserDownloadDispatcher.delete(context, download.id) }
                )
            }
        }
    }

    @Composable
    private fun DownloadStatusRow(
        download: BrowserDownloadStatus,
        onCancel: () -> Unit,
        onDelete: () -> Unit
    ) {
        val fraction = download.progressFraction
        ListItem(
            headlineContent = { Text(download.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text(
                        "${if (download.mode == com.example.httpsbrowser.data.BrowserDownloadMode.HIGH) "高速" else "通常"}・${download.phase}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text(
                            "${(fraction * 100).toInt()}%  ${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes ?: 0L)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else if (download.downloadedBytes > 0L) {
                        Text("${formatBytes(download.downloadedBytes)} を取得済み", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            trailingContent = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(
                        if (download.isSuccessful) "完了" else if (download.isTerminal) "確認" else "進行中",
                        color = if (download.isTerminal && !download.isSuccessful) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (download.isTerminal) {
                        TextButton(onClick = onDelete) { Text("削除") }
                    } else {
                        TextButton(onClick = onCancel) { Text("停止") }
                    }
                }
            }
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    @Composable
    private fun DiagnosticsPage(onBack: () -> Unit, onShare: () -> Unit) {
        val context = LocalContext.current
        var details by remember { mutableStateOf(CrashDiagnostics.read(context)) }
        PageHeader("クラッシュ診断", onBack, actionLabel = "更新") { details = CrashDiagnostics.read(context) }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("アプリの終了後に再度起動すると、Android 11以降ではOSが記録した終了理由を表示します。自動送信は行いません。")
            TextButton(onClick = onShare, modifier = Modifier.padding(top = 8.dp)) { Text("診断情報を共有") }
            if (details.isBlank()) {
                Text("まだ診断情報はありません。クラッシュ後にアプリを再度開き、この画面で更新してください。", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(details, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    @Composable
    private fun OpenSourceLicensesPage(onBack: () -> Unit) {
        val context = LocalContext.current
        var fulgurisNotice by remember { mutableStateOf("") }
        var thirdPartyNotice by remember { mutableStateOf("") }
        var cpalText by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val documents = withContext(Dispatchers.IO) {
                fun readAsset(name: String) = runCatching {
                    context.assets.open("licenses/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrDefault("ライセンス文書を読み込めませんでした。")
                Triple(
                    readAsset("FULGURIS_CPAL_NOTICE.md"),
                    readAsset("THIRD_PARTY_NOTICES.md"),
                    readAsset("CPAL-1.0.txt")
                )
            }
            fulgurisNotice = documents.first
            thirdPartyNotice = documents.second
            cpalText = documents.third
        }
        PageHeader("オープンソースライセンス", onBack)
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Powered by Fulguris Browser", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ねこぶらうざはFulguris由来の動画ライフサイクル、全画面表示、Google Translate遷移を最小限適合しています。詳細な変更記録と対応ソースは以下に表示します。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                HorizontalDivider()
            }
            item { LicenseSection("Fulguris CPAL-1.0変更記録", fulgurisNotice) }
            item { LicenseSection("第三者通知", thirdPartyNotice) }
            item { LicenseSection("CPAL-1.0 全文", cpalText) }
        }
    }

    @Composable
    private fun LicenseSection(title: String, content: String) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (content.isBlank()) "読み込み中…" else content,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        HorizontalDivider()
    }

    @Composable
    private fun DataPage(onClear: () -> Unit, onBack: () -> Unit) {
        var confirmation by remember { mutableStateOf(false) }
        PageHeader("閲覧データ", onBack)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("履歴、開いているタブ、WebView のキャッシュを消去します。ブックマークは残ります。")
            TextButton(onClick = { confirmation = true }, modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("閲覧データを消去")
            }
        }
        if (confirmation) AlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text("閲覧データを消去しますか？") },
            text = { Text("履歴、タブ、キャッシュを削除します。") },
            confirmButton = { TextButton(onClick = { onClear(); confirmation = false }) { Text("消去") } },
            dismissButton = { TextButton(onClick = { confirmation = false }) { Text("キャンセル") } }
        )
    }

    @Composable
    fun BookmarkEditorDialog(
        title: String,
        initialTitle: String,
        initialUrl: String,
        onConfirm: (String, String) -> Unit,
        onDismiss: () -> Unit
    ) {
        var bookmarkTitle by remember(title, initialTitle) { mutableStateOf(initialTitle) }
        var bookmarkUrl by remember(title, initialUrl) { mutableStateOf(initialUrl) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(value = bookmarkTitle, onValueChange = { bookmarkTitle = it }, singleLine = true, label = { Text("名前") })
                    OutlinedTextField(value = bookmarkUrl, onValueChange = { bookmarkUrl = it }, singleLine = true, label = { Text("URL または検索語") }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { onConfirm(bookmarkTitle, bookmarkUrl) }) { Text("決定") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
        )
    }

    @Composable
    private fun AdBlockEditorDialog(title: String, initialName: String, initialUrl: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
        var name by remember(title, initialName) { mutableStateOf(initialName) }
        var url by remember(title, initialUrl) { mutableStateOf(initialUrl) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("リスト名") })
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("https://…") }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { onConfirm(name, url) }) { Text("保存") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
        )
    }

    @Composable
    private fun PageHeader(title: String, onBack: () -> Unit, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "設定に戻る") }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
            }
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Icon(Icons.Default.Add, null); Text(actionLabel) }
        }
    }

    @Composable
    private fun NavigationItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
        ListItem(modifier = Modifier.clickable { onClick() }, headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) })
    }

    @Composable private fun SheetTitle(text: String) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
    }

    @Composable private fun EmptyRow(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }

    @Composable private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
        ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) })
    }
}
