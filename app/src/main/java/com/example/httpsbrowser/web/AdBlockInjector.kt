package com.example.httpsbrowser.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * フィルターリストやJNIに依存しない軽量な広告表示抑制。
 * ネットワーク遮断はMinimalAdBlockClientのYouTube限定処理に任せる。
 */
object AdBlockInjector {
    private const val GENERIC_HIDE_CSS = """
        [class*="ad-"], [id*="ad-"],
        [class*="banner"], [id*="banner"],
        [class*="sponsor"], [id*="sponsor"],
        [class*="pr_"], [id*="pr_"],
        [data-ad], [aria-label="広告"],
        .adsbygoogle, .ad-container, .sponsored-content,
        .native-ad, .taboola-container, .outbrain-widget {
            display: none !important;
            height: 0 !important;
            width: 0 !important;
            visibility: hidden !important;
        }
    """

    private const val YOUTUBE_PRUNE_JS = """
        const AD_KEYS = new Set([
            'adPlacements', 'playerAds', 'adBreakHeartbeatParams',
            'adSlots', 'adReasons', 'promoted', 'ypc_spin_up'
        ]);
        function pruneAds(obj, depth = 0) {
            if (!obj || typeof obj !== 'object' || depth > 10) return;
            for (const key in obj) {
                if (AD_KEYS.has(key)) delete obj[key];
                else pruneAds(obj[key], depth + 1);
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
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            const res = await origFetch.apply(this, args);
            const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || '');
            if (/youtubei\/v1\/(player|next)/.test(url)) {
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
