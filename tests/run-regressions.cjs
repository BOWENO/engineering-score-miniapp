const { spawnSync } = require('node:child_process');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
for (const name of ['miniprogram-login','miniprogram-schedule-notice','miniprogram-schedule',
  'repair-regression','role-diagnostics','web-backend-diagnostics','web-schedule-filters',
  'miniprogram-all-pages','review-request-boundaries','review-write-recovery','daily-deduction-board','score-live-updates','ux-input-protection','native-ux-regression']) {
  const result = spawnSync(process.execPath, [path.join(__dirname, `${name}.cjs`)], { cwd: root, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
