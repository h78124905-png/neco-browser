package com.example.httpsbrowser

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.os.PowerManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.Locale
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.ConcurrentHashMap
import com.example.httpsbrowser.ui.BrowserScreen
import com.example.httpsbrowser.ui.HttpsBrowserTheme
import com.example.httpsbrowser.web.BrowserWebViewRegistry

class MainActivity : ComponentActivity() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private var incomingUrl by mutableStateOf<String?>(null)
    private lateinit var appRoot: FrameLayout
    /** 通常ページをComposeのAndroidViewから分離して保持する、選択タブ専用のnative host。 */
    private lateinit var normalWebContentHost: FrameLayout
    private lateinit var composeOverlayView: ComposeView
    private var normalWebContentBoundsReady = false
    // WebView本体は画面全体に置く。サイト内のfixed/overlay要素を親Viewで切らず、
    // 入力だけをComposeが計測したページ領域へ制限する。
    private var pageTouchLeft = 0
    private var pageTouchTop = 0
    private var pageTouchRight = 0
    private var pageTouchBottom = 0
    private var forwardingNormalWebTouch = false
    /** Composeの右端スクロールレールが見える通常ページだけ、レール用のタッチ領域を予約する。 */
    private var normalWebContentReservesRightTouchRail = false
    /** Googleのページ内モーダルなど、通常WebViewをComposeより前面に置くページかを保持する。 */
    private var normalWebContentPlacedAboveCompose = false
    @Volatile private var fullscreenVideoView: View? = null
    private var fullscreenVideoTabId: String? = null
    private val videoDimensionsByTab = ConcurrentHashMap<String, VideoDimensions>()
    private var fullscreenContainer: FrameLayout? = null
    private var videoControlsContainer: FrameLayout? = null
    private var videoSpeedIndex = 0
    private var videoSpeedRate = 1.0f
    private var videoSpeedButton: TextView? = null
    private var onVideoSpeedChanged: ((Float) -> Unit)? = null
    private var videoControlsRegistry: BrowserWebViewRegistry? = null
    private var videoControlsTabId: String? = null
    private var videoControlsPageUrl: String = ""
    private var videoControlsAllowedHosts: List<String> = emptyList()
    private var videoControlsInitialRate: Float = 1.0f
    private var pictureInPictureActive by mutableStateOf(false)
    @Volatile private var pictureInPictureTransitionRequested = false

    // custom viewは全画面・PiP遷移で座標が変わる。sourceRectHintを追従させる。
    private val pipHintLayoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (view === fullscreenVideoView && (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)) {
            updatePictureInPictureParams(view)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        activeActivity = this
        incomingUrl = httpsViewUrl(intent)

        // custom viewはComposeのAndroidViewに重ねず、Fulgurisと同じくActivityのnative rootへ追加する。
        // これにより動画surfaceの親・測定サイズがCompose再構成で変わらない。
        appRoot = FrameLayout(this)
        appRoot.setBackgroundColor(Color.BLACK)
        // Fulgurisと同じく、system bars/IMEの座標補正はrootで一度だけ行う。
        // Compose側のstatusBarsPadding/navigationBarsPadding/imePaddingと二重適用しない。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(appRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, imeBottom)
            )
            WindowInsetsCompat.CONSUMED
        }
        // 通常ページのWebViewはComposeのAndroidViewから完全に分離し、このnative hostへ
        // 接続する。Composeの再構成・タブ切替で親ViewをremoveView()しない。
        normalWebContentHost = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
        }
        composeOverlayView = ComposeView(this).apply {
            setContent {
                HttpsBrowserTheme {
                    BrowserScreen(viewModel(), externalUrl = incomingUrl)
                }
            }
        }
        // Fulgurisと同じく、画面の表示階層は単一のUI rootにする。
        // 旧native hostを背面に残すとWebViewの親・z順・タッチ座標が二重管理になる。
        appRoot.addView(composeOverlayView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(appRoot)
        ViewCompat.requestApplyInsets(appRoot)
    }

    /**
     * Compose rootが最前面でも、ページ矩形内で始まった連続タッチをnative WebViewへ転送する。
     * 右端レール用の細い領域はComposeへ残し、通常ページ上のスクロール・ピンチを阻害しない。
     */
    private fun forwardPageTouchToNativeWebView(event: MotionEvent): Boolean {
        if (::normalWebContentHost.isInitialized.not() || normalWebContentHost.visibility != View.VISIBLE) return false
        val railWidth = if (normalWebContentReservesRightTouchRail) {
            (40f * resources.displayMetrics.density).roundToInt()
        } else 0
        val location = IntArray(2)
        normalWebContentHost.getLocationOnScreen(location)
        val hostScreenX = location[0].toFloat()
        val hostScreenY = location[1].toFloat()
        val rawX = event.rawX
        val rawY = event.rawY
        val withinHost = normalWebContentBoundsReady &&
            rawX >= hostScreenX && rawX < hostScreenX + normalWebContentHost.width - railWidth &&
            rawY >= hostScreenY && rawY < hostScreenY + normalWebContentHost.height
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            forwardingNormalWebTouch = withinHost
        }
        if (!forwardingNormalWebTouch) return false
        val forwarded = MotionEvent.obtain(event)
        forwarded.setLocation(rawX - hostScreenX, rawY - hostScreenY)
        normalWebContentHost.dispatchTouchEvent(forwarded)
        forwarded.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            forwardingNormalWebTouch = false
        }
        return true
    }

    /** 選択タブの通常WebViewをnative hostへ接続し、Composeの再構成から親Viewを分離する。 */
    fun showNormalWebContent(registry: BrowserWebViewRegistry, tabId: String) {
        if (::normalWebContentHost.isInitialized.not()) return
        registry.attachToNativeHost(tabId, normalWebContentHost)
        // Composeからページ矩形を受けるまで全画面の仮LayoutParamsを見せない。
        normalWebContentHost.visibility = if (normalWebContentBoundsReady) View.VISIBLE else View.INVISIBLE
        CrashDiagnostics.record("normal_webview_native_host_shown", "tab=$tabId")
    }

    /**
     * Composeのシートやダイアログを前面にする。WebView自体は不可視化・切離しせず、
     * Composeの下で表示を維持することで動画・音声の描画サーフェスと再生sessionを保つ。
     */
    fun setNormalWebContentVisible(visible: Boolean) {
        if (::normalWebContentHost.isInitialized.not()) return
        if (normalWebContentHost.childCount == 0) {
            normalWebContentHost.visibility = View.GONE
            return
        }
        if (visible && normalWebContentPlacedAboveCompose) normalWebContentHost.bringToFront()
        else composeOverlayView.bringToFront()
        normalWebContentHost.visibility = if (normalWebContentBoundsReady) View.VISIBLE else View.INVISIBLE
    }

    /**
     * ホームタブでも既存WebViewをhostから外さない。通常のタブ切替で親Viewを外すと、
     * Chromiumが動画の描画サーフェスを破棄し再生を停止するため、Composeホームを前面に
     * 重ねて背後のWebViewセッションを保持する。明示的なホーム復帰・タブ削除・Activity
     * 破棄だけがWebViewを破棄する経路となる。
     */
    fun hideNormalWebContent() {
        if (::normalWebContentHost.isInitialized.not()) return
        // ホーム／シークレットの新規ホームでは、直前ページのnative WebViewを保持するが
        // Composeホームを覆わない。次の通常ページ計測で前面状態を再設定する。
        normalWebContentPlacedAboveCompose = false
        normalWebContentReservesRightTouchRail = false
        composeOverlayView.bringToFront()
        normalWebContentHost.visibility = if (normalWebContentHost.childCount > 0 && normalWebContentBoundsReady) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /** Composeが計測したページ矩形だけを通常WebViewへ割り当て、下部操作UIと重ねない。 */
    fun setNormalWebContentBounds(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        reserveRightTouchRail: Boolean,
        placeAboveCompose: Boolean
    ) {
        if (::normalWebContentHost.isInitialized.not() || width <= 0 || height <= 0) return
        // 全画面custom view表示中はComposeの再計測が発生しても、通常WebView hostの
        // bringToFront()でfullscreen containerを覆わない。これが全画面動画を操作不能にする。
        if (fullscreenContainer != null) return
        // Page boxはComposeがstatus bar下から下部バー上まで測定した値をそのまま使う。
        // Google系では右端予約を外し、重ねる型Webポップアップの全領域をWebViewへ渡す。
        normalWebContentReservesRightTouchRail = reserveRightTouchRail
        normalWebContentPlacedAboveCompose = placeAboveCompose
        // 通常ページではnative hostをComposeより前面に置く。hostのLayoutParamsをページ矩形へ
        // 更新するため、WebViewはComposeの再構成から完全に独立する。
        if (placeAboveCompose) normalWebContentHost.bringToFront()
        else composeOverlayView.bringToFront()
        val current = normalWebContentHost.layoutParams as? FrameLayout.LayoutParams ?: return
        normalWebContentBoundsReady = true
        pageTouchLeft = left
        pageTouchTop = top
        pageTouchRight = left + width
        pageTouchBottom = top + height
        // WebView hostそのものをComposeが計測したページ矩形へ合わせる。
        // これでhostをComposeより前面に置いても、下部のAddressBar/TabBarは操作できる。
        if (current.width != width || current.height != height ||
            current.leftMargin != left || current.topMargin != top
        ) {
            current.leftMargin = left
            current.topMargin = top
            current.width = width
            current.height = height
            normalWebContentHost.layoutParams = current
        }
        if (normalWebContentHost.childCount > 0) {
            // レイアウト更新時もhostを不可視化しない。IME・シート・タブ切替で
            // WebViewのサーフェスが破棄されると動画再生が止まる端末がある。
            normalWebContentHost.visibility = View.VISIBLE
        }
    }

    /**
     * Chromium WebChromeClientが渡すfullscreen custom viewを、Activity root上の単一native containerへ
     * 接続する。通常WebViewはCompose側に残るためAwContentsの描画先を切り替えない。
     */
    fun showFullscreenCustomView(view: View, tabId: String?) {
        if (::appRoot.isInitialized.not()) return
        if (fullscreenVideoView === view && fullscreenContainer != null) return
        hideFullscreenCustomView(fullscreenVideoView)

        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        appRoot.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        fullscreenContainer = container
        videoControlsContainer?.let { existing -> (existing.parent as? ViewGroup)?.removeView(existing) }
        videoControlsContainer = createVideoControlsContainer().also { controls ->
            container.addView(controls, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply { setMargins(18, 0, 0, 0) })
        }
        fullscreenVideoTabId = tabId
        setFullscreenVideoForPictureInPicture(view)
        runCatching { view.keepScreenOn = true }
        setFullscreenSystemBars(true)
        CrashDiagnostics.record("fullscreen_native_container_shown", "view=${view.javaClass.name}")
    }

    /** native fullscreen containerを一度だけ除去し、通常画面とPiP設定を復帰する。 */
    fun hideFullscreenCustomView(expectedView: View? = null) {
        val view = fullscreenVideoView
        if (expectedView != null && view !== expectedView) return
        fullscreenContainer?.let { container ->
            appRoot.removeView(container)
            container.removeAllViews()
        }
        val controlsRegistry = videoControlsRegistry
        val controlsTabId = videoControlsTabId
        fullscreenContainer = null
        videoControlsContainer = null
        fullscreenVideoTabId = null
        // PiPまたは全画面終了後にだけ通常のCompose操作UIを戻す。
        if (::composeOverlayView.isInitialized) composeOverlayView.visibility = View.VISIBLE
        runCatching { view?.keepScreenOn = false }
        setFullscreenVideoForPictureInPicture(null)
        setFullscreenSystemBars(false)
        if (controlsRegistry != null && controlsTabId != null && !isInPictureInPictureMode) {
            showVideoControls(
                controlsRegistry,
                controlsTabId,
                videoControlsPageUrl,
                videoControlsAllowedHosts,
                videoControlsInitialRate,
                onVideoSpeedChanged
            )
        }
        CrashDiagnostics.record("fullscreen_native_container_hidden", "view=${view?.javaClass?.name.orEmpty()}")
    }

    /**
     * WebViewが取得した実映像サイズをタブごとに記録し、全画面中のタブならPiP設定を即時更新する。
     * 画面回転ではなくvideoWidth/videoHeightを使うため、縦持ち中の横動画も横長PiPになる。
     */
    fun updatePictureInPictureVideoDimensions(tabId: String, width: Int, height: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updatePictureInPictureVideoDimensions(tabId, width, height) }
            return
        }
        if (tabId.isBlank() || width <= 0 || height <= 0) return
        val dimensions = VideoDimensions(width, height)
        if (videoDimensionsByTab.put(tabId, dimensions) == dimensions) return
        if (fullscreenVideoTabId == tabId) updatePictureInPictureParams(fullscreenVideoView)
    }

    /** 全画面custom viewが存在する間だけPiPへ移行できる。 */
    fun setFullscreenVideoForPictureInPicture(view: View?) {
        val previousView = fullscreenVideoView
        if (previousView !== view) previousView?.removeOnLayoutChangeListener(pipHintLayoutListener)
        fullscreenVideoView = view
        if (previousView !== view) view?.addOnLayoutChangeListener(pipHintLayoutListener)
        if (view == null) pictureInPictureTransitionRequested = false
        updatePictureInPictureParams(view)
    }

    /** WebViewがPiP開始に伴いonHideCustomViewを先に送っても、動画Viewを外さないための判定。 */
    fun shouldRetainFullscreenCustomView(): Boolean =
        pictureInPictureActive || pictureInPictureTransitionRequested

    /** 動画を検出したタブの操作UIを通常画面へ表示する。全画面中は同じUIをcustom view側へ移す。 */
    fun showVideoControls(
        registry: BrowserWebViewRegistry,
        tabId: String,
        pageUrl: String = "",
        allowedHosts: List<String> = emptyList(),
        initialRate: Float = 1.0f,
        onPlaybackRateChanged: ((Float) -> Unit)? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { showVideoControls(registry, tabId, pageUrl, allowedHosts, initialRate, onPlaybackRateChanged) }
            return
        }
        if (fullscreenContainer == null && !isAllowedVideoControlHost(pageUrl, allowedHosts)) {
            videoControlsContainer?.visibility = View.GONE
            return
        }
        videoControlsRegistry = registry
        videoControlsTabId = tabId
        videoControlsPageUrl = pageUrl
        videoControlsAllowedHosts = allowedHosts
        videoControlsInitialRate = initialRate
        videoSpeedRate = initialRate.coerceIn(1.0f, 2.0f)
        videoSpeedIndex = VIDEO_SPEEDS.indices.minByOrNull { kotlin.math.abs(VIDEO_SPEEDS[it] - videoSpeedRate) } ?: 0
        onVideoSpeedChanged = onPlaybackRateChanged
        // 同じActivity内の別動画・別タブにも、保存済みの速度を引き継ぐ。
        registry.setVideoPlaybackRate(tabId, videoSpeedRate)
        if (videoControlsContainer != null) {
            videoControlsContainer?.visibility = View.VISIBLE
            videoControlsContainer?.bringToFront()
            return
        }
        val controls = createVideoControlsContainer()
        videoControlsContainer = controls
        appRoot.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START
        ).apply { setMargins(18, 0, 0, 0) })
        controls.bringToFront()
    }

    private fun isAllowedVideoControlHost(url: String, allowedHosts: List<String>): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull() ?: return false
        return allowedHosts.any { configured ->
            val normalized = configured.trim().lowercase(Locale.ROOT)
                .removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
            normalized.isNotBlank() && (host == normalized || host.endsWith(".$normalized"))
        }
    }

    private fun createVideoControlsContainer(): FrameLayout = FrameLayout(this).apply {
        minimumWidth = (52 * resources.displayMetrics.density).toInt()
        minimumHeight = (168 * resources.displayMetrics.density).toInt()
        val pip = createPipButton()
        addView(pip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val speed = TextView(this@MainActivity).apply {
            text = "×${videoSpeedRate}"
            contentDescription = "再生速度を変更"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 14, 8, 14)
            minimumWidth = (44 * resources.displayMetrics.density).toInt()
            minimumHeight = (52 * resources.displayMetrics.density).toInt()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
                setColor(0xC20D1118.toInt())
                setStroke(1, 0x88FFFFFF.toInt())
            }
            setOnClickListener {
                videoSpeedIndex = (videoSpeedIndex + 1) % VIDEO_SPEEDS.size
                val rate = VIDEO_SPEEDS[videoSpeedIndex]
                videoSpeedRate = rate
                text = "×${rate}"
                onVideoSpeedChanged?.invoke(rate)
                videoControlsRegistry?.let { registry ->
                    videoControlsTabId?.let { id -> registry.setVideoPlaybackRate(id, rate) }
                }
            }
        }
        videoSpeedButton = speed
        addView(speed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (64 * resources.displayMetrics.density).toInt()
        })
    }

    /**
     * 白いPiPボタンから、PiP動画taskとブラウザ操作taskを同時に開始する。
     * Android 16ではPiP移行後のバックグラウンドActivity起動が拒否され得るため、
     * ユーザー操作中にブラウザtaskを先に起動し、元ActivityのonPauseでPiPへ入る。
     */
    fun enterFullscreenPictureInPictureMode(): Boolean {
        val videoView = fullscreenVideoView
        if (videoView == null || !supportsPictureInPicture() || isInPictureInPictureMode || pictureInPictureTransitionRequested) return false
        pipActivity = this
        pictureInPictureTransitionRequested = true
        val browserStarted = runCatching { startActivity(createBrowserTaskIntent()) }.isSuccess
        if (!browserStarted) {
            pictureInPictureTransitionRequested = false
            val entered = runCatching { enterPictureInPictureMode(buildPictureInPictureParams(videoView)) }.getOrDefault(false)
            if (!entered) CrashDiagnostics.record("pip_enter_failed", "browser_task_and_direct_pip_failed")
            return entered
        }
        // onPauseが届かない端末でも、ユーザー操作由来の猶予時間内にPiPへ移行する。
        window.decorView.postDelayed({
            if (pictureInPictureTransitionRequested && !isInPictureInPictureMode && fullscreenVideoView === videoView) {
                enterPictureInPictureFromPendingRequest(videoView)
            }
        }, 250L)
        CrashDiagnostics.record("pip_browser_task_requested", "source=explicit\napi=${Build.VERSION.SDK_INT}")
        return true
    }

    private fun enterPictureInPictureFromPendingRequest(videoView: View) {
        val entered = runCatching { enterPictureInPictureMode(buildPictureInPictureParams(videoView)) }.getOrDefault(false)
        if (!entered) {
            pictureInPictureTransitionRequested = false
            CrashDiagnostics.record("pip_enter_failed", "enterPictureInPictureMode=false")
        } else {
            CrashDiagnostics.record("pip_enter_requested", "source=browser_task_on_pause\napi=${Build.VERSION.SDK_INT}")
        }
    }

    override fun onPause() {
        super.onPause()
        if (pictureInPictureTransitionRequested && !isInPictureInPictureMode) {
            fullscreenVideoView?.let(::enterPictureInPictureFromPendingRequest)
        }
        // WebView.onPause() は呼ばず、バックグラウンド再生用のCPU維持だけを確保する。
        acquireWakeLockIfNeeded()
        requestAudioFocusIfNeeded()
    }

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "neco-browser:youtube-bg-lock"
            )
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
        }
    }

    private fun requestAudioFocusIfNeeded() {
        if (audioManager == null) {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { _ ->
                        // フォーカスを失ってもアプリ側から再生を停止しない。
                    }
                    .build()
            }
            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                { _ ->
                    // 旧APIでもフォーカス変化による再生停止は行わない。
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun releaseWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun abandonAudioFocusIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
        audioFocusRequest = null
        audioManager = null
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12以降は、事前に設定したauto-enterがジェスチャーPiPをより滑らかに開始する。
        // ここで明示enterを重ねると、WebView custom viewの停止・再親子化と競合し黒画面化し得る。
        // API 26〜30だけ従来の明示経路を使う。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && fullscreenVideoView != null && !isInPictureInPictureMode) {
            enterFullscreenPictureInPictureMode()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) pictureInPictureTransitionRequested = false
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureActive = isInPictureInPictureMode
        pictureInPictureTransitionRequested = false
        // ブラウザtaskはPiP開始前のユーザー操作中に起動済みであるため、
        // Android 16のPiP後バックグラウンド起動制限に依存しない。
        // PiP windowには動画だけを残す。操作ボタンやCompose下部バーはPiP中に合成しない。
        videoControlsContainer?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) {
            if (::composeOverlayView.isInitialized) composeOverlayView.visibility = View.GONE
        } else if (fullscreenContainer == null && ::composeOverlayView.isInitialized) {
            composeOverlayView.visibility = View.VISIBLE
        }
        CrashDiagnostics.record(
            if (isInPictureInPictureMode) "pip_entered" else "pip_exited",
            "fullscreenView=${fullscreenVideoView != null}"
        )
    }

    override fun onDestroy() {
        releaseWakeLockIfNeeded()
        abandonAudioFocusIfNeeded()
        fullscreenVideoView?.removeOnLayoutChangeListener(pipHintLayoutListener)
        fullscreenVideoView = null
        fullscreenContainer = null
        if (::normalWebContentHost.isInitialized) normalWebContentHost.removeAllViews()
        if (activeActivity === this) activeActivity = null
        if (pipActivity === this && !isInPictureInPictureMode) pipActivity = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl = httpsViewUrl(intent)
    }

    private fun createPipButton(): TextView = TextView(this).apply {
        text = "PiP"
        contentDescription = "ピクチャーインピクチャーで再生"
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(8, 14, 8, 14)
        minimumWidth = (44 * resources.displayMetrics.density).toInt()
        minimumHeight = (52 * resources.displayMetrics.density).toInt()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(0xC20D1118.toInt())
            setStroke(1, 0x88FFFFFF.toInt())
        }
        setOnClickListener { enterFullscreenPictureInPictureMode() }
    }

    private fun supportsPictureInPicture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun updatePictureInPictureParams(videoView: View?) {
        if (!supportsPictureInPicture()) return
        runCatching { setPictureInPictureParams(buildPictureInPictureParams(videoView)) }
    }

    private fun buildPictureInPictureParams(videoView: View?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        videoView?.let { view ->
            val bounds = Rect()
            if (view.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0) {
                builder.setSourceRectHint(bounds)
                val dimensions = fullscreenVideoTabId?.let(videoDimensionsByTab::get)
                val aspectWidth = dimensions?.width ?: bounds.width()
                val aspectHeight = dimensions?.height ?: bounds.height()
                val ratio = aspectWidth.toFloat() / aspectHeight.toFloat()
                if (ratio in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) {
                    builder.setAspectRatio(Rational(aspectWidth, aspectHeight))
                }
            } else {
                builder.setAspectRatio(Rational(16, 9))
            }
        }
        // PiPは標準RemoteActionで、左から10秒戻し・再生/停止・10秒送りを表示する。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setActions(listOf(
                createPipSeekAction(-10, REQUEST_PIP_SEEK_BACK, "◀ 10秒戻る"),
                createPipPlayPauseAction(),
                createPipSeekAction(10, REQUEST_PIP_SEEK_FORWARD, "10秒送り ▶")
            ))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 明示PiPの補助としてauto-enterも有効にする。通常ページではvideoViewがnullのため無効。
            builder.setAutoEnterEnabled(videoView != null)
            if (videoView != null) builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** PiP動画のWebView/custom viewを再親子化せず、通常ブラウズ用の既存Activityだけを別taskで開く。 */
    private fun createBrowserTaskIntent(): Intent = Intent(this, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
    }

    private fun createPipSeekAction(seconds: Int, requestCode: Int, label: String): RemoteAction {
        val intent = Intent(this, PipControlReceiver::class.java).apply {
            action = if (seconds < 0) ACTION_PIP_SEEK_BACK else ACTION_PIP_SEEK_FORWARD
            putExtra(EXTRA_PIP_SEEK_SECONDS, seconds)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_browser), label, label, pendingIntent
        )
    }

    private fun createPipPlayPauseAction(): RemoteAction {
        val intent = Intent(this, PipControlReceiver::class.java).apply {
            action = ACTION_PIP_PLAY_PAUSE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_PIP_PLAY_PAUSE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_browser),
            "● 再生/停止",
            "再生/停止",
            pendingIntent
        )
    }

    private fun createOpenBrowserRemoteAction(): RemoteAction {
        val intent = createBrowserTaskIntent()
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_BROWSER_FROM_PIP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_browser),
            "ブラウズを開く",
            "PiP再生を続けたままブラウザを操作",
            pendingIntent
        )
    }

    private fun setFullscreenSystemBars(fullscreen: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            // ホームジェスチャーを最優先するためnavigation barは隠さず、status barだけを制御する。
            if (fullscreen) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun httpsViewUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        }
    }

    private data class VideoDimensions(val width: Int, val height: Int)

    internal fun handlePipSeek(seconds: Int) {
        videoControlsRegistry?.let { registry ->
            videoControlsTabId?.let { tabId -> registry.seekVideo(tabId, seconds) }
        }
    }

    internal fun handlePipPlayPause() {
        videoControlsRegistry?.let { registry ->
            videoControlsTabId?.let { tabId -> registry.toggleVideoPlayback(tabId) }
        }
    }

    internal companion object {
        private var activeActivity: MainActivity? = null
        private var pipActivity: MainActivity? = null
        internal fun activeInstance(): MainActivity? = pipActivity ?: activeActivity
        const val ACTION_PIP_SEEK_BACK = "com.example.httpsbrowser.PIP_SEEK_BACK"
        const val ACTION_PIP_SEEK_FORWARD = "com.example.httpsbrowser.PIP_SEEK_FORWARD"
        const val ACTION_PIP_PLAY_PAUSE = "com.example.httpsbrowser.PIP_PLAY_PAUSE"
        const val EXTRA_PIP_SEEK_SECONDS = "seconds"
        const val REQUEST_PIP_SEEK_BACK = 4_022
        const val REQUEST_PIP_SEEK_FORWARD = 4_023
        const val REQUEST_PIP_PLAY_PAUSE = 4_024
        const val MIN_PIP_ASPECT_RATIO = 1f / 2.39f
        const val MAX_PIP_ASPECT_RATIO = 2.39f
        private val VIDEO_SPEEDS = floatArrayOf(1.0f, 1.5f, 2.0f)
        const val REQUEST_OPEN_BROWSER_FROM_PIP = 4_021
    }
}
