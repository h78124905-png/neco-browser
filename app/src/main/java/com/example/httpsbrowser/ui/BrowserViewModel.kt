package com.example.httpsbrowser.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.httpsbrowser.data.AddressDisplayMode
import com.example.httpsbrowser.data.Bookmark
import com.example.httpsbrowser.data.BrowserRepository
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.BrowserUiState
import com.example.httpsbrowser.data.HistoryEntry
import com.example.httpsbrowser.data.PreparedNavigation
import com.example.httpsbrowser.data.SettingsPage
import com.example.httpsbrowser.data.Suggestion
import com.example.httpsbrowser.data.SuggestionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BrowserRepository(application)
    private var persistJob: Job? = null
    private var suggestionJob: Job? = null

    var uiState by mutableStateOf(BrowserUiState())
        private set

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { repository.load() }
            val normalizedTabs = stored.tabs.ifEmpty { listOf(homeTab()) }.map { tab ->
                val isLegacyStartTab = tab.url == GOOGLE_HOME && tab.title == "新しいタブ"
                if (tab.url.isBlank() || tab.isHome || isLegacyStartTab) tab.copy(
                    url = "", lastRequestedUrl = "", displayText = "", title = tab.title.ifBlank { "ホーム" }, isHome = true
                ) else tab.copy(isHome = false)
            }
            val selected = stored.selectedTabId?.takeIf { id -> normalizedTabs.any { it.id == id } }
                ?: normalizedTabs.first().id
            uiState = BrowserUiState(
                tabs = normalizedTabs,
                selectedTabId = selected,
                addressInput = normalizedTabs.firstOrNull { it.id == selected }?.displayText.orEmpty(),
                history = stored.history,
                bookmarks = stored.bookmarks,
                settings = stored.settings
            )
        }
    }

    fun selectTab(id: String) {
        val tab = uiState.tabs.firstOrNull { it.id == id } ?: return
        uiState = uiState.copy(
            selectedTabId = id,
            addressInput = if (tab.isHome) "" else tab.displayText.ifBlank { tab.url },
            isAddressFocused = false,
            isSuggestionPanelVisible = false,
            suggestions = emptyList(),
            isTabSheetVisible = false
        )
        persistSoon()
    }

    fun addTab(url: String = "", isPrivate: Boolean = false): BrowserTab {
        val prepared = if (url.isBlank()) null else buildNavigation(url)
        val tab = if (prepared == null) homeTab(isPrivate) else BrowserTab(
            url = prepared.url,
            title = prepared.displayText.ifBlank { "新しいタブ" },
            displayText = prepared.displayText,
            displayMode = prepared.displayMode,
            lastRequestedUrl = prepared.url,
            isHome = false,
            isPrivate = isPrivate
        )
        uiState = uiState.copy(
            tabs = uiState.tabs + tab,
            selectedTabId = tab.id,
            addressInput = tab.displayText,
            isAddressFocused = false,
            isSuggestionPanelVisible = false,
            suggestions = emptyList()
        )
        persistSoon()
        return tab
    }

    fun closeTab(id: String): BrowserTab? {
        val current = uiState.tabs
        val closingIndex = current.indexOfFirst { it.id == id }
        if (closingIndex < 0) return null
        val remaining = current.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val newTab = homeTab()
            uiState = uiState.copy(
                tabs = listOf(newTab),
                selectedTabId = newTab.id,
                addressInput = "",
                isAddressFocused = false,
                isSuggestionPanelVisible = false,
                suggestions = emptyList()
            )
        } else {
            val next = remaining.getOrElse((closingIndex - 1).coerceAtLeast(0)) { remaining.last() }
            uiState = uiState.copy(
                tabs = remaining,
                selectedTabId = next.id,
                addressInput = if (next.isHome) "" else next.displayText.ifBlank { next.url },
                isAddressFocused = false,
                isSuggestionPanelVisible = false,
                suggestions = emptyList()
            )
        }
        persistSoon()
        return current[closingIndex]
    }

    fun startAddressEditing() {
        val tab = uiState.selectedTab ?: return
        // Google検索ページではdisplayTextに検索語を保持している。URLバーを開いた直後から
        // その語を候補生成へ渡し、入力し直さなくても履歴・Google候補を表示する。
        beginAddressEditing(if (tab.isHome) "" else tab.displayText.ifBlank { tab.url })
    }

    fun stopAddressEditing() {
        suggestionJob?.cancel()
        val tab = uiState.selectedTab ?: return
        uiState = uiState.copy(
            addressInput = if (tab.isHome) "" else tab.displayText.ifBlank { tab.url },
            isAddressFocused = false,
            isSuggestionPanelVisible = false,
            suggestions = emptyList()
        )
    }

    fun setAddressInput(value: String) = beginAddressEditing(value)

    /**
     * URLバーをタップした場合と文字を入力した場合で、候補の生成・非同期取得・表示条件を共通化する。
     * これによりGoogle検索ページを編集した直後も、表示中の検索語から候補を開始できる。
     */
    private fun beginAddressEditing(value: String) {
        val query = value.trim()
        val localSuggestions = createSuggestions(value)
        uiState = uiState.copy(
            addressInput = value,
            isAddressFocused = true,
            // 端末内候補がゼロでも2文字以上ならGoogle候補の到着を待ってパネルを開く。
            isSuggestionPanelVisible = query.length >= 2 || (query.isNotBlank() && localSuggestions.isNotEmpty()),
            suggestions = localSuggestions
        )
        suggestionJob?.cancel()
        if (query.length < 2) return
        suggestionJob = viewModelScope.launch {
            delay(180)
            val googleQueries = withContext(Dispatchers.IO) { fetchGoogleSuggestions(query) }
            // 古い入力に対する応答や、編集終了後の応答では画面を上書きしない。
            if (uiState.isAddressFocused && uiState.isSuggestionPanelVisible && uiState.addressInput == value) {
                val refreshed = createSuggestions(value, googleQueries)
                uiState = uiState.copy(suggestions = refreshed, isSuggestionPanelVisible = refreshed.isNotEmpty())
            }
        }
    }

    fun prepareNavigation(input: String = uiState.addressInput): PreparedNavigation? {
        val prepared = buildNavigation(input) ?: return null
        updateSelected { tab ->
            tab.copy(
                url = prepared.url,
                lastRequestedUrl = prepared.url,
                displayText = prepared.displayText,
                displayMode = prepared.displayMode,
                isHome = false
            )
        }
        suggestionJob?.cancel()
        uiState = uiState.copy(
            addressInput = prepared.displayText,
            isAddressFocused = false,
            isSuggestionPanelVisible = false,
            suggestions = emptyList()
        )
        persistSoon()
        return prepared
    }

    /**
     * ホームはWebView履歴の項目ではなく、最後に表示したHTTPS文書を背後に保つアプリ内オーバーレイ。
     * 再開先だけをlastRequestedUrlに保持し、Chromiumのabout:blank履歴や擬似的なforward状態を作らない。
     */
    fun openHome() {
        updateSelected { tab ->
            val resumeUrl = tab.lastRequestedUrl.ifBlank { tab.url }
            tab.copy(
                url = "",
                lastRequestedUrl = resumeUrl,
                title = "ホーム",
                displayText = "",
                displayMode = AddressDisplayMode.URL,
                isHome = true,
                canGoBack = false,
                canGoForward = false
            )
        }
        suggestionJob?.cancel()
        uiState = uiState.copy(addressInput = "", isAddressFocused = false, isSuggestionPanelVisible = false, suggestions = emptyList())
        persistSoon()
    }

    /** ホームの「進む」はWebViewのforwardではなく、背後に保持したページを再表示する操作。 */
    fun canResumeSelectedTabFromHome(): Boolean {
        val tab = uiState.selectedTab ?: return false
        return tab.isHome && buildNavigation(tab.lastRequestedUrl) != null
    }

    fun resumeSelectedTabFromHome() {
        val tab = uiState.selectedTab ?: return
        val prepared = buildNavigation(tab.lastRequestedUrl) ?: return
        updateSelected { current ->
            current.copy(
                url = prepared.url,
                lastRequestedUrl = prepared.url,
                title = prepared.displayText.ifBlank { prepared.url },
                displayText = prepared.displayText,
                displayMode = prepared.displayMode,
                isHome = false
            )
        }
        uiState = uiState.copy(addressInput = prepared.displayText, isAddressFocused = false, isSuggestionPanelVisible = false, suggestions = emptyList())
        persistSoon()
    }

    fun onPageStarted(tabId: String, url: String) {
        if (!isHttps(url)) return
        updateTab(tabId) { it.copy(lastRequestedUrl = url, isHome = false) }
        // リンク、戻る、キーボード検索を含むすべての遷移で候補とキーボードを閉じる。
        if (uiState.selectedTabId == tabId) {
            suggestionJob?.cancel()
            uiState = uiState.copy(isAddressFocused = false, isSuggestionPanelVisible = false, suggestions = emptyList())
        }
    }

    fun onPageFinished(tabId: String, url: String, title: String?) {
        if (!isHttps(url)) return
        updateTab(tabId) { tab ->
            val googleQuery = googleSearchQuery(url)
            tab.copy(
                url = url,
                title = title?.takeIf(String::isNotBlank) ?: tab.title,
                displayText = googleQuery ?: url,
                displayMode = if (googleQuery != null) AddressDisplayMode.SEARCH else AddressDisplayMode.URL,
                isHome = false
            )
        }
        val tab = uiState.tabs.firstOrNull { it.id == tabId } ?: return
        if (!tab.isPrivate) {
            val entry = HistoryEntry(title = tab.title, url = url, query = tab.displayText.takeIf { tab.displayMode == AddressDisplayMode.SEARCH })
            uiState = uiState.copy(history = listOf(entry) + uiState.history.filterNot { it.url == url }.take(499))
        }
        if (uiState.selectedTabId == tabId && !uiState.isAddressFocused) uiState = uiState.copy(addressInput = tab.displayText)
        persistSoon()
    }

    /**
     * YouTube等のSPAがHistory APIだけでURLを変えた時の追従処理。
     * main-frame loadを伴わないためonPageFinishedだけでは共有先URLが古いまま残る。
     */
    fun onVisitedHistory(tabId: String, url: String) {
        if (!isHttps(url)) return
        updateTab(tabId) { tab ->
            val googleQuery = googleSearchQuery(url)
            tab.copy(
                url = url,
                lastRequestedUrl = url,
                displayText = googleQuery ?: url,
                displayMode = if (googleQuery != null) AddressDisplayMode.SEARCH else AddressDisplayMode.URL,
                isHome = false
            )
        }
        val tab = uiState.tabs.firstOrNull { it.id == tabId } ?: return
        if (!tab.isPrivate) {
            val entry = HistoryEntry(title = tab.title, url = url, query = tab.displayText.takeIf { tab.displayMode == AddressDisplayMode.SEARCH })
            uiState = uiState.copy(history = listOf(entry) + uiState.history.filterNot { it.url == url }.take(499))
        }
        if (uiState.selectedTabId == tabId && !uiState.isAddressFocused) {
            uiState = uiState.copy(addressInput = tab.displayText)
        }
        persistSoon()
    }

    fun onTitleChanged(tabId: String, title: String) {
        updateTab(tabId) { it.copy(title = title.ifBlank { it.title }) }
        persistSoon()
    }

    fun onHistoryStateChanged(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        updateTab(tabId) { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun openSuggestion(suggestion: Suggestion): PreparedNavigation? = when (suggestion.type) {
        SuggestionType.GOOGLE_SEARCH -> prepareNavigation(suggestion.url)
        else -> prepareNavigation(suggestion.url)
    }

    fun toggleTabSheet() { uiState = uiState.copy(isTabSheetVisible = !uiState.isTabSheetVisible) }

    /** シークレット切替は既存の通常タブを変換せず、専用の非履歴タブを選択または作成する。 */
    fun switchToPrivateTab() {
        uiState.tabs.lastOrNull { it.isPrivate }?.let { selectTab(it.id) } ?: addTab(isPrivate = true)
    }

    fun switchToNormalTab() {
        uiState.tabs.lastOrNull { !it.isPrivate }?.let { selectTab(it.id) } ?: addTab()
    }

    fun isPrivateTab(tabId: String): Boolean = uiState.tabs.firstOrNull { it.id == tabId }?.isPrivate == true

    fun openSettings(page: SettingsPage = SettingsPage.ROOT) {
        suggestionJob?.cancel()
        uiState = uiState.copy(isSettingsSheetVisible = true, settingsPage = page, isAddressFocused = false, isSuggestionPanelVisible = false, suggestions = emptyList())
    }

    fun closeSettings() { uiState = uiState.copy(isSettingsSheetVisible = false, settingsPage = SettingsPage.ROOT) }
    fun showSettingsPage(page: SettingsPage) { uiState = uiState.copy(settingsPage = page) }
    fun backFromSettingsPage() {
        uiState = if (uiState.settingsPage == SettingsPage.ROOT) uiState.copy(isSettingsSheetVisible = false)
        else uiState.copy(settingsPage = SettingsPage.ROOT)
    }

    fun setFullscreen(value: Boolean) { uiState = uiState.copy(isFullscreen = value) }

    /** 通常ページのWebView履歴が尽きたら、起点を問わず独自ホームへ戻る。 */
    fun returnSelectedTabToHome() {
        if (uiState.selectedTab?.isHome == false) openHome()
    }

    fun updateSettings(transform: (BrowserSettings) -> BrowserSettings) {
        uiState = uiState.copy(settings = transform(uiState.settings))
        persistSoon()
    }

    fun addBookmark(title: String, url: String): Boolean {
        val prepared = buildNavigation(url) ?: return false
        val existing = uiState.bookmarks.firstOrNull { it.url == prepared.url }
        val bookmark = Bookmark(id = existing?.id ?: java.util.UUID.randomUUID().toString(), title = title.trim().ifBlank { prepared.displayText.ifBlank { prepared.url } }, url = prepared.url, createdAt = existing?.createdAt ?: System.currentTimeMillis())
        uiState = uiState.copy(bookmarks = listOf(bookmark) + uiState.bookmarks.filterNot { it.id == bookmark.id })
        persistSoon()
        return true
    }

    fun updateBookmark(id: String, title: String, url: String): Boolean {
        val prepared = buildNavigation(url) ?: return false
        val old = uiState.bookmarks.firstOrNull { it.id == id } ?: return false
        val updated = old.copy(title = title.trim().ifBlank { prepared.displayText.ifBlank { prepared.url } }, url = prepared.url)
        uiState = uiState.copy(bookmarks = uiState.bookmarks.map { if (it.id == id) updated else it })
        persistSoon()
        return true
    }

    fun removeBookmark(id: String) = removeBookmarks(setOf(id))

    fun removeBookmarks(ids: Set<String>) {
        if (ids.isEmpty()) return
        uiState = uiState.copy(bookmarks = uiState.bookmarks.filterNot { it.id in ids })
        persistSoon()
    }

    /** グリッドの右下側（リスト先頭）または左上側（リスト末尾）へ選択項目をまとめて移動する。 */
    fun moveBookmarks(ids: Set<String>, toEnd: Boolean) {
        if (ids.isEmpty()) return
        val selected = uiState.bookmarks.filter { it.id in ids }
        val remaining = uiState.bookmarks.filterNot { it.id in ids }
        if (selected.isEmpty()) return
        uiState = uiState.copy(bookmarks = if (toEnd) remaining + selected else selected + remaining)
        persistSoon()
    }

    fun isBookmarked(url: String): Boolean = url.isNotBlank() && uiState.bookmarks.any { it.url == url }

    fun removeHistory(id: String) {
        uiState = uiState.copy(history = uiState.history.filterNot { it.id == id })
        persistSoon()
    }

    fun clearBrowsingData(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.clearBrowsingData(keepBookmarks = true) }
            val newTab = homeTab()
            uiState = uiState.copy(tabs = listOf(newTab), selectedTabId = newTab.id, addressInput = "", history = emptyList(), isAddressFocused = false, isSuggestionPanelVisible = false, suggestions = emptyList())
            onDone()
        }
    }

    private fun updateSelected(transform: (BrowserTab) -> BrowserTab) {
        uiState.selectedTab?.let { selected -> updateTab(selected.id, transform) }
    }

    private fun updateTab(id: String, transform: (BrowserTab) -> BrowserTab) {
        uiState = uiState.copy(tabs = uiState.tabs.map { if (it.id == id) transform(it) else it })
    }

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            repository.save(uiState)
        }
    }

    private fun createSuggestions(input: String, googleQueries: List<String> = emptyList()): List<Suggestion> {
        val needle = input.trim()
        if (needle.isBlank()) return emptyList()
        // reverseLayout の候補パネルでは先頭要素が最下部に表示されるため、
        // この順序を「下側ほど高優先度」として定義する。
        val results = linkedMapOf<String, Suggestion>()
        // Google 検索として保存された履歴だけから、入力先頭が一致する最新1件を最優先にする。
        uiState.history.firstOrNull { entry -> entry.query?.startsWith(needle, ignoreCase = true) == true }?.let { entry ->
            results.putIfAbsent(entry.url, Suggestion(entry.query.orEmpty(), "", entry.query.orEmpty(), SuggestionType.HISTORY))
        }
        uiState.tabs.filter { !it.isHome && (it.title.contains(needle, true) || it.url.contains(needle, true)) }.forEach {
            results.putIfAbsent(it.url, Suggestion(it.title, it.url, it.url, SuggestionType.OPEN_TAB))
        }
        uiState.bookmarks.filter { it.title.contains(needle, true) || it.url.contains(needle, true) }.forEach {
            results.putIfAbsent(it.url, Suggestion(it.title, it.url, it.url, SuggestionType.BOOKMARK))
        }
        googleQueries.forEach { query ->
            results.putIfAbsent("google:$query", Suggestion(query, "", query, SuggestionType.GOOGLE_SEARCH))
        }
        // 自分が入力した語だけで検索する候補は、補完候補より低優先として最上部に置く。
        results.putIfAbsent("google:$needle", Suggestion(needle, "", needle, SuggestionType.GOOGLE_SEARCH))
        // reverseLayoutでは先頭が最下部の優先位置になる。表示上限は履歴を含めて合計6件。
        return results.values.take(MAX_SUGGESTIONS)
    }

    private fun fetchGoogleSuggestions(query: String): List<String> = runCatching {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = (URI("https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded")
            .toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 3_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) HTTPS-Tab-Browser/1.0")
        }
        try {
            connection.inputStream.bufferedReader().use { reader ->
                val array = JSONArray(reader.readText())
                val suggestions = array.optJSONArray(1) ?: JSONArray()
                List(suggestions.length()) { index -> suggestions.optString(index).trim() }
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_REMOTE_SUGGESTIONS)
            }
        } finally {
            // 検索候補は短時間に連続して要求されるため、成功・失敗・キャンセルを問わず接続を解放する。
            connection.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun buildNavigation(input: String): PreparedNavigation? {
        val value = input.trim()
        if (value.isBlank()) return null
        return if (looksLikeUrl(value)) {
            val url = secureUrl(value) ?: return null
            PreparedNavigation(url, url, AddressDisplayMode.URL)
        } else {
            val encoded = URLEncoder.encode(value, Charsets.UTF_8.name())
            PreparedNavigation("https://www.google.com/search?q=$encoded", value, AddressDisplayMode.SEARCH)
        }
    }

    private fun looksLikeUrl(value: String): Boolean = value.startsWith("http://", true) ||
        value.startsWith("https://", true) ||
        (value.none(Char::isWhitespace) && value.contains('.') && !value.startsWith("."))

    private fun secureUrl(raw: String): String? = runCatching {
        val withoutHttp = raw.trim().removePrefix("http://").removePrefix("HTTP://")
        val candidate = if (withoutHttp.startsWith("https://", true)) withoutHttp else "https://$withoutHttp"
        val uri = URI(candidate)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
        uri.toString()
    }.getOrNull()

    private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)
    private fun googleSearchQuery(url: String): String? = runCatching {
        val uri = Uri.parse(url)
        if (uri.host?.endsWith("google.com") == true && uri.path == "/search") uri.getQueryParameter("q")?.takeIf(String::isNotBlank) else null
    }.getOrNull()

    private fun homeTab(isPrivate: Boolean = false) = BrowserTab(
        title = if (isPrivate) "シークレット" else "ホーム",
        isPrivate = isPrivate
    )

    private companion object {
        const val GOOGLE_HOME = "https://www.google.com/"
        const val MAX_SUGGESTIONS = 6
        // 入力語自身と最新履歴候補の余地を残し、Google取得分は最大5件にする。
        const val MAX_REMOTE_SUGGESTIONS = 5
    }
}
