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

        .html5-video-player video,
        video.html5-main-video,
        video[style*="display: none"],
        video[style*="visibility: hidden"] {
            display: block !important;
            visibility: visible !important;
            opacity: 1 !important;
            z-index: 100 !important;
            position: relative !important;
            background: transparent !important;
        }
    """

    private const val YOUTUBE_PRUNE_JS = """
        // [1] Service Worker は無効化しない (YouTubeの正常な動作を維持)

        // [2] 強化版 JSON改変ロジック (Deep Pruning)
        function pruneAds(obj, depth = 0) {
            if (!obj || typeof obj !== 'object' || depth > 12) return;
            for (const key in obj) {
                // ad, promotion, paid, sponsor で始まるキーを全て削除
                if (/^(ad|ads|promotion|paid|sponsor|preroll|midroll|postroll)/i.test(key)) {
                    delete obj[key];
                } 
                // playabilityStatus を "OK" に偽装し、黒画面(エラー)とアドブロック検知を防ぐ
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

        // [4] XMLHttpRequest (XHR) フック (モバイル版や古いプレーヤー用)
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

        // [5] 広告用 video タグの強制破壊 (Ad Video Killer)
        const killAdVideo = (video) => {
            const src = video.src || video.currentSrc || '';
            // 広告ストリームの特徴 (ctier=AD, oad, adformat など)
            if (src.includes('ctier=AD') || src.includes('&oad') || src.includes('adformat') || src.includes('/ad_') || (src.includes('googlevideo.com/videoplayback?') && src.includes('ad'))) {
                video.pause();
                video.removeAttribute('src');
                video.load(); // 強制リセット
            }
        };

        // [6] DOMの常時監視・修復 (MutationObserver)
        const observer = new MutationObserver(() => {
            // 広告要素の物理削除
            document.querySelectorAll('.ytp-ad-module, .video-ads, #player-ads, .ytp-ad-overlay-container, ytd-ad-slot-renderer').forEach(el => el.remove());
            
            // スキップボタンの自動クリック
            const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, button[aria-label*="Skip"]');
            if (skipBtn) skipBtn.click();

            // 全ての video タグをチェックし、広告ストリームなら強制破壊
            document.querySelectorAll('video').forEach(v => {
                killAdVideo(v);
                // インラインスタイルの隠蔽を剥奪
                if (v.style.display === 'none' || v.style.visibility === 'hidden') {
                    v.style.display = 'block';
                    v.style.visibility = 'visible';
                    v.style.opacity = '1';
                }
            });

            // プレイヤーの広告クラス削除
            const players = document.querySelectorAll('.html5-video-player.ad-showing, .html5-video-player.ad-interrupting');
            players.forEach(el => {
                el.classList.remove('ad-showing', 'ad-interrupting');
            });
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
                style.textContent = ${quote(GENERIC_HIDE_CSS)};
                document.head.appendChild(style);
            };
            install();
            if (!document.head) document.addEventListener('DOMContentLoaded', install, {once:true});

            $YOUTUBE_PRUNE_JS
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
