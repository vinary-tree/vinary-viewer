#!/usr/bin/env node
// Cross-platform, non-interactive display wrapper for Electron tests and tools.
//
// Linux needs a display protocol even when every BrowserWindow is hidden. We provide either an isolated Xvfb
// X11 server or an isolated Weston headless Wayland compositor. macOS and Windows already have a system display
// service, so their analogue is the native compositor plus VV_HEADLESS=1; Electron harnesses and the real app
// honor that flag by keeping native windows hidden and off the taskbar/Dock.
//
//   node scripts/run-electron-headless.mjs [--backend=auto|x11|wayland|native]
//        [--width=1400] [--height=1000] [--env=KEY=VALUE] electron|node [arguments...]

import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);

export function selectBackend(requested = 'auto', platform = process.platform) {
  const value = String(requested || 'auto').toLowerCase();
  if (platform === 'linux') {
    if (value === 'auto') return 'x11'; // Electron's documented CI baseline; Wayland remains an explicit gate.
    if (value === 'x11' || value === 'wayland') return value;
    throw new Error(`headless backend ${value} is not available on Linux (use x11 or wayland)`);
  }
  if (platform === 'darwin' || platform === 'win32') {
    if (value === 'auto' || value === 'native') return 'native';
    throw new Error(`headless backend ${value} is not available on ${platform} (use native)`);
  }
  throw new Error(`headless Electron is not configured for platform ${platform}`);
}

function without(source, ...keys) {
  const out = { ...source };
  for (const key of keys) delete out[key];
  return out;
}

export function buildHeadlessPlan({
  platform = process.platform,
  backend = 'auto',
  executable,
  args = [],
  env = process.env,
  width = 1400,
  height = 1000,
  runtimeDir = null,
  socket = `vv-headless-${process.pid}`,
} = {}) {
  if (!executable) throw new Error('a child executable is required');
  const selected = selectBackend(backend, platform);
  const common = { ...env, VV_HEADLESS: '1', VV_HEADLESS_BACKEND: selected };

  if (selected === 'x11') {
    const childEnv = without(common, 'WAYLAND_DISPLAY', 'WAYLAND_SOCKET');
    Object.assign(childEnv, {
      VV_OZONE: 'x11',
      ELECTRON_OZONE_PLATFORM_HINT: 'x11',
      GDK_BACKEND: 'x11',
      XDG_SESSION_TYPE: 'x11',
    });
    return {
      backend: selected,
      command: 'xvfb-run',
      args: ['-a', '-s', `-screen 0 ${width}x${height}x24`, executable, ...args],
      env: childEnv,
      runtimeDir: null,
    };
  }

  if (selected === 'wayland') {
    if (!runtimeDir) throw new Error('the Wayland backend requires a private runtime directory');
    const childEnv = without(common, 'DISPLAY', 'WAYLAND_SOCKET');
    Object.assign(childEnv, {
      XDG_RUNTIME_DIR: runtimeDir,
      WAYLAND_DISPLAY: socket,
      VV_OZONE: 'wayland',
      ELECTRON_OZONE_PLATFORM_HINT: 'wayland',
      GDK_BACKEND: 'wayland',
      XDG_SESSION_TYPE: 'wayland',
    });
    return {
      backend: selected,
      command: 'weston',
      args: [
        '--backend=headless', '--renderer=pixman', `--width=${width}`, `--height=${height}`,
        '--fake-seat', `--socket=${socket}`, '--no-config', `--log=${path.join(runtimeDir, 'weston.log')}`,
      ],
      childCommand: executable,
      childArgs: args,
      env: childEnv,
      runtimeDir,
      socket,
    };
  }

  const nativeEnv = without(common, 'VV_OZONE', 'ELECTRON_OZONE_PLATFORM_HINT', 'GDK_BACKEND',
    'XDG_SESSION_TYPE', 'WAYLAND_DISPLAY', 'WAYLAND_SOCKET');
  return {
    backend: selected,
    command: executable,
    args,
    env: nativeEnv,
    runtimeDir: null,
  };
}

function parseCli(argv) {
  let backend = process.env.VV_HEADLESS_BACKEND || 'auto';
  let width = Number(process.env.VV_HEADLESS_WIDTH || 1400);
  let height = Number(process.env.VV_HEADLESS_HEIGHT || 1000);
  const additions = {};
  let i = 0;
  for (; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--') { i += 1; break; }
    if (!arg.startsWith('--')) break;
    if (arg.startsWith('--backend=')) backend = arg.slice('--backend='.length);
    else if (arg.startsWith('--width=')) width = Number(arg.slice('--width='.length));
    else if (arg.startsWith('--height=')) height = Number(arg.slice('--height='.length));
    else if (arg.startsWith('--env=')) {
      const assignment = arg.slice('--env='.length);
      const split = assignment.indexOf('=');
      if (split <= 0) throw new Error(`invalid environment assignment: ${assignment}`);
      additions[assignment.slice(0, split)] = assignment.slice(split + 1);
    } else throw new Error(`unknown headless-runner option: ${arg}`);
  }
  if (!Number.isInteger(width) || width < 320 || !Number.isInteger(height) || height < 240) {
    throw new Error(`invalid headless display size: ${width}x${height}`);
  }
  const command = argv[i];
  if (!command) throw new Error('usage: run-electron-headless.mjs [options] electron|node [arguments...]');
  return { backend, width, height, additions, command, args: argv.slice(i + 1) };
}

