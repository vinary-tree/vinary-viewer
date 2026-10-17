'use strict';

// Own the temporary HOME outside Electron (as remote-daemon-events-e2e-runner does): Chromium can flush profile and
// session files after Node's in-process `exit` callbacks, so only the parent process can guarantee that the
// integration test leaves nothing behind — and it never touches the developer's real HOME or ~/.config.

const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const scratch = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-remote-pdf-e2e-')));

try {
  const result = spawnSync(process.execPath, [path.join(ROOT, 'scripts', 'run-electron-headless.mjs'),
    'electron', '--no-sandbox', path.join(__dirname, 'remote-pdf-e2e.js')], {
    cwd: ROOT,
    env: { ...process.env, VV_REMOTE_PDF_E2E_HOME: scratch },
    stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.signal) {
    console.error(`remote-pdf Electron test terminated by ${result.signal}`);
    process.exitCode = 1;
  } else {
    process.exitCode = result.status == null ? 1 : result.status;
  }
} finally {
  fs.rmSync(scratch, { recursive: true, force: true });
}
