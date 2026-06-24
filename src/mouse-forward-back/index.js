'use strict';
// Native X11 hook: report the mouse back/forward thumb buttons (X11 buttons 8/9) that Electron 3 on
// Linux otherwise swallows below the renderer. register(cb, win.getNativeWindowHandle()) calls
// cb('back') / cb('forward'). Degrades to a no-op if not on Linux or not yet built.
module.exports = { register: function () {} };
if (process.platform === 'linux') {
  try {
    var addon = require('./build/Release/mouse-forward-back.node');
    module.exports.register = function (callback, handle) {
      addon.register(function (btn) {
        if (btn === 8) callback('back');
        else if (btn === 9) callback('forward');
      }, handle);
    };
  } catch (e) { /* not built → register stays a no-op (thumb buttons inert; vmd unaffected) */ }
}
