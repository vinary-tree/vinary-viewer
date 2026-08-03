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

function isLiveWindow(win) {
  return Boolean(win && !win.isDestroyed()
    && (win.isVisible() || (enabled && win.vvHeadlessActive === true)));
}

module.exports = { enabled, browserWindowOptions, showForTest, isLiveWindow };
