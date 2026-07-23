'use strict';

// The resident-daemon control seam: `vv1 ping` / `vv1 stop` (src/vinary/main/daemon.cljs) and the two
// clients built on it — scripts/vv-daemon.mjs (used by install.sh + uninstall.sh) and the staleness
// self-heal in scripts/vv-open.mjs.
//
// What it protects. The daemon is identified by the SOCKET it owns, never by who started it: `vv` spawns
// one on demand, so the resident process usually belongs to no service manager, and a service-manager
// restart alone leaves it running — its replacement loses the single-instance lock and quits, so the user
// silently keeps the OLD build. Everything asserted here is what makes "a re-install ends on the new build"
// true rather than aspirational.
//
// Isolation (this runs on a developer machine with a REAL daemon live):
//   • its own XDG_RUNTIME_DIR, so it can never reach the developer's socket. Deliberately SHORT: a unix
//     socket path is capped at ~104 bytes (sun_path), and a long temp dir fails with EINVAL at listen().
//   • a mirror app directory whose package.json carries a different "name", which is what gives Electron a
//     separate userData dir and therefore a SEPARATE single-instance lock. (HOME does not work for this on
//     macOS — Chromium takes the real home regardless.)
//
// Needs a display for the window-count assertions (like the other Electron smokes), and a built
// dist/main/main.js — it spawns real daemons.
// Run: npm run test:daemon     (headless Linux: xvfb-run -a npm run test:daemon)

const assert = require('assert');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { spawn, execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const BUNDLE = path.join(ROOT, 'dist', 'main', 'main.js');
const RUNTIME_DIR = `/tmp/vv-smoke-${process.pid}`;          // short on purpose — see the header
const SOCK = path.join(RUNTIME_DIR, 'vinary-viewer.sock');
const APP = `/tmp/vv-smoke-app-${process.pid}`;
const ELECTRON = path.join(ROOT, 'node_modules', '.bin', 'electron');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const env = () => ({ ...process.env, XDG_RUNTIME_DIR: RUNTIME_DIR, XDG_CONFIG_HOME: path.join(APP, 'config'), VV_POOL: '0' });

// A mirror app dir: real package.json (renamed) + real copies of the scripts under test, with the heavy
// directories symlinked. The scripts must be real files — node resolves import.meta.url through symlinks,
// so a symlinked scripts/ would make vv-open.mjs compute the REAL repo as its root and defeat the isolation.
function makeMirrorApp() {
  fs.mkdirSync(path.join(APP, 'scripts'), { recursive: true });
  const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'));
  pkg.name = `vinary-viewer-smoke-${process.pid}`;
  fs.writeFileSync(path.join(APP, 'package.json'), JSON.stringify(pkg, null, 2));
  for (const f of ['vv-open.mjs', 'vv-daemon.mjs', 'daemon-socket.mjs']) {
    fs.copyFileSync(path.join(ROOT, 'scripts', f), path.join(APP, 'scripts', f));
  }
  for (const d of ['dist', 'resources', 'node_modules']) fs.symlinkSync(path.join(ROOT, d), path.join(APP, d));
}

// ---- socket helpers (the client's own view of the protocol, re-implemented here so the test does not
// ---- pass merely because the client and the daemon share a bug) -------------------------------------------
function request(payload, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const c = net.connect(SOCK);
    let out = '';
    let settled = false;
    const done = (fn, arg) => { if (settled) return; settled = true; clearTimeout(t); c.destroy(); fn(arg); };
    const t = setTimeout(() => done(reject, new Error('timeout')), timeoutMs);
    c.once('connect', () => c.end(payload));
    c.on('data', (d) => { out += d; });
    c.once('error', (e) => done(reject, e));
    c.once('close', () => done(resolve, out.trim()));
  });
}
const ping = async () => JSON.parse(await request('vv1 ping\n'));
function isFree() {
  return new Promise((resolve) => {
    const c = net.connect(SOCK);
    c.once('connect', () => { c.destroy(); resolve(false); });
    c.once('error', () => { c.destroy(); resolve(true); });
  });
}
async function waitUntil(fn, ms, what) {
  const dl = Date.now() + ms;
  for (;;) {
    if (await fn()) return;
    assert.ok(Date.now() < dl, `timed out waiting for ${what}`);
    await sleep(200);
  }
}
function runNode(script, args, opts = {}) {
  try {
    const stdout = execFileSync(process.execPath, [script, ...args], { encoding: 'utf8', env: env(), stdio: ['ignore', 'pipe', 'pipe'], ...opts });
    return { code: 0, stdout };
  } catch (e) {
    return { code: e.status, stdout: (e.stdout || '').toString(), stderr: (e.stderr || '').toString() };
  }
}

