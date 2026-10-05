const fs = require('fs');

const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
for (const required of [
  'YOUTUBE_NO_AD_WARM_PLAYER_SCRIPT',
  'WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_NO_AD_WARM_PLAYER_SCRIPT, originRules)',
  'youtube_no_ad_warm_player_ready',
  'window.__nekoBrowserNoAdWarmPlayer=true',
  'isInlinePlaybackNoAd',
  'experiment=warm_only',
  'if (!entry.aggressiveAdBlockingEnabled)',
  'YOUTUBE_SABR_PATCH_ONLY_SCRIPT',
  'var target=50+Math.floor(Math.random()*100)',
  'return passThrough();',
]) {
  if (!source.includes(required)) throw new Error(`Warm-only YouTube comparison invariant missing: ${required}`);
}
for (const forbidden of [
  'var target=25+Math.floor(Math.random()*50)',
  'loadUrl(\'https://www.youtube.com\')',
  'enterPictureInPictureMode',
]) {
  if (source.includes(forbidden)) throw new Error(`Warm-only experiment must not change this path: ${forbidden}`);
}
console.log('YouTube warm-player-only comparison with unchanged SABR target: OK');
