'use strict';

// A hermetic environment for a harness that boots the REAL main process (dist/main/main.js), in-process or as a
// child. An isolated $XDG_CONFIG_HOME (each harness makes its own) covers the app's settings, recents and window
// geometry, and gives the harness a single-instance lock of its own — which is exactly why it is not enough:
//
//   • $XDG_RUNTIME_DIR. Every primary serves the per-user `vv` socket, $XDG_RUNTIME_DIR/vinary-viewer.sock
//     (vinary.main.daemon). A harness on the developer's runtime dir would be the instance a `vv <file>` reaches
//     while no daemon runs — and, before the daemon learned to probe, it took a LIVE daemon's socket for good.
//     Piped-stdin and commit-diff spills live under the runtime dir as well, with their age-gated sweeps.
//   • The password-manager bridge (vinary.main.passwords). Every window asks for the bridge's state as it boots
//     (vinary.renderer.core), and main answers by running the developer's real password-manager CLIs for their
//     status. They act on the developer's own accounts and sessions, and one of them starts an agent in
//     $XDG_RUNTIME_DIR that outlives a private runtime dir — an orphaned agent per run (observed: one per
//     daemon-smoke run, per Wayland-backend run). `passwords.edn {:enabled? false}` in the isolated config
//     turns the bridge off.
//
//   const { runtimeDir, cleanup } = hermeticRuntime(CONFIG_HOME);   // before dist/main/main.js is required
//   …
//   cleanup();                                                         // in the harness's own teardown
//
// The runtime dir is deliberately SHORT and under /tmp: a Unix socket path is capped at ~104 bytes (sun_path), and
// a long temp dir fails with EINVAL at listen().

const fs = require('fs');
const os = require('os');
const path = require('path');

const PASSWORDS_OFF = '{:enabled? false}\n';

/** Disable the password-manager bridge in an isolated config home (left as is if the harness wrote its own). */
function disablePasswordBridge(configHome) {
  const dir = path.join(configHome, 'vinary-viewer');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'passwords.edn');
  if (!fs.existsSync(file)) fs.writeFileSync(file, PASSWORDS_OFF);
  return file;
}

/**
 * Give this process a private runtime dir and switch the password bridge off in `configHome`. Mutates
 * process.env, so call it before the real main is required (or pass `hermeticEnv(...)` to a child).
 *
 * A Wayland backend (scripts/run-electron-headless.mjs) names its compositor socket RELATIVE to the runtime dir
 * it created; that name is made absolute first, so moving the runtime dir does not lose the display.
 */
function hermeticRuntime(configHome) {
  const base = process.platform === 'win32' ? os.tmpdir() : '/tmp';
  const runtimeDir = fs.realpathSync(fs.mkdtempSync(path.join(base, 'vv-rt-')));
  fs.chmodSync(runtimeDir, 0o700);
  const display = process.env.WAYLAND_DISPLAY;
  if (display && !path.isAbsolute(display) && process.env.XDG_RUNTIME_DIR) {
    process.env.WAYLAND_DISPLAY = path.join(process.env.XDG_RUNTIME_DIR, display);
  }
  process.env.XDG_RUNTIME_DIR = runtimeDir;
  disablePasswordBridge(configHome);
  return { runtimeDir, cleanup: () => fs.rmSync(runtimeDir, { recursive: true, force: true }) };
}

module.exports = { hermeticRuntime, disablePasswordBridge, PASSWORDS_OFF };
