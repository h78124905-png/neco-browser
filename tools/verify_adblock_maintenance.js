const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');
const requireText = (text, needle, message) => {
  if (!text.includes(needle)) throw new Error(message);
};

const gradle = read('app/build.gradle.kts');
requireText(
  gradle,
  'implementation("androidx.datastore:datastore-preferences:1.2.1")',
  'DataStore must stay on the audited stable 1.2.1 line.'
);
requireText(
  gradle,
  'implementation("androidx.work:work-runtime-ktx:2.11.2")',
  'WorkManager must keep the audited 2.11.2 network/retry fixes.'
);
requireText(
  gradle,
  'implementation("androidx.webkit:webkit:1.17.0")',
  'WebKit must remain on the audited current stable 1.17.0 line.'
);

const adBlocker = read('app/src/main/java/com/example/httpsbrowser/data/AdBlocker.kt');
requireText(adBlocker, 'instanceFollowRedirects = false', 'Filter-list downloads must not follow redirects implicitly.');
requireText(adBlocker, 'readBytesLimited(MAX_LIST_BYTES)', 'Filter-list downloads must retain their size limit.');
requireText(adBlocker, 'try {\n            require(request.responseCode in 200..299)', 'Filter-list download must validate the response inside a managed request scope.');
requireText(adBlocker, 'finally {\n            request.disconnect()\n        }', 'Filter-list HTTP connections must be released on success and failure.');
requireText(adBlocker, 'NetworkType.UNMETERED', 'Background list updates must remain limited to non-metered networks.');
requireText(adBlocker, 'ExistingPeriodicWorkPolicy.KEEP', 'Repeated app startup must not enqueue duplicate list-update work.');

console.log('Adblock maintenance regression checks: OK');
