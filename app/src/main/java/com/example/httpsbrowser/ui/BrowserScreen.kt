package com.example.httpsbrowser.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.httpsbrowser.MainActivity
import com.example.httpsbrowser.data.BrowserDownloadDispatcher
import com.example.httpsbrowser.data.BrowserDownloadMode
import com.example.httpsbrowser.data.BrowserDownloadRequest
import com.example.httpsbrowser.data.SettingsPage
import com.example.httpsbrowser.web.BrowserWebCallbacks
import com.example.httpsbrowser.web.BrowserWebViewRegistry
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import java.io.File
import java.util.Locale
import java.io.FileInputStream
import java.net.URI

private data class PendingWebPermission(
    val origin: String,
    val webResources: Set<String>,
    val appPermissions: Array<String>,
    val reply: (Boolean) -> Unit
)

private data class FullscreenContent(
    val view: View,
    val callback: WebChromeClient.CustomViewCallback
)

/**
 * GoogleアカウントのWebダイアログはページ内の右端をスクロール操作に使う。
 * DOM・viewport・CSSには触れず、Google系文書でのみ独自レールを外してWebViewへ全てのタッチを渡す。
 */
private fun isGoogleWebSurface(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
    return host == "google.com" || host.endsWith(".google.com") || host.startsWith("google.") || host.contains(".google.")
}

private fun shouldShowRightEdgeScrollRail(url: String): Boolean = true

private fun isGoogleImageSurface(url: String): Boolean {
    val lower = url.lowercase(Locale.ROOT)
    return isGoogleWebSurface(url) && (lower.contains("tbm=isch") || lower.contains("udm=2"))
}

