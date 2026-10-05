package com.example.httpsbrowser.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object AdBlockInjector {

    private const val GENERIC_HIDE_CSS = """
        ytd-ad-slot-renderer, ytd-promoted-video-renderer, ytd-promoted-sparkles-web-renderer,
        ytd-display-ad-renderer, ytd-action-companion-ad-renderer, ytd-companion-slot-renderer,
        ytd-in-feed-ad-layout-renderer, ytd-ad-slot-renderer,
        .ytp-ad-module, .video-ads, #player-ads, .ytp-ad-overlay-container,
        ytd-rich-item-renderer:has(> ytd-ad-slot-renderer),
        ytd-shorts:has(> .ytd-reel-video-renderer > ytd-ad-slot-renderer),
        .ytp-ad-text-overlay, .ytp-ad-preview-container, .ytp-ad-image-overlay,
        [class*="ad-"], [id*="ad-"], [class*="banner"], [id*="banner"],
        [data-ad], [aria-label="広告"], .adsbygoogle, .taboola-container, .outbrain-widget {
            display: none !important;
            visibility: hidden !important;
            height: 0 !important;
            width: 0 !important;
            opacity: 0 !important;
            pointer-events: none !important;
        }

        /* 黒画面・音だけ状態を防ぐための強制表示 */
        .html5-video-player video, video.html5-main-video,
        video[style*="display: none"], video[style*="visibility: hidden"] {
            display: block !important;
            visibility: visible !important;
            opacity: 1 !important;
            z-index: 100 !important;
            position: relative !important;
            background: transparent !important;
        }
    """

    private const val YOUTUBE_PRUNE_JS = """
        // Page Visibility API の完全偽装
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });

        // 関連イベントの完全握りつぶし
        ['visibilitychange', 'pagehide', 'blur', 'focusout'].forEach(event => {
            document.addEventListener(event, e => {
                e.stopImmediatePropagation();
                e.preventDefault();
            }, true);
            window.addEventListener(event, e => {
                e.stopImmediatePropagation();
                e.preventDefault();
            }, true);
        });

        // window.onblur も無効化
        window.onblur = null;

        // [1] Service Worker とキャッシュの抹殺 (SSAIのキャッシュ再利用を防ぐ)
        if (navigator.serviceWorker) {
            navigator.serviceWorker.getRegistrations().then(regs => regs.forEach(r => r.unregister()));
            if (window.caches) caches.keys().then(keys => keys.forEach(k => caches.delete(k)));
        }

        // [2] JSON改変 (黒画面対策)
        // ★中庸版の思想: status には触れず、errorScreen と reason だけを削除する
        const AD_KEYS = new Set([
            'adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams',
            'adReasons', 'promoted', 'ypc_spin_up', 'adBreaks', 'adFormat',
            'adYieldGroupKey', 'clientGpu', 'paidContentOverlay', 'adBreakParams'
        ]);

        function pruneAds(obj, depth = 0) {
            if (!obj || typeof obj !== 'object' || depth > 12) return;
            for (const key in obj) {
                if (AD_KEYS.has(key)) {
                    if (Array.isArray(obj[key])) obj[key] = [];
                    else delete obj[key];
                } 
                // ★重要: status には触れない（映像トラック破棄を防止）
                // errorScreen と reason だけを削除してエラー画面を消す
                else if (key === 'playabilityStatus' && obj[key]) {
                    delete obj[key].errorScreen;
                    delete obj[key].reason;
                    delete obj[key].adBlockerDetected;
                }
                else {
                    pruneAds(obj[key], depth + 1);
                }
            }
        }

        // グローバル変数フック
        ['ytInitialPlayerResponse', 'ytInitialData'].forEach(name => {
            let val;
            try {
                Object.defineProperty(window, name, {
                    get() { return val; },
                    set(v) { pruneAds(v); val = v; },
                    configurable: true
                });
            } catch (e) {}
        });

        // [3] fetch フックの限定復活 (貫通対策)
        // ★検知リスクを最小限にするため、/youtubei/v1/player のみに限定
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            const res = await origFetch.apply(this, args);
            const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || '');
            
            // player API のみフック
            if (/youtubei\/v1\/player/.test(url)) {
                try {
                    const clone = res.clone();
                    const json = await clone.json();
                    pruneAds(json);
                    return new Response(JSON.stringify(json), {
                        status: res.status, statusText: res.statusText, headers: res.headers
                    });
                } catch (e) {}
            }
            return res;
        };

        // [4] スキップボタンの自動クリックのみ（videoタグへの操作は一切行わない）
        function clickSkipButton() {
            const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, [class*="skip-button"]');
            if (skipBtn) {
                skipBtn.click();
            }
        }

        // ==========================================================
        // [Auto-Resume Layer] (独立した安全弁・既存処理の後段に配置)
        // ==========================================================
        let lastUserActionTime = 0;
        let userIntent = null; // 'pause' | 'play' | 'interaction' | null

        // 1. ユーザー操作の検知 (UI構造変更に強い汎用的な監視)
        document.addEventListener('pointerdown', (e) => {
            const player = e.target.closest('.html5-video-player');
            if (player) {
                lastUserActionTime = Date.now();
                const playBtn = e.target.closest('.ytp-play-button, .ytp-large-play-button');
                if (playBtn) {
                    const video = document.querySelector('video.html5-main-video');
                    userIntent = video && !video.paused ? 'pause' : 'play';
                } else {
                    userIntent = 'interaction';
                }
            }
        }, true);

        // 2. キーボード操作の検知 (入力フィールド除外)
        document.addEventListener('keydown', (e) => {
            const active = document.activeElement;
            if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) {
                return;
            }
            if (e.code === 'Space' || e.key === ' ' || e.key === 'k') {
                lastUserActionTime = Date.now();
                const video = document.querySelector('video.html5-main-video');
                userIntent = video && !video.paused ? 'pause' : 'play';
            }
        }, true);

        // 3. pause イベントの監視と安全な復帰 (状態遷移ベース)
        document.addEventListener('pause', (e) => {
            const video = e.target;
            if (video.tagName !== 'VIDEO' || video.ended) return;

            const now = Date.now();
            const isRecentUserAction = (now - lastUserActionTime) < 3000;

            if (userIntent === 'pause') return;
            if (isRecentUserAction && video.seeking) return;

            const player = document.querySelector('.html5-video-player');
            if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) return;

            setTimeout(() => {
                if (video.paused && !video.ended && !video.seeking && userIntent !== 'pause') {
                    const player = document.querySelector('.html5-video-player');
                    const isAd = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
                    if (!isAd) {
                        video.play().catch(() => {});
                    }
                }
            }, 400);
        }, true);

        // 4. play イベントで意図をリセット (操作時刻は保持)
        document.addEventListener('play', (e) => {
            if (e.target.tagName === 'VIDEO') {
                userIntent = null;
            }
        }, true);

        // 5. 保険のポーリング (状態遷移中の誤作動防止付き)
        setInterval(() => {
            const video = document.querySelector('video.html5-main-video');
            if (!video || video.ended || !video.paused) return;
            if (userIntent === 'pause') return;
            if (video.seeking) return;

            const player = document.querySelector('.html5-video-player');
            const isAd = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
            if (isAd) return;

            video.play().catch(() => {});
        }, 2000);

        const observer = new MutationObserver(clickSkipButton);
        observer.observe(document.documentElement, { childList: true, subtree: true });
        setInterval(clickSkipButton, 500);
    """

    private val FULL_SCRIPT = """
        (function() {
            const install = function() {
                if (!document.head || document.getElementById('__youtube_adblock_stealth_css')) return;
                const style = document.createElement('style');
                style.id = '__youtube_adblock_stealth_css';
                style.textContent = \${quote(GENERIC_HIDE_CSS)};
                document.head.appendChild(style);
            };
            install();
            if (!document.head) document.addEventListener('DOMContentLoaded', install, {once:true});

            \${YOUTUBE_PRUNE_JS}
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
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
