# Third-Party Notices

## Brave adblock-rust

The native content-filtering engine is built from the [`adblock`](https://crates.io/crates/adblock) crate maintained by Brave Software. The project is pinned to the compatible **0.13.3** crate release in `app/src/main/rust/Cargo.toml`; its serialized-engine cache is versioned so an engine produced by another upstream version is never restored. It is licensed under the **Mozilla Public License 2.0**. The project source is available at <https://github.com/brave/adblock-rust>.

The WebView bridge delegates ABP/AdGuard matching to the native engine. For YouTube and Google video search, only decode-critical media, subdocument, player-script, and player-API requests are protected from false positives; non-critical resources remain eligible for the selected filters. Player response JSON is not modified.

## AdGuard filter lists

The application downloads and bundles snapshots of the following AdGuard Android optimized filters from their official HTTPS endpoints:

- AdGuard Japanese filter (Optimized): <https://filters.adtidy.org/android/filters/7_optimized.txt>

Filter content remains subject to its upstream licenses and terms. Source URLs are displayed in the application’s ad-block settings page.

## Brave adblock-resources

The application bundles Brave's official scriptlet resource manifest solely to resolve scriptlets referenced by the the built-in AdGuard Android filter list.

- Source repository: <https://github.com/brave/adblock-resources>
- Fixed source commit: `9a0cc4312e155cb5b16b701afc0ab9285dc30f24`
- Bundled file: `app/src/main/assets/adblock_resources/brave_resources.json`
- SHA-256: `dca2802415565b15ceb7288811685d47ddf4bc6b0c4324357ac66e33c1de4948`
- License: **Mozilla Public License 2.0**

User-added remote filter lists do not receive scriptlet execution permission.

## Fulguris WebView lifecycle and translation path

The application contains CPAL-1.0-derived adaptations in `app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt` and `app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt`. These adaptations cover the page-lifecycle rearm/duplicate-completion guard, full-screen custom-view ownership sequence, and Google Translate navigation path used by Fulguris.

- Source repository: <https://github.com/Slion/Fulguris>
- Original code: <https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/view/WebPageClient.kt> and <https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/activity/WebBrowserActivity.kt>
- Original copyright notices: Stéphane Lenclud (2020–2021) and A.C.R. Development (2014)
- License for the adapted source: **Common Public Attribution License 1.0 (CPAL-1.0)**
- License text: `LICENSES/CPAL-1.0.txt`
- Modification notice and corresponding-source location: `FULGURIS_CPAL_NOTICE.md`

In accordance with CPAL-1.0 attribution terms and Fulguris' published project terms, the application displays `Powered by Fulguris Browser`, the modification notice, and the CPAL-1.0 text in its Settings > Open Source Licenses page. Fulguris is not affiliated with or responsible for this application.

The force-dark implementation is intentionally restored to the prior `121e47b` WebView API path. Dark Reader is not bundled or executed.

## Design references (no source code copied or bundled)

The following open-source projects are consulted for architecture and compatibility decisions only. No source files, assets, or binary code from this section are copied into the application.

- **AndroidX WebKit**: <https://github.com/androidx/androidx/tree/androidx-main/webkit> — used through the declared `androidx.webkit` dependency for feature-gated document-start scripts and dark-mode APIs. It is licensed under **Apache-2.0**.
- **AndroidX Activity / PiP Hint Tracker**: <https://android.googlesource.com/platform/frameworks/support/+/androidx-main/activity/activity/src/main/java/androidx/activity/PipHintTracker.kt> — the application already uses the `androidx.activity` dependency. Its public PiP source-rectangle tracking design is consulted to keep the fullscreen WebView custom view's `sourceRectHint` current during layout changes. The implementation here is independently written and does not copy that source file. AndroidX is licensed under **Apache-2.0**.
- **Lightning Browser**: <https://github.com/anthonycr/Lightning-Browser> — consulted for its public separation of incognito session cleanup from normal browsing data. Its code and assets are not copied. This comparison informed the decision not to clear all shared WebView cookies when closing a private tab, because doing so would also destroy normal-tab sign-in sessions in this single-profile application. Lightning Browser is licensed under **MPL-2.0**.

The Google video-search repair deliberately does not add an external player, proxy, downloader, or remote script. It preserves the platform WebView's native media pipeline, removes app-injected visual filtering from the sensitive video-search document, and retains only the existing Brave/AdGuard network filtering path.
