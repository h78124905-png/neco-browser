# Fulguris由来コードに関するCPAL-1.0通知

本プロジェクトには、FulgurisのWebView動画表示ライフサイクルおよび全画面custom view管理から派生・適合したコードが含まれます。

| 項目 | 内容 |
|---|---|
| 対象ファイル | `app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt`、`app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt`、`app/src/main/java/com/example/httpsbrowser/MainActivity.kt` |
| 原コード | Fulguris `app/src/main/java/fulguris/view/WebPageClient.kt` のロード状態再arm・重複`onPageFinished`抑止、および `app/src/main/java/fulguris/activity/WebBrowserActivity.kt` の`onShowCustomView`／`onHideCustomView`状態遷移とGoogle Translate URL生成 |
| 原プロジェクト | [Fulguris](https://github.com/Slion/Fulguris) |
| 原著作権表示 | Copyright © 2020–2021 Stéphane Lenclud、Copyright 2014 A.C.R. Development |
| 対象ライセンス | [Common Public Attribution License 1.0（CPAL-1.0）](LICENSES/CPAL-1.0.txt) |
| 初期開発者 | Stéphane Lenclud |
| 変更日 | 2026-08-21 |
| ソース提供先 | [aaaaaisss/https-tab-browser](https://github.com/aaaaaisss/https-tab-browser) の対象ファイルおよび本通知 |

## 変更内容

FulgurisのActivity、Hilt、RxJava、XMLレイアウト、独自タブモデル、独自`WebViewEx`に結合した大規模構成から、ねこぶらうざのCompose UIと既存のBrave adblock-rust統合に必要な最小部分だけを適合しました。

`BrowserWebView.kt`では、main-frame要求・`onPageStarted`・戻る・進む・再読込の際にページ完了状態を再armし、`WebView.progress == 100`の最初の`onPageFinished`だけで暗色CSS、広告cosmetic、Cookie書込み、履歴UI通知を処理します。これはYouTubeなどが`onPageFinished`を複数回呼ぶ場合と、履歴復帰でmain-frame要求が来ない場合の両方を扱うための構成です。

`BrowserScreen.kt`では、custom viewを一度だけ所有し、重複した`onShowCustomView`を即時に拒否します。`MainActivity.kt`はFulgurisのActivity構成を参考に、custom viewをComposeのAndroidViewへ重ねず、Activity root上の黒いnative `FrameLayout`へ一度だけ接続します。PiP遷移中はこのcontainerを保持し、解除時にはcontainer、keep-screen-on、system bars、callbackの順で復帰します。Fulgurisの回転固定、`VideoView.stopPlayback()`、独自カーソルレイヤー、XML Activity全体は採用していません。

翻訳ボタンは、Fulgurisと同じ`https://translate.google.com/translate?sl=auto&tl=<端末ロケール>&u=<現在URL>`を安全に組み立て、現在のタブを通常遷移させます。端末内翻訳モデル、DOMテキスト抽出、本文置換は採用していません。

暗色化はユーザーの指定によりFulguris由来controllerを削除し、`121e47b`時点のWebView標準API・動画ページ除外・一般ページ用深いCSS構成を復帰しています。Fulgurisの旧ColorMatrix反転フォールバックおよびDark Readerは採用していません。

## アプリ内の帰属表示

CPAL-1.0 §14およびFulgurisの公開Termsに沿い、アプリ設定の **オープンソースライセンス** 画面に次の文言、変更記録、第三者通知、CPAL-1.0本文を可視表示します。

> Powered by Fulguris Browser

この画面は設定から常時開け、起動時の操作を妨げるダイアログは表示しません。この通知はFulgurisの商標・帰属を示すものであり、Fulgurisが本プロジェクトを支持・保証することを意味しません。

## 対応ソースの利用可能期間

APKを配布する限り、上記の対象ソースおよびCPAL-1.0通知は本リポジトリで利用可能にします。第三者へAPKを渡す場合も、この通知、対象ソース、CPAL-1.0本文へのリンクを添付または案内してください。
