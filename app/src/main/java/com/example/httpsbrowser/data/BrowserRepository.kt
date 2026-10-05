package com.example.httpsbrowser.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.browserDataStore by preferencesDataStore(name = "browser_state")

class BrowserRepository(private val context: Context) {
    private object Keys {
        val tabs = stringPreferencesKey("tabs")
        val selectedTab = stringPreferencesKey("selected_tab")
        val history = stringPreferencesKey("history")
        val bookmarks = stringPreferencesKey("bookmarks")
        val forceDark = booleanPreferencesKey("force_dark")
        val forceDarkInitialized = booleanPreferencesKey("force_dark_initialized")
        val darkModeExcludedHosts = stringPreferencesKey("dark_mode_excluded_hosts")
        val adBlock = booleanPreferencesKey("ad_block")
        val javascript = booleanPreferencesKey("javascript")
        val videoControlHosts = stringPreferencesKey("video_control_hosts")
        val videoPlaybackRate = floatPreferencesKey("video_playback_rate")
    }

    suspend fun load(): BrowserPersistedState {
        val preferences = context.browserDataStore.data.first()
        // 旧版では初期値が OFF だったため、既存インストールは一度だけ ON へ移行する。
        val forceDarkPages = if (preferences[Keys.forceDarkInitialized] == true) {
            preferences[Keys.forceDark] ?: true
        } else {
            context.browserDataStore.edit { stored ->
                stored[Keys.forceDark] = true
                stored[Keys.forceDarkInitialized] = true
            }
            true
        }
        return BrowserPersistedState(
            tabs = decodeTabs(preferences[Keys.tabs]).ifEmpty { listOf(BrowserTab()) },
            selectedTabId = preferences[Keys.selectedTab],
            history = decodeHistory(preferences[Keys.history]),
            bookmarks = decodeBookmarks(preferences[Keys.bookmarks]),
            settings = BrowserSettings(
                forceDarkPages = forceDarkPages,
                darkModeExcludedHosts = decodeStringList(preferences[Keys.darkModeExcludedHosts]),
                adBlockingEnabled = preferences[Keys.adBlock] ?: true,
                javascriptEnabled = preferences[Keys.javascript] ?: true,
                                    videoControlHosts = decodeStringList(preferences[Keys.videoControlHosts]),
                    videoPlaybackRate = (preferences[Keys.videoPlaybackRate] ?: 1.0f).coerceIn(1.0f, 2.0f)

            )
        )
    }

    suspend fun save(state: BrowserUiState) {
        // シークレットタブはアプリ終了後に復元せず、履歴・通常タブだけを永続化する。
        val persistentTabs = state.tabs.filterNot { it.isPrivate }
        val persistentSelectedId = state.selectedTab?.takeUnless { it.isPrivate }?.id
            ?: persistentTabs.firstOrNull()?.id
        context.browserDataStore.edit { preferences ->
            preferences[Keys.tabs] = encodeTabs(persistentTabs).toString()
            if (persistentSelectedId != null) preferences[Keys.selectedTab] = persistentSelectedId
            else preferences.remove(Keys.selectedTab)
            preferences[Keys.history] = encodeHistory(state.history.take(500)).toString()
            preferences[Keys.bookmarks] = encodeBookmarks(state.bookmarks).toString()
            preferences[Keys.forceDark] = state.settings.forceDarkPages
            preferences[Keys.forceDarkInitialized] = true
            preferences[Keys.darkModeExcludedHosts] = encodeStringList(state.settings.darkModeExcludedHosts).toString()
            preferences[Keys.adBlock] = state.settings.adBlockingEnabled
            preferences[Keys.javascript] = state.settings.javascriptEnabled
            preferences[Keys.videoControlHosts] = encodeStringList(state.settings.videoControlHosts).toString()
            preferences[Keys.videoPlaybackRate] = state.settings.videoPlaybackRate.coerceIn(1.0f, 2.0f)
        }
    }

