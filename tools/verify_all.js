const { spawnSync } = require('child_process');

const checks = [
  'tools/verify_browser_stabilization.js',
  'tools/verify_download_modes.js',
  'tools/verify_youtube_sanitizer.js',
  'tools/verify_youtube_noad_warm_player.js',
  'tools/verify_youtube_sabr_patch_only.js',
  'tools/verify_brave_sabr_resource.js',
  'tools/verify_adblock_maintenance.js',
  'tools/verify_pip_open_browser_action.js'
];

for (const check of checks) {
  console.log(`\n=== ${check} ===`);
  const result = spawnSync(process.execPath, [check], { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

console.log('\nAll browser regression checks: OK');
