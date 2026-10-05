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
        if (navigator.serviceWorker) {
            navigator.serviceWorker.getRegistrations().then(regs => regs.forEach(r => r.unregister()));
            if (window.caches) caches.keys().then(keys => keys.forEach(k => caches.delete(k)));
        }

        const AD_KEYS = new Set([
            'adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams',
            'adReasons', 'adBreaks', 'adFormat', 'adYieldGroupKey',
            'clientGpu', 'paidContentOverlay', 'adBreakParams',
            'promoted', 'ypc_spin_up'
        ]);

        function pruneAds(obj, depth = 0) {
            if (!obj || typeof obj !== 'object' || depth > 12) return;
            for (const key in obj) {
                if (AD_KEYS.has(key)) {
                    if (Array.isArray(obj[key])) obj[key] = [];
                    else if (obj[key] && typeof obj[key] === 'object') obj[key] = {};
                    else delete obj[key];
                } else if (key === 'playabilityStatus' && obj[key]) {
                    delete obj[key].errorScreen;
                    delete obj[key].reason;
                    delete obj[key].adBlockerDetected;
                } else {
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

        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            const res = await origFetch.apply(this, args);
            const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || '');

            if (/youtubei\/v1\/player/.test(url)) {
                try {
                    const clone = res.clone();
                    const json = await clone.json();
                    pruneAds(json);
                    return new Response(JSON.stringify(json), {
                        status: res.status,
                        statusText: res.statusText,
                        headers: res.headers
                    });
                } catch (e) {}
            }
            return res;
        };

        function clickSkipButton() {
            const skipBtn = document.querySelector(
                '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, [class*="skip-button"]'
            );
            if (skipBtn) {
                try { skipBtn.click(); } catch (e) {}
            }
        }

        const observer = new MutationObserver(clickSkipButton);
        observer.observe(document.documentElement || document, {
            childList: true,
            subtree: true
        });
        setInterval(clickSkipButton, 500);
        clickSkipButton();
    """

    private val FULL_SCRIPT = """
        (function() {
            const install = function() {
                if (!document.head || document.getElementById('__youtube_adblock_stealth_css')) return;
                const style = document.createElement('style');
                style.id = '__youtube_adblock_stealth_css';
                style.textContent = ${quote(GENERIC_HIDE_CSS)};
                document.head.appendChild(style);
            };
            install();
            if (!document.head) {
                document.addEventListener('DOMContentLoaded', install, {once:true});
            }

            ${YOUTUBE_PRUNE_JS}
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
