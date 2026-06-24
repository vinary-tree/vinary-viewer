#!/usr/bin/env node
// Idempotently patch vmd's create-window.js with three changes, each marker-guarded + backed up:
//   [vmd-img] render image files (PNG/JPG/GIF/SVG/WebP/…) as a full-width <img> (vmd otherwise
//             reads them as UTF-8 text). The view is a bare <div class="vmd-image-view"> — NOT
//             .markdown-body — so vmd's markdown path can't inherit the class onto the next doc.
//   [vmd-nav] forward OS-level app-command back/forward to the renderer's history (some mice/WMs
//             deliver the thumb buttons as an app-command). sidebar.js handles DOM mouse events.
//   [vmd-mfb] register the native X11 hook that grabs the mouse back/forward thumb buttons
//             (Electron 3 on Linux swallows them) and forwards them to the same history channels.
// Paths come from the environment (set by apply.sh): VV_VMD_DIR = vmd's package dir, VV_HOME =
// the vinary-viewer install dir. Each patch is a safe no-op (with a warning) if its anchor isn't
// found — e.g. a future vmd that rewrote these blocks. String.raw keeps the /\.html?$/ regex literal.
const fs = require('fs');
const VV_VMD_DIR = process.env.VV_VMD_DIR || '';
const VV_HOME = process.env.VV_HOME || '';
const F = VV_VMD_DIR + '/main/create-window.js';
const BACKUP = F + '.vv.bak';

let s;
try { s = fs.readFileSync(F, 'utf8'); } catch (e) { process.exit(0); }   // vmd absent / mid-upgrade
const orig = s;

// ---- Patch 1: [vmd-img] image rendering ----
if (s.indexOf('[vmd-img]') === -1) {
  const ANCHOR = String.raw`      const contents = fromFile
        ? fs.readFileSync(windowOptions.filePath, { encoding: 'utf8' })
        : windowOptions.contents;

      win.webContents.send('md', {
        filePath: windowOptions.filePath,
        isHTML: windowOptions.filePath && /\.html?$/.test(windowOptions.filePath),
        baseUrl,
        contents,
      });`;
  const REPL = String.raw`      // [vmd-img] render image/SVG files as a full-width <img> (vmd otherwise reads them as text)
      const vmdImgExt = ['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp', '.ico', '.apng', '.avif'];
      const vmdIsImage = fromFile
        && vmdImgExt.indexOf(path.extname(windowOptions.filePath).toLowerCase()) >= 0;
      const contents = vmdIsImage
        ? '<div class="vmd-image-view"><img src="' + encodeURI('file://' + path.resolve(windowOptions.filePath)) + '" alt=""></div>'
        : (fromFile
          ? fs.readFileSync(windowOptions.filePath, { encoding: 'utf8' })
          : windowOptions.contents);

      win.webContents.send('md', {
        filePath: windowOptions.filePath,
        isHTML: vmdIsImage || (windowOptions.filePath && /\.html?$/.test(windowOptions.filePath)),
        baseUrl,
        contents,
      });`;
  if (s.indexOf(ANCHOR) !== -1) s = s.replace(ANCHOR, REPL);
  else console.error('[vmd-img] anchor not found in create-window.js; image rendering left unpatched');
}

// ---- Patch 2: [vmd-nav] app-command → renderer history ----
if (s.indexOf('[vmd-nav]') === -1) {
  const ANCHOR = String.raw`  win.webContents.on('did-finish-load', sendMarkdown);`;
  const REPL = String.raw`  win.webContents.on('did-finish-load', sendMarkdown);

  // [vmd-nav] forward OS-level back/forward commands (some mice / window managers deliver the
  // thumb buttons as an app-command rather than as DOM mouse events) to the renderer's file
  // history. sidebar.js listens for 'history-back'/'history-forward' → navBack/navForward.
  win.on('app-command', (ev, cmd) => {
    if (cmd === 'browser-backward') win.webContents.send('history-back');
    else if (cmd === 'browser-forward') win.webContents.send('history-forward');
  });`;
  if (s.indexOf(ANCHOR) !== -1) s = s.replace(ANCHOR, REPL);
  else console.error('[vmd-nav] anchor not found in create-window.js; app-command nav left unpatched');
}

// ---- Patch 3: [vmd-mfb] native X11 hook for the mouse back/forward thumb buttons ----
// (anchors on the [vmd-nav] app-command block that Patch 2 just inserted into `s`)
if (s.indexOf('[vmd-mfb]') === -1) {
  const ANCHOR = String.raw`  win.on('app-command', (ev, cmd) => {
    if (cmd === 'browser-backward') win.webContents.send('history-back');
    else if (cmd === 'browser-forward') win.webContents.send('history-forward');
  });`;
  const REPL = ANCHOR + '\n' + String.raw`
  // [vmd-mfb] map the mouse back/forward thumb buttons (X11 buttons 8/9). Electron 3 on Linux
  // swallows them before the renderer (no DOM event / app-command), so a tiny native X11 hook
  // grabs them on this window and forwards to the same history channels the menu/mouse use.
  try {
    require('` + VV_HOME + `/mouse-forward-back').register((dir) => {
      if (win && !win.isDestroyed()) win.webContents.send(dir === 'back' ? 'history-back' : 'history-forward');
    }, win.getNativeWindowHandle());
  } catch (e) { /* addon missing/unbuilt → thumb buttons simply inert; vmd unaffected */ }`;
  if (s.indexOf(ANCHOR) !== -1) s = s.replace(ANCHOR, REPL);
  else console.error('[vmd-mfb] anchor not found in create-window.js; thumb-button hook left unpatched');
}

if (s !== orig) {
  try { fs.writeFileSync(BACKUP, orig); } catch (e) { /* best-effort backup */ }
  fs.writeFileSync(F, s);
  console.log('[vmd-sidebar] create-window.js patched (image view + app-command nav + thumb-button hook)');
}
