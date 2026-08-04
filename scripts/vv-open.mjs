#!/usr/bin/env node
// The `vv <file>` GUI client — hands the files to the warm resident process over its Unix socket (so a new
// window opens with NO cold start), and is INDEPENDENT of systemd: if no daemon is reachable it starts one
// itself (`electron "$REPO" --daemon`, detached), waits for the socket, then sends. Falls back to opening
// directly (single-instance routes it or becomes the instance) if even that fails. It also retires an IDLE
// daemon that is running an older build than dist/main/main.js, so a rebuild takes effect on the next open
// (see retireIfStaleAndIdle). The socket path and helpers come from ./daemon-socket.mjs, which mirrors
// vinary.main.daemon/socket-path.
//
// ADR-0036: this client also (1) consumes `-t/--type TYPE` flags (raw tokens forwarded — the ONE resolver
// lives daemon-side in vinary.file-type; this scan is a mechanical twin of scan-open-args), and (2) drains
// piped stdin to a private spill file (the resident daemon cannot see this terminal's pipe), inserting it
// as the FIRST document (or where a lone `-` places it). The daemon replies on the open connection ONLY to
// reject (unknown type, mispaired types) — that error is printed here, on the invoking terminal.
// Usage: `node vv-open.mjs [-t TYPE …] [files/URLs/- …]`.
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

// ── argv scan (mechanical twin of vinary.file-type/scan-open-args — validation stays daemon-side) ──
// `-t V` / `--type V` / `--type=V` / `--file-type[=V]` collect raw type tokens in flag order; a lone `-`
// marks where the stdin document goes among the files; every other dashed arg is not this client's
// business (the launcher consumed the mode flag) and is dropped, exactly as before.
const files = [];
const types = [];
let dashIndex = null;
{
  const raw = process.argv.slice(2);
  let scanError = null;
  for (let i = 0; i < raw.length && !scanError; i += 1) {
    const a = raw[i];
    if (!a) continue;
    if (a === '-t' || a === '--type' || a === '--file-type') {
      const v = raw[i + 1];
      if (!v || v.startsWith('-')) scanError = `vv: ${a} requires a value (a MIME type, short type token, or grammar language)`;
      else { types.push(v); i += 1; }
    } else if (a.startsWith('--type=') || a.startsWith('--file-type=')) {
      const v = a.slice(a.indexOf('=') + 1);
      if (!v) scanError = `vv: ${a} requires a value after '='`;
      else types.push(v);
    } else if (a === '-') {
      if (dashIndex != null) scanError = "vv: '-' (the stdin document) may appear only once";
      else dashIndex = files.length;
    } else if (a.startsWith('-')) {
      // other flags dropped (see above)
    } else {
      files.push(isUrl(a) ? a : path.resolve(process.cwd(), a));
    }
  }
  if (scanError) {
    process.stderr.write(scanError + '\n');
    process.exit(2);
  }
}

// ── piped stdin → private spill file (JS twin of vinary.terminal.stdin — placement rules shared with
// the daemon socket; change them together with daemon-socket.mjs / vinary.main.daemon/socket-path) ──
async function drainStdin() {
  if (process.stdin.isTTY) return null;                       // a terminal is never drained
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);        // binary-safe: `cat x.pdf | vv -t pdf`
  const buf = Buffer.concat(chunks);
  return buf.length > 0 ? buf : null;                         // empty pipe = NO stdin document
}
function stdinRoot() {
  const rt = process.env.XDG_RUNTIME_DIR;
  if (rt) return path.join(rt, 'vinary-viewer', 'stdin');
  return path.join(process.env.TMPDIR || '/tmp', `vinary-viewer-${process.getuid()}`, 'stdin');
}
function spill(buf) {
  const dir = path.join(stdinRoot(), randomUUID());
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const p = path.join(dir, 'stdin');                          // basename `stdin` = the tab label
  fs.writeFileSync(p, buf, { mode: 0o600 });
  return p;
}
let stdinPath = null;   // the daemon owns the spill's lifetime after a successful hand-off — no exit hook
function cleanupSpill() {
  if (!stdinPath) return;
  try { fs.rmSync(stdinPath, { force: true }); } catch { /* best-effort */ }
  try { fs.rmdirSync(path.dirname(stdinPath)); } catch { /* best-effort */ }
}

