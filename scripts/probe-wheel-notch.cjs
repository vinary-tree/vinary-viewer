'use strict';
// What does ONE mouse-wheel notch deliver to Chromium (Electron) as a DOM WheelEvent, at several window zoom factors?
// The measurement behind vinary.slides.deck/wheel-notches (docs/scientific/12 §8.7): a slide flips once the wheel's
// normalized delta reaches pdf.js's threshold, so the size of one notch decides how many notches a flip takes.
//
//   node scripts/run-electron-headless.mjs --backend=x11 electron --no-sandbox scripts/probe-wheel-notch.cjs | tee …
//
// X11 only: xdotool presses button 5 (down) and 4 (up) on the private Xvfb display the headless runner starts —
// the X server's own wheel events, the ones a mouse sends. It prints one line, `WHEEL-PROBE {json}`: per zoom factor,
// every event's deltaY / deltaMode / wheelDeltaY, and window.devicePixelRatio. The app is not involved: a bare
// BrowserWindow with a private userData directory, so nothing touches the developer's configuration.
const { app, BrowserWindow } = require('electron');
const cp = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const USERDATA = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-wheel-probe-'));
app.setPath('userData', USERDATA);
const ZOOMS = [1, 1.25, 1.5, 0.8];
const NOTCHES = 3;

const PAGE = `<!doctype html><meta charset=utf-8><body style="margin:0;height:300vh">
<script>
window.events = [];
addEventListener('wheel', (e) => { events.push({ dx: e.deltaX, dy: e.deltaY, mode: e.deltaMode, wdy: e.wheelDeltaY });
  e.preventDefault(); }, { passive: false });
</script></body>`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const xdo = (...args) => cp.execFileSync('xdotool', args, { stdio: ['ignore', 'pipe', 'pipe'] }).toString();

app.whenReady().then(async () => {
  const win = new BrowserWindow({ x: 0, y: 0, width: 400, height: 300, show: true, frame: false });
  await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(PAGE));
  await sleep(800);
  win.focus();
  const results = { display: process.env.DISPLAY, electron: process.versions.electron, chrome: process.versions.chrome };
  let code = 0;
  try {
    xdo('mousemove', '--sync', '200', '150');
    for (const zoom of ZOOMS) {
      win.webContents.setZoomFactor(zoom);
      await sleep(300);
      for (const [label, button] of [['down', '5'], ['up', '4']]) {
        await win.webContents.executeJavaScript('window.events = []; true');
        for (let i = 0; i < NOTCHES; i++) { xdo('click', button); await sleep(250); }
        await sleep(400);
        const events = await win.webContents.executeJavaScript('window.events');
        results[`zoom ${zoom} ${label}`] = events.map((e) => `dy ${e.dy} mode ${e.mode} wdy ${e.wdy}`);
      }
      results[`zoom ${zoom} devicePixelRatio`] = await win.webContents.executeJavaScript('window.devicePixelRatio');
    }
  } catch (e) {
    results.error = String((e && e.message) || e);
    code = 1;
  }
  console.log('WHEEL-PROBE ' + JSON.stringify(results));
  fs.rmSync(USERDATA, { recursive: true, force: true });
  app.exit(code);
});
