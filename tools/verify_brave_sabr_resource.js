const fs = require('fs');
const resources = JSON.parse(fs.readFileSync('app/src/main/assets/adblock_resources/brave_resources.json', 'utf8'));
const resource = resources.find((item) => item.name === 'brave-yt-sabr-fix.js');
if (!resource) throw new Error('brave-yt-sabr-fix.js resource is missing');
const script = Buffer.from(resource.content, 'base64').toString('utf8');
new Function(script);
for (const required of ['backoffTimeMs', 'isInlinePlaybackNoAd', 'googlevideo.com', 'sabr=1']) {
  if (!script.includes(required)) throw new Error(`SABR fix lacks required guard: ${required}`);
}
const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
for (const required of ['YOUTUBE_SABR_PATCH_ONLY_SCRIPT', 'youtubeSabrPatchOnlyScriptHandler', 'aggressiveAdBlockingEnabled']) {
  if (!source.includes(required)) throw new Error(`WebView registration lacks: ${required}`);
}
console.log('Brave SABR resource and aggressive-mode patch-only registration: OK');
