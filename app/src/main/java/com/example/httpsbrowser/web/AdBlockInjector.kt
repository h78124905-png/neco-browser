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
    if (navigator.serviceWorker) {
        navigator.serviceWorker.getRegistrations().then(rs => rs.forEach(r => {
            if (r.scope.includes('youtube.com') || r.scope.includes('googlevideo.com')) r.unregister();
        })).catch(() => {});
    }

    const findDeep = (selector) => {
        const found = [];
        const visit = (root) => {
            if (!root) return;
            try {
                if (root.querySelectorAll) root.querySelectorAll(selector).forEach(el => found.push(el));
                if (root.querySelectorAll) root.querySelectorAll('*').forEach(el => {
                    if (el.shadowRoot) visit(el.shadowRoot);
                });
            } catch (_) {}
        };
        visit(document);
        return found;
    };
    const findOneDeep = (selector) => findDeep(selector)[0] || null;

    const skipAndClean = () => {
        const player = findOneDeep('.html5-video-player');
        const video = findOneDeep('video');
        const isAdPlaying = !!(player && (
            player.classList.contains('ad-showing') ||
            player.classList.contains('ad-interrupting') ||
            findOneDeep('.ytp-ad-module, .video-ads, .ytp-ad-overlay-container')
        ));

        if (isAdPlaying && video) {
            let skipBtn = findOneDeep(
                '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, [class*="skip-button"]'
            );
            if (!skipBtn) {
                const adContainer = findOneDeep('.ytp-ad-module, .video-ads');
                if (adContainer && adContainer.querySelector) {
                    skipBtn = adContainer.querySelector('button, [role="button"], svg');
                }
            }
            if (skipBtn) {
                try { skipBtn.click(); } catch (_) {}
            } else {
                try {
                    if (video.playbackRate < 16) {
                        video.__necoAdSpeedup = true;
                        video.playbackRate = 16;
                        video.muted = true;
                    }
                } catch (_) {}
            }
        } else if (video && video.__necoAdSpeedup) {
            try { video.playbackRate = 1; video.muted = false; } catch (_) {}
            try { delete video.__necoAdSpeedup; } catch (_) {}
        }

        const adSelectors = [
            'ytd-ad-slot-renderer', 'ytd-promoted-video-renderer', 'ytd-promoted-sparkles-web-renderer',
            'ytd-display-ad-renderer', 'ytd-action-companion-ad-renderer', 'ytd-companion-slot-renderer',
            '.ytp-ad-module', '.video-ads', '#player-ads', '.ytp-ad-overlay-container',
            'ytd-rich-item-renderer:has(> ytd-ad-slot-renderer)',
            'ytd-shorts:has(> .ytd-reel-video-renderer > ytd-ad-slot-renderer)'
        ];
        findDeep(adSelectors.join(',')).forEach(el => { try { el.remove(); } catch (_) {} });
    };

    const observer = new MutationObserver(() => skipAndClean());
    observer.observe(document.documentElement, { attributes: true, childList: true, subtree: true });
    document.addEventListener('play', skipAndClean, true);
    document.addEventListener('timeupdate', skipAndClean, true);
    document.addEventListener('volumechange', skipAndClean, true);
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
