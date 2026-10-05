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
    const AD_KEYS = new Set([
        'adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams',
        'adReasons', 'promoted', 'ypc_spin_up', 'adBreaks', 'adFormat',
        'adYieldGroupKey', 'clientGpu', 'paidContentOverlay'
    ]);

    function pruneAds(obj, depth = 0) {
        if (!obj || typeof obj !== 'object' || depth > 12) return;
        for (const key in obj) {
            if (AD_KEYS.has(key)) {
                if (Array.isArray(obj[key])) obj[key] = [];
                else delete obj[key];
            } else if (typeof obj[key] === 'object') {
                pruneAds(obj[key], depth + 1);
            }
        }
    }

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

    const matchesApi = url => /youtubei\/v1\/(player|next|browse|search)/.test(url || '');
    const origFetch = window.fetch;
    window.fetch = async function(...args) {
        const res = await origFetch.apply(this, args);
        const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || '');
        if (matchesApi(url)) {
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

    const origOpen = XMLHttpRequest.prototype.open;
    const origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url, ...rest) {
        this._necoUrl = url;
        return origOpen.apply(this, [method, url, ...rest]);
    };
    XMLHttpRequest.prototype.send = function(body) {
        if (matchesApi(this._necoUrl)) {
            this.addEventListener('readystatechange', function() {
                if (this.readyState === 4 && this.status === 200) {
                    try {
                        const json = JSON.parse(this.responseText);
                        pruneAds(json);
                        Object.defineProperty(this, 'responseText', { writable: true, value: JSON.stringify(json) });
                        Object.defineProperty(this, 'response', { writable: true, value: JSON.stringify(json) });
                    } catch (e) {}
                }
            });
        }
        return origSend.apply(this, [body]);
    };

    // [6] Service Worker & Cache の完全抹消 (追加)
    if (navigator.serviceWorker) {
        navigator.serviceWorker.getRegistrations().then(rs => rs.forEach(r => {
            if (r.scope.includes('youtube.com') || r.scope.includes('googlevideo.com')) {
                r.unregister();
            }
        })).catch(() => {});
    }
    if (window.caches) {
        caches.keys().then(ks => ks.forEach(k => {
            if (k.includes('youtube') || k.includes('googlevideo')) caches.delete(k);
        })).catch(() => {});
    }

    // [7] 強化版 DOM Skipper (アクティブなvideoを特定し、srcパラメータで広告判定)
    function getActiveVideo() {
        const videos = Array.from(document.querySelectorAll('video'));
        return videos.find(v => !v.paused && v.currentTime > 0) ||
               videos.find(v => (v.src || v.currentSrc || '').includes('ctier=AD')) ||
               videos[0];
    }

    function isAdVideo(video) {
        if (!video) return false;
        const src = video.src || video.currentSrc || '';
        return src.includes('ctier=AD') ||
               src.includes('adformat=') ||
               src.includes('/ad/') ||
               src.includes('&ad=');
    }

    function skipAndClean() {
        const player = document.querySelector('.html5-video-player');
        const video = getActiveVideo();

        const isAdPlaying = (player && (
            player.classList.contains('ad-showing') ||
            player.classList.contains('ad-interrupting')
        )) ||
        isAdVideo(video) ||
        document.querySelector('.ytp-ad-module, .video-ads, .ytp-ad-text-overlay, .ytp-ad-preview-container');

        if (isAdPlaying && video) {
            let skipBtn = document.querySelector(
                '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, [class*="skip-button"]'
            );
            if (!skipBtn) {
                const adContainer = document.querySelector('.ytp-ad-module, .video-ads');
                if (adContainer) {
                    skipBtn = adContainer.querySelector('button, [role="button"], svg');
                }
            }

            if (skipBtn) {
                try { skipBtn.click(); } catch (_) {}
            } else {
                try {
                    if (video.playbackRate < 16) video.playbackRate = 16;
                    video.muted = true;
                    if (video.duration && video.currentTime < video.duration - 1) {
                        video.currentTime = video.duration - 0.1;
                    }
                } catch (_) {}
            }
        } else if (video && video.playbackRate === 16) {
            try {
                video.playbackRate = 1;
                video.muted = false;
            } catch (_) {}
        }

        const adSelectors = [
            'ytd-ad-slot-renderer', 'ytd-promoted-video-renderer', 'ytd-promoted-sparkles-web-renderer',
            'ytd-display-ad-renderer', 'ytd-action-companion-ad-renderer', 'ytd-companion-slot-renderer',
            '.ytp-ad-module', '.video-ads', '#player-ads', '.ytp-ad-overlay-container',
            'ytd-rich-item-renderer:has(> ytd-ad-slot-renderer)',
            'ytd-shorts:has(> .ytd-reel-video-renderer > ytd-ad-slot-renderer)'
        ];
        document.querySelectorAll(adSelectors.join(',')).forEach(el => {
            try { el.remove(); } catch (_) {}
        });
    }

    setInterval(skipAndClean, 300);

    const observer = new MutationObserver(skipAndClean);
    observer.observe(document.documentElement, { attributes: true, childList: true, subtree: true });
    document.addEventListener('play', skipAndClean, true);
    document.addEventListener('timeupdate', skipAndClean, true);
    skipAndClean();

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
