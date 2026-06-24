# 6 — The native mouse-button addon

Most mice have two extra **thumb buttons** for back/forward. Making them navigate `vmd`'s file history
turns out to require a small native addon. This chapter explains why, and how the addon works.

> The addon is **optional**. If it isn't built, the thumb buttons are simply inert; keyboard
> (`Alt`+`←`/`→`) and on-screen navigation are unaffected.

## Why the thumb buttons need native code

On Linux/X11 the thumb buttons are **pointer buttons 8 and 9**. In `vmd`'s Electron (Electron 3) on
Linux, **Chromium consumes those buttons below the JavaScript layer**:

- No DOM event reaches the renderer — there is no `mousedown`/`auxclick` for buttons 8/9.
- Electron 3 does not emit an `app-command` for them either (that path was added in later Electron).

So no amount of renderer- or main-process JavaScript can observe them. (This was confirmed empirically:
in `vmd`'s DevTools, the left/right/middle buttons produce events but the thumb buttons produce nothing.)
The only way to catch them is **below Chromium, at the X server** — which is what the addon does.

## What the addon does

`src/mouse-forward-back/` is a tiny native Node addon (vendored from
[`mouse-forward-back`](https://github.com/jostrander/mouse-forward-back), MIT). Its public API is one
function:

```js
require('…/mouse-forward-back').register(callback, win.getNativeWindowHandle());
// callback('back')  when button 8 is released
// callback('forward') when button 9 is released
```

Internally it:

1. Opens its own connection to the X display.
2. Establishes a **passive button grab** on `vmd`'s window for buttons 8 and 9:

   ```c
   XGrabButton(display, 8 /* and 9 */, AnyModifier, window, True,
               ButtonPressMask | ButtonReleaseMask,
               GrabModeAsync, GrabModeAsync, None, None);
   ```

3. Runs an event loop (`XNextEvent`) on a background thread; on a `ButtonRelease` for 8 or 9 it signals
   the Node event loop (`uv_async_send`), which invokes `callback('back'|'forward')`.

The main-process `[vmd-mfb]` patch wires that callback to `vmd`'s history channels:

```js
require('…/mouse-forward-back').register((dir) => {
  if (win && !win.isDestroyed())
    win.webContents.send(dir === 'back' ? 'history-back' : 'history-forward');
}, win.getNativeWindowHandle());
```

So a thumb-button press becomes the very same `history-back`/`history-forward` IPC that the menu and the
on-screen paths use (see [07 — History navigation](07-history-navigation.md)).

### Grab scope

`XGrabButton` here is a **passive grab on `vmd`'s window**, so it activates only while the pointer is over
that window. Pressing the thumb buttons anywhere else behaves normally. In practice this means the buttons
navigate `vmd`'s history exactly when you're pointing at `vmd` — which is what you want — and never
interfere with other applications. The grab coexists with Chromium's own input handling (no `BadAccess`
conflict), so building it has no side effects on the rest of `vmd`.

## Building it for `vmd`'s Electron ABI

A native Node addon is compiled against a specific **ABI** (`NODE_MODULE_VERSION`). The addon must match
`vmd`'s **Electron** ABI, *not* your system Node's. The build therefore targets `vmd`'s bundled Electron:

```sh
node-gyp rebuild --target=<vmd's electron version> --arch=x64 \
                 --dist-url=https://electronjs.org/headers
```

`apply.sh` (and `npm run build:addon`) detect the version automatically from
`<vmd-pkg>/node_modules/electron/package.json` and build once if `build/Release/mouse-forward-back.node`
is absent. Requirements: a C++ toolchain, `node-gyp`, and **libX11 development headers** (the addon links
`-lX11`). The addon's own `package.json` pins `nan` (the Node addon C++ helpers) and is built with
`--ignore-scripts` so the wrong-ABI auto-build never runs.

> If the build can't run (no toolchain/headers), the `[vmd-mfb]` patch's `try/catch` simply leaves the
> register call a no-op — `vmd` is unaffected and the thumb buttons stay inert.

## Why it isn't shipped pre-built

The compiled `.node` is specific to an OS, architecture, and Electron ABI, so a checked-in binary would be
wrong for most users and would silently fail to load. `vinary-viewer` ships only the **sources** and
builds locally at install time, guaranteeing an ABI match with whatever `vmd`/Electron you have.

## Verifying

```sh
ls ~/.local/share/vinary-viewer/mouse-forward-back/build/Release/mouse-forward-back.node
```

If present, launch `vmd`, hover the window, and press a thumb button — the document should navigate. If
absent, run `npm run build:addon` from the repository and check the toolchain/header requirements above.