    suspend fun clearBrowsingData(keepBookmarks: Boolean) {
        context.browserDataStore.edit { preferences ->
            preferences.remove(Keys.history)
            preferences.remove(Keys.tabs)
            preferences.remove(Keys.selectedTab)
            if (!keepBookmarks) preferences.remove(Keys.bookmarks)
        }
    }

    private fun decodeTabs(raw: String?): List<BrowserTab> = decodeArray(raw) { item ->
        BrowserTab(
            id = item.optString("id", java.util.UUID.randomUUID().toString()),
            url = item.optString("url", "https://www.google.com/"),
            title = item.optString("title", "新しいタブ"),
            displayText = item.optString("displayText", ""),
            displayMode = runCatching { AddressDisplayMode.valueOf(item.optString("displayMode")) }
                .getOrDefault(AddressDisplayMode.URL),
            lastRequestedUrl = item.optString("lastRequestedUrl", item.optString("url")),
            // WebViewのBackForwardListそのものは保存していないため、以前の真偽値を復元すると
            // 「有効に見えるが戻れない」操作になってしまう。再生成後のWebViewが実測で更新するまでOFFにする。
            canGoBack = false,
            canGoForward = false,
            isHome = item.optBoolean("isHome", item.optString("url").isBlank()),
            isPrivate = item.optBoolean("isPrivate", false)
        )
    }

    private fun decodeHistory(raw: String?): List<HistoryEntry> = decodeArray(raw) { item ->
        HistoryEntry(
            id = item.optString("id", java.util.UUID.randomUUID().toString()),
            title = item.optString("title", ""),
            url = item.optString("url", ""),
            query = item.optString("query").ifBlank { null },
            visitedAt = item.optLong("visitedAt", System.currentTimeMillis())
        )
    }

    private fun decodeBookmarks(raw: String?): List<Bookmark> = decodeArray(raw) { item ->
        Bookmark(
            id = item.optString("id", java.util.UUID.randomUUID().toString()),
            title = item.optString("title", ""),
            url = item.optString("url", ""),
            createdAt = item.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun <T> decodeArray(raw: String?, map: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index -> map(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun decodeStringList(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index -> array.optString(index).trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    private fun encodeStringList(values: List<String>) = JSONArray().apply {
        values.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().forEach(::put)
    }

    private fun encodeTabs(tabs: List<BrowserTab>) = JSONArray().apply {
        tabs.forEach { tab -> put(JSONObject().apply {
            put("id", tab.id); put("url", tab.url); put("title", tab.title)
            put("displayText", tab.displayText); put("displayMode", tab.displayMode.name)
            // BackForwardListはWebViewインスタンス内だけの状態であり、DataStoreへ保存しない。
            // 次回生成時はfalseから開始し、onHistoryStateで実体に合わせて更新する。
            put("lastRequestedUrl", tab.lastRequestedUrl)
            put("isHome", tab.isHome); put("isPrivate", tab.isPrivate)
        }) }
    }

    private fun encodeHistory(history: List<HistoryEntry>) = JSONArray().apply {
        history.forEach { entry -> put(JSONObject().apply {
            put("id", entry.id); put("title", entry.title); put("url", entry.url)
            put("query", entry.query); put("visitedAt", entry.visitedAt)
        }) }
    }

    private fun encodeBookmarks(bookmarks: List<Bookmark>) = JSONArray().apply {
        bookmarks.forEach { bookmark -> put(JSONObject().apply {
            put("id", bookmark.id); put("title", bookmark.title); put("url", bookmark.url)
            put("createdAt", bookmark.createdAt)
        }) }
    }
}

data class BrowserPersistedState(
    val tabs: List<BrowserTab>,
    val selectedTabId: String?,
    val history: List<HistoryEntry>,
    val bookmarks: List<Bookmark>,
    val settings: BrowserSettings
)
