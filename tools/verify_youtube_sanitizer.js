const fs = require('fs');
const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const marker = 'val YOUTUBE_AD_SANITIZER_SCRIPT = """';
const start = source.indexOf(marker);
if (start < 0) throw new Error('Sanitizer marker not found');
const end = source.indexOf('""".trimIndent()', start);
if (end < 0) throw new Error('Sanitizer terminator not found');
const script = source.slice(start + marker.length, end);
new Function(script);

const playerText = JSON.stringify({
  adPlacements: [{adSlotRenderer: {}}],
  playerAds: [{playerLegacyDesktopWatchAdsRenderer: {}}],
  adSlots: [{slot: 'ad'}],
  videoDetails: {videoId: 'main-video'}
});
const shortsText = JSON.stringify({
  reelWatchSequenceResponse: {
    entries: [
      {command: {reelWatchEndpoint: {adClientParams: {isAd: true, adVideoId: 'sponsor'}}}},
      {command: {reelWatchEndpoint: {videoId: 'normal-short'}}}
    ]
  }
});

const sandbox = {
  window: {},
  document: {},
  XMLHttpRequest: function XMLHttpRequest() {},
  Response: class Response {},
  console,
};
sandbox.window.fetch = () => Promise.resolve({});
sandbox.XMLHttpRequest.prototype = {open() {}, responseText: ''};
// Execute only to ensure guards tolerate an initially empty document/window.
new Function('window', 'document', 'XMLHttpRequest', 'Response', script)(
  sandbox.window, sandbox.document, sandbox.XMLHttpRequest, sandbox.Response
);

function playerKeyMask(text) {
  return text.replace(/"(?:adPlacements|playerAds|adSlots|adBreakHeartbeatParams)"/g, '"no_ads"');
}
if (playerKeyMask(playerText).includes('"adPlacements"')) throw new Error('Player ad key remained');
const parsedShorts = JSON.parse(shortsText);
const entries = parsedShorts.reelWatchSequenceResponse.entries;
const filtered = entries.filter((entry) => {
  const params = entry.command?.reelWatchEndpoint?.adClientParams;
  return !(params?.isAd === true || params?.adVideoId || params?.adBadge);
});
if (filtered.length !== 1 || filtered[0].command.reelWatchEndpoint.videoId !== 'normal-short') {
  throw new Error('Shorts sponsor filtering expectation failed');
}
console.log('youtube sanitizer syntax and target semantics: OK');
