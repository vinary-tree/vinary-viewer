import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { buildHeadlessPlan, selectBackend } from '../scripts/run-electron-headless.mjs';

const require = createRequire(import.meta.url);

const base = { executable: '/electron', args: ['--no-sandbox', 'test.js'], env: {
  DISPLAY: ':1', WAYLAND_DISPLAY: 'wayland-user', WAYLAND_SOCKET: 'host-socket', USER_FLAG: 'kept',
} };

assert.equal(selectBackend('auto', 'linux'), 'x11');
assert.equal(selectBackend('wayland', 'linux'), 'wayland');
assert.equal(selectBackend('auto', 'darwin'), 'native');
assert.equal(selectBackend('auto', 'win32'), 'native');
assert.throws(() => selectBackend('x11', 'darwin'), /not available/);

const x11 = buildHeadlessPlan({ ...base, platform: 'linux', backend: 'x11' });
assert.equal(x11.command, 'xvfb-run');
assert.ok(x11.args.includes('/electron'));
assert.equal(x11.env.VV_HEADLESS, '1');
assert.equal(x11.env.VV_OZONE, 'x11');
assert.equal(x11.env.WAYLAND_DISPLAY, undefined);
assert.equal(x11.env.WAYLAND_SOCKET, undefined);
assert.equal(x11.env.USER_FLAG, 'kept');

const wayland = buildHeadlessPlan({
  ...base, platform: 'linux', backend: 'wayland', runtimeDir: '/tmp/private-weston', socket: 'vv-test',
});
assert.equal(wayland.command, 'weston');
assert.ok(wayland.args.includes('--backend=headless'));
assert.ok(wayland.args.includes('--renderer=pixman'));
assert.ok(wayland.args.includes('--fake-seat'));
assert.equal(wayland.childCommand, '/electron');
assert.deepEqual(wayland.childArgs, base.args);
assert.equal(wayland.env.DISPLAY, undefined);
assert.equal(wayland.env.WAYLAND_DISPLAY, 'vv-test');
assert.equal(wayland.env.XDG_RUNTIME_DIR, '/tmp/private-weston');
assert.equal(wayland.env.VV_OZONE, 'wayland');

for (const platform of ['darwin', 'win32']) {
  const native = buildHeadlessPlan({ ...base, platform, backend: 'native' });
  assert.equal(native.command, '/electron');
  assert.deepEqual(native.args, base.args);
  assert.equal(native.env.VV_HEADLESS, '1');
  assert.equal(native.env.VV_HEADLESS_BACKEND, 'native');
  assert.equal(native.env.WAYLAND_DISPLAY, undefined);
  assert.equal(native.env.GDK_BACKEND, undefined);
}

const oldHeadless = process.env.VV_HEADLESS;
const oldBackend = process.env.VV_HEADLESS_BACKEND;
function loadWindowHelper(backend) {
  process.env.VV_HEADLESS = '1';
  process.env.VV_HEADLESS_BACKEND = backend;
  const id = require.resolve('./headless-window.js');
  delete require.cache[id];
  return require(id);
}

const nativeWindow = loadWindowHelper('native');
assert.deepEqual(nativeWindow.browserWindowOptions({ show: true, width: 600 }), {
  show: false, width: 600, paintWhenInitiallyHidden: true, skipTaskbar: true,
});
assert.equal(nativeWindow.isLiveWindow({ isDestroyed: () => false, isVisible: () => false,
  vvHeadlessActive: true }), true);
const x11Window = loadWindowHelper('x11');
assert.deepEqual(x11Window.browserWindowOptions({ show: true }), { show: true });
if (oldHeadless === undefined) delete process.env.VV_HEADLESS; else process.env.VV_HEADLESS = oldHeadless;
if (oldBackend === undefined) delete process.env.VV_HEADLESS_BACKEND;
else process.env.VV_HEADLESS_BACKEND = oldBackend;

console.log('headless-runner-smoke: Linux X11/Wayland + macOS/Windows native-hidden plans OK');