@Composable
fun BrowserScreen(viewModel: BrowserViewModel, externalUrl: String? = null) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // WebViewはActivity contextで生成する。application contextではWindow/表示設定に紐づかず、
    // 動画surface・全画面・autofillの描画経路が不安定になり得る。
    val registry = remember { BrowserWebViewRegistry(context) }
    val state = viewModel.uiState
    val selectedTab = state.selectedTab
    var progress by remember(selectedTab?.id) { mutableIntStateOf(0) }
    var scrollFraction by remember(selectedTab?.id) { mutableFloatStateOf(0f) }
    var rendererVersion by remember { mutableIntStateOf(0) }
    var pendingPermission by remember { mutableStateOf<PendingWebPermission?>(null) }
    var fullscreenContent by remember { mutableStateOf<FullscreenContent?>(null) }
    var longPressedLink by remember { mutableStateOf<String?>(null) }
    var linkShortcutUrl by remember { mutableStateOf<String?>(null) }
    var externalAppUrl by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var addBookmarkDialog by remember { mutableStateOf(false) }
    var editingHomeBookmark by remember { mutableStateOf<com.example.httpsbrowser.data.Bookmark?>(null) }
    var homeBookmarkEditMode by remember { mutableStateOf(false) }
    var homeBookmarkSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingPageArchive by remember { mutableStateOf<File?>(null) }
    var pendingDownload by remember { mutableStateOf<BrowserDownloadRequest?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val pending = pendingPermission ?: return@rememberLauncherForActivityResult
        val granted = pending.appPermissions.all { result[it] == true ||
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        pending.reply(granted)
        pendingPermission = null
    }

    /** 編集状態・フォーカス・IMEを同じ操作で終了し、遷移後にキーボードだけが残らないようにする。 */
    fun endAddressEditing() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        viewModel.stopAddressEditing()
    }

    /** 新規URL入力だけをWebViewへ命令し、戻る・進む・ページ内遷移ではWebView履歴を再読込しない。 */
    fun navigate(input: String? = null) {
        // IME callbackはCompose再構成前のstateを参照し得るため、入力部品から渡された最新値を優先する。
        val navigationInput = input ?: viewModel.uiState.addressInput
        endAddressEditing()
        val prepared = viewModel.prepareNavigation(navigationInput) ?: return
        selectedTab?.let { tab -> registry.load(tab.id, prepared.url) }
    }

    val pageArchiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val archive = pendingPageArchive
        pendingPageArchive = null
        if (destination != null && archive != null) {
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    FileInputStream(archive).use { input -> input.copyTo(output) }
                } ?: error("保存先を開けませんでした。")
            }.onSuccess {
                notice = "ページを保存しました。"
            }.onFailure {
                notice = "ページを保存できませんでした: ${it.message ?: "保存先を確認してください。"}"
            }
        }
        archive?.delete()
    }

    LaunchedEffect(externalUrl) { externalUrl?.let(::navigate) }
    LaunchedEffect(state.isAddressFocused) {
        if (!state.isAddressFocused) {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }
    // IMEの可視性はWebView hostの再配置や端末実装の通知順によって一時的にfalseになり得る。
    // ここで編集状態を終了すると、URLバーをタップした直後にフォーカスとIMEが失われるため、
    // 編集終了は戻る操作・明示的な画面操作・TextFieldの実フォーカス喪失だけで行う。
    LaunchedEffect(state.bookmarks) {
        homeBookmarkSelection = homeBookmarkSelection.intersect(state.bookmarks.map { it.id }.toSet())
    }
    DisposableEffect(Unit) { onDispose { registry.close() } }

    LaunchedEffect(
        state.isTabSheetVisible,
        state.isSettingsSheetVisible,
        pendingPermission,
        longPressedLink,
        linkShortcutUrl,
        externalAppUrl,
        notice,
        addBookmarkDialog,
        editingHomeBookmark
    ) {
        val overlayVisible = state.isTabSheetVisible || state.isSettingsSheetVisible ||
            pendingPermission != null || longPressedLink != null || linkShortcutUrl != null || externalAppUrl != null ||
            notice != null || addBookmarkDialog || editingHomeBookmark != null
        (activity as? MainActivity)?.setNormalWebContentVisible(!overlayVisible)
    }

    /**
     * Fulgurisの`onHideCustomView`と同じ所有権の順序で、custom viewを一度だけ解放する。
     * 先に状態を空にするため、callbackに伴う再入onHideCustomViewでは何も二重に外さない。
     */
    fun finishFullscreen(notifyPage: Boolean) {
        val content = fullscreenContent ?: return
        fullscreenContent = null
        selectedTab?.let { registry.setFullscreenVideoDarkeningSuppressed(it.id, false) }
        // Activity rootのnative containerを先に外す。ComposeのAndroidViewへ差し戻さないため、
        // Chromiumの動画surfaceが再親子化されず、全画面終了時の停止を抑える。
        (activity as? MainActivity)?.hideFullscreenCustomView(content.view)
        viewModel.setFullscreen(false)
        if (notifyPage) runCatching { content.callback.onCustomViewHidden() }
    }

    fun handleWebViewHideFullscreen() {
        if ((activity as? MainActivity)?.shouldRetainFullscreenCustomView() == true) {
            // PiP遷移ではWebViewがonHideCustomViewを先に通知することがある。
            // この時点でcustom viewを外すと通常の下部バーがPiPに入り、再生も止まる。
            com.example.httpsbrowser.CrashDiagnostics.record("pip_custom_view_retained", "reason=webview_hide_during_pip")
            return
        }
        finishFullscreen(false)
    }

    /**
     * Fulgurisの`onShowCustomView`から、Compose UIに必要な最小の状態遷移だけを移植する。
     * WebChromeClientが重複してcustom viewを送った場合、新しいviewを重ねず即時に拒否する。
     */
    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenContent != null) {
            com.example.httpsbrowser.CrashDiagnostics.record("fullscreen_duplicate_show", "ignored=true")
            runCatching { callback.onCustomViewHidden() }
            return
        }
        fullscreenContent = FullscreenContent(view, callback)
        selectedTab?.let { registry.setFullscreenVideoDarkeningSuppressed(it.id, true) }
        viewModel.setFullscreen(true)
        // PiP、native fullscreen container、system barはActivityへ一元化する。
        (activity as? MainActivity)?.let { hostActivity ->
            hostActivity.showFullscreenCustomView(view, selectedTab?.id)
            selectedTab?.let { tab ->
                hostActivity.showVideoControls(
                    registry = registry,
                    tabId = tab.id,
                    pageUrl = tab.url,
                    allowedHosts = state.settings.videoControlHosts,
                    initialRate = state.settings.videoPlaybackRate,
                    onPlaybackRateChanged = { rate ->
                        viewModel.updateSettings { it.copy(videoPlaybackRate = rate) }
                    }
                )
            }
        }
    }

    LaunchedEffect(selectedTab?.id, selectedTab?.isHome, selectedTab?.lastRequestedUrl, state.settings, rendererVersion) {
        val hostActivity = activity as? MainActivity
        val tab = selectedTab
        if (hostActivity == null || tab == null || tab.isHome) {
            // ホームは背後のWebViewを保持したままComposeを前面化するだけで、履歴状態を加工しない。
            hostActivity?.hideNormalWebContent()
        } else {
            registry.obtain(tab, state.settings, callbacksFor(
                viewModel = viewModel,
                registry = registry,
                tabId = tab.id,
                onProgress = { progress = it },
                onScrollPosition = { scrollFraction = it },
                onFullscreen = ::enterFullscreen,
                onHideFullscreen = ::handleWebViewHideFullscreen,
                onVideoDimensions = { width, height ->
                    hostActivity.updatePictureInPictureVideoDimensions(tab.id, width, height)
                    hostActivity.showVideoControls(
                        registry = registry,
                        tabId = tab.id,
                        pageUrl = tab.url,
                        allowedHosts = state.settings.videoControlHosts,
                        initialRate = state.settings.videoPlaybackRate,
                        onPlaybackRateChanged = { rate ->
                            viewModel.updateSettings { it.copy(videoPlaybackRate = rate) }
                        }
                    )
                },
                onPermission = { origin, resources, reply ->
                    pendingPermission = PendingWebPermission(origin, resources, requiredAndroidPermissions(resources), reply)
                },
                onLongPress = { longPressedLink = it },
                showNotice = { notice = it },
                onExternalApp = { externalAppUrl = it },
                onRendererGone = {
                    rendererVersion++
                },
                onDownloadRequested = { pendingDownload = it },
                onPageArchiveReady = { sourcePath, fileName ->
                    pendingPageArchive = File(sourcePath)
                    pageArchiveLauncher.launch(fileName)
                }
            ))
        }
    }

    /** 独自ホームはCompose表示のみを切り替え、同じWebViewの実履歴を破棄・加工しない。 */
    fun returnSelectedTabToHome() {
        val tabId = selectedTab?.takeIf { !it.isHome }?.id
        // AddressBarのTextFieldValueはstate.addressInputの変化に同期するため、先に空文字を流して残留を防ぐ。
        viewModel.returnSelectedTabToHome()
        tabId?.let {
            (activity as? MainActivity)?.hideNormalWebContent()
        }
    }

    BackHandler {
        when {
            state.isFullscreen -> finishFullscreen(true)
            state.isAddressFocused -> viewModel.stopAddressEditing()
            state.isTabSheetVisible -> viewModel.toggleTabSheet()
            state.isSettingsSheetVisible -> viewModel.backFromSettingsPage()
            homeBookmarkEditMode -> {
                homeBookmarkEditMode = false
                homeBookmarkSelection = emptySet()
            }
            // 通常ページでは、WebView実履歴を最後まで使い切った次の戻るで必ず独自ホームへ戻る。
            // タブの生成起点・callback到着順・過去のミラー状態には依存させない。
            selectedTab?.isHome == false -> {
                if (registry.canGoBack(selectedTab.id)) registry.goBack(selectedTab.id)
                else returnSelectedTabToHome()
            }
            // ホームではAndroidの通常戻るとして終了する。
            else -> activity?.finish()
        }
    }

    Box(
        // system bars/IMEはMainActivityのrootで一度だけ処理済み。ここでpaddingを重ねない。
        modifier = if (selectedTab?.isHome == true) {
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        } else {
            Modifier.fillMaxSize()
        }
    ) {
        // Chromium WebViewのfullscreenは元のWebViewを残したままcustom viewを上に重ねる。
        // 通常Viewを外すとAwContentsの描画先が切り替わり、再生停止や黒画面の原因になる。
        if (selectedTab != null) {
            // 表示領域と操作バーを重ねずに分離する。操作バーのタップは WebView へ透過しない。
            Column(Modifier.fillMaxSize()) {
                Box(
                    (if (state.isFullscreen) Modifier.fillMaxSize() else Modifier.weight(1f).fillMaxWidth())
                ) {
                    if (selectedTab.isHome) {
                        HomeScreen(
                            bookmarks = state.bookmarks,
                            editMode = homeBookmarkEditMode,
                            selectedIds = homeBookmarkSelection,
                            onOpenBookmark = { bookmark -> navigate(bookmark.url) },
                            onAddBookmark = { addBookmarkDialog = true },
                            onEnterEditMode = { id ->
                                homeBookmarkEditMode = true
                                homeBookmarkSelection = setOf(id)
                            },
                            onToggleSelection = { id ->
                                homeBookmarkSelection = if (id in homeBookmarkSelection) homeBookmarkSelection - id else homeBookmarkSelection + id
                            },
                            onExitEditMode = {
                                homeBookmarkEditMode = false
                                homeBookmarkSelection = emptySet()
                            },
                            onBackgroundTap = ::endAddressEditing
                        )
                    } else {
                        // Fulgurisと同じく、WebViewはツールバー外側のコンテンツ領域へ直接配置する。
                        // Activity全体の背面host、positionInRoot、タッチ中継は使わない。
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                FrameLayout(context).apply {
                                    clipChildren = true
                                    clipToPadding = true
                                }
                            },
                            update = { container ->
                                registry.attachToNativeHost(selectedTab.id, container)
                            },
                            onRelease = { container -> container.removeAllViews() }
                        )
                    }
                        if (!state.isFullscreen) {
                            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                                RightEdgeScrollRail(
                                    currentFraction = scrollFraction,
                                    onScrollToFraction = { fraction -> registry.scrollToFraction(selectedTab.id, fraction) }
                                )
                            }
                        }
                    }
                if (!state.isFullscreen) {
                    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    if (state.isSuggestionPanelVisible) SuggestionPanel(state.suggestions) { suggestion ->
                        navigate(suggestion.url)
                    }
                    AddressBar(
                        value = state.addressInput,
                        progress = progress,
                        isEditing = state.isAddressFocused,
                        onValueChange = viewModel::setAddressInput,
                        // 新規入力時だけURLを読み込む。WebViewの戻る・進むではloadUrlを呼ばない。
                        onSubmit = { input -> navigate(input) },
                        onTranslate = { if (!selectedTab.isHome) registry.translateToJapanese(selectedTab.id) },
                        onRefresh = { if (!selectedTab.isHome) registry.reload(selectedTab.id) },
                        onEditingStarted = viewModel::startAddressEditing,
                        onEditingStopped = viewModel::stopAddressEditing
                    )
                    // IME表示中はURLバーと横の翻訳・更新ボタンだけをキーボード直上に固定する。
                    // 操作列とタブバーを同時に再計測しないため、キーボードにめり込んだり戻ったりしない。
                    if (!state.isAddressFocused) {
                        // 通常ページの戻るはWebView履歴の有無を問わず有効にする。履歴が尽きた時は必ずホームへ戻すため、
                        // callback到着の遅れでボタン自体が押せなくなる状態を作らない。
                        val canReturnToHome = !selectedTab.isHome
                        val actualCanGoForward = if (selectedTab.isHome) {
                            viewModel.canResumeSelectedTabFromHome()
                        } else {
                            registry.canGoForward(selectedTab.id)
                        }
                        NavigationRow(
                            canGoBack = canReturnToHome,
                            canGoForward = actualCanGoForward,
                            onTabs = { endAddressEditing(); viewModel.toggleTabSheet() },
                            onBack = {
                                viewModel.stopAddressEditing()
                                if (!selectedTab.isHome) {
                                    // 履歴があればWebViewを戻し、尽きていれば起点を問わず独自ホームへ戻る。
                                    if (registry.canGoBack(selectedTab.id)) registry.goBack(selectedTab.id)
                                    else returnSelectedTabToHome()
                                }
                            },
                            onSearch = viewModel::startAddressEditing,
                            onForward = {
                                viewModel.stopAddressEditing()
                                // ホームの進むは背後の最後のページを再開する。通常ページだけWebView履歴を進める。
                                if (selectedTab.isHome) viewModel.resumeSelectedTabFromHome()
                                else registry.goForward(selectedTab.id)
                            },
                            onBookmark = { viewModel.stopAddressEditing(); addBookmarkDialog = true },
                            onHistory = { viewModel.stopAddressEditing(); viewModel.openSettings(SettingsPage.HISTORY) },
                            onDownloads = { viewModel.stopAddressEditing(); viewModel.openSettings(SettingsPage.DOWNLOADS) },
                            onSavePage = {
                                viewModel.stopAddressEditing()
                                if (!selectedTab.isHome) registry.savePageArchive(selectedTab.id, selectedTab.title)
                            },
                            onShare = { viewModel.stopAddressEditing(); if (!selectedTab.isHome) shareUrl(context, registry.currentUrl(selectedTab.id) ?: selectedTab.url) },
                            onSettings = { viewModel.stopAddressEditing(); viewModel.openSettings() }
                        )
                        TabBar(
                            tabs = state.tabs,
                            selectedTabId = state.selectedTabId,
                            onSelect = { id -> viewModel.stopAddressEditing(); viewModel.selectTab(id) },
                            onClose = { id -> viewModel.stopAddressEditing(); registry.remove(id); viewModel.closeTab(id) },
                            onAdd = { viewModel.stopAddressEditing(); viewModel.addTab() }
                        )
                    }
                    }
                }
                }
            }

        if (!state.isFullscreen && selectedTab?.isHome == true && homeBookmarkEditMode) {
            BookmarkEditActionBar(
                selectedCount = homeBookmarkSelection.size,
                onEdit = {
                    editingHomeBookmark = state.bookmarks.firstOrNull { it.id in homeBookmarkSelection }
                },
                onDelete = {
                    viewModel.removeBookmarks(homeBookmarkSelection)
                    homeBookmarkEditMode = false
                    homeBookmarkSelection = emptySet()
                },
                onMoveToBottomRight = { viewModel.moveBookmarks(homeBookmarkSelection, false) },
                onMoveToTopLeft = { viewModel.moveBookmarks(homeBookmarkSelection, true) },
                onDone = {
                    homeBookmarkEditMode = false
                    homeBookmarkSelection = emptySet()
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 138.dp)
            )
        }

        if (state.isTabSheetVisible) {
            BrowserSheets.TabSheet(
                tabs = state.tabs,
                selectedTabId = state.selectedTabId,
                onSelect = viewModel::selectTab,
                onClose = { id -> registry.remove(id); viewModel.closeTab(id) },
                onNewTab = { isPrivate -> viewModel.addTab(isPrivate = isPrivate) },
                onPrivateModeChanged = { enabled ->
                    if (enabled) viewModel.switchToPrivateTab() else viewModel.switchToNormalTab()
                },
                onDismiss = viewModel::toggleTabSheet
            )
        }
        if (state.isSettingsSheetVisible) {
            BrowserSheets.SettingsSheet(
                state = state,
                onSettings = viewModel::updateSettings,
                onOpenUrl = { url ->
                    navigate(url)
                    viewModel.closeSettings()
                },
                onOpenPage = viewModel::showSettingsPage,
                onBack = viewModel::backFromSettingsPage,
                onDismiss = viewModel::closeSettings,
                onSaveBookmark = viewModel::addBookmark,
                onUpdateBookmark = viewModel::updateBookmark,
                onDeleteBookmark = viewModel::removeBookmark,
                onDeleteHistory = viewModel::removeHistory,
                onClear = { viewModel.clearBrowsingData { registry.clearAllBrowsingData() }; viewModel.closeSettings() },
                onDownloads = { viewModel.showSettingsPage(SettingsPage.DOWNLOADS) },
                onShareDiagnostics = { runCatching { com.example.httpsbrowser.CrashDiagnostics.share(context) }.onFailure { notice = "診断情報を共有できませんでした。" } },
                onNotice = { notice = it }
            )
        }
    }

    if (addBookmarkDialog) {
        val tab = selectedTab
        BrowserSheets.BookmarkEditorDialog(
            title = "ブックマークを追加",
            initialTitle = tab?.title?.takeIf { it != "ホーム" }.orEmpty(),
            initialUrl = tab?.url.orEmpty(),
            onConfirm = { title, url ->
                if (viewModel.addBookmark(title, url)) addBookmarkDialog = false else notice = "HTTPS URL または検索語を入力してください。"
            },
            onDismiss = { addBookmarkDialog = false }
        )
    }

    linkShortcutUrl?.let { url ->
        BrowserSheets.BookmarkEditorDialog(
            title = "ホームショートカットに追加",
            initialTitle = Uri.parse(url).host.orEmpty(),
            initialUrl = url,
            onConfirm = { title, savedUrl ->
                if (viewModel.addBookmark(title, savedUrl)) linkShortcutUrl = null
                else notice = "HTTPS URL または検索語を入力してください。"
            },
            onDismiss = { linkShortcutUrl = null }
        )
    }

    editingHomeBookmark?.let { bookmark ->
        BrowserSheets.BookmarkEditorDialog(
            title = "ブックマークを編集",
            initialTitle = bookmark.title,
            initialUrl = bookmark.url,
            onConfirm = { title, url ->
                if (viewModel.updateBookmark(bookmark.id, title, url)) editingHomeBookmark = null
                else notice = "HTTPS URL または検索語を入力してください。"
            },
            onDismiss = { editingHomeBookmark = null }
        )
    }

    pendingPermission?.let { pending ->
        AlertDialog(
            onDismissRequest = { pending.reply(false); pendingPermission = null },
            title = { Text("サイト権限の確認") },
            text = { Text("${pending.origin} が ${pending.webResources.joinToString()} へのアクセスを求めています。許可しますか？") },
            confirmButton = {
                Button(onClick = {
                    if (pending.appPermissions.isEmpty()) { pending.reply(false); pendingPermission = null }
                    else permissionLauncher.launch(pending.appPermissions)
                }) { Text("許可") }
            },
            dismissButton = { TextButton(onClick = { pending.reply(false); pendingPermission = null }) { Text("拒否") } }
        )
    }

    externalAppUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { externalAppUrl = null },
            title = { Text("外部アプリをブロックしました") },
            text = { Text("このリンクはアプリを開こうとしました。対応アプリで開く場合だけ、下のボタンを押してください。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)) }
                    externalAppUrl = null
                }) { Text("アプリで開く") }
            },
            dismissButton = { TextButton(onClick = { externalAppUrl = null }) { Text("このブラウザに留まる") } }
        )
    }

    pendingDownload?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("ダウンロード") },
            text = {
                Text(
                    "${request.fileName} をDownloadsへ保存します。\n\n" +
                        "通常: Android標準の安定したダウンロード\n" +
                        "高速: Range対応の大きなファイルを最大4分割で取得。対応しないサイトでは通常へ自動切替"
                )
            },
            confirmButton = {
                Button(onClick = {
                    BrowserDownloadDispatcher.start(context, request, BrowserDownloadMode.NORMAL)
                    pendingDownload = null
                }) { Text("通常") }
            },
            dismissButton = {
                TextButton(onClick = {
                    BrowserDownloadDispatcher.start(context, request, BrowserDownloadMode.HIGH)
                    pendingDownload = null
                }) { Text("高速") }
            }
        )
    }

    longPressedLink?.let { url ->
        AlertDialog(
            onDismissRequest = { longPressedLink = null },
            title = { Text("リンクの操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("開く場所や共有方法を選択してください。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.addTab(url); longPressedLink = null }
                    ) { Text("新しいタブで開く") }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { shareUrl(context, url); longPressedLink = null }
                    ) { Text("共有") }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { linkShortcutUrl = url; longPressedLink = null }
                    ) { Text("ホームショートカットに追加") }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("URL", url))
                            longPressedLink = null
                        }
                    ) { Text("URL をコピー") }
                }
            },
            dismissButton = { TextButton(onClick = { longPressedLink = null }) { Text("キャンセル") } }
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("閉じる") } },
            title = { Text("ねこぶらうざ") },
            text = { Text(message) }
        )
    }
}

