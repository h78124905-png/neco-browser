package com.example.httpsbrowser.data

import java.util.UUID

enum class AddressDisplayMode { URL, SEARCH }

enum class SettingsPage { ROOT, BOOKMARKS, HISTORY, DOWNLOADS, DARK_EXCLUSIONS, DATA, DIAGNOSTICS, OPEN_SOURCE_LICENSES }

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "ホーム",
    val displayText: String = "",
    val displayMode: AddressDisplayMode = AddressDisplayMode.URL,
    val lastRequestedUrl: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** ホームはWebView履歴に入れず、通常ページの履歴が尽きた時だけ表示するアプリ内オーバーレイ。 */
    val isHome: Boolean = true,
    /**
     * シークレットタブは履歴・タブ復元へ保存しない。
     * Android System WebViewがmulti-profileを提供しない端末では、Cookie等を通常タブと完全分離できない。
     * 通常ログインを壊す全Cookie削除は行わず、profile API対応時にのみ隔離を有効化する。
     */
    val isPrivate: Boolean = false
)

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val query: String? = null,
    val visitedAt: Long = System.currentTimeMillis()
)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class BrowserSettings(
    val forceDarkPages: Boolean = true,
    /** 自動判定に関わらず追加暗色化を行わないホスト名。例: example.com はサブドメインも対象。 */
    val darkModeExcludedHosts: List<String> = emptyList(),
    val adBlockingEnabled: Boolean = true,
    val javascriptEnabled: Boolean = true,
    /** 全画面以外でも動画操作UIを表示する許可ホスト。空なら全画面のみ。 */
    val videoControlHosts: List<String> = emptyList(),
    /** 動画操作ボタンで選択した速度を全動画へ引き継ぐ。 */
    val videoPlaybackRate: Float = 1.0f
)

data class BrowserUiState(
    val tabs: List<BrowserTab> = listOf(BrowserTab()),
    val selectedTabId: String? = tabs.firstOrNull()?.id,
    val addressInput: String = "",
    val suggestions: List<Suggestion> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val settings: BrowserSettings = BrowserSettings(),
    val isAddressFocused: Boolean = false,
    val isSuggestionPanelVisible: Boolean = false,
    val isTabSheetVisible: Boolean = false,
    val isSettingsSheetVisible: Boolean = false,
    val settingsPage: SettingsPage = SettingsPage.ROOT,
    val isFullscreen: Boolean = false
) {
    val selectedTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.firstOrNull()
}

data class Suggestion(
    val primary: String,
    val secondary: String,
    val url: String,
    val type: SuggestionType
)

enum class SuggestionType { OPEN_TAB, BOOKMARK, HISTORY, GOOGLE_SEARCH }

data class PreparedNavigation(
    val url: String,
    val displayText: String,
    val displayMode: AddressDisplayMode
)
