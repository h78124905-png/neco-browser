package com.example.httpsbrowser.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object AdBlockInjector {
    private const val GENERIC_HIDE_CSS = """
        ytd-ad-slot-renderer, ytd-promoted-video-renderer, ytd-promoted-sparkles-web-renderer,
        ytd-display-ad-renderer, ytd-action-companion-ad-renderer, ytd-companion-slot-renderer,
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
    """

    private const val YOUTUBE_PRUNE_JS = """
        function clickSkipButton() {
            const skipBtn = document.querySelector(
                '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, [class*="skip-button"]'
            );
            if (skipBtn) {
                try { skipBtn.click(); } catch (_) {}
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
            if (!document.head) document.addEventListener('DOMContentLoaded', install, {once:true});

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
        "'" + value.replace("\\", "\\\\").replace("'", "\'").replace("\n", "\\n") + "'"
}