function resolveCommand(alias) {
  if (alias === 'electron') return require('electron');
  if (alias === 'node') return process.execPath;
  throw new Error(`unsupported child command ${alias}; use electron or node`);
}

function waitForExit(child) {
  if (child.exitCode != null || child.signalCode != null) {
    return Promise.resolve({ code: child.exitCode, signal: child.signalCode });
  }
  return new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (code, signal) => resolve({ code, signal }));
  });
}

async function waitForSocket(child, socketPath, timeoutMs = 10000) {
  const exited = waitForExit(child).then((result) => ({ exited: result }));
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (fs.existsSync(socketPath)) return;
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new Error('timed out waiting for the headless Weston socket');
    const tick = new Promise((resolve) => setTimeout(() => resolve({ tick: true }), Math.min(50, remaining)));
    const outcome = await Promise.race([exited, tick]);
    if (outcome.exited) {
      throw new Error(`Weston exited before its socket was ready (${outcome.exited.signal || outcome.exited.code})`);
    }
  }
}

async function stopChild(child) {
  if (!child || !child.pid || child.exitCode != null || child.signalCode != null) return;
  child.kill('SIGTERM');
  const exited = waitForExit(child);
  const timeout = new Promise((resolve) => setTimeout(resolve, 3000, null));
  if (await Promise.race([exited, timeout]) === null) {
    child.kill('SIGKILL');
    await waitForExit(child).catch(() => {});
  }
}

async function runWayland(plan) {
  const weston = spawn(plan.command, plan.args, { env: plan.env, stdio: 'inherit' });
  let child = null;
  let interrupted = false;
  const stopOnSignal = () => {
    interrupted = true;
    if (child && child.pid) child.kill('SIGTERM');
    if (weston.pid) weston.kill('SIGTERM');
  };
  const signals = ['SIGINT', 'SIGTERM', 'SIGHUP'];
  for (const signal of signals) process.once(signal, stopOnSignal);
  try {
    await waitForSocket(weston, path.join(plan.runtimeDir, plan.socket));
    child = spawn(plan.childCommand, plan.childArgs, { env: plan.env, stdio: 'inherit' });
    const outcome = await Promise.race([
      waitForExit(child).then((result) => ({ source: 'child', result })),
      waitForExit(weston).then((result) => ({ source: 'weston', result })),
    ]);
    if (outcome.source === 'weston') {
      if (interrupted) return 1;
      throw new Error(`headless Weston exited while its child was running (${outcome.result.signal || outcome.result.code})`);
    }
    const { result } = outcome;
    if (result.signal) {
      console.error(`headless ${plan.backend} child terminated by ${result.signal}`);
      return 1;
    }
    return result.code == null ? 1 : result.code;
  } finally {
    for (const signal of signals) process.removeListener(signal, stopOnSignal);
    await stopChild(child);
    await stopChild(weston);
  }
}

export async function runCli(argv = process.argv.slice(2)) {
  const parsed = parseCli(argv);
  const selected = selectBackend(parsed.backend);
  let runtimeDir = null;
  try {
    if (selected === 'wayland') {
      runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-weston-'));
      fs.chmodSync(runtimeDir, 0o700);
    }
    const plan = buildHeadlessPlan({
      backend: selected,
      executable: resolveCommand(parsed.command),
      args: parsed.args,
      env: { ...process.env, ...parsed.additions },
      width: parsed.width,
      height: parsed.height,
      runtimeDir,
    });
    if (plan.backend === 'wayland') return await runWayland(plan);
    const result = spawnSync(plan.command, plan.args, { env: plan.env, stdio: 'inherit' });
    if (result.error) throw result.error;
    if (result.signal) {
      console.error(`headless ${plan.backend} child terminated by ${result.signal}`);
      return 1;
    }
    return result.status == null ? 1 : result.status;
  } catch (error) {
    const log = runtimeDir && path.join(runtimeDir, 'weston.log');
    if (log && fs.existsSync(log)) {
      const tail = fs.readFileSync(log, 'utf8').trim().split('\n').slice(-20).join('\n');
      if (tail) error.message += `\nWeston log tail:\n${tail}`;
    }
    throw error;
  } finally {
    if (runtimeDir) fs.rmSync(runtimeDir, { recursive: true, force: true });
  }
}

const invokedDirectly = process.argv[1]
  && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (invokedDirectly) {
  runCli().then((code) => { process.exitCode = code; }).catch((error) => {
    console.error(`headless runner: ${error && error.message ? error.message : error}`);
    process.exitCode = 1;
  });
}
