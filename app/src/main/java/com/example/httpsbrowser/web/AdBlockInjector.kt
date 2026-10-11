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
        [data-ad], [aria-label="広告"], .adsbygoogle, .taboola-container, .outbrain-widget {
            display: none !important;
            visibility: hidden !important;
            height: 0 !important;
            width: 0 !important;
            opacity: 0 !important;
            pointer-events: none !important;
        }

        /* 貫通した広告は末尾へ送るまでの一瞬も見せない */
        .html5-video-player.ad-showing video, .html5-video-player.ad-interrupting video {
            opacity: 0 !important;
        }
    """

    private const val YOUTUBE_PRUNE_JS = """
        // Page Visibility API の完全偽装
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });

        // バックグラウンド・PiP中にYouTubeが自分で一時停止しないよう、可視性イベントだけを握りつぶす。
        ['visibilitychange', 'webkitvisibilitychange'].forEach(event => {
            window.addEventListener(event, e => { e.stopImmediatePropagation(); }, true);
            document.addEventListener(event, e => { e.stopImmediatePropagation(); }, true);
        });
        window.addEventListener('blur', e => { e.stopImmediatePropagation(); }, true);
        try { Document.prototype.hasFocus = function() { return true; }; } catch (e) {}

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

        // [4] 広告が貫通した時の最後の砦: スキップを押し、押せなければミュートして末尾へ送る。
        let adMutedByUs = false;
        function clickSkipButton() {
            const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-container button, [class*="skip-button"]');
            if (skipBtn) {
                skipBtn.click();
            }
            const player = document.querySelector('.html5-video-player');
            const video = document.querySelector('video.html5-main-video') || document.querySelector('.html5-video-player video');
            const isAd = !!(player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
            if (!video) return;
            if (isAd) {
                if (!video.muted) { video.muted = true; adMutedByUs = true; }
                if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.3) {
                    try { video.currentTime = video.duration - 0.1; } catch (e) {}
                }
            } else if (adMutedByUs) {
                video.muted = false;
                adMutedByUs = false;
            }
        }

        // ==========================================================
        // [Auto-Resume Layer] (独立した安全弁・既存処理の後段に配置)
        // ==========================================================
        let lastUserActionTime = 0;
        let userIntent = null; // 'pause' | 'play' | 'interaction' | null

        // 1. ユーザー操作の検知 (UI構造変更に強い汎用的な監視)
        document.addEventListener('pointerdown', (e) => {
            // m.youtube の操作UIはplayer外にあることがあるため、画面タップは全てユーザー操作として扱う。
            lastUserActionTime = Date.now();
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

        // ネイティブ側（PiPボタン・通知）の再生/停止もユーザー操作として扱う。
        document.addEventListener('neko-user-intent', (e) => {
            lastUserActionTime = Date.now();
            userIntent = e.detail === 'pause' ? 'pause' : 'play';
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
            if (isRecentUserAction) { userIntent = 'pause'; return; }

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

        // 4. PiP対象videoの再生状態をAndroidへ通知する。
        // 複数のvideoがあるページでも、1つでも再生中なら「video再生中」とする。audioはPiP状態に影響させない。
        const playingMedia = new Set();

        function notifyMediaState() {
            try {
                if (!window.NekoMediaBridge) return;
                if (playingMedia.size > 0) {
                    window.NekoMediaBridge.onMediaPlay(document.title || 'メディア', location.href);
                } else {
                    window.NekoMediaBridge.onMediaPause();
                }
            } catch (_) {}
        }

        document.addEventListener('play', (e) => {
            const media = e.target;
            if (media instanceof HTMLVideoElement) {
                playingMedia.add(media);
                userIntent = null;
                notifyMediaState();
            }
        }, true);

        document.addEventListener('pause', (e) => {
            const media = e.target;
            if (media instanceof HTMLVideoElement) {
                playingMedia.delete(media);
                notifyMediaState();
            }
        }, true);

        document.addEventListener('ended', (e) => {
            const media = e.target;
            if (media instanceof HTMLVideoElement) {
                playingMedia.delete(media);
                try {
                    if (window.NekoMediaBridge) {
                        if (playingMedia.size > 0) {
                            window.NekoMediaBridge.onMediaPlay(document.title || 'メディア', location.href);
                        } else {
                            window.NekoMediaBridge.onMediaEnded();
                        }
                    }
                } catch (_) {}
            }
        }, true);

        // 既に生成済みのメディアがあるページにも対応する。
        document.querySelectorAll('video').forEach((media) => {
            if (!media.paused && !media.ended) playingMedia.add(media);
        });
        notifyMediaState();

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

        // 広告の開始は動画イベントで即検知する。DOM変更は1フレームに1回へまとめて負荷を抑える。
        ['loadedmetadata', 'durationchange', 'playing', 'timeupdate'].forEach(type => {
            document.addEventListener(type, clickSkipButton, true);
        });
        let skipScheduled = false;
        const observer = new MutationObserver(() => {
            if (skipScheduled) return;
            skipScheduled = true;
            requestAnimationFrame(() => { skipScheduled = false; clickSkipButton(); });
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
        setInterval(clickSkipButton, 500);

        // [6] 再生速度の維持。ページ読み込み直後から動き、Android側で選んだ現在の速度をブリッジで参照する。
        function nekoWantedRate() {
            try {
                if (window.NekoMediaBridge && typeof window.NekoMediaBridge.getPlaybackRate === 'function') {
                    const r = Number(window.NekoMediaBridge.getPlaybackRate());
                    if (r > 0) return r;
                }
            } catch (e) {}
            return 0;
        }
        function keepRate(video) {
            if (!video || video.tagName !== 'VIDEO') return;
            const player = (video.closest && video.closest('.html5-video-player')) || document.getElementById('movie_player');
            if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) return;
            const r = nekoWantedRate();
            if (!r) return;
            try {
                if (player && typeof player.setPlaybackRate === 'function' && typeof player.getPlaybackRate === 'function' &&
                    Math.abs(player.getPlaybackRate() - r) > 0.001) {
                    player.setPlaybackRate(r);
                }
            } catch (e) {}
            try {
                if (Math.abs(video.defaultPlaybackRate - r) > 0.001) video.defaultPlaybackRate = r;
                if (Math.abs(video.playbackRate - r) > 0.001) video.playbackRate = r;
            } catch (e) {}
        }
        ['loadedmetadata', 'play', 'playing', 'ratechange', 'durationchange'].forEach(type => {
            document.addEventListener(type, e => keepRate(e.target), true);
        });
        setInterval(() => {
            const v = document.querySelector('video.html5-main-video') || document.querySelector('video');
            if (v && !v.paused) keepRate(v);
        }, 700);
    """

    private val FULL_SCRIPT = """
        (function() {
            // Google検索ではYouTube向けdocument-start injectorを適用しない。
            const host = (location.hostname || '').toLowerCase();
            if (host === 'google.com' || host.endsWith('.google.com') || host.startsWith('google.')) return;
            // YouTube専用の処理（可視性偽装・JSON prune・自動再開）はYouTubeだけで動かす。他サイトの入力やfocusを壊さない。
            const isYoutube = /(^|\.)youtube\.com${'$'}/.test(host) || /(^|\.)youtube-nocookie\.com${'$'}/.test(host);
            const install = function() {
                if (!document.head || document.getElementById('__youtube_adblock_stealth_css')) return;
                const style = document.createElement('style');
                style.id = '__youtube_adblock_stealth_css';
                style.textContent = ${quote(GENERIC_HIDE_CSS)};
                document.head.appendChild(style);
            };
            install();
            if (!document.head) document.addEventListener('DOMContentLoaded', install, {once:true});

            if (isYoutube) {
                ${YOUTUBE_PRUNE_JS}
            }
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
