const fs = require('fs');

const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/MainActivity.kt', 'utf8');
const required = [
  'builder.setActions(listOf(createOpenBrowserRemoteAction()))',
  'Intent(this, MainActivity::class.java)',
  'action = Intent.ACTION_MAIN',
  'addCategory(Intent.CATEGORY_LAUNCHER)',
  'Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK',
  'PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE',
  'Icon.createWithResource(this, R.drawable.ic_browser)',
];
for (const text of required) {
  if (!source.includes(text)) throw new Error(`PiP open-browser action missing: ${text}`);
}
for (const forbidden of [
  'BrowserActivity',
  'BrowserScreenHost',
  'restorePersistentSession',
]) {
  if (source.includes(forbidden)) throw new Error(`PiP action must not change app startup architecture: ${forbidden}`);
}
console.log('PiP open-browser action uses the existing MainActivity in a separate task: OK');
