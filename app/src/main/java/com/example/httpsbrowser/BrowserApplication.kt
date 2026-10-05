package com.example.httpsbrowser

import android.app.Application
import androidx.webkit.WebViewCompat

class BrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        // 端末のSystem WebView/Chrome実装差を診断できるよう、URLや閲覧内容を含めず提供元と版だけを記録する。
        val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(this) }.getOrNull()
        CrashDiagnostics.record(
            "webview_provider",
            "package=${provider?.packageName.orEmpty()}\nversion=${provider?.versionName.orEmpty()}"
        )
    }
}
