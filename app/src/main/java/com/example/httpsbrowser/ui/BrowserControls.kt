package com.example.httpsbrowser.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.httpsbrowser.data.Bookmark
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.Suggestion
import com.example.httpsbrowser.data.SuggestionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.roundToInt

private val BottomBarBlack = Color(0xFF05070A)
private val BottomBarButton = Color(0xFF1C2531)
private val BottomBarButtonEmphasis = Color(0xFF2C5C92)
private val BottomBarText = Color(0xFFF2F6FC)

@Composable
fun AddressBar(
    value: String,
    progress: Int,
    isEditing: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onTranslate: () -> Unit,
    onRefresh: () -> Unit,
    onEditingStarted: () -> Unit,
    onEditingStopped: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // ×ボタンで消去した直後にBasicTextFieldへフォーカスが入ると、onEditingStarted()が
    // 現在のURLを再投入してしまう。次のフォーカス獲得時の編集開始を一度だけ抑制する。
    var suppressNextEditingStart by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (textFieldValue.text != value) textFieldValue = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(isEditing) {
        if (isEditing) {
            // 編集状態へ正式に遷移した後は、次のフォーカス獲得で通常どおり編集開始してよい。
            // ×ボタン直後のフォーカス獲得は既にonFocusChanged側で消費済みのため、ここでは
            // 万一の残留フラグだけを確実にクリアする。
            suppressNextEditingStart = false
            // 編集開始時は現在の文字列末尾へカーソルを置く。編集終了時にここで
            // フォーカスを強制解除しないことで、画面更新とタップが競合してIMEが
            // 一瞬で閉じることを防ぐ。
            if (textFieldValue.text != value) {
                textFieldValue = TextFieldValue(value, TextRange(value.length))
            } else {
                textFieldValue = textFieldValue.copy(
                    selection = TextRange(textFieldValue.text.length),
                    composition = null
                )
            }
            focusRequester.requestFocus()
        }
    }

    Column(Modifier.fillMaxWidth().background(BottomBarBlack).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = { updated -> textFieldValue = updated; onValueChange(updated.text) },
                modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(Color(0xFF474747))
                    .focusRequester(focusRequester).onFocusChanged { focus ->
                        if (focus.isFocused && !isEditing) {
                            // ×ボタンで消去した直後のフォーカス獲得では、onEditingStarted()を呼ぶと
                            // 現在のURLが再投入されて消去が無効化される。一度だけ編集開始を抑制する。
                            if (suppressNextEditingStart) {
                                suppressNextEditingStart = false
                            } else {
                                onEditingStarted()
                            }
                        }
                        // IMEを閉じた端末でも、ホームの空白部や他の操作でフォーカスが外れれば
                        // 編集状態を必ず終了し、下部の操作列・タブバーを復帰する。
                        if (!focus.isFocused && isEditing) onEditingStopped()
                    },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontSize = 14.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(Color.White),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // IMEの検索確定直後にWebViewへフォーカスが移っても、端末差でキーボードが
                    // 残らないよう先に明示的に閉じる。ViewModel側も候補・編集状態を同時に終了する。
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                    onSubmit(textFieldValue.text)
                }),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Google 検索または HTTPS URL を入力", modifier = Modifier.size(18.dp), tint = Color.White)
                        Box(modifier = Modifier.weight(1f).padding(horizontal = 7.dp)) {
                            if (textFieldValue.text.isBlank()) Text("Google 検索または HTTPS URL", color = Color(0xFFD0D0D0), fontSize = 13.sp, maxLines = 1)
                            innerTextField()
                        }
                        if (textFieldValue.text.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    // キーボードが閉じている（未編集）状態で×を押すと、decorationBox内の
                                    // タップがBasicTextFieldへフォーカスを渡し、onEditingStarted()が現在のURLを
                                    // 再投入してしまう。消去直後の編集開始だけを一度抑制する。
                                    if (!isEditing) suppressNextEditingStart = true
                                    textFieldValue = TextFieldValue("", TextRange.Zero)
                                    // 値更新で編集状態を有効化してから、フォーカスとIMEを
                                    // 明示的に要求する。onEditingStarted()は現在URLの再投入を
                                    // 引き起こすため呼ばない。
                                    onValueChange("")
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "入力を消去", modifier = Modifier.size(18.dp), tint = Color.White)
                            }
                        }
                    }
                }
            )
            IconButton(
                onClick = onTranslate,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = BottomBarButton, contentColor = BottomBarText)
            ) { Icon(Icons.Default.Translate, contentDescription = "このページを日本語へ翻訳") }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = BottomBarButton, contentColor = BottomBarText)
            ) { Icon(Icons.Default.Refresh, contentDescription = "再読み込み") }
        }
        if (progress in 1..99) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun SuggestionPanel(suggestions: List<Suggestion>, onClick: (Suggestion) -> Unit) {
    if (suggestions.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        LazyColumn(
            // ViewModelは合計6件までに制限する。reverseLayoutでは先頭要素が下端に置かれるため、
            // ViewModelの優先順（最初=最優先）が画面の下から上へ確実に並ぶ。
            modifier = Modifier.height((suggestions.size * 48).coerceAtMost(288).dp),
            reverseLayout = true
        ) {
            items(suggestions, key = { "${it.type}:${it.url}" }) { suggestion ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onClick(suggestion) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (suggestion.type) {
                            SuggestionType.OPEN_TAB -> Icons.Default.Tab
                            SuggestionType.BOOKMARK -> Icons.Default.Bookmark
                            SuggestionType.HISTORY -> Icons.Default.History
                            SuggestionType.GOOGLE_SEARCH -> Icons.Default.Search
                        },
                        contentDescription = null
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(suggestion.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (suggestion.secondary.isNotBlank()) {
                            Text(suggestion.secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NavigationRow(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onTabs: () -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onForward: () -> Unit,
    onBookmark: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onSavePage: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().background(BottomBarBlack).padding(horizontal = 8.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavButton(Icons.Default.Tab, "タブ一覧", onTabs)
        NavButton(Icons.AutoMirrored.Filled.ArrowBack, "戻る", onBack, enabled = canGoBack)
        NavButton(Icons.Default.Search, "アドレスバーを編集", onSearch, emphasized = true)
        NavButton(Icons.AutoMirrored.Filled.ArrowForward, "進む", onForward, enabled = canGoForward)
        Box {
            NavButton(Icons.Default.Menu, "メニュー", { menuExpanded = true })
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("ホームに追加") }, leadingIcon = { Icon(Icons.Default.Bookmark, null) }, onClick = { menuExpanded = false; onBookmark() })
                DropdownMenuItem(text = { Text("履歴") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { menuExpanded = false; onHistory() })
                DropdownMenuItem(text = { Text("ダウンロード") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menuExpanded = false; onDownloads() })
                DropdownMenuItem(text = { Text("ページを保存") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menuExpanded = false; onSavePage() })
                DropdownMenuItem(text = { Text("共有") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menuExpanded = false; onShare() })
                DropdownMenuItem(text = { Text("設定") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menuExpanded = false; onSettings() })
            }
        }
    }
}

@Composable
private fun NavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(if (emphasized) 44.dp else 40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (emphasized) BottomBarButtonEmphasis else BottomBarButton,
            contentColor = BottomBarText,
            disabledContainerColor = Color(0xFF131820),
            disabledContentColor = Color(0xFF657080)
        )
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(if (emphasized) 24.dp else 21.dp)) }
}

@Composable
fun TabBar(tabs: List<BrowserTab>, selectedTabId: String?, onSelect: (String) -> Unit, onClose: (String) -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(BottomBarBlack).padding(start = 5.dp, top = 2.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            tabs.forEach { tab ->
                val selected = tab.id == selectedTabId
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                tab.isPrivate && selected -> Color(0xFF3D235B)
                                tab.isPrivate -> Color(0xFF2A1B38)
                                selected -> Color(0xFF18375B)
                                else -> Color(0xFF1A2029)
                            }
                        )
                        .then(
                            when {
                                tab.isPrivate && selected -> Modifier.border(2.dp, Color(0xFFC084FC), RoundedCornerShape(12.dp))
                                tab.isPrivate -> Modifier.border(1.dp, Color(0xFF7C4D9E), RoundedCornerShape(12.dp))
                                selected -> Modifier.border(2.dp, Color(0xFF66B5FF), RoundedCornerShape(12.dp))
                                else -> Modifier.border(1.dp, Color(0xFF394554), RoundedCornerShape(12.dp))
                            }
                        )
                        .clickable { onSelect(tab.id) }
                        .padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BookmarkFavicon(
                        url = tab.url,
                        title = tab.title.ifBlank { "ホーム" },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        tab.title.ifBlank { "ホーム" },
                        color = BottomBarText,
                        modifier = Modifier.width(42.dp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(25.dp), colors = IconButtonDefaults.iconButtonColors(contentColor = BottomBarText)) {
                        Icon(Icons.Default.Close, contentDescription = "${tab.title} を閉じる", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(34.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = BottomBarButton, contentColor = BottomBarText)) {
            Icon(Icons.Default.Add, contentDescription = "新しいタブ")
        }
    }
}

@Composable
fun RightEdgeScrollRail(
    currentFraction: Float,
    onScrollToFraction: (Float) -> Unit
) {
    val density = LocalDensity.current
    val trackHeight = 148.dp
    val normalThumbHeight = 20.dp
    val activeThumbHeight = 32.dp
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(currentFraction) }
    val thumbHeight by animateDpAsState(
        targetValue = if (isDragging) activeThumbHeight else normalThumbHeight,
        label = "scrollThumbHeight"
    )
    val railWidth by animateDpAsState(
        targetValue = if (isDragging) 34.dp else 22.dp,
        label = "scrollRailWidth"
    )
    val usableTrackPx = with(density) { (trackHeight - thumbHeight).toPx() }

    LaunchedEffect(currentFraction, isDragging) {
        if (!isDragging) dragFraction = currentFraction.coerceIn(0f, 1f)
    }
    val dragState = rememberDraggableState { delta ->
        dragFraction = (dragFraction + delta / usableTrackPx).coerceIn(0f, 1f)
        onScrollToFraction(dragFraction)
    }
    Box(
        modifier = Modifier
            .width(railWidth)
            .height(trackHeight)
            .alpha(if (isDragging) 0.92f else 0.55f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDragging) Color(0x44212A35) else Color.Transparent)
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = { isDragging = true },
                onDragStopped = { isDragging = false }
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (usableTrackPx * dragFraction).roundToInt()) }
                .height(thumbHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isDragging) Color(0xFF89C4FF) else Color(0xAA89C4FF))
        )
    }
}

