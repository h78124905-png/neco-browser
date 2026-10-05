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
        // [1] Service Worker & Cache の完全抹消
        if (navigator.serviceWorker) {
            navigator.serviceWorker.getRegistrations().then(rs => rs.forEach(r => r.unregister()));
        }
        if (window.caches) {
            caches.keys().then(ks => ks.forEach(k => caches.delete(k)));
        }

        // [2] JSON改変ロジック (Deep Pruning)
        function pruneAds(obj, depth = 0) {
            if (!obj || typeof obj !== 'object' || depth > 12) return;
            for (const key in obj) {
                if (/^(ad|ads|promotion|paid|sponsor|preroll|midroll|postroll)/i.test(key)) {
                    delete obj[key];
                } 
                else if (key === 'playabilityStatus' && obj[key]) {
                    obj[key].status = 'OK';
                    delete obj[key].errorScreen;
                    delete obj[key].reason;
                    delete obj[key].contextualized;
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

        // [3] Fetch API フック
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            const res = await origFetch.apply(this, args);
            const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || '');
            if (/youtubei\/v1\/(player|next|browse|search)/.test(url)) {
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

        // [4] XMLHttpRequest (XHR) フック
        const origOpen = XMLHttpRequest.prototype.open;
        const origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url, ...rest) {
            this._url = url;
            return origOpen.apply(this, [method, url, ...rest]);
        };
        XMLHttpRequest.prototype.send = function(body) {
            if (/youtubei\/v1\/(player|next|browse|search)/.test(this._url)) {
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

        // [5] 広告強制スキップ & スタイル修復 (超攻撃的モード)
        const forceSkipAndFix = () => {
            const player = document.querySelector('.html5-video-player');
            const video = document.querySelector('video');
            
            if (player && video) {
                // 広告再生中 (ad-showing) なら強制終了
                if (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')) {
                    video.currentTime = video.duration || 9999; // 広告を終わらせる
                    player.classList.remove('ad-showing', 'ad-interrupting');
                    video.play().catch(() => {}); // 本編再開
                }
                
                // スタイル隠蔽の強制修復
                if (video.style.visibility === 'hidden' || video.style.display === 'none' || video.style.opacity === '0') {
                    video.style.visibility = 'visible';
                    video.style.display = 'block';
                    video.style.opacity = '1';
                    video.style.zIndex = '9999';
                }
            }
            
            // スキップボタン自動クリック
            const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button');
            if (skipBtn) skipBtn.click();
        };

        // 500msごとに監視・修復
        setInterval(forceSkipAndFix, 500);

        // DOM変更の常時監視
        const observer = new MutationObserver(() => {
            forceSkipAndFix();
        });
        observer.observe(document.documentElement, { 
            attributes: true, 
            subtree: true, 
            childList: true,
            attributeFilter: ['class', 'style', 'src']
        });
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
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
