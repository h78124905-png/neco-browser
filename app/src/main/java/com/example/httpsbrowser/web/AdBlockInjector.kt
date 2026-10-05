package com.example.httpsbrowser.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object AdBlockInjector {

    private const val GENERIC_HIDE_CSS = """
        [class*="ad-"], [id*="ad-"],
        [class*="banner"], [id*="banner"],
        [class*="sponsor"], [id*="sponsor"],
        [data-ad], [aria-label="広告"],
        .adsbygoogle, .ad-container, .sponsored-content,
        .native-ad, .taboola-container, .outbrain-widget,
        .ytp-ad-module, .video-ads, #player-ads, .ytp-ad-overlay-container,
        ytd-ad-slot-renderer, ytd-promoted-video-renderer {
            display: none !important;
            height: 0 !important;
            width: 0 !important;
            visibility: hidden !important;
            opacity: 0 !important;
            pointer-events: none !important;
        }

        /* 映像強制表示 (z-index最大化) */
        .html5-video-player video,
        video.html5-main-video,
        video {
            display: block !important;
            visibility: visible !important;
            opacity: 1 !important;
            z-index: 9999 !important;
            position: relative !important;
            background: transparent !important;
            filter: none !important;
        }
    """

    private const val YOUTUBE_PRUNE_JS = """
(function() {
    // [1] Service Worker の強制削除 (JS側での保険)
    if (navigator.serviceWorker) {
        navigator.serviceWorker.getRegistrations().then(rs => rs.forEach(r => {
            if (r.scope.includes('youtube.com') || r.scope.includes('googlevideo.com')) {
                r.unregister();
            }
        }));
    }

    // [2] 広告の強制スキップ & 物理削除 (JSON改変に依存しない安定手法)
    const skipAndClean = () => {
        const player = document.querySelector('.html5-video-player');
        const video = document.querySelector('video');
        
        // 広告再生中 (ad-showing / ad-interrupting) の検出
        if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {
            // スキップボタンがあればクリック
            const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, [class*="skip-button"]');
            if (skipBtn) {
                skipBtn.click();
            } else if (video) {
                // ボタンがなければ強制終了 (currentTimeをdurationに)
                video.currentTime = video.duration || 9999;
                player.classList.remove('ad-showing', 'ad-interrupting');
            }
        }

        // 広告コンテナの物理削除 (display:none より remove() がレイアウト崩れを防ぐ)
        const adSelectors = [
            'ytd-ad-slot-renderer', 'ytd-promoted-video-renderer', 'ytd-promoted-sparkles-web-renderer',
            'ytd-display-ad-renderer', 'ytd-action-companion-ad-renderer', 'ytd-companion-slot-renderer',
            '.ytp-ad-module', '.video-ads', '#player-ads', '.ytp-ad-overlay-container',
            'ytd-rich-item-renderer:has(> ytd-ad-slot-renderer)',
            'ytd-shorts:has(> .ytd-reel-video-renderer > ytd-ad-slot-renderer)'
        ];
        document.querySelectorAll(adSelectors.join(',')).forEach(el => el.remove());
    };

    // [3] 高頻度監視 (MutationObserver + Video Events)
    const observer = new MutationObserver(skipAndClean);
    observer.observe(document.documentElement, { attributes: true, childList: true, subtree: true });
    
    // 動画の再生・時間更新イベントでもスキップチェックを実行
    document.addEventListener('play', skipAndClean, true);
    document.addEventListener('timeupdate', skipAndClean, true);
    document.addEventListener('volumechange', skipAndClean, true);
    
    // 初期実行
    skipAndClean();
})();
"""
    private val FULL_SCRIPT = """
        (function() {
            const install = function() {
                if (!document.head || document.getElementById('__minimal_adblock_css')) return;
                const style = document.createElement('style');
                style.id = '__minimal_adblock_css';
                style.textContent = ${'$'}{quote(GENERIC_HIDE_CSS)};
                document.head.appendChild(style);
            };
            install();
            if (!document.head) document.addEventListener('DOMContentLoaded', install, {once:true});

            ${'$'}YOUTUBE_PRUNE_JS
        })();
    """.trimIndent()

    fun inject(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, FULL_SCRIPT, setOf("*"))
            }
        }
    }

    private fun quote(value: String): String =
        "'" + value.replace("'", "\\'").replace("\n", "\\n") + "'"
}