function startDaemon() {
  const p = spawn(ELECTRON, [APP, '--daemon'], { env: env(), detached: true, stdio: 'ignore' });
  p.unref();
  return p;
}

async function main() {
  fs.mkdirSync(RUNTIME_DIR, { recursive: true });
  makeMirrorApp();

  // 1. A control frame must NOT be valid JSON. This is the whole backward-compatibility contract: a
  //    pre-vv1 daemon parses every message with JSON.parse inside a try/catch and only opens a window on
  //    success, so a malformed frame is inert on it. A JSON-shaped control message (no `args`) would open
  //    a stray empty window on every old daemon a new client probes.
  assert.throws(() => JSON.parse('vv1 ping'), 'the vv1 control frame must not parse as JSON');
  assert.throws(() => JSON.parse('vv1 stop'), 'the vv1 control frame must not parse as JSON');

  startDaemon();
  await waitUntil(async () => !(await isFree()), 30000, 'the daemon to bind its socket');

  // 2. ping reports who we are and WHICH BUILD we loaded — the fields install.sh compares.
  const a = await ping();
  assert.strictEqual(a.ok, true);
  assert.ok(Number.isInteger(a.pid) && a.pid > 0, 'ping reports a pid');
  assert.ok(typeof a.version === 'string' && a.version.length, 'ping reports a version');
  assert.strictEqual(a.daemon, true, '--daemon is reported as such');
  assert.strictEqual(a.windows, 0, 'a fresh daemon has no windows');
  assert.strictEqual(a.bundleMtimeMs, fs.statSync(BUNDLE).mtimeMs,
    'ping reports the mtime of the bundle it actually loaded');

  // 3. The v0.2 open message still opens a window — the control frames are additive, and an OLD `vv`
  //    client must keep working against a NEW daemon. (No instanceId — a pre-instance-id client.)
  await request(JSON.stringify({ args: [path.join(ROOT, 'README.md')] }));
  await waitUntil(async () => (await ping()).windows === 1, 30000, 'the open message to produce a window');

  // 4. An unknown control command is refused, not treated as an open (it must never open a window).
  const unknown = JSON.parse(await request('vv1 nope\n'));
  assert.strictEqual(unknown.ok, false, 'an unknown command is refused');
  assert.strictEqual((await ping()).windows, 1, 'a refused command opens no window');

  // 5. `--expect-bundle` is the install-time verification: it agrees while fresh…
  const fresh = runNode(path.join(APP, 'scripts', 'vv-daemon.mjs'), ['ping', '--expect-bundle', BUNDLE]);
  assert.strictEqual(fresh.code, 0, 'ping --expect-bundle succeeds against a daemon on the current build');

  // …and reports exit 5 once the bundle on disk is newer than the one the daemon loaded.
  const before = fs.statSync(BUNDLE).mtimeMs;
  fs.utimesSync(BUNDLE, new Date(), new Date(Date.now() + 1000));
  const stale = runNode(path.join(APP, 'scripts', 'vv-daemon.mjs'), ['ping', '--expect-bundle', BUNDLE]);
  assert.strictEqual(stale.code, 5, 'a stale daemon is reported as stale (exit 5)');

  // 6. Self-heal deferral: stale, but a window is open → `vv` must NOT restart it. Losing a live session
  //    is worse than an old renderer, and the staleness resolves itself when the user closes the window.
  runNode(path.join(APP, 'scripts', 'vv-open.mjs'), [path.join(ROOT, 'CHANGELOG.txt')]);
  await waitUntil(async () => (await ping()).windows === 2, 30000, 'the second file to open');
  const deferred = await ping();
  assert.strictEqual(deferred.pid, a.pid, 'a daemon with windows open is never restarted underneath the user');

  // 6a. INSTANCE-ID IDEMPOTENCY — the fix for duplicate launch windows. One `vinary-viewer` invocation can
  //     deliver more than one open signal (the socket open AND a second-instance from a sibling racing the
  //     single-instance lock); every signal of one launch carries the same instanceId, and the daemon must open
  //     at most ONE window per id. Sending the same id twice reproduces that race deterministically. Counts are
  //     relative to a baseline, since this runs after step 6; the daemon is stopped at step 7 regardless.
  const base = (await ping()).windows;
  await request(JSON.stringify({ args: [], instanceId: 'smoke-id-X' }));
  await waitUntil(async () => (await ping()).windows === base + 1, 30000, 'the first signal for an id to open one window');
  await request(JSON.stringify({ args: [], instanceId: 'smoke-id-X' }));   // the DUPLICATE signal of the same launch
  await sleep(2000);                                                       // give any wrongful window time to appear
  assert.strictEqual((await ping()).windows, base + 1,
    'a second open with the SAME instanceId must NOT open another window (the duplicate-launch fix)');
  await request(JSON.stringify({ args: [], instanceId: 'smoke-id-Y' }));   // a genuinely different launch
  await waitUntil(async () => (await ping()).windows === base + 2, 30000, 'a distinct instanceId opens its own window (not coalesced)');
  await request(JSON.stringify({ args: [] }));                             // no id (OS/legacy) → never deduped
  await waitUntil(async () => (await ping()).windows === base + 3, 30000, 'an id-less open always produces a window');

  // 7. stop quits the process and frees the socket, so the lock is available to the next launch.
  const stopped = runNode(path.join(APP, 'scripts', 'vv-daemon.mjs'), ['stop', '--notice']);
  assert.strictEqual(stopped.code, 0, `stop should succeed: ${stopped.stderr || ''}`);
  await waitUntil(isFree, 20000, 'the socket to be released');
  // …and the process itself is gone, not merely detached from its socket — the single-instance lock is what
  // the next launch needs, and only a dead process releases it. Polled: the socket is unlinked while the
  // process is exiting, and it can still be visible (briefly, as a zombie) until its launcher reaps it.
  const gone = (pid) => { try { process.kill(pid, 0); return false; } catch { return true; } };
  await waitUntil(async () => gone(a.pid), 15000, 'the daemon process to exit');

  // 8. Self-heal proper: stale AND idle → `vv` retires it and the next open runs the new build.
  startDaemon();
  await waitUntil(async () => !(await isFree()), 30000, 'the second daemon to bind');
  const b = await ping();
  fs.utimesSync(BUNDLE, new Date(), new Date(Date.now() + 2000));      // make it stale while idle
  runNode(path.join(APP, 'scripts', 'vv-open.mjs'), [path.join(ROOT, 'README.md')]);
  await waitUntil(async () => {
    if (await isFree()) return false;
    const c = await ping().catch(() => null);
    return c && c.pid !== b.pid && c.windows >= 1;
  }, 40000, 'the stale idle daemon to be replaced');

  // leave the bundle's mtime as we found it, so a later `vv` does not think the tree is stale
  fs.utimesSync(BUNDLE, new Date(), new Date(before));

  runNode(path.join(APP, 'scripts', 'vv-daemon.mjs'), ['stop']);
  await waitUntil(isFree, 20000, 'the socket to be released at teardown');

  console.log('daemon control-protocol smoke OK');
}

main()
  .catch((err) => { console.error(err && err.stack ? err.stack : err); process.exitCode = 1; })
  .finally(async () => {
    try { runNode(path.join(APP, 'scripts', 'vv-daemon.mjs'), ['stop']); } catch { /* best effort */ }
    fs.rmSync(RUNTIME_DIR, { recursive: true, force: true });
    fs.rmSync(APP, { recursive: true, force: true });
  });
