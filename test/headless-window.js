'use strict';

// Renderer harnesses use this on macOS/Windows, where there is no separate virtual display server. Linux also
// sets VV_HEADLESS, but Xvfb/Weston already isolates its native windows. Keeping the behavior identical across
// platforms prevents a test from flashing a real window merely because it calls show() to force a paint.

// Xvfb/Weston are already isolated and need visible compositor surfaces for faithful layout/focus behavior.
// Suppress show() only for the macOS/Windows native analogue.
const enabled = process.env.VV_HEADLESS === '1' && process.env.VV_HEADLESS_BACKEND === 'native';

function browserWindowOptions(options = {}) {
  if (!enabled) return options;
  return { ...options, show: false, paintWhenInitiallyHidden: true, skipTaskbar: true };
}

function showForTest(win) {
  if (!enabled && win && !win.isDestroyed()) win.show();
}

// The app's live window — never the real main's hidden warm-pool slot (VV_POOL, default 1), which
// BrowserWindow.getAllWindows() lists FIRST. Pick a harness's window with this, not with `getAllWindows()[0]`.
function isLiveWindow(win) {
  return Boolean(win && !win.isDestroyed()
    && (win.isVisible() || (enabled && win.vvHeadlessActive === true)));
}

// Renderer-side source of an async function: how many animation frames the page composites in 300 ms. A setTimeout
// bounds the wait, so a window whose frames never come still answers. A painting window at 60 Hz gives ~18; a
// hidden Linux window (not drawn — the warm-pool slot) gives 0 or 1, however long it is polled.
const COUNT_FRAMES = `async () => {
  let frames = 0;
  const t0 = performance.now();
  await new Promise((done) => {
    const step = () => { frames++; if (performance.now() - t0 < 300) requestAnimationFrame(step); else done(); };
    requestAnimationFrame(step);
    setTimeout(done, 400);
  });
  return frames;
}`;

// Fewest frames in 300 ms that count as painting: well above a hidden window's 0-1, well below 60 Hz's ~18, so a
// loaded machine dropping frames still passes.
const MIN_PAINTING_FRAMES = 5;

// The precondition of any measurement of what a user sees: `win` is the live window and composites frames. A window
// still booting paints slowly, so this polls (`tries` × ~300 ms) and resolves to the first passing frame count; it
// rejects, naming both facts, when it is still not painting at the last poll — a measurement there would be of no
// window a user sees.
async function assertPainting(win, tries = 10) {
  let frames = 0;
  for (let i = 0; i < tries; i++) {
    frames = await win.webContents.executeJavaScript(`(${COUNT_FRAMES})()`, true);
    if (isLiveWindow(win) && frames >= MIN_PAINTING_FRAMES) return frames;
  }
  throw new Error(`window #${win.id} is not painting (live=${isLiveWindow(win)}, ${frames} animation frames in ` +
                  `300 ms; need >= ${MIN_PAINTING_FRAMES})`);
}

module.exports = { enabled, browserWindowOptions, showForTest, isLiveWindow, COUNT_FRAMES, MIN_PAINTING_FRAMES,
                   assertPainting };
