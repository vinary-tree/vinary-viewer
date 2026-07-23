// The resident daemon's Unix socket — the ONE JavaScript copy of the path + request helper, imported by
// scripts/vv-open.mjs (the `vv` client) and scripts/vv-daemon.mjs (ping/stop). It mirrors
// vinary.main.daemon/socket-path, the ClojureScript side of the same contract:
// `$XDG_RUNTIME_DIR/vinary-viewer.sock` when set (per-user, cleaned on logout), else a uid-qualified path
// under the temp dir. Change the two together.
import net from 'node:net';
import path from 'node:path';

export function socketPath() {
  const rt = process.env.XDG_RUNTIME_DIR;
  return rt && rt !== ''
    ? path.join(rt, 'vinary-viewer.sock')
    : path.join(process.env.TMPDIR || '/tmp', `vinary-viewer-${process.getuid()}.sock`);
}

// Connect, send `payload`, half-close, and resolve with the daemon's reply ('' if it said nothing).
//
// Three outcomes worth telling apart:
//   • reject ENOENT / ECONNREFUSED — no live daemon (no socket, or a stale socket file with no listener)
//   • resolve ''  or  reject ETIMEDOUT — a daemon that does not speak `vv1`: pre-upgrade builds parse every
//     message as JSON, log "bad request", and neither reply nor close. The timeout IS the legacy signal.
//   • resolve '<json>' — a `vv1`-speaking daemon
export function request(sock, payload, timeoutMs = 2000) {
  return new Promise((resolve, reject) => {
    const c = net.connect(sock);
    let out = '';
    let settled = false;
    const done = (fn, arg) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      c.destroy();
      fn(arg);
    };
    const timer = setTimeout(
      () => done(reject, Object.assign(new Error('daemon did not reply'), { code: 'ETIMEDOUT' })),
      timeoutMs,
    );
    c.once('connect', () => c.end(payload));
    c.on('data', (d) => { out += d; });
    c.once('error', (e) => done(reject, e));
    c.once('close', () => done(resolve, out.trim()));
  });
}

// `vv1 ping` → the daemon's status object, or null when it cannot answer (no daemon, or a legacy one).
export async function ping(sock, timeoutMs = 2000) {
  try {
    const reply = await request(sock, 'vv1 ping\n', timeoutMs);
    return reply ? JSON.parse(reply) : null;
  } catch {
    return null;
  }
}

// True once nothing is listening on `sock` — a CONNECT probe, never existsSync: the socket file outlives an
// unclean exit, so its presence proves nothing about whether a daemon is alive.
export function isFree(sock) {
  return new Promise((resolve) => {
    const c = net.connect(sock);
    c.once('connect', () => { c.destroy(); resolve(false); });
    c.once('error', () => { c.destroy(); resolve(true); });
  });
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Poll `isFree` until the socket frees up or the deadline passes. Returns true if it freed.
export async function waitFree(sock, deadlineMs) {
  const dl = Date.now() + deadlineMs;
  for (;;) {
    if (await isFree(sock)) return true;
    if (Date.now() >= dl) return false;
    await sleep(100);
  }
}