private fun callbacksFor(
    viewModel: BrowserViewModel,
    registry: BrowserWebViewRegistry,
    tabId: String,
    onProgress: (Int) -> Unit,
    onScrollPosition: (Float) -> Unit,
    onFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit,
    onVideoDimensions: (Int, Int) -> Unit,
    onPermission: (String, Set<String>, (Boolean) -> Unit) -> Unit,
    onLongPress: (String) -> Unit,
    showNotice: (String) -> Unit,
    onExternalApp: (String) -> Unit,
    onRendererGone: () -> Unit,
    onDownloadRequested: (BrowserDownloadRequest) -> Unit,
    onPageArchiveReady: (String, String) -> Unit
) = object : BrowserWebCallbacks {
    override fun onPageStarted(tabId: String, url: String) = viewModel.onPageStarted(tabId, url)
    override fun onPageFinished(tabId: String, url: String, title: String?) = viewModel.onPageFinished(tabId, url, title)
    override fun onVisitedHistory(tabId: String, url: String) = viewModel.onVisitedHistory(tabId, url)
    override fun onTitle(tabId: String, title: String) = viewModel.onTitleChanged(tabId, title)
    override fun onHistoryState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) = viewModel.onHistoryStateChanged(tabId, canGoBack, canGoForward)
    override fun onBackHistoryExhausted(tabId: String) {
        // 非選択タブの遅延callbackが現在のタブをホームへ戻さないよう、tabIdを必ず照合する。
        if (viewModel.uiState.selectedTabId == tabId) viewModel.returnSelectedTabToHome()
    }
    override fun onProgress(tabId: String, progress: Int) = onProgress(progress)
    override fun onScrollPosition(tabId: String, fraction: Float) = onScrollPosition(fraction)
    override fun onHttpsUpgrade(url: String) = registry.load(tabId, url)
    override fun onBlockedNavigation(url: String) = showNotice("HTTPS 接続のみ許可されています。\n$url")
    override fun onSslError(url: String) = showNotice("証明書エラーのため安全に接続できませんでした。\n$url")
    override fun onRendererGone(tabId: String) = onRendererGone()
    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = onFullscreen(view, callback)
    override fun onHideFullscreen() = onHideFullscreen()
    override fun onVideoDimensions(tabId: String, width: Int, height: Int) = onVideoDimensions(width, height)
    override fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit) = onPermission(origin, resources, reply)
    override fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit) = onPermission(origin, setOf("位置情報"), reply)
    override fun onPopupRequested(): String? = viewModel.addTab(isPrivate = viewModel.isPrivateTab(tabId)).id
    override fun onLinkLongPressed(url: String) = onLongPress(url)
    override fun onDownloadRequested(request: BrowserDownloadRequest) = onDownloadRequested(request)
    override fun onPageArchiveReady(sourcePath: String, fileName: String) = onPageArchiveReady(sourcePath, fileName)
    override fun onExternalAppRequested(url: String) = onExternalApp(url)
    override fun onPageInteraction() = viewModel.stopAddressEditing()
    override fun onNotice(message: String) = showNotice(message)
}

private fun requiredAndroidPermissions(resources: Set<String>): Array<String> = buildSet {
    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) add(Manifest.permission.RECORD_AUDIO)
    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) add(Manifest.permission.CAMERA)
    if ("位置情報" in resources) add(Manifest.permission.ACCESS_FINE_LOCATION)
}.toTypedArray()

private fun shareUrl(context: Context, url: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }, "ページを共有"))
}
