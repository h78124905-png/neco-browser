package com.example.httpsbrowser.web

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewClientCompat
import java.io.ByteArrayInputStream

/** フィルターリストを持たず、YouTube広告リクエストだけを最小限遮断するclient基底。 */
open class MinimalAdBlockClient : WebViewClientCompat() {
    private val youtubeAdKeywords = listOf(
        "ctier=AD",
        "&oad",
        "youtube.com/api/stats/ads",
        "googlevideo.com/videoplayback?ctier=AD"
    )

    protected fun shouldBlockMinimalAd(url: String): Boolean =
        youtubeAdKeywords.any { url.contains(it, ignoreCase = true) }

    protected fun createEmptyAdResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "No Content",
        mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store"
        ),
        ByteArrayInputStream(ByteArray(0))
    )
}
