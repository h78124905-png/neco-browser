# WebView callback subclasses are instantiated by Chromium and are not referenced directly.
-keep class com.example.httpsbrowser.web.BrowserWebViewRegistry { *; }
-keep class com.example.httpsbrowser.web.MinimalAdBlockClient { *; }
-keep class com.example.httpsbrowser.web.AdBlockInjector { *; }

# Methods exposed through WebView JavaScript interfaces must remain callable.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Compose/runtime annotations used by generated code.
-keepattributes *Annotation*
