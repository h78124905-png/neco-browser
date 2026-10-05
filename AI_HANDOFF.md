# Android WebViewブラウザ 引き継ぎ資料

## 目的
このプロジェクトは、Android WebViewベースのブラウザです。Google画像検索のポップアップ表示、WebView表示領域、YouTubeのPiP、動画再生速度、軽量広告ブロックを実装しています。

## 現在の重要な実装

- `app/src/main/java/com/example/httpsbrowser/MainActivity.kt`
  - Activity root、Window Insets、全画面動画、PiP、PiP/速度操作UI
- `app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt`
  - Compose UI、WebView配置、全画面開始時の動画操作UI表示
- `app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt`
  - WebView設定、popup WebView、MinimalAdBlockClient、PiP用JS、動画速度適用
- `app/src/main/java/com/example/httpsbrowser/web/AdBlockInjector.kt`
  - document-start広告CSSとYouTube広告データprune
- `app/src/main/java/com/example/httpsbrowser/web/MinimalAdBlockClient.kt`
  - YouTube広告関連URLの最小限ネットワーク遮断
- `app/src/main/java/com/example/httpsbrowser/data/BrowserModels.kt`
  - BrowserSettings、タブ、設定ページ
- `app/src/main/java/com/example/httpsbrowser/data/BrowserRepository.kt`
  - DataStore永続化
- `app/build.gradle.kts`
  - releaseでR8・リソース縮小を有効化
- `app/proguard-rules.pro`
  - WebView callbackとJavascriptInterfaceの保持ルール

## 広告ブロック
Rust/JNI、Braveエンジン、AdGuard/EasyListフィルター資産、フィルター更新処理は削除済みです。
端末側AdGuard DNSとの併用を前提に、アプリ内では軽量なCSS/JS注入とYouTube広告URL遮断のみを行います。

## 設定UI
暗色化と広告ブロックは、それぞれ単一トグルです。high/normal切替は削除済みです。
「元から暗いページでは追加暗色化しない」設定も削除済みです。
暗色化は `WebSettingsCompat.setAlgorithmicDarkeningAllowed()` を使用し、`setForceDark()`は使用していません。

## 動画操作
- ユーザーが設定した許可ホストで動画操作UIを表示
- 全画面動画中もPiPボタン・速度ボタンを表示
- 再生速度は `defaultPlaybackRate` と `playbackRate` の両方を設定
- 動画生成後もMutationObserver、loadedmetadata/canplay、250msタイマーで速度を再適用

## ビルド

```bash
# JDK 17以上、Android SDK API 35が必要
./gradlew assembleDebug --no-daemon
./gradlew assembleRelease --no-daemon
```

releaseはR8とリソース縮小を有効にしています。リリース用keystoreを使う場合は以下を設定してください。

```bash
export KEYSTORE_FILE=/absolute/path/to/release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
./gradlew assembleRelease --no-daemon
```

keystore未設定時は、検証用としてdebug keystoreへフォールバックします。本番配布では必ず固有のrelease keystoreを使用してください。

## 注意

- APKだけではKotlin/Gradleソースを編集できません。AIへはこのZIPを渡してください。
- `local.properties`は環境固有なので同梱していません。Android SDKの場所に合わせて生成してください。
- `build/`、`.gradle/`、`local.properties`は同梱していません。
- APKは動作確認用の成果物として別途渡せます。
