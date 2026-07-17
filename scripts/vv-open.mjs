#!/usr/bin/env node
// The `vv <file>` GUI client — hands the files to the warm resident process over its Unix socket (so a new
// window opens with NO cold start), and is INDEPENDENT of systemd: if no daemon is reachable it starts one
// itself (`electron "$REPO" --daemon`, detached), waits for the socket, then sends. Falls back to opening
// directly (single-instance routes it or becomes the instance) if even that fails. The socket path mirrors
// vinary.main.daemon/socket-path. Usage: `node vv-open.mjs [files/URLs …]`.
import net from 'node:net';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ELECTRON = path.join(ROOT, 'node_modules', '.bin', 'electron');
const rt = process.env.XDG_RUNTIME_DIR;
const SOCK = rt && rt !== ''
  ? path.join(rt, 'vinary-viewer.sock')
  : path.join(process.env.TMPDIR || '/tmp', `vinary-viewer-${process.getuid()}.sock`);

// keep URLs verbatim; resolve local paths against the launch cwd (the daemon has a different cwd)
const isUrl = (s) => /^[a-z][a-z0-9+.-]*:\/\//i.test(s);
const args = process.argv.slice(2).filter((a) => a && !a.startsWith('-')).map((a) => (isUrl(a) ? a : path.resolve(process.cwd(), a)));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function send() {
  return new Promise((resolve, reject) => {
    const c = net.connect(SOCK);
    c.once('connect', () => c.end(JSON.stringify({ args })));
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

(async () => {
  if (await trySendUntil(0).catch(() => false)) process.exit(0);   // a daemon is already up → done
  // no daemon reachable → start one ourselves (systemd not required), then send
  spawn(ELECTRON, [ROOT, '--daemon'], { detached: true, stdio: 'ignore' }).unref();
  if (await trySendUntil(8000)) process.exit(0);
  // last resort: open directly (single-instance will route into whatever primary exists, or become it)
  spawn(ELECTRON, [ROOT, ...args], { detached: true, stdio: 'ignore' }).unref();
  process.exit(0);
})();
