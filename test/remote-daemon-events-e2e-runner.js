'use strict';

// Own the temporary HOME outside Electron. Chromium can flush profile/session files after Node's in-process
// `exit` callbacks, so only the parent process can guarantee that the integration test leaves no artifacts.

const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const scratch = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-remote-events-e2e-')));

try {
  const result = spawnSync(process.execPath, [path.join(ROOT, 'scripts', 'run-electron-headless.mjs'),
    'electron', '--no-sandbox', path.join(__dirname, 'remote-daemon-events-e2e.js')], {
    cwd: ROOT,
    env: {
      ...process.env,
      VV_REMOTE_EVENTS_E2E_HOME: scratch,
    },
    stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.signal) {
    console.error(`remote daemon-events Electron test terminated by ${result.signal}`);
    process.exitCode = 1;
  } else {
    process.exitCode = result.status == null ? 1 : result.status;
  }
} finally {
  fs.rmSync(scratch, { recursive: true, force: true });
}
