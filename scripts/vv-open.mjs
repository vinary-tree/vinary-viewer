#!/usr/bin/env node
// The `vv <file>` GUI client — hands the files to the warm resident process over its Unix socket (so a new
// window opens with NO cold start), and is INDEPENDENT of systemd: if no daemon is reachable it starts one
// itself (`electron "$REPO" --daemon`, detached), waits for the socket, then sends. Falls back to opening
// directly (single-instance routes it or becomes the instance) if even that fails. It also retires an IDLE
// daemon that is running an older build than dist/main/main.js, so a rebuild takes effect on the next open
// (see retireIfStaleAndIdle). The socket path and helpers come from ./daemon-socket.mjs, which mirrors
// vinary.main.daemon/socket-path. Usage: `node vv-open.mjs [files/URLs …]`.
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { socketPath, ping, request, waitFree } from './daemon-socket.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ELECTRON = path.join(ROOT, 'node_modules', '.bin', 'electron');
const BUNDLE = path.join(ROOT, 'dist', 'main', 'main.js');
const SOCK = socketPath();

// keep URLs verbatim; resolve local paths against the launch cwd (the daemon has a different cwd)
const isUrl = (s) => /^[a-z][a-z0-9+.-]*:\/\//i.test(s);
const args = process.argv.slice(2).filter((a) => a && !a.startsWith('-')).map((a) => (isUrl(a) ? a : path.resolve(process.cwd(), a)));

// One idempotency key per `vinary-viewer` invocation. It rides on EVERY window-open signal this launch can
// deliver to the daemon — the socket open message, and (for the direct fallback that can itself open a window
// as a primary or via second-instance) the spawned process's argv — so the daemon opens exactly one window per
// invocation even when two signals race. The --daemon bootstrap never opens a window, so it needs no id.
const INSTANCE = randomUUID();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function send() {
  return new Promise((resolve, reject) => {
    const c = net.connect(SOCK);
    c.once('connect', () => c.end(JSON.stringify({ args, instanceId: INSTANCE })));
    c.once('error', reject);
    c.once('close', () => resolve(true));
  });
}
async function trySendUntil(deadlineMs) {
  const dl = Date.now() + deadlineMs;
  for (;;) {
    try { await send(); return true; } catch (e) {
      if (Date.now() >= dl) return false;
      await sleep(100);
    }
  }
}

// A resident daemon keeps serving the build it loaded at boot, so a rebuild (./install.sh, or a bare
// `npm run release`) leaves it stale until something replaces it. Retire an idle stale one here so the next
// open transparently gets the new build — but NEVER one with windows on screen: closing a live session
// behind the user's back is worse than a slightly old renderer, and the staleness resolves itself as soon as
// they close those windows. A pre-vv1 daemon cannot answer (and cannot report its windows), so it is left
// alone — ./install.sh is what replaces those, and only once.
async function retireIfStaleAndIdle() {
  const status = await ping(SOCK, 250);        // 250 ms is the worst case, and only against a legacy daemon
  if (!status || !status.bundleMtimeMs) return;
  let onDisk;
  try { onDisk = fs.statSync(BUNDLE).mtimeMs; } catch { return; }
  if (status.bundleMtimeMs >= onDisk) return;  // already the current build
  if ((status.windows || 0) > 0) return;       // the user is mid-session — defer
  try { await request(SOCK, 'vv1 stop\n', 2000); } catch { /* quit mid-reply — expected */ }
  // Must outlast the daemon's own shutdown watchdog (vinary.main.daemon/stop-watchdog-ms, 5 s): a wedged
  // before-quit is forced down AT that mark, so waiting only 5 s here would give up in the same instant and
  // hand the file to the stale daemon we just asked to leave.
  await waitFree(SOCK, 8000);
}

(async () => {
  await retireIfStaleAndIdle().catch(() => {});
  if (await trySendUntil(0).catch(() => false)) process.exit(0);   // a daemon is already up → done
  // no daemon reachable → start one ourselves (systemd not required), then send
  spawn(ELECTRON, [ROOT, '--daemon'], { detached: true, stdio: 'ignore' }).unref();
  if (await trySendUntil(8000)) process.exit(0);
  // last resort: open directly (single-instance will route into whatever primary exists, or become it). Carry
  // the instance-id in argv so that whether this process becomes the primary (initial open) or is delivered to
  // an existing primary via second-instance, the daemon still opens exactly one window for this invocation.
  spawn(ELECTRON, [ROOT, `--vv-instance-id=${INSTANCE}`, ...args], { detached: true, stdio: 'ignore' }).unref();
  process.exit(0);
})();
