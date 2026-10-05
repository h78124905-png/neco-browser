package com.example.httpsbrowser.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object AdBlockInjector {

    // 1. CSS: 映像の強制表示と広告オーバーレイの完全排除
    private const val GENERIC_HIDE_CSS = """
        /* 広告要素の完全排除 */
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

        /* YouTubeプレーヤーの隠蔽を強制解除 (最重要) */
        .html5-video-player video,
        video.html5-main-video,
        .html5-video-player.ad-showing video,
        .html5-video-player.ad-interrupting video,
        video[style*="display: none"],
        video[style*="visibility: hidden"] {
            display: block !important;
            visibility: visible !important;
            opacity: 1 !important;
            z-index: 100 !important;
            position: relative !important;
            background: transparent !important;
        }

        /* プレイヤー自体の広告状態クラスを無効化 */
        .html5-video-player.ad-showing,
        .html5-video-player.ad-interrupting {
            /* 広告状態であってもプレーヤーの構造は維持 */
        }
    """

    // 2. JS: SW無効化、APIフック(XHR/Fetch)、DOM修復
    private const val YOUTUBE_PRUNE_JS = """
        // [1] Service Worker の無効化 (隠蔽スクリプトの再注入を防ぐ)
        if (navigator.serviceWorker) {
            navigator.serviceWorker.getRegistrations().then(regs => regs.forEach(r => r.unregister()));
            Object.defineProperty(navigator, 'serviceWorker', { get: () => undefined });
        }

        const AD_KEYS = new Set([
            'adPlacements', 'playerAds', 'adBreakHeartbeatParams', 'adSlots',
            'adReasons', 'promoted', 'ypc_spin_up', 'adIntro', 'paidContent',
            'adBreaks', 'adBreak', 'adLogMessage', 'adClient', 'adSlot', 'adSense',
            'adTrackingUrl', 'adUrl', 'adFormat', 'adType', 'adCueRanges',
            'adModules', 'adPreroll', 'adState'
        ]);

        // [2] JSON改変ロジック (広告削除 + エラー状態の強制解除)
        function pruneAds(obj, depth = 0) {
            if (!obj || typeof obj !== 'object' || depth > 12) return;
            for (const key in obj) {
                if (AD_KEYS.has(key)) {
                    delete obj[key];
                }
                // ★最重要: playabilityStatus を "OK" に偽装し、黒画面(エラー)を防ぐ
                else if (key === 'playabilityStatus' && obj[key]) {
                    obj[key].status = 'OK';
                    delete obj[key].errorScreen;
                    delete obj[key].reason;
                    delete obj[key].contextualized;
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
            if (/youtubei/v1/(player|next|browse|search)/.test(url)) {
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
            if (/youtubei/v1/(player|next|browse|search)/.test(this._url)) {
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

        // [5] DOMの常時監視・修復 (MutationObserver)
        // YouTubeがJSで隠蔽しようとするのを、ミリ秒単位で修復する
        const observer = new MutationObserver(() => {
            // 広告要素の物理削除
            document.querySelectorAll('.ytp-ad-module, .video-ads, #player-ads, .ytp-ad-overlay-container, ytd-ad-slot-renderer').forEach(el => el.remove());

            // プレイヤーの広告クラス削除と強制再生
            const players = document.querySelectorAll('.html5-video-player.ad-showing, .html5-video-player.ad-interrupting');
            players.forEach(el => {
                el.classList.remove('ad-showing', 'ad-interrupting');
                const video = el.querySelector('video');
                if (video && video.paused) {
                    video.play().catch(() => {});
                }
            });

            // video タグへのインラインスタイル隠蔽を剥奪
            document.querySelectorAll('video[style*="display: none"], video[style*="visibility: hidden"]').forEach(v => {
                v.style.display = 'block';
                v.style.visibility = 'visible';
                v.style.opacity = '1';
            });
        });

        observer.observe(document.documentElement, {
            attributes: true,
            subtree: true,
            childList: true,
            attributeFilter: ['class', 'style']
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

    // Kotlin文字列からJS文字列リテラルへの安全なエスケープ
    private fun quote(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
