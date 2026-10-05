package com.example.httpsbrowser.web

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.URLUtil
import androidx.webkit.SafeBrowsingResponseCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.content.Intent
import com.example.httpsbrowser.CrashDiagnostics
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserDownloadRequest
import com.example.httpsbrowser.data.BrowserTab
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class BrowserWebViewRegistry(
    private val context: Context
) {
    private val entries = ConcurrentHashMap<String, Entry>()

    fun obtain(tab: BrowserTab, settings: BrowserSettings, callbacks: BrowserWebCallbacks): WebView {
        val entry = entries[tab.id] ?: Entry(createWebView(tab.id)).also { entries[tab.id] = it }
        entry.callbacks = callbacks
        entry.settings = settings
        entry.adBlockingEnabled = settings.adBlockingEnabled
        ensureYoutubePictureInPictureScript(entry)
        // 121e47bの構成を基準にする。動画文書を対象へ含めるかは明示設定で選択する。
        val darkModeChanged = entry.appliedForceDark != settings.forceDarkPages ||
            entry.appliedDarkModeExcludedHosts != settings.darkModeExcludedHosts
        val currentUrl = entry.loadedUrl ?: tab.lastRequestedUrl
        configure(
            entry.webView,
            settings,
            isVideoPlaybackDocumentUrl(currentUrl),
            entry.documentIsAlreadyDark && false,
            currentUrl
        )
        entry.appliedForceDark = settings.forceDarkPages
        entry.appliedDarkModeExcludedHosts = settings.darkModeExcludedHosts
        if (darkModeChanged && entry.loadedUrl != null) {
            // 暗色化切替だけでWebViewを再読込しない。
            val url = entry.loadedUrl.orEmpty()
            applyDeepDarkCss(
                entry.webView,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(
                        settings,
                        isVideoPlaybackDocumentUrl(url),
                        url,
                        entry.documentIsAlreadyDark && false
                    ),
                youtubePage = isYoutubeDocumentUrl(url)
            )
        }
        if (entry.loadedUrl == null) {
            entry.loadedUrl = tab.lastRequestedUrl
            entry.activeDocumentUrl = tab.lastRequestedUrl
            entry.rearmPageLifecycle(tab.lastRequestedUrl)
            CrashDiagnostics.recordWebViewNavigation(tab.lastRequestedUrl)
            prepareDarkDocumentStartScript(entry, tab.lastRequestedUrl)
            entry.webView.loadUrl(tab.lastRequestedUrl)
        }
        return entry.webView
    }

    /**
     * 標準フィルタの初回コンパイルまたは更新後に、すでに生成済みのWebViewへ遮断規則を再適用する。
     * 初期load時にengine未準備だった場合でも、次の遷移を待たずYouTube document-start scriptletを登録する。
     */
    fun refreshContentFiltering() {
        entries.values.forEach { entry ->
            val url = entry.webView.url ?: entry.loadedUrl ?: entry.activeDocumentUrl.orEmpty()
            if (url.isBlank()) return@forEach
        }
    }

    fun load(tabId: String, url: String) {
        entries[tabId]?.let { entry ->
            if (isHttps(url)) {
                // 新規遷移はユーザーの意図を優先し、連打で残った旧ページへの戻る要求を破棄する。
                entry.cancelBackNavigation()
                entry.loadedUrl = url
                entry.documentIsAlreadyDark = false
                entry.rearmPageLifecycle(url)
                // shouldInterceptRequest はUIスレッド外から呼ばれ得るため、
                // コールバック内で WebView.url を読む代わりに遷移前に親URLを保持する。
                entry.activeDocumentUrl = url
                // onPageStartedより前に旧文書の白い最終フレームを隠す。暗色化除外サイトから
                // 通常サイトへ連続遷移しても、白いページが先に見える状態を作らない。
                beginDarkRevealGuard(entry.webView, entry, url)
                CrashDiagnostics.recordWebViewNavigation(url)
                prepareDarkDocumentStartScript(entry, url)
                entry.webView.loadUrl(url)
            } else entry.callbacks.onBlockedNavigation(url)
        }
    }

    fun reload(tabId: String) = entries[tabId]?.let { entry ->
        entry.cancelBackNavigation()
        val url = entry.webView.url.orEmpty()
        entry.activeDocumentUrl = url
        beginDarkRevealGuard(entry.webView, entry, url)
        entry.rearmPageLifecycle(url)
        entry.webView.reload()
    }

    /**
     * 戻る要求をWebViewの遷移完了ごとに一件ずつ処理する。連打時に古いBackForwardListを読んで
     * 操作を落とさず、履歴を使い切った場合はcallbackでホームへ戻す。
     *
     * @return 要求を受理した場合はtrue。開始時点で履歴がない場合だけfalse。
     */
    fun goBack(tabId: String): Boolean {
        val entry = entries[tabId] ?: return false
        if (!entry.beginBackNavigation()) return true
        val view = entry.webView
        if (!canNavigateHistory(view, -1)) {
            entry.cancelBackNavigation()
            return false
        }
        val history = view.copyBackForwardList()
        val targetUrl = history.getItemAtIndex(history.currentIndex - 1).url
        entry.activeDocumentUrl = targetUrl
        beginDarkRevealGuard(view, entry, targetUrl)
        entry.rearmPageLifecycle(targetUrl)
        view.goBack()
        return true
    }

    fun canGoBack(tabId: String): Boolean = entries[tabId]?.webView?.let { canNavigateHistory(it, -1) } == true

    fun canGoForward(tabId: String): Boolean = entries[tabId]?.webView?.let { canNavigateHistory(it, 1) } == true

    private fun canNavigateHistory(view: WebView, direction: Int): Boolean {
        val history = view.copyBackForwardList()
        val targetIndex = history.currentIndex + direction
        return targetIndex in 0 until history.size && isHttps(history.getItemAtIndex(targetIndex).url)
    }

    private fun notifyHistoryState(tabId: String, view: WebView) {
        entries[tabId]?.callbacks?.onHistoryState(
            tabId = tabId,
            canGoBack = canNavigateHistory(view, -1),
            canGoForward = canNavigateHistory(view, 1)
        )
    }

    /** WebViewが戻る先を反映した後だけ、連打で積んだ次の戻る要求を実行する。 */
    private fun completeBackNavigation(tabId: String, entry: Entry, view: WebView) {
        if (!entry.completeBackNavigation()) return
        view.post {
            if (!entry.isActive) return@post
            if (!goBack(tabId)) entry.callbacks.onBackHistoryExhausted(tabId)
        }
    }

    /** 全画面custom video surfaceへページCSSの反転が及ばないよう、表示中だけ暗色CSSを外す。 */
    fun setFullscreenVideoDarkeningSuppressed(tabId: String, suppressed: Boolean) {
        entries[tabId]?.let { entry ->
            entry.fullscreenVideoDarkeningSuppressed = suppressed
            val url = entry.webView.url ?: entry.activeDocumentUrl.orEmpty()
            val videoPage = isVideoPlaybackDocumentUrl(url)
            applyDeepDarkCss(
                entry.webView,
                enabled = !suppressed && shouldApplyPageCssDarkening(
                    entry.settings,
                    videoPage,
                    url,
                    entry.documentIsAlreadyDark && false
                ),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            CrashDiagnostics.record("video_dark_css_suppressed", "tab=$tabId\nsuppressed=$suppressed\nurl=$url")
        }
    }


    /** SPA遷移を含む実際の表示URL。共有とrenderer再作成ではタブ保存値より優先する。 */
    fun currentUrl(tabId: String): String? = entries[tabId]?.let { entry ->
        entry.webView.url?.takeIf(::isHttps)
            ?: entry.activeDocumentUrl?.takeIf(::isHttps)
            ?: entry.loadedUrl?.takeIf(::isHttps)
    }

    /**
     * 選択タブの通常WebViewをActivity rootのnative hostへ接続する。
     * Compose AndroidViewを介さないため、再構成時にAwContentsの親・測定経路を変えない。
     */
    fun attachToNativeHost(tabId: String, host: ViewGroup): Boolean {
        val view = entries[tabId]?.webView ?: return false
        // タブ切替で前面WebViewを一括除去したり不可視化すると、Chromiumは
        // 描画先の消滅として扱う場合がある。YouTubeの再生sessionを維持するため、
        // 既存WebViewはhost内で可視のまま重ね、選択タブだけを前面へ移動する。
        if (view.parent !== host) {
            (view.parent as? ViewGroup)?.removeView(view)
            host.addView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        view.visibility = View.VISIBLE
        view.bringToFront()
        return true
    }
    /**
     * 非選択タブはhost内に残す。親Viewからの切離しは、タブを閉じるかレジストリを
     * 破棄する時だけに限定し、動画・音声・ログインのWebView状態を保つ。
     */
    fun detachFromNativeHost(tabId: String, host: ViewGroup? = null) {
        val view = entries[tabId]?.webView ?: return
        val parent = view.parent as? ViewGroup ?: return
        if (host == null) parent.removeView(view)
    }


    /**
     * Fulgurisと同じく、翻訳はGoogle Translateのページ遷移として実行する。
     * 端末内モデルや本文DOMの置換を使わないため、動的ページの部分翻訳・再スキャン・
     * 大容量JNIを持たず、ブラウザの戻る操作で原文へ戻れる。
     */
    fun translateToJapanese(tabId: String) = entries[tabId]?.let { entry ->
        val sourceUrl = entry.webView.url.orEmpty()
        val translateUrl = googleTranslateUrl(sourceUrl)
        when {
            translateUrl == null -> entry.callbacks.onNotice("HTTPSページの読み込み完了後に翻訳してください。")
            isGoogleTranslateDocumentUrl(sourceUrl) -> entry.callbacks.onNotice("このページはすでにGoogle翻訳で開かれています。")
            else -> load(tabId, translateUrl)
        }
    }

    /** 現ページを MHTML として一時保存し、UI 側でユーザーが選んだ保存先へ書き出す。 */
    fun savePageArchive(tabId: String, title: String) = entries[tabId]?.let { entry ->
        val archiveDirectory = File(context.cacheDir, "page_archives").apply { mkdirs() }
        val baseName = title.ifBlank { "page" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(64).ifBlank { "page" }
        val target = File(archiveDirectory, "${baseName}_${System.currentTimeMillis()}.mht")
        entry.webView.saveWebArchive(target.absolutePath, false) { savedPath ->
            val archive = savedPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            if (archive != null) entry.callbacks.onPageArchiveReady(archive.absolutePath, archive.name)
            else entry.callbacks.onNotice("ページを保存できませんでした。読み込み完了後にもう一度お試しください。")
        }
    }
    fun goForward(tabId: String) = entries[tabId]?.let { entry ->
        entry.webView.takeIf { canNavigateHistory(it, 1) }?.let { view ->
            val history = view.copyBackForwardList()
            val targetUrl = history.getItemAtIndex(history.currentIndex + 1).url
            entry.activeDocumentUrl = targetUrl
            beginDarkRevealGuard(view, entry, targetUrl)
            entry.rearmPageLifecycle(targetUrl)
            view.goForward()
            view.post { notifyHistoryState(tabId, view) }
        }
    }
    /** 動画操作overlayから現在タブ内のvideo要素へ再生速度を適用する。ページ遷移後も同じAPIを再利用できる。 */
    fun setVideoPlaybackRate(tabId: String, rate: Float) {
        val safeRate = rate.coerceIn(0.25f, 4.0f)
        entries[tabId]?.webView?.evaluateJavascript(
            """
            (function(rate){
              window.__httpsBrowserPlaybackRate = rate;
              function applyRate(){
                document.querySelectorAll('video').forEach(function(v){
                  try {
                    v.defaultPlaybackRate = rate;
                    if (Math.abs(v.playbackRate - rate) > 0.001) v.playbackRate = rate;
                    v.dispatchEvent(new Event('ratechange'));
                  } catch (_) {}
                });
              }
              applyRate();
              if (!window.__httpsBrowserPlaybackRateObserver) {
                var observer = new MutationObserver(applyRate);
                observer.observe(document.documentElement || document, {childList:true, subtree:true});
                document.addEventListener('loadedmetadata', applyRate, true);
                document.addEventListener('canplay', applyRate, true);
                window.__httpsBrowserPlaybackRateObserver = observer;
              }
              if (window.__httpsBrowserPlaybackRateTimer) clearInterval(window.__httpsBrowserPlaybackRateTimer);
              window.__httpsBrowserPlaybackRateTimer = setInterval(applyRate, 250);
            })($safeRate);
            """.trimIndent(),
            null
        )
    }

    fun seekVideo(tabId: String, seconds: Int) {
        val safeSeconds = seconds.coerceIn(-60, 60)
        entries[tabId]?.webView?.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){v.currentTime=Math.max(0,Math.min(v.duration||Infinity,v.currentTime+$safeSeconds));});",
            null
        )
    }

    fun scrollBy(tabId: String, deltaY: Int) = entries[tabId]?.webView?.scrollBy(0, deltaY)
    fun scrollToTop(tabId: String) = entries[tabId]?.webView?.scrollTo(0, 0)
    /** `pageDown(true)`は縮尺値に依存せずWebView自身の文書末尾へ移動する。 */
    fun scrollToBottom(tabId: String) = entries[tabId]?.webView?.pageDown(true)
    fun scrollToFraction(tabId: String, fraction: Float) = entries[tabId]?.let { entry ->
        val safeFraction = fraction.coerceIn(0f, 1f)
        // AndroidのcontentHeightはWebView倍率・端末実装差で物理scrollYと一致しない。
        // ページ自身のCSS文書高さとviewportから最大scrollYを求め、ピッカーの0..1を
        // ページ先頭..末尾の0..1へ比例変換する。
        val fractionLiteral = safeFraction.toString()
        entry.webView.evaluateJavascript(
            "(function(){var d=document.documentElement,b=document.body;var h=Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0);var v=window.innerHeight||document.documentElement.clientHeight;window.scrollTo(0,Math.max(0,(h-v)*$fractionLiteral));})();",
            null
        )
    }

    fun remove(tabId: String) {
        entries.remove(tabId)?.let { entry ->
            entry.isActive = false
            runCatching { entry.documentStartScriptHandler?.remove() }
            runCatching { entry.siteDocumentStartScriptHandler?.remove() }
            runCatching { entry.youtubePictureInPictureScriptHandler?.remove() }
            entry.documentStartScriptHandler = null
            entry.siteDocumentStartScriptHandler = null
            entry.youtubePictureInPictureScriptHandler = null
            entry.cookieFlushRunnable?.let(entry.webView::removeCallbacks)
            entry.cookieFlushRunnable = null
            (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
            entry.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                destroy()
            }
        }
    }

    fun destroyAll() {
        entries.keys.toList().forEach(::remove)
    }

    /** 画面そのものが閉じる時だけ、ネイティブフィルタを解放する。 */
    fun close() {
        destroyAll()
        // 遅延集約中のCookieもアプリ終了時には確実にディスクへ反映する。
        runCatching { CookieManager.getInstance().flush() }
    }

    fun clearAllBrowsingData() {
        entries.values.forEach { entry ->
            entry.webView.clearHistory()
            entry.webView.clearCache(true)
            entry.webView.clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        destroyAll()
    }

    private fun createWebView(tabId: String): WebView = object : WebView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            val scrollRange = ((contentHeight * (entries[tabId]?.pageScale ?: 1f)).toInt() - height).coerceAtLeast(0)
            val fraction = if (scrollRange == 0) 0f else t.toFloat() / scrollRange
            entries[tabId]?.callbacks?.onScrollPosition(tabId, fraction.coerceIn(0f, 1f))
        }
    }.apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        // FulgurisのWebViewEx/XML設定と同じく、ページ全体へかかるAndroidのfocus highlightを
        // 無効化する。WebViewの実描画面へ半透明のfocus層が残る端末差を避ける。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        isFocusable = true
        isFocusableInTouchMode = true
        // WebViewへ恒久的なオフスクリーンGPUレイヤーを強制しない。
        // HTML5動画はChromiumが専用の合成面を管理するため、通常のLAYER_TYPE_NONEに委ねる。
        // これによりGoogle動画プレビューの映像面と親WebViewの黒白レイヤーの競合を避ける。
        setLayerType(View.LAYER_TYPE_NONE, null)
        // 独自の右端レールを使うため、横方向のedge effect/scrollbarが動画の左端に
        // 白いレイヤーとして露出しないよう、WebView標準のスクロール装飾を無効化する。
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // WebView専用UA分岐を避け、通常のモバイルChrome相当のページを要求する。
            // Version/端末情報は残し、WebView識別子だけを取り除く。
            userAgentString = userAgentString.replace("; wv", "")
            // Fulgurisの標準モバイル表示に合わせ、viewportの拡大縮小を強制しない。
            // WebViewはコンテンツ領域の直接の子なので、アプリ側のhostサイズ補正は不要。
            useWideViewPort = false
            loadWithOverviewMode = false
            databaseEnabled = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 動画ページのプレーヤー初期化やログイン確認を妨げない。
            mediaPlaybackRequiresUserGesture = false
            safeBrowsingEnabled = true
            // Fulgurisの通常タブと同じく、初期フォーカスによるページ先頭への不要なscrollを避ける。
            setNeedInitialFocus(false)
        }
        // ログイン状態と埋め込みプレーヤーの認証をアプリ内で維持する。
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // ページへ公開するのは再生videoの幅・高さを受け取るread-onlyの数値窓口だけ。
        // 任意URLやJavaScript実行機能を公開しない。
        addJavascriptInterface(object {
            @JavascriptInterface
            fun report(width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    entries[tabId]?.callbacks?.onVideoDimensions(tabId, width, height)
                }
            }
        }, VIDEO_DIMENSIONS_BRIDGE_NAME)
        webViewClient = SecureClient(tabId)
        webChromeClient = SecureChromeClient(tabId)
        AdBlockInjector.inject(this)
        setDownloadListener(SecureDownloadListener(tabId))
        setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                entries[tabId]?.callbacks?.onPageInteraction()
            }
            false
        }
        setOnLongClickListener {
            val url = when (hitTestResult.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE -> hitTestResult.extra
                else -> null
            }
            if (!url.isNullOrBlank()) entries[tabId]?.callbacks?.onLinkLongPressed(url)
            false
        }
    }

    /**
     * `121e47b`で使用していた暗色化構成。
     *
     * WebView標準のAlgorithmic Darkening／Force Darkは全画面custom video surfaceにも及び得る。
     * そのため動画文書では、動画サイト暗色化設定がONでも標準暗色化を常に停止する。ページ本文だけの
     * CSS反転は別途許可し、`video`の二重反転で映像そのものを常に正常色に保つ。切替に伴うreloadはしない。
     */
    private fun configure(
        view: WebView,
        settings: BrowserSettings,
        videoPage: Boolean,
        documentIsAlreadyDark: Boolean = false,
        url: String = ""
    ) {
        view.settings.javaScriptEnabled = settings.javascriptEnabled
        val allowPlatformDarkening = shouldApplyPlatformDarkening(settings, videoPage, documentIsAlreadyDark, url)
        // targetSdk 35ではJetpack WebKitのAlgorithmic Darkeningが後方互換を担う。
        // Force Dark系は旧targetSdk向けで、動画surfaceを含む描画経路へ副作用を持ち得るため使わない。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, allowPlatformDarkening)
        }
        CrashDiagnostics.record(
            "dark_mode_configured",
            "engine=legacy_121e47b\nforceRequested=${settings.forceDarkPages}\nvideoPage=$videoPage\ndocumentIsAlreadyDark=$documentIsAlreadyDark\nmanualExclusion=${isDarkModeExcluded(settings, url)}\nplatformDarkening=$allowPlatformDarkening\npageCssDarkening=${shouldApplyPageCssDarkening(settings, videoPage, url, documentIsAlreadyDark)}"
        )
    }

    /** video surfaceの全画面合成を反転しないため、標準暗色化は動画文書では常に無効にする。 */
    private fun shouldApplyPlatformDarkening(
        settings: BrowserSettings,
        videoPage: Boolean,
        documentIsAlreadyDark: Boolean = false,
        url: String = ""
    ): Boolean = settings.forceDarkPages && !videoPage && !documentIsAlreadyDark && !isDarkModeExcluded(settings, url)

    /** 動画サイト上書きはページ本文のCSS反転だけを有効にする。Shortsは映像面を優先して常に除外する。 */
    private fun shouldApplyPageCssDarkening(
        settings: BrowserSettings,
        videoPage: Boolean,
        url: String = "",
        documentIsAlreadyDark: Boolean = false
    ): Boolean = settings.forceDarkPages && (!videoPage || true) &&
        !isYoutubeShortsDocumentUrl(url) && !documentIsAlreadyDark && !isDarkModeExcluded(settings, url)

    /** 設定したexample.comはwww・任意サブドメインを含め、host境界をまたいで誤一致しない。 */
    private fun isDarkModeExcluded(settings: BrowserSettings, url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull() ?: return false
        return settings.darkModeExcludedHosts.any { excluded ->
            val normalized = excluded.trim().lowercase(Locale.ROOT).removePrefix("www.")
            normalized.isNotBlank() && (host == normalized || host.endsWith(".$normalized"))
        }
    }

    /**
     * ページの背景・color-scheme・theme-colorを読むだけで、DOMを変更せずに既存暗色ページを判定する。
     * 背景輝度が0.18以下、またはページ自身がdark color-schemeを明示した場合のみ暗いとみなす。
     */
    private fun detectAlreadyDarkDocument(view: WebView, entry: Entry, url: String) {
        // YouTube・Google動画などは専用CSS/PiP保護経路を維持し、一般文書だけで判定する。
        if (!false || !isHttps(url) ||
            isVideoPlaybackDocumentUrl(url) || isDarkModeExcluded(entry.settings, url)
        ) {
            releaseDarkRevealGuard(view, entry, url)
            return
        }
        // 既に注入した反転CSSを外した本来のcomputed styleだけを読む。alpha=0の間に行うため白フラッシュは出ない。
        view.evaluateJavascript(DARK_DETECTOR_WITHOUT_OVERRIDE_SCRIPT) { result ->
            val isAlreadyDark = result.trim() == "true"
            // 非同期評価中に遷移・ホーム復帰した場合は、古い文書の結果を採用しない。
            if (entry.activeDocumentUrl != url) return@evaluateJavascript
            if (entry.documentIsAlreadyDark == isAlreadyDark) {
                releaseDarkRevealGuard(view, entry, url)
                return@evaluateJavascript
            }
            entry.documentIsAlreadyDark = isAlreadyDark
            val videoPage = isVideoPlaybackDocumentUrl(url)
            configure(view, entry.settings, videoPage, isAlreadyDark, url)
            applyDeepDarkCss(
                view,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(entry.settings, videoPage, url, isAlreadyDark),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            CrashDiagnostics.record("already_dark_document_detected", "url=$url\ndark=$isAlreadyDark")
            releaseDarkRevealGuard(view, entry, url)
        }
    }

    /** ページ開始時もWebViewを可視に保つ。Fulguris同様、判定中に表示面全体を隠さない。 */
    private fun beginDarkRevealGuard(view: WebView, entry: Entry, url: String) {
        entry.darkRevealPending = false
        view.alpha = 1f
    }

    private fun releaseDarkRevealGuard(view: WebView, entry: Entry, url: String) {
        entry.darkRevealPending = false
        view.alpha = 1f
    }

    /**
     * 一般ページは121e47b型の反転CSSを維持する。一方YouTubeはWeb Componentsが多いため、
     * 反転でなく専用の前景・背景色を指定し、映像surfaceにfilterを一切適用しない。
     */
    /**
     * `<video>`の実符号化サイズを監視し、PiP用に横長・縦長をActivityへ通知する。
     * DOM変更・metadata・resize・再生開始のいずれでも再評価し、同じサイズはページ側で重複通知しない。
     */
    private fun installVideoDimensionsReporter(view: WebView) {
        view.evaluateJavascript(VIDEO_DIMENSIONS_REPORTER_SCRIPT, null)
    }

    private fun applyDeepDarkCss(view: WebView, enabled: Boolean, youtubePage: Boolean = false) {
        // Fulgurisと同じくWebView標準のAlgorithmic Darkening/Force Darkだけを使う。
        // ページ全体へstyle/filterを注入すると、Google Imagesのdialogやvideo overlayの
        // stacking context・viewportを壊し、gray layerとして見える端末がある。
        return
    }

    /**
     * YouTube originだけでPiP阻害を解除し、広告遮断がONなら動画応答内の広告メタデータも
     * document start時から除去する。動画バイト列・認証Cookie・URL遷移には触れない。
     */
    private fun ensureYoutubePictureInPictureScript(entry: Entry) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            CrashDiagnostics.record("youtube_pip_unlock_unsupported", "reason=document_start_api_unavailable")
            return
        }
        val originRules = setOf(
            "https://youtube.com", "https://*.youtube.com",
            "https://youtube-nocookie.com", "https://*.youtube-nocookie.com"
        )
        if (entry.youtubePictureInPictureScriptHandler == null) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_PIP_UNLOCK_SCRIPT, originRules)
            }.onSuccess { handler ->
                entry.youtubePictureInPictureScriptHandler = handler
                CrashDiagnostics.record("youtube_pip_unlock_ready", "documentStart=true")
            }.onFailure { throwable ->
                CrashDiagnostics.record("youtube_pip_unlock_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }
    }

    /**
     * 指定2標準リストからBraveが解決したscriptletを、対応するHTTPS主文書のJSより先に注入する。
     * Rust側のtrust境界により、ユーザー追加URLの規則はscriptlet本文を返せない。
     * YouTubeはiframeにも同じscriptletを届ける専用経路があるため、ここでは二重注入を避ける。
     */
    /**
     * WebViewの初回可視化より前に一般ページの暗色CSSを登録し、白背景が一度描画されるのを防ぐ。
     * 指定ホスト・動画ページは登録しないため、既存のYouTube/PiP保護経路と手動除外を侵害しない。
     */
    private fun prepareDarkDocumentStartScript(entry: Entry, url: String) {
        runCatching { entry.darkDocumentStartScriptHandler?.remove() }
        entry.darkDocumentStartScriptHandler = null
        // 独自document-start dark CSSは使用しない。
    }

    private inner class SecureClient(private val tabId: String) : WebViewClientCompat() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.isForMainFrame) return false
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                val fallback = intentFallbackUrl(url)
                if (fallback != null) entries[tabId]?.callbacks?.onHttpsUpgrade(fallback)
                else entries[tabId]?.callbacks?.onExternalAppRequested(url)
                return true
            }
            val secureUrl = upgradeToHttps(url)
            return when {
                secureUrl == null -> {
                    entries[tabId]?.callbacks?.onBlockedNavigation(url)
                    true
                }
                secureUrl != url -> {
                    entries[tabId]?.callbacks?.onHttpsUpgrade(secureUrl)
                    true
                }
                else -> false
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val entry = entries[tabId] ?: return null

            // ライフサイクル管理のみ残し、ネットワーク判定はすべて削除。
            if (request.isForMainFrame) entry.rearmPageLifecycle(request.url.toString())

            // すべてスルー（AdGuard DNSとJSに完全委任）。
            return super.shouldInterceptRequest(view, request)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (!isHttps(url)) return
            val entry = entries[tabId] ?: return
            // YouTube等のSPAはmain-frame loadを発生させずURLだけをhistory APIで更新する。
            // 共有・アドレスバー・renderer再作成用のタブURLをここで最新化する。
            entry.loadedUrl = url
            entry.activeDocumentUrl = url
            configure(
                view,
                entry.settings,
                isVideoPlaybackDocumentUrl(url),
                entry.documentIsAlreadyDark && false,
                url
            )
            applyDeepDarkCss(
                view,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(
                        entry.settings,
                        isVideoPlaybackDocumentUrl(url),
                        url,
                        entry.documentIsAlreadyDark && false
                    ),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            detectAlreadyDarkDocument(view, entry, url)
            entry.callbacks.onVisitedHistory(tabId, url)
            notifyHistoryState(tabId, view)
            completeBackNavigation(tabId, entry, view)
        }

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
            super.onScaleChanged(view, oldScale, newScale)
            // getScale()はWeb rendererとUI threadの競合で不正確なため、変更callbackの値を保持する。
            entries[tabId]?.pageScale = newScale.takeIf { it > 0f } ?: 1f
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            CrashDiagnostics.recordWebViewNavigation(url)
            val entry = entries[tabId]
            entry?.documentIsAlreadyDark = false
            entry?.activeDocumentUrl = url
            entry?.rearmPageLifecycle(url)
            view.setBackgroundColor(android.graphics.Color.BLACK)
            entry?.let { beginDarkRevealGuard(view, it, url) }
            // 121e47bと同じく、遷移先が動画文書かどうかに応じて標準暗色化を再設定する。
            entry?.let {
                configure(view, it.settings, isVideoPlaybackDocumentUrl(url), url = url)
                // commit可視化まで待つとbodyの初期白背景が一瞬現れることがあるため、開始時にも適用する。
                applyDeepDarkCss(
                    view,
                    enabled = !it.fullscreenVideoDarkeningSuppressed &&
                        shouldApplyPageCssDarkening(it.settings, isVideoPlaybackDocumentUrl(url), url),
                    youtubePage = isYoutubeDocumentUrl(url)
                )
            }
            installVideoDimensionsReporter(view)
            entry?.callbacks?.onPageStarted(tabId, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val entry = entries[tabId]
            // 121e47bの暗色化経路を、動画上書き設定を含めて初回可視化時から適用する。
            applyDeepDarkCss(
                view,
                enabled = entry?.let { !it.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(it.settings, isVideoPlaybackDocumentUrl(url), url) } == true,
                youtubePage = isYoutubeDocumentUrl(url)
            )
            if (entry != null) {
                if (false) detectAlreadyDarkDocument(view, entry, url)
                else releaseDarkRevealGuard(view, entry, url)
            }
            super.onPageCommitVisible(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val entry = entries[tabId] ?: return
            // FulgurisがYouTube/キャッシュ復帰で行うのと同じく、progress=100の最初の完了だけを
            // 採用する。重複したonPageFinishedでCSS注入・Cookie flush・履歴通知を繰り返さない。
            if (!entry.tryCompletePageLifecycle(view.progress)) {
                CrashDiagnostics.record("page_finished_skipped", "url=$url\\nprogress=${view.progress}")
                return
            }
            applyDeepDarkCss(
                view,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(
                        entry.settings,
                        isVideoPlaybackDocumentUrl(url),
                        url,
                        entry.documentIsAlreadyDark && false
                    ),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            if (false) detectAlreadyDarkDocument(view, entry, url)
            else releaseDarkRevealGuard(view, entry, url)
            if (isVideoPlaybackDocumentUrl(url)) recordVideoViewportMetrics(view, url, entry)
            scheduleCookieFlush(view, entry)
            entry.callbacks.onPageFinished(tabId, url, view.title)
            notifyHistoryState(tabId, view)
            // SPA以外ではonPageFinishedを履歴遷移完了の保険として扱う。ただし、連打で既に
            // 次の戻る要求へ進んだ後の古いcallbackでは、その新しい要求を完了扱いにしない。
            if (entry.activeDocumentUrl == url) completeBackNavigation(tabId, entry, view)
        }


        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel() // 証明書エラーを無視して接続することは絶対にしない。
            entries[tabId]?.callbacks?.onSslError(error.url)
        }

        /**
         * WebViewの既定interstitialへ進ませず、危険ページでは直前の安全な文書へ戻す。
         * falseを渡すことで、個人利用・最小通信の方針に合わせてこの判定を報告しない。
         */
        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponseCompat
        ) {
            CrashDiagnostics.record("webview_safe_browsing_blocked", "threatType=$threatType")
            when {
                WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_BACK_TO_SAFETY) -> {
                    callback.backToSafety(false)
                    entries[tabId]?.callbacks?.onNotice("安全でない可能性があるページをブロックしました。")
                }
                WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_SHOW_INTERSTITIAL) -> {
                    // 古いWebViewでは、Chromium標準の警告画面を表示して利用者に判断を委ねる。
                    callback.showInterstitial(false)
                }
                else -> {
                    // 応答APIが不完全な実装では、既定の警告を試みる。失敗時もアプリ本体は落とさない。
                    runCatching { callback.showInterstitial(false) }
                }
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // 同じURLを即時に再生成すると、壊れたページ・メモリ不足でレンダラーが再度落ちる無限ループになる。
            // 既に描画プロセスを失ったWebViewには loadUrl/clearHistory/stopLoading を実行せず、destroyだけを行う。
            val entry = entries.remove(tabId)
            entry?.isActive = false
            entry?.cookieFlushRunnable?.let(view::removeCallbacks)
            entry?.cookieFlushRunnable = null
            // native hostへ接続済みでも、終了済みrendererを親に残さない。
            (view.parent as? ViewGroup)?.removeView(view)
            val callbacks = entry?.callbacks ?: BrowserWebCallbacks.Empty
            CrashDiagnostics.recordWebViewRendererGone(detail.didCrash(), detail.rendererPriorityAtExit())
            runCatching { view.destroy() }
            callbacks.onRendererGone(tabId)
            return true
        }
    }

    private inner class SecureChromeClient(private val tabId: String) : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String) {
            entries[tabId]?.callbacks?.onTitle(tabId, title)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            entries[tabId]?.callbacks?.onProgress(tabId, newProgress)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            entries[tabId]?.callbacks?.onShowFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            entries[tabId]?.callbacks?.onHideFullscreen()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val resources = request.resources.toSet()
            entries[tabId]?.callbacks?.onWebPermissionRequest(request.origin.toString(), resources) { accepted ->
                if (accepted) request.grant(resources.toTypedArray()) else request.deny()
            } ?: request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: android.webkit.GeolocationPermissions.Callback
        ) {
            entries[tabId]?.callbacks?.onGeolocationPermission(origin) { accepted ->
                callback.invoke(origin, accepted, false)
            } ?: callback.invoke(origin, false, false)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            if (!isUserGesture) return false // 自動ポップアップは拒否する。
            val current = entries[tabId] ?: return false
            val newTabId = current.callbacks.onPopupRequested() ?: return false
            val popupView = createWebView(newTabId)
            // Transportへ渡して読み込みを開始する前に、popup WebView自身の初期サイズを確定する。
            // WRAP_CONTENT/未測定のままGoogleのJSにviewportを読ませない。
            popupView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            configure(popupView, current.settings, false)
            // 新規ウィンドウの WebView を、そのまま新しいタブへ接続する。
            // 空文字を loadedUrl に入れると Compose 再構成時に読み込み状態が不整合になるため null を維持する。
            entries[newTabId] = Entry(
                webView = popupView,
                callbacks = current.callbacks,
                settings = current.settings,
                appliedForceDark = current.settings.forceDarkPages,
            )
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = popupView
            // Google Images等はTransport受領直後にviewportを読む。親へ追加される前の
            // 0x0状態でsendToTarget()すると、後からサイズが確定してもdialogのCSSが
            // 再計算されないため、次のUI loopまで遅延させる。
            popupView.post {
                if (popupView.parent == null && view.width > 0 && view.height > 0) {
                    popupView.measure(
                        View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY)
                    )
                    popupView.layout(0, 0, view.width, view.height)
                }
                resultMsg.sendToTarget()
            }
            return true
        }
    }

    private inner class SecureDownloadListener(private val tabId: String) : DownloadListener {
        override fun onDownloadStart(
            url: String, userAgent: String, contentDisposition: String,
            mimeType: String, contentLength: Long
        ) {
            if (!isHttps(url)) {
                entries[tabId]?.callbacks?.onBlockedNavigation(url)
                return
            }
            val entry = entries[tabId] ?: return
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            entry.callbacks.onDownloadRequested(
                BrowserDownloadRequest(
                    url = url,
                    fileName = fileName,
                    mimeType = mimeType,
                    userAgent = userAgent,
                    cookie = CookieManager.getInstance().getCookie(url),
                    // 一部の配布サイトはRefererを要求するため、リンクを押したページのHTTPS URLも渡す。
                    referer = entry.webView.url?.takeIf(::isHttps)
                )
            )
        }
    }

    private data class Entry(
        val webView: WebView,
        var loadedUrl: String? = null,
        /** YouTube originを含むiframeへ登録するBrave scriptlet。 */
        var documentStartScriptHandler: ScriptHandler? = null,
        var documentStartScriptUrl: String? = null,
        /** 一般ページの白フラッシュを防ぐdocument-start暗色CSS。 */
        var darkDocumentStartScriptHandler: ScriptHandler? = null,
        /** 主文書のoriginにだけ登録する、指定2標準リスト由来のtrusted scriptlet。 */
        var siteDocumentStartScriptHandler: ScriptHandler? = null,
        var siteDocumentStartScriptUrl: String? = null,
        var youtubePictureInPictureScriptHandler: ScriptHandler? = null,
        /** 組込みYouTube広告メタデータ除去script。外部リストには実行権限を与えない。 */
        /** warm navigationのplayer requestだけを補正する組込みscript。攻めたモード限定。 */
        /** 既存sessionを再取得せずSABR backoffだけを短縮する組込みscript。攻めたモード限定。 */
        var cookieFlushRunnable: Runnable? = null,
        var callbacks: BrowserWebCallbacks = BrowserWebCallbacks.Empty,
        var settings: BrowserSettings = BrowserSettings(),
        var appliedForceDark: Boolean? = null,
        var appliedDarkModeExcludedHosts: List<String>? = null,
        @Volatile var darkRevealPending: Boolean = false,
        /** PageCommitVisible後に読んだ、ページ自身の暗色テーマ状態。遷移・ホーム復帰では必ずfalseへ戻す。 */
        @Volatile var documentIsAlreadyDark: Boolean = false,
        /** about:blank完了まで、停止済みの旧HTTPS文書callbackをUIへ渡さない。 */
        @Volatile var fullscreenVideoDarkeningSuppressed: Boolean = false,
        @Volatile var activeDocumentUrl: String? = null,
        /** onScaleChangedで受け取るWebViewの最新拡大率。初期値は標準倍率。 */
        @Volatile var pageScale: Float = 1f,
        @Volatile var adBlockingEnabled: Boolean = true,
        @Volatile var isActive: Boolean = true,
        @Volatile private var backNavigationInFlight: Boolean = false,
        @Volatile private var queuedBackRequests: Int = 0,
        @Volatile private var lifecycleUrl: String? = null,
        @Volatile private var pageFinishedDone: Boolean = false
    ) {
        /**
         * Fulguris WebPageClientの`onPageFinishedDone`再armに相当する最小状態。
         * `shouldInterceptRequest`からも呼ばれるため、UI状態やWebView本体には触れない。
         */
        @Synchronized
        fun rearmPageLifecycle(url: String) {
            lifecycleUrl = url
            pageFinishedDone = false
        }

        /** `progress == 100`の最初のonPageFinishedだけを後処理に通す。 */
        @Synchronized
        fun tryCompletePageLifecycle(progress: Int): Boolean {
            if (pageFinishedDone || progress != 100) return false
            pageFinishedDone = true
            return true
        }

        /** 同一WebViewの戻る遷移を重ねず、連打分は小さく保留する。 */
        @Synchronized
        fun beginBackNavigation(): Boolean {
            if (backNavigationInFlight) {
                queuedBackRequests = (queuedBackRequests + 1).coerceAtMost(MAX_QUEUED_BACK_REQUESTS)
                return false
            }
            backNavigationInFlight = true
            return true
        }

        @Synchronized
        fun cancelBackNavigation() {
            backNavigationInFlight = false
            queuedBackRequests = 0
        }

        /** 現在の遷移が反映された後に、保留された次の一回を実行するか返す。 */
        @Synchronized
        fun completeBackNavigation(): Boolean {
            if (!backNavigationInFlight) return false
            backNavigationInFlight = false
            if (queuedBackRequests <= 0) return false
            queuedBackRequests -= 1
            return true
        }

        companion object {
            private const val MAX_QUEUED_BACK_REQUESTS = 12
        }
    }

    /** Cookie書込みをページ完了ごとに同期実行せず、連続遷移をまとめてから一度だけ行う。 */
    private fun scheduleCookieFlush(view: WebView, entry: Entry) {
        entry.cookieFlushRunnable?.let(view::removeCallbacks)
        val runnable = Runnable {
            entry.cookieFlushRunnable = null
            if (entry.isActive) runCatching { CookieManager.getInstance().flush() }
        }
        entry.cookieFlushRunnable = runnable
        view.postDelayed(runnable, COOKIE_FLUSH_DEBOUNCE_MS)
    }

    private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

    /** FulgurisのGoogle Translate URL経路。中国語だけは地域を含む完全タグを渡す。 */
    private fun googleTranslateUrl(sourceUrl: String): String? {
        val source = runCatching { Uri.parse(sourceUrl) }.getOrNull() ?: return null
        if (!source.scheme.equals("https", ignoreCase = true) || source.host.isNullOrBlank()) return null
        val locale = Locale.getDefault()
        val targetLanguage = if (locale.language.equals("zh", ignoreCase = true)) {
            locale.toLanguageTag()
        } else {
            locale.language.ifBlank { "en" }
        }
        return Uri.Builder()
            .scheme("https")
            .authority("translate.google.com")
            .appendPath("translate")
            .appendQueryParameter("sl", "auto")
            .appendQueryParameter("tl", targetLanguage)
            .appendQueryParameter("u", sourceUrl)
            .build()
            .toString()
    }

    private fun isGoogleTranslateDocumentUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.host.equals("translate.google.com", ignoreCase = true) && uri.path == "/translate"
    }.getOrDefault(false)

    private fun intentFallbackUrl(url: String): String? = runCatching {
        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        intent.getStringExtra("browser_fallback_url")?.let(::upgradeToHttps)
    }.getOrNull()

    private fun youtubeHost(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /** document-start APIは完全なHTTPS origin ruleを要求する。 */
    private fun documentStartOriginRule(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) null
        else "https://${uri.host.lowercase()}"
    }.getOrNull()

    private fun isYoutubeDocumentUrl(url: String): Boolean {
        val host = youtubeHost(url) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }

    /** ShortsはURLで判定できるため、ページ暗色化CSSを使わず映像surfaceを常にそのまま保つ。 */
    private fun isYoutubeShortsDocumentUrl(url: String): Boolean = runCatching {
        isYoutubeDocumentUrl(url) && URI(url).path.startsWith("/shorts/")
    }.getOrDefault(false)

    /** プレーヤー・ページ骨格に触れる規則を避け、広告・販促要素だけをYouTubeへ再適用する。 */
    private fun isSafeYoutubeAdSelector(selector: String): Boolean {
        val normalized = selector.lowercase().trim()
        if (normalized.isBlank() || normalized.length > 500) return false
        val adToken = listOf("ad", "promoted", "sponsor", "masthead", "merchandise", "paid", "brand").any(normalized::contains)
        val layoutToken = listOf("#player", "video", "iframe", "ytd-app", "ytm-app", "ytd-page-manager", "html", "body").any(normalized::contains)
        return adToken && !layoutToken
    }

    /** Google検索は動画タブとプレビュー展開を同じ検索文書上で行うため、広いcosmetic適用を避ける。 */
    private fun isGoogleSearchDocumentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        return (host == "google.com" || host.endsWith(".google.com")) && uri.path == "/search"
    }


    private fun isGoogleVideoSearchDocumentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!isGoogleSearchDocumentUrl(url)) return false
        val query = uri.rawQuery.orEmpty()
        return GOOGLE_VIDEO_SEARCH_QUERY_REGEX.containsMatchIn(query)
    }

    /** ダークCSSと動画映像面の競合を避ける必要がある文書。 */
    private fun isVideoPlaybackDocumentUrl(url: String): Boolean =
        isYoutubeDocumentUrl(url) || isGoogleVideoSearchDocumentUrl(url)

    /**
     * Google動画タブが生成するiframe、player script、映像chunk、内部APIは誤遮断から守る。
     * 画像・スタイル・fontなどの非必須要求は通常のBrave規則に渡し、広告枠の遮断余地を残す。
     */
    private fun isGoogleVideoPreviewResource(documentUrl: String, resourceType: String): Boolean =
        isGoogleVideoSearchDocumentUrl(documentUrl) && resourceType in PLAYBACK_CRITICAL_RESOURCE_TYPES

    /**
     * 映像復号とiframe表示に必須な要求だけを保護する。script/XHRは通常のBrave規則へ渡すことで、
     * 以前機能していたYouTube広告・計測のネットワーク遮断を回復する。
     */
    private fun isYoutubePlaybackResource(url: String, resourceType: String): Boolean {
        if (resourceType !in YOUTUBE_PLAYBACK_PROTECTED_RESOURCE_TYPES) return false
        val host = youtubeHost(url) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
            host == "youtubei.googleapis.com"
    }

    /** 再生保護の例外として、広告・計測専用と明示できる宛先だけ規則評価を継続する。 */
    private fun isYoutubeAdOrTrackingNetwork(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        return host == "ads.youtube.com" || host.endsWith(".ads.youtube.com") ||
            host == "doubleclick.net" || host.endsWith(".doubleclick.net") ||
            host == "googlesyndication.com" || host.endsWith(".googlesyndication.com") ||
            host == "googleadservices.com" || host.endsWith(".googleadservices.com") ||
            host == "googletagservices.com" || host.endsWith(".googletagservices.com") ||
            ((host == "youtube.com" || host.endsWith(".youtube.com")) &&
                (path.startsWith("/api/stats/ads") || path.startsWith("/_get_ads") ||
                    path.startsWith("/pcs/activeview") || path.startsWith("/pagead") ||
                    path.contains("/youtubei/v1/player/ad_break") || path.startsWith("/get_midroll_")))
    }

    private fun recordVideoViewportMetrics(view: WebView, url: String, entry: Entry) {
        view.evaluateJavascript(VIDEO_VIEWPORT_METRICS_SCRIPT) { raw ->
            val metrics = runCatching { JSONTokener(raw ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()
            if (metrics.isNotBlank()) {
                CrashDiagnostics.record(
                    "youtube_viewport_metrics",
                    "host=${youtubeHost(url).orEmpty()}\nwebViewWidth=${view.width}\nwebViewHeight=${view.height}\nscrollX=${view.scrollX}\nscrollY=${view.scrollY}\nscale=${entry.pageScale}\n$metrics"
                )
            }
        }
    }

    /**
     * Braveの`$redirect`はdata URLとして返る。WebViewではリダイレクト先URLを安全に再発行できないため、
     * 埋込み可能な小さなscript/style/imageだけを検証して返す。主文書、iframe、media、XHRは対象外とする。
     */
    private fun createSafeBraveRedirectResponse(dataUrl: String?, resourceType: String): WebResourceResponse? {
        if (dataUrl.isNullOrBlank() || resourceType !in SAFE_REDIRECT_RESOURCE_TYPES ||
            dataUrl.length > MAX_SAFE_REDIRECT_DATA_URL_CHARS
        ) return null
        val match = DATA_URL_BASE64_REGEX.matchEntire(dataUrl) ?: return null
        val mimeType = match.groupValues[1].lowercase()
        if (!isSafeRedirectMimeType(resourceType, mimeType)) return null
        val bytes = runCatching { Base64.decode(match.groupValues[2], Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_SAFE_REDIRECT_BYTES }
            ?: return null
        val encoding = if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.endsWith("+xml")) {
            "utf-8"
        } else null
        return WebResourceResponse(
            mimeType, encoding, 200, "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            ByteArrayInputStream(bytes)
        )
    }

    private fun isSafeRedirectMimeType(resourceType: String, mimeType: String): Boolean = when (resourceType) {
        "script" -> mimeType in SAFE_REDIRECT_SCRIPT_MIME_TYPES
        "stylesheet" -> mimeType == "text/css"
        "image" -> mimeType in SAFE_REDIRECT_IMAGE_MIME_TYPES
        else -> false
    }

    private fun resourceTypeFor(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"
        val headers = request.requestHeaders
        val destination = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }?.value?.lowercase()
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value?.lowercase().orEmpty()
        return when (destination) {
            "script" -> "script"
            "style" -> "stylesheet"
            "image" -> "image"
            "font" -> "font"
            "audio", "video", "track" -> "media"
            "iframe", "frame" -> "subdocument"
            "empty" -> "xmlhttprequest"
            else -> when {
                "text/css" in accept -> "stylesheet"
                "javascript" in accept || "ecmascript" in accept -> "script"
                "image/" in accept -> "image"
                "video/" in accept || "audio/" in accept -> "media"
                "application/json" in accept || "text/event-stream" in accept -> "xmlhttprequest"
                request.url.path?.endsWith(".js", true) == true -> "script"
                request.url.path?.endsWith(".css", true) == true -> "stylesheet"
                request.url.path?.matches(IMAGE_EXTENSION_REGEX) == true -> "image"
                request.url.path?.matches(MEDIA_EXTENSION_REGEX) == true -> "media"
                else -> "other"
            }
        }
    }

    private companion object {
        const val ABOUT_BLANK_URL = "about:blank"
        const val VIDEO_DIMENSIONS_BRIDGE_NAME = "NekoBrowserVideoDimensions"
        const val MAX_STATIC_COSMETIC_SELECTORS = 500
        const val MAX_AGGRESSIVE_YOUTUBE_SELECTORS = 2_000
        const val GENERIC_COSMETIC_DELAY_MS = 350L
        const val MAX_SAFE_REDIRECT_DATA_URL_CHARS = 256 * 1024
        const val MAX_SAFE_REDIRECT_BYTES = 128 * 1024
        val DATA_URL_BASE64_REGEX = Regex("^data:([^;,]+);base64,([A-Za-z0-9+/=]+)$", RegexOption.IGNORE_CASE)
        val SAFE_REDIRECT_RESOURCE_TYPES = setOf("script", "stylesheet", "image")
        val SAFE_REDIRECT_SCRIPT_MIME_TYPES = setOf("application/javascript", "application/x-javascript", "text/javascript")
        val SAFE_REDIRECT_IMAGE_MIME_TYPES = setOf("image/gif", "image/png", "image/svg+xml")

        const val COOKIE_FLUSH_DEBOUNCE_MS = 750L
        const val YOUTUBE_SCRIPTLET_DOCUMENT_URL = "https://www.youtube.com/"
        /**
         * 既存ページのDOM・style・viewportを一切変更しない暗色テーマ検出。
         * 透明背景は親要素を遡り、0.18以下の実背景、または0.35以下かつdark color-schemeを採用する。
         */
        /** ページ自身のstyleを変えずに、既存のねこぶらうざ暗色styleだけを一時停止して本来の色を返す。 */
        val DARK_DETECTOR_WITHOUT_OVERRIDE_SCRIPT = """
            (function(){
              var style=document.getElementById('__https_browser_deep_dark');
              var previous=style?style.textContent:null;
              if(style) style.textContent='';
              try {
                function parseColor(value){
                  var m=String(value||'').match(/rgba?\(\s*([\d.]+)[,\s]+\s*([\d.]+)[,\s]+\s*([\d.]+)(?:[,\s]+\s*([\d.]+))?\s*\)/i);
                  if(!m) return null;
                  var a=m[4]===undefined?1:parseFloat(m[4]);
                  return a>0.02?[parseFloat(m[1]),parseFloat(m[2]),parseFloat(m[3])]:null;
                }
                function lum(rgb){return (0.2126*rgb[0]+0.7152*rgb[1]+0.0722*rgb[2])/255;}
                function background(node){
                  for(var current=node,i=0;current&&i<8;i++,current=current.parentElement){
                    var color=parseColor(getComputedStyle(current).backgroundColor);
                    if(color) return lum(color);
                  }
                  return null;
                }
                var body=background(document.body);
                var value=body===null?background(document.documentElement):body;
                var root=getComputedStyle(document.documentElement);
                var bodyStyle=document.body?getComputedStyle(document.body):null;
                var scheme=(root.colorScheme+' '+(bodyStyle?bodyStyle.colorScheme:'')).toLowerCase();
                return value!==null ? (value<=0.18 || (value<=0.35&&scheme.indexOf('dark')!==-1)) : scheme.indexOf('dark')!==-1;
              } finally { if(style) style.textContent=previous; }
            })();
        """.trimIndent()

        val ALREADY_DARK_DOCUMENT_DETECTOR_SCRIPT = """
            (function(){
              function parseColor(value){
                var m=String(value||'').match(/rgba?\(\s*([\d.]+)[,\s]+\s*([\d.]+)[,\s]+\s*([\d.]+)(?:[,\s]+\s*([\d.]+))?\s*\)/i);
                if(!m){
                  var hex=String(value||'').match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
                  if(!hex) return null;
                  var raw=hex[1];
                  if(raw.length===3) raw=raw.replace(/(.)/g,'$1$1');
                  return [parseInt(raw.slice(0,2),16),parseInt(raw.slice(2,4),16),parseInt(raw.slice(4,6),16)];
                }
                var a=m[4]===undefined?1:parseFloat(m[4]);
                return a>0.02?[parseFloat(m[1]),parseFloat(m[2]),parseFloat(m[3])]:null;
              }
              function luminance(rgb){
                return (0.2126*rgb[0]+0.7152*rgb[1]+0.0722*rgb[2])/255;
              }
              function backgroundOf(node){
                var current=node;
                for(var i=0; current && i<8; i++,current=current.parentElement){
                  var color=parseColor(getComputedStyle(current).backgroundColor);
                  if(color) return luminance(color);
                }
                return null;
              }
              var bodyBackground=backgroundOf(document.body);
              var background=bodyBackground===null?backgroundOf(document.documentElement):bodyBackground;
              var rootStyle=getComputedStyle(document.documentElement);
              var bodyStyle=document.body?getComputedStyle(document.body):null;
              var scheme=(rootStyle.colorScheme+' '+(bodyStyle?bodyStyle.colorScheme:'')).toLowerCase();
              var meta=document.querySelector('meta[name="theme-color"]');
              var metaColor=meta?parseColor(meta.content):null;
              if(background!==null) return background<=0.18 || (background<=0.35 && scheme.indexOf('dark')!==-1);
              return scheme.indexOf('dark')!==-1 || (metaColor!==null && luminance(metaColor)<=0.18);
            })();
        """.trimIndent()

        /** 一般ページを最初の描画前から黒い暗色CSSで覆い、document-start未対応時はnative背景の黒を保つ。 */
        fun darkDocumentStartScript(): String = """
            (function(){
              var id='__https_browser_deep_dark';
              var style=document.getElementById(id);
              if(!style){style=document.createElement('style');style.id=id;(document.documentElement||document.head).appendChild(style);}
              style.textContent=${JSONObject.quote(DEEP_DARK_CSS)};
            })();
        """.trimIndent()

        // Brave Android PR #28593と同じく、YouTubeのページ側PiP阻害フラグを最小限だけ無効化する。
        // config未生成・対応外構造ではno-opとし、複数回のSPA遷移でも追加の要素を作らない。
        val VIDEO_DIMENSIONS_REPORTER_SCRIPT = """
            (function(){
              if(window.__nekoBrowserVideoDimensionsReporter) return;
              window.__nekoBrowserVideoDimensionsReporter=true;
              var last='';
              function bestVideo(){
                var videos=Array.prototype.slice.call(document.querySelectorAll('video'));
                videos.sort(function(a,b){
                  var as=(a.videoWidth||0)*(a.videoHeight||0),bs=(b.videoWidth||0)*(b.videoHeight||0);
                  if(!a.paused) as+=1000000000;
                  if(!b.paused) bs+=1000000000;
                  return bs-as;
                });
                return videos[0];
              }
              function report(){
                var video=bestVideo();
                if(!video || !video.videoWidth || !video.videoHeight) return;
                var value=video.videoWidth+'x'+video.videoHeight;
                if(value===last) return;
                last=value;
                try{window.NekoBrowserVideoDimensions.report(video.videoWidth,video.videoHeight);}catch(_e){}
              }
              function track(video){
                if(!video || video.__nekoBrowserDimensionsTracked) return;
                video.__nekoBrowserDimensionsTracked=true;
                ['loadedmetadata','resize','playing','loadeddata'].forEach(function(name){video.addEventListener(name,report,{passive:true});});
              }
              function scan(){document.querySelectorAll('video').forEach(track);report();}
              scan();
              new MutationObserver(scan).observe(document.documentElement||document,{subtree:true,childList:true});
            })();
        """.trimIndent()

        // uBlock Originの現行YouTube規則（adPlacements/adSlots/playerAds/Shorts）を、
        // WebViewで利用可能なdocument-start JavaScriptへ最小限に翻訳した組込み補助。
        // 通常動画の応答はJSON全走査をせずキー名だけを無効化し、Shortsだけで広告entryを解析する。
        val YOUTUBE_PIP_UNLOCK_SCRIPT = """
            (function(){
              function modifyYtcfgFlags(){
                try{
                  if(!window.ytcfg || typeof window.ytcfg.get!=='function') return;
                  var config=window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS');
                  config=config&&config.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH;
                  if(!config || typeof config.serializedExperimentFlags!=='string') return;
                  var flags=config.serializedExperimentFlags;
                  var replacements=[
                    ['html5_picture_in_picture_blocking_ontimeupdate=true','html5_picture_in_picture_blocking_ontimeupdate=false'],
                    ['html5_picture_in_picture_blocking_onresize=true','html5_picture_in_picture_blocking_onresize=false'],
                    ['html5_picture_in_picture_blocking_document_fullscreen=true','html5_picture_in_picture_blocking_document_fullscreen=false'],
                    ['html5_picture_in_picture_blocking_standard_api=true','html5_picture_in_picture_blocking_standard_api=false'],
                    ['html5_picture_in_picture_logging_onresize=true','html5_picture_in_picture_logging_onresize=false']
                  ];
                  replacements.forEach(function(pair){flags=flags.replace(pair[0],pair[1]);});
                  config.serializedExperimentFlags=flags;
                }catch(_e){}
              }
              function unlock(video){
                if(!video) return;
                try{video.disablePictureInPicture=false;}catch(_e){}
                try{video.removeAttribute('disablePictureInPicture');}catch(_e){}
              }
              function unlockAll(){document.querySelectorAll('video').forEach(unlock);}
              function startVideos(){
                unlockAll();
                var root=document.documentElement||document;
                new MutationObserver(function(records){
                  records.forEach(function(record){
                    if(record.type==='attributes' && record.target && record.target.tagName==='VIDEO') unlock(record.target);
                    record.addedNodes&&record.addedNodes.forEach(function(node){
                      if(node.nodeType!==1) return;
                      if(node.tagName==='VIDEO') unlock(node);
                      if(node.querySelectorAll) node.querySelectorAll('video').forEach(unlock);
                    });
                  });
                }).observe(root,{subtree:true,childList:true,attributes:true,attributeFilter:['disablepictureinpicture']});
              }
              modifyYtcfgFlags();
              if(!window.ytcfg){
                document.addEventListener('load',function(event){
                  if(event.target&&event.target.tagName==='SCRIPT') modifyYtcfgFlags();
                },true);
              }
              if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',startVideos,{once:true}); else startVideos();
            })();
        """.trimIndent()
        // 指定101リストのyoutube.com/m.youtube.com専用cosmetic規則だけを固定適用する。
        // #player、video、ytm-player、grid/layoutコンテナは意図的に含めない。
        val DEEP_DARK_CSS = "html{background:#000!important;color-scheme:dark!important}" +
            "body{background:#fff!important;color:#111!important;filter:invert(1) hue-rotate(180deg)!important}" +
            "img,canvas,iframe,svg,picture,object,embed{filter:invert(1) hue-rotate(180deg)!important}" +
            "video,video::-webkit-media-controls-panel,video::-webkit-media-controls-enclosure{filter:invert(1) hue-rotate(180deg)!important}" +
            "input,textarea,select{background:#e8e8e8!important;color:#111!important}"
        // YouTubeは反転ではなく前景・背景を直接指定し、動画surfaceは常にfilter:noneで保護する。
        val YOUTUBE_PAGE_DARK_CSS = "html,body,ytd-app,ytm-app{background:#0f0f0f!important;color:#f1f1f1!important;color-scheme:dark!important}" +
            "#masthead-container,#masthead,ytd-masthead,ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer{background:#0f0f0f!important;color:#f1f1f1!important}" +
            "ytd-app *,ytm-app *{border-color:#3f3f3f!important}" +
            "ytd-app a,ytm-app a,ytd-app yt-formatted-string,ytm-app yt-formatted-string,ytd-app h1,ytd-app h2,ytd-app h3,ytd-app h4,ytd-app span,ytm-app span{color:#f1f1f1!important}" +
            "input,textarea,select{background:#202020!important;color:#f1f1f1!important;border-color:#555!important}" +
            "video,video *,#player video,ytm-player video{filter:none!important;background:#000!important;color-scheme:normal!important}" +
            ".ytp-gradient-top,.ytp-gradient-bottom{filter:none!important}"
        val YOUTUBE_AD_CSS = """
            #player-ads,.ytp-ad-overlay-container,.ytp-ad-module,
            ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-promoted-video-renderer,
            ytd-promoted-sparkles-web-renderer,ytd-companion-slot-renderer,
            ytd-action-companion-ad-renderer,ytm-ad-slot-renderer,
            ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,
            ytd-rich-item-renderer:has(> ytd-ad-slot-renderer),
            ytd-shorts:has(> .ytd-reel-video-renderer > ytd-ad-slot-renderer),
            ytd-search-pyv-renderer.ytd-item-section-renderer,
            ytd-watch-next-secondary-results-renderer > ytd-ad-slot-renderer,
            ytd-rich-item-renderer > ytd-ad-slot-renderer,
            ytd-item-section-renderer > ytd-ad-slot-renderer,
            ytm-rich-item-renderer > ad-slot-renderer,
            lazy-list > ad-slot-renderer,
            ytm-companion-slot[data-content-type] > ytm-companion-ad-renderer,
            #masthead-ad.ytd-rich-grid-renderer,
            .ytp-suggested-action > .ytp-suggested-action-badge,
            yt-overlay-product-sticker {
              display:none!important;visibility:hidden!important;
            }
        """.trimIndent()
        // Google動画タブを含む動画文書で、映像面と重なり要素を実寸診断する。
        val VIDEO_VIEWPORT_METRICS_SCRIPT = """
            (function(){
              function rect(selector){
                var e=document.querySelector(selector),r=e&&e.getBoundingClientRect();
                return r?{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}:null;
              }
              return JSON.stringify({
                innerWidth:window.innerWidth,
                clientWidth:document.documentElement.clientWidth,
                scrollWidth:document.documentElement.scrollWidth,
                visualWidth:window.visualViewport?Math.round(window.visualViewport.width):null,
                visualOffsetLeft:window.visualViewport?Math.round(window.visualViewport.offsetLeft):null,
                scrollX:window.scrollX,
                documentOverflowX:getComputedStyle(document.documentElement).overflowX,
                bodyOverflowX:document.body?getComputedStyle(document.body).overflowX:null,
                leftStack:(document.elementsFromPoint?document.elementsFromPoint(1,Math.max(1,Math.min(window.innerHeight-1,160))):[]).slice(0,5).map(function(e){var s=getComputedStyle(e);return {tag:e.tagName,id:e.id,cls:(e.className&&String(e.className).slice(0,120))||'',position:s.position,z:s.zIndex,bg:s.backgroundColor};}),
                body:rect('body'),
                player:rect('#player,ytm-player'),
                video:rect('video')
              });
            })();
        """.trimIndent()
        // 各リソース要求ごとに Regex を生成しない。ページの大量リソース読み込み時の
        // Kotlinヒープ確保を抑え、ネイティブフィルタ評価だけに処理を限定する。
        val IMAGE_EXTENSION_REGEX = Regex(".*\\.(png|jpe?g|gif|webp|svg|avif)$", RegexOption.IGNORE_CASE)
        val MEDIA_EXTENSION_REGEX = Regex(".*\\.(mp4|webm|m3u8|mpd|mp3|m4a)$", RegexOption.IGNORE_CASE)
        val GOOGLE_VIDEO_SEARCH_QUERY_REGEX = Regex("(?:^|&)(?:tbm=vid|udm=7)(?:&|$)")
        // 動画本体と再生iframeだけを保護し、script/XHRはYouTubeを含む全サイトでBraveへ判定させる。
        // 初回遷移だけscript/XHRを素通りさせると広告初期化が通り、再読み込み時との差が生じる。
        val PLAYBACK_CRITICAL_RESOURCE_TYPES = setOf("media", "subdocument")
        val YOUTUBE_PLAYBACK_PROTECTED_RESOURCE_TYPES = setOf("media", "subdocument")
        val COLLECT_COSMETIC_KEYS_SCRIPT = """
            (function(){
              var classes=[],ids=[],seenClasses=new Set(),seenIds=new Set();
              var elements=document.querySelectorAll('[class],[id]');
              for(var i=0;i<elements.length;i++){
                if(ids.length>=800 && classes.length>=1200) break;
                var element=elements[i];
                if(element.id && !seenIds.has(element.id) && ids.length<800){seenIds.add(element.id);ids.push(element.id);}
                if(element.classList){element.classList.forEach(function(name){if(!seenClasses.has(name) && classes.length<1200){seenClasses.add(name);classes.push(name);}});}
              }
              return JSON.stringify({classes:classes,ids:ids});
            })();
        """.trimIndent()
    }

    private fun upgradeToHttps(url: String): String? = runCatching {
        val uri = URI(url)
        when (uri.scheme?.lowercase()) {
            "https" -> uri.toString()
            "http" -> URI("https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            else -> null
        }
    }.getOrNull()
}

interface BrowserWebCallbacks {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String?)
    fun onVisitedHistory(tabId: String, url: String)
    fun onTitle(tabId: String, title: String)
    fun onHistoryState(tabId: String, canGoBack: Boolean, canGoForward: Boolean)
    /** 連打キューがWebView履歴を使い切った時だけ、選択中タブを独自ホームへ戻す。 */
    fun onBackHistoryExhausted(tabId: String)
    fun onProgress(tabId: String, progress: Int)
    fun onScrollPosition(tabId: String, fraction: Float)
    fun onHttpsUpgrade(url: String)
    fun onBlockedNavigation(url: String)
    fun onSslError(url: String)
    fun onRendererGone(tabId: String)
    fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideFullscreen()
    fun onVideoDimensions(tabId: String, width: Int, height: Int)
    fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit)
    fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit)
    fun onPopupRequested(): String?
    fun onLinkLongPressed(url: String)
    fun onDownloadRequested(request: BrowserDownloadRequest)
    fun onPageArchiveReady(sourcePath: String, fileName: String)
    fun onExternalAppRequested(url: String)
    fun onPageInteraction()
    fun onNotice(message: String)

    data object Empty : BrowserWebCallbacks {
        override fun onPageStarted(tabId: String, url: String) = Unit
        override fun onPageFinished(tabId: String, url: String, title: String?) = Unit
        override fun onVisitedHistory(tabId: String, url: String) = Unit
        override fun onTitle(tabId: String, title: String) = Unit
        override fun onHistoryState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) = Unit
        override fun onBackHistoryExhausted(tabId: String) = Unit
        override fun onProgress(tabId: String, progress: Int) = Unit
        override fun onScrollPosition(tabId: String, fraction: Float) = Unit
        override fun onHttpsUpgrade(url: String) = Unit
        override fun onBlockedNavigation(url: String) = Unit
        override fun onSslError(url: String) = Unit
        override fun onRendererGone(tabId: String) = Unit
        override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = Unit
        override fun onHideFullscreen() = Unit
        override fun onVideoDimensions(tabId: String, width: Int, height: Int) = Unit
        override fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit) = reply(false)
        override fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit) = reply(false)
        override fun onPopupRequested(): String? = null
        override fun onLinkLongPressed(url: String) = Unit
        override fun onDownloadRequested(request: BrowserDownloadRequest) = Unit
        override fun onPageArchiveReady(sourcePath: String, fileName: String) = Unit
        override fun onExternalAppRequested(url: String) = Unit
        override fun onPageInteraction() = Unit
        override fun onNotice(message: String) = Unit
    }
}