@Composable
fun BookmarkFavicon(url: String, title: String, modifier: Modifier = Modifier) {
    var favicon by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        favicon = withContext(Dispatchers.IO) { loadFavicon(url) }
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF5E5E5E)),
        contentAlignment = Alignment.Center
    ) {
        if (favicon != null) {
            Image(
                bitmap = favicon!!,
                contentDescription = "$title のサイトアイコン",
                // faviconは配布サイズが小さいため、余白を切り取って拡大しない。
                // 少し小さくFit表示し、低解像度画像の粗さと端の切れを抑える。
                modifier = Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(7.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(
                Icons.Default.Bookmark,
                contentDescription = "$title のサイトアイコン",
                tint = Color(0xFFE8E8E8)
            )
        }
    }
}

private fun loadFavicon(pageUrl: String): ImageBitmap? = runCatching {
    val pageUri = URI(pageUrl)
    if (!pageUri.scheme.equals("https", ignoreCase = true) || pageUri.host.isNullOrBlank()) return null
    val faviconUri = URI("https", null, pageUri.host, pageUri.port, "/favicon.ico", null, null)
    val connection = (faviconUri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 3_000
        readTimeout = 3_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) HTTPS-Tab-Browser/1.0")
    }
    connection.inputStream.use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
}.getOrNull()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    bookmarks: List<Bookmark>,
    editMode: Boolean,
    selectedIds: Set<String>,
    onOpenBookmark: (Bookmark) -> Unit,
    onAddBookmark: () -> Unit,
    onEnterEditMode: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onExitEditMode: () -> Unit,
    onBackgroundTap: () -> Unit
) {
    val bookmarkCells: List<HomeCell> = bookmarks.take(24).map { HomeCell.BookmarkCell(it) }
    val cells: List<HomeCell> = if (editMode) bookmarkCells else bookmarkCells + HomeCell.AddCell
    val rows = cells.chunked(4)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 14.dp, vertical = 12.dp)) {
        // グリッド外の背景をタップしたらURL編集を終了する。編集モード中は同時に選択も終了する。
        Box(
            modifier = Modifier.fillMaxSize().pointerInput(editMode) {
                detectTapGestures(onTap = {
                    onBackgroundTap()
                    if (editMode) onExitEditMode()
                })
            }
        )
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.End
        ) {
            rows.reversed().forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.reversed().forEach { cell ->
                        when (cell) {
                            is HomeCell.BookmarkCell -> {
                                val selected = cell.bookmark.id in selectedIds
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(60.dp).clip(RoundedCornerShape(10.dp))
                                        .then(if (selected) Modifier.border(2.dp, Color(0xFF7EC8FF), RoundedCornerShape(10.dp)) else Modifier)
                                        .combinedClickable(
                                            onClick = {
                                                if (editMode) onToggleSelection(cell.bookmark.id)
                                                else onOpenBookmark(cell.bookmark)
                                            },
                                            onLongClick = {
                                                if (editMode) onToggleSelection(cell.bookmark.id)
                                                else onEnterEditMode(cell.bookmark.id)
                                            }
                                        )
                                ) {
                                    BookmarkFavicon(
                                        url = cell.bookmark.url,
                                        title = cell.bookmark.title.ifBlank { cell.bookmark.url },
                                        modifier = Modifier.size(38.dp)
                                    )
                                    Text(
                                        cell.bookmark.title.ifBlank { cell.bookmark.url },
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            HomeCell.AddCell -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(60.dp).clickable { onAddBookmark() }
                            ) {
                                Box(
                                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF5E5E5E)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Default.Add, contentDescription = "ブックマークを追加", tint = Color.White) }
                                Text("追加", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarkEditActionBar(
    selectedCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveToBottomRight: () -> Unit,
    onMoveToTopLeft: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE1E2733))
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("ブックマークを選択: $selectedCount 件", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(enabled = selectedCount == 1, onClick = onEdit) { Text("編集") }
                TextButton(enabled = selectedCount > 0, onClick = onDelete) { Text("削除") }
                TextButton(enabled = selectedCount > 0, onClick = onMoveToBottomRight) { Text("右下へ") }
                TextButton(enabled = selectedCount > 0, onClick = onMoveToTopLeft) { Text("左上へ") }
                TextButton(onClick = onDone) { Text("完了") }
            }
        }
    }
}

private sealed interface HomeCell {
    data class BookmarkCell(val bookmark: Bookmark) : HomeCell
    data object AddCell : HomeCell
}