// One idempotency key per `vinary-viewer` invocation. It rides on EVERY window-open signal this launch can
// deliver to the daemon — the socket open message, and (for the direct fallback that can itself open a window
// as a primary or via second-instance) the spawned process's argv — so the daemon opens exactly one window per
// invocation even when two signals race. The --daemon bootstrap never opens a window, so it needs no id.
const INSTANCE = randomUUID();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function send(message) {
  return new Promise((resolve, reject) => {
    const c = net.connect(SOCK);
    let reply = '';
    c.once('connect', () => c.end(JSON.stringify(message)));
    c.on('data', (d) => { reply += d; });    // the half-close still receives — the daemon replies only to reject
    c.once('error', reject);
    c.once('close', () => resolve(reply));
  });
}
async function trySendUntil(message, deadlineMs) {
  const dl = Date.now() + deadlineMs;
  for (;;) {
    try { return { reply: await send(message) }; } catch (e) {
      if (Date.now() >= dl) return null;
      await sleep(100);
    }
  }
}
// The open path's only reply is a rejection ({"ok":false,"error":"vv: …"}); silence means the open was taken.
function openRejection(reply) {
  if (!reply) return null;
  try {
    const p = JSON.parse(reply);
    return p && p.ok === false ? (p.error || 'vv: open rejected') : null;
  } catch { return null; }
}
function finish(reply) {
  const err = openRejection(reply);
  if (err) {
    process.stderr.write(err + '\n');
    cleanupSpill();                          // the daemon refused the open — the spill is ours to remove
    process.exit(1);
  }
  process.exit(0);
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
  // Drain stdin FIRST — a piped document is a snapshot at EOF (the producer decides when that is), and the
  // daemon must not be contacted until the spill exists at its position among the files.
  const buf = await drainStdin().catch(() => null);
  let stdinIndex = null;
  if (buf) {
    stdinPath = spill(buf);
    stdinIndex = dashIndex != null ? Math.min(dashIndex, files.length) : 0;   // stdin is file 0 by default
    files.splice(stdinIndex, 0, stdinPath);
  } else if (dashIndex != null) {
    process.stderr.write("vv: '-' given but stdin carries no piped data (stdin is a terminal, or the pipe was empty)\n");
    process.exit(2);
  }
  const message = { args: files, types, stdinIndex, cwd: process.cwd(), instanceId: INSTANCE };

  await retireIfStaleAndIdle().catch(() => {});
  const first = await trySendUntil(message, 0).catch(() => null);
  if (first) return finish(first.reply);       // a daemon is already up → done
  // no daemon reachable → start one ourselves (systemd not required), then send
  spawn(ELECTRON, [ROOT, '--daemon'], { detached: true, stdio: 'ignore' }).unref();
  const second = await trySendUntil(message, 8000);
  if (second) return finish(second.reply);
  // last resort: open directly (single-instance will route into whatever primary exists, or become it). Carry
  // the instance-id in argv so that whether this process becomes the primary (initial open) or is delivered to
  // an existing primary via second-instance, the daemon still opens exactly one window for this invocation.
  // The type flags travel verbatim (pairing is by order, so flag position is free) and `--vv-stdin=<path>`
  // marks which positional arg is the spilled stdin document (startup/doc-specs reads it back out).
  const typeFlags = types.flatMap((t) => ['-t', t]);
  const stdinFlag = stdinPath ? [`--vv-stdin=${stdinPath}`] : [];
  spawn(ELECTRON, [ROOT, `--vv-instance-id=${INSTANCE}`, ...stdinFlag, ...typeFlags, ...files],
        { detached: true, stdio: 'ignore' }).unref();
  process.exit(0);
})();
