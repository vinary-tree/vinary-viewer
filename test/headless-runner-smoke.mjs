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

// assertPainting — every latency harness's precondition (docs/scientific/10 §12). A fake window answers the frame
// count its renderer would; the real one runs COUNT_FRAMES, which must at least parse as a function expression.
assert.equal(typeof new Function(`return (${x11Window.COUNT_FRAMES});`)(), 'function');
assert.equal(x11Window.MIN_PAINTING_FRAMES, 5);
const fakeWindow = (answer, { visible = true, live } = {}) => ({
  id: 7, isDestroyed: () => false, isVisible: () => visible, vvHeadlessActive: live,
  webContents: { executeJavaScript: async (src) => { assert.match(src, /requestAnimationFrame/); return answer(); } },
});
assert.equal(await x11Window.assertPainting(fakeWindow(() => 18)), 18);
// the hidden warm-pool slot's signature: 0-1 frames however long it is polled
await assert.rejects(x11Window.assertPainting(fakeWindow(() => 1), 3),
                     /window #7 is not painting \(live=true, 1 animation frames in 300 ms; need >= 5\)/);
// painting but not the live window (an invisible window on X11)
await assert.rejects(x11Window.assertPainting(fakeWindow(() => 18, { visible: false }), 3), /live=false/);
// a window still booting passes on the first poll that paints, and no later one runs
let polls = 0;
assert.equal(await x11Window.assertPainting(fakeWindow(() => (++polls < 3 ? 2 : 12)), 5), 12);
assert.equal(polls, 3);
// native backend: hidden, but flagged live by the real main
assert.equal(await nativeWindow.assertPainting(fakeWindow(() => 18, { visible: false, live: true })), 18);
if (oldHeadless === undefined) delete process.env.VV_HEADLESS; else process.env.VV_HEADLESS = oldHeadless;
if (oldBackend === undefined) delete process.env.VV_HEADLESS_BACKEND;
else process.env.VV_HEADLESS_BACKEND = oldBackend;

console.log('headless-runner-smoke: Linux X11/Wayland + macOS/Windows native-hidden plans, painting precondition OK');
