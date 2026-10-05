const fs = require('fs');
const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const marker = 'val YOUTUBE_SABR_PATCH_ONLY_SCRIPT = """';
const start = source.indexOf(marker);
if (start < 0) throw new Error('Patch-only SABR script marker not found');
const end = source.indexOf('""".trimIndent()', start);
if (end < 0) throw new Error('Patch-only SABR script terminator not found');
const script = source.slice(start + marker.length, end);
new Function(script);
for (const forbidden of ['cancelPlayback', 'loadVideoById', 'isInlinePlaybackNoAd', 'Object.assign']) {
  if (script.includes(forbidden)) throw new Error(`Patch-only script must not use ${forbidden}`);
}

function readField4Backoff(bytes) {
  if (bytes[0] !== 0x20) return null;
  let value = 0;
  let shift = 0;
  for (let index = 1; index < bytes.length && shift < 35; index += 1, shift += 7) {
    value += (bytes[index] & 0x7f) * (2 ** shift);
    if ((bytes[index] & 0x80) === 0) return value;
  }
  return null;
}

function requirePatchedBackoff(bytes, label) {
  const value = readField4Backoff(bytes);
  if (value === null || value < 50 || value > 149) {
    throw new Error(`${label}: expected field-4 SABR backoff in the 50–149 ms target range, got ${value}`);
  }
}

(async () => {
  const original = new Uint8Array([0x20, 0xd8, 0x09]); // field 4: 1240 ms
  class FakeXmlHttpRequest {
    constructor() {
      this.listeners = {};
      this.readyState = 1;
      this.response = original.buffer.slice(0);
    }
    open() {}
    send() {
      this.readyState = 4;
      for (const name of ['readystatechange', 'load', 'loadend']) {
        for (const listener of this.listeners[name] || []) listener.call(this);
      }
    }
    addEventListener(name, listener) {
      (this.listeners[name] ||= []).push(listener);
    }
  }
  const window = {
    fetch: () => Promise.resolve(new Response(original)),
  };
  const document = { querySelector: () => null };
  new Function('window', 'document', 'Response', 'Uint8Array', 'XMLHttpRequest', script)(
    window, document, Response, Uint8Array, FakeXmlHttpRequest,
  );
  const response = await window.fetch('https://rr1---sn.googlevideo.com/videoplayback?sabr=1');
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.length !== original.length) throw new Error('SABR fetch response length changed unexpectedly');
  requirePatchedBackoff(bytes, 'SABR fetch backoff response');

  const xhr = new FakeXmlHttpRequest();
  let xhrLoadSawPatchedResponse = false;
  xhr.addEventListener('load', function () {
    const loadBytes = new Uint8Array(this.response);
    const backoff = readField4Backoff(loadBytes);
    xhrLoadSawPatchedResponse = backoff !== null && backoff >= 50 && backoff <= 149;
  });
  xhr.open('GET', 'https://rr1---sn.googlevideo.com/videoplayback?sabr=1');
  xhr.send();
  if (!xhrLoadSawPatchedResponse) throw new Error('SABR XHR response must be patched before load listeners read it');
  const xhrBytes = new Uint8Array(xhr.response);
  if (xhrBytes.length !== original.length) throw new Error('SABR XHR response length changed unexpectedly');
  requirePatchedBackoff(xhrBytes, 'SABR XHR backoff response');

  const failingWindow = {
    fetch: () => Promise.resolve(new Response(new ReadableStream({
      start(controller) { controller.error(new Error('simulated SABR read failure')); },
    }))),
  };
  new Function('window', 'document', 'Response', 'Uint8Array', script)(
    failingWindow,
    document,
    Response,
    Uint8Array,
  );
  let fallback;
  try {
    fallback = await failingWindow.fetch('https://rr1---sn.googlevideo.com/videoplayback?sabr=1');
  } catch (_error) {
    throw new Error('SABR inspection failure must preserve the pass-through response instead of rejecting player fetch');
  }
  if (!(fallback instanceof Response)) throw new Error('SABR inspection fallback must return a Response');
  console.log('YouTube SABR patch-only session-preserving behavior: OK');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
