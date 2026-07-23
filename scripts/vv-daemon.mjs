#!/usr/bin/env node
// Talk to the resident vinary-viewer process — the ONE implementation of "which build is running?" and
// "stop it", used by install.sh, uninstall.sh, and the `vv` client (scripts/vv-open.mjs).
//
// Why this exists: the daemon is identified by the SOCKET it owns (equivalently, the single-instance lock),
// never by who started it. `vv` spawns one on demand when no socket answers, so the resident process usually
// belongs to no service manager at all — and `systemctl --user restart` / `launchctl kickstart -k` then
// restart a job that isn't the one serving you. The replacement instance loses the lock race, quits with
// status 0, and the OLD build keeps serving. Stopping the socket owner first is what makes a re-install
// actually land on the new build.
//
// Usage:
//   vv-daemon.mjs ping [--json] [--deadline <ms>] [--expect-bundle <path>]
//   vv-daemon.mjs stop [--timeout <ms>] [--notice]
//
// Exit codes:  0 ok · 2 usage · 3 no daemon running · 4 legacy daemon (cannot answer) · 5 stale build
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { socketPath, request, ping, isFree, waitFree, sleep } from './daemon-socket.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SOCK = socketPath();

const argv = process.argv.slice(2);
const cmd = argv[0];
const flag = (name, fallback = null) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const has = (name) => argv.includes(name);

const mtimeMs = (p) => { try { return fs.statSync(p).mtimeMs; } catch { return null; } };

// ---- pid discovery: only for a LEGACY daemon, which cannot be asked to quit -------------------------------
// Ordered by precision. lsof/fuser name the actual socket owner; the pgrep fallback is a last resort and is
// deliberately anchored to THIS repo path so it cannot match an unrelated Electron app.
function ownerPids() {
  const tryCmd = (bin, args) => {
    try {
      return execFileSync(bin, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
        .split(/\s+/).map((s) => parseInt(s, 10)).filter((n) => Number.isInteger(n) && n > 0 && n !== process.pid);
    } catch { return []; }
  };
  const found = tryCmd('lsof', ['-t', SOCK]);
  if (found.length) return { pids: [...new Set(found)], how: 'lsof' };
  const fused = tryCmd('fuser', [SOCK]);
  if (fused.length) return { pids: [...new Set(fused)], how: 'fuser' };
  const grepped = tryCmd('pgrep', ['-f', `${ROOT}.*--daemon`]);
  if (grepped.length) return { pids: [...new Set(grepped)], how: 'pgrep' };
  return { pids: [], how: null };
}

function signal(pids, sig) {
  for (const pid of pids) { try { process.kill(pid, sig); } catch { /* already gone */ } }
}

// ---- commands --------------------------------------------------------------------------------------------
async function doPing() {
  const deadline = parseInt(flag('--deadline', '2000'), 10);
  const expect = flag('--expect-bundle');
  const dl = Date.now() + deadline;
  let status = null;
  for (;;) {                                   // retry: after a service-manager restart the daemon needs a moment
    status = await ping(SOCK, Math.min(2000, Math.max(250, deadline)));
    if (status) break;
    if (Date.now() >= dl) break;
    await sleep(200);
  }
  if (!status) {
    const free = await isFree(SOCK);
    if (has('--json')) console.log(JSON.stringify({ ok: false, reason: free ? 'no-daemon' : 'legacy' }));
    else console.log(free ? 'no resident daemon is running' : 'a resident daemon is running but is too old to answer (pre-vv1)');
    return free ? 3 : 4;
  }
  if (has('--json')) console.log(JSON.stringify(status));
  else console.log(`resident daemon: pid ${status.pid}, version ${status.version}, ${status.windows} window(s) open`);

  if (expect) {
    const want = mtimeMs(expect);
    if (want == null) { console.error(`warning: cannot stat ${expect} to verify the running build`); return 0; }
    // Equality, not >=: the daemon reports the mtime of the very file it loaded, so a match is proof it is
    // running THIS build. Sub-ms float drift is not a concern — both sides read the same stat field.
    if (status.bundleMtimeMs !== want) {
      console.error(`warning: the resident daemon (pid ${status.pid}) is NOT running the build just installed`);
      console.error(`         its bundle: ${new Date(status.bundleMtimeMs || 0).toISOString()}`);
      console.error(`         on disk:    ${new Date(want).toISOString()}`);
      console.error(`         restart it with:  node ${path.relative(process.cwd(), fileURLToPath(import.meta.url))} stop`);
      return 5;
    }
    if (!has('--json')) console.log('    ✓ running the build just installed');
  }
  return 0;
}

async function doStop() {
  const timeout = parseInt(flag('--timeout', '8000'), 10);
  if (await isFree(SOCK)) { if (has('--notice')) console.log('    no resident daemon is running'); return 0; }

  const status = await ping(SOCK, 1500);
  if (status && has('--notice')) {
    const w = status.windows || 0;
    console.log(`    stopping resident daemon pid ${status.pid} (version ${status.version})`
      + (w > 0 ? ` — ${w} open window${w === 1 ? '' : 's'} will close` : ''));
  }

  if (status) {
    // Graceful: the daemon quits through app.quit(), so before-quit runs (SSH pools torn down, window
    // bounds persisted). It replies BEFORE quitting, so a dropped connection here is expected.
    try { await request(SOCK, 'vv1 stop\n', 2000); } catch { /* it quit mid-reply — fine */ }
    if (await waitFree(SOCK, timeout)) { if (has('--notice')) console.log('    stopped'); return 0; }
  }

  // Legacy daemon (or one that ignored the request): terminate the socket owner by pid.
  const { pids, how } = ownerPids();
  if (!pids.length) {
    console.error('warning: a daemon holds the socket but its pid could not be determined'
      + ' (install lsof, or stop it manually)');
    return 3;
  }
  if (has('--notice')) console.log(`    daemon is pre-vv1 (cannot be asked to quit); terminating pid ${pids.join(', ')} [${how}]`);
  signal(pids, 'SIGTERM');
  if (await waitFree(SOCK, Math.min(timeout, 5000))) { if (has('--notice')) console.log('    stopped'); return 0; }
  signal(pids, 'SIGKILL');
  if (await waitFree(SOCK, 3000)) { if (has('--notice')) console.log('    stopped (forced)'); return 0; }
  console.error('warning: the resident daemon did not stop; the next launch may still serve the old build');
  return 1;
}

const exit = (code) => process.exit(code);
switch (cmd) {
  case 'ping': exit(await doPing()); break;
  case 'stop': exit(await doStop()); break;
  default:
    console.error('usage: vv-daemon.mjs ping [--json] [--deadline <ms>] [--expect-bundle <path>]');
    console.error('       vv-daemon.mjs stop [--timeout <ms>] [--notice]');
    exit(2);
}
