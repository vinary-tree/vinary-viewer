'use strict';

// vv-cli terminal-graphics smoke — proves the image pipeline END-TO-END through the built binary:
//   • a Markdown image (`![](pic.png)` → rewritten to a file:// URL by the shared pipeline) resolves to bytes
//     and encodes to a KITTY (ESC_G f=32) or SIXEL (ESC P) escape under `--graphics kitty|sixel`;
//   • `--no-graphics` (and a piped, non-TTY stdout) degrades to a labelled `🖼 name` placeholder — ZERO escapes;
//   • a standalone image file, and an SVG (rasterised at its INTRINSIC size, not blown to full width), both draw;
//   • a tall image is followed by its row-footprint newlines so following text never overprints it;
//   • unsupported formats (webp) and remote (http) srcs degrade to labelled placeholders, never a crash.
//
// The `--graphics` force flag is what makes this testable without a real kitty/sixel terminal (a piped stdout is
// not a TTY, so auto-detection yields no graphics — the flag bypasses the TERM/TTY gate). Fixtures are built with
// pngjs so the test needs no binary assets. Run: node test/graphics-smoke.js  (wired into `npm test` via test:cli).

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { PNG } = require('pngjs');
const jpeg = require('jpeg-js');
const { GifWriter } = require('omggif');

const ROOT = path.resolve(__dirname, '..');
const CLI = path.join(ROOT, 'dist', 'cli', 'vv-cli.js');
assert.ok(fs.existsSync(CLI), 'vv-cli.js must be built (npm run compile:cli) before the graphics smoke');

const KITTY = '_Ga=T';          // kitty per-image transmit header (continuation chunks carry only m=, so this counts IMAGES not chunks)
const SIXEL = 'P';           // sixel DCS introducer
const ST = '\\';             // string terminator
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-gfx-smoke-'));
let passed = 0;
function ok(cond, msg) { assert.ok(cond, msg); console.log('  ✓ ' + msg); passed++; }

// run the CLI. utf8 is safe: kitty (base64) + sixel escapes are all ASCII, ESC is 0x1b, and the `🖼` placeholder
// is UTF-8 — all decode cleanly (latin1 would instead mangle the emoji).
function run(args) {
  return execFileSync('node', [CLI, ...args], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}
function count(hay, needle) { let n = 0, i = 0; while ((i = hay.indexOf(needle, i)) !== -1) { n++; i += needle.length; } return n; }

function solidPng(file, w, h, [r, g, b]) {
  const p = new PNG({ width: w, height: h });
  for (let i = 0; i < w * h; i++) { const o = i * 4; p.data[o] = r; p.data[o + 1] = g; p.data[o + 2] = b; p.data[o + 3] = 255; }
  fs.writeFileSync(file, PNG.sync.write(p));
}

console.log('graphics-smoke: terminal image pipeline');

const pic = path.join(tmp, 'pic.png');   solidPng(pic, 24, 12, [200, 80, 40]);
const tall = path.join(tmp, 'tall.png'); solidPng(tall, 40, 80, [60, 160, 220]);
fs.writeFileSync(path.join(tmp, 'badge.svg'),
  '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="16"><rect width="32" height="16" fill="teal"/></svg>');
fs.writeFileSync(path.join(tmp, 'doc.md'), '# Doc\n\n![a picture](pic.png)\n\n![badge](badge.svg)\n');

// ── 1. Markdown images → kitty escapes (F1 file:// resolution + F2 block detection + F6 force flag) ──────────
const k = run(['--graphics', 'kitty', '--width', '40', path.join(tmp, 'doc.md')]);
ok(count(k, KITTY) === 2, 'two Markdown images (png + svg) each emit a kitty graphics escape');
ok(/_Ga=T,f=32,s=\d+,v=\d+,c=\d+,r=\d+,C=1/.test(k), 'kitty control header is well-formed (a=T,f=32,s,v,c,r,C=1)');
ok(k.includes(ST), 'kitty escapes are ST-terminated');

// ── 2. Markdown images → sixel ───────────────────────────────────────────────────────────────────────────
const x = run(['--graphics', 'sixel', '--width', '40', path.join(tmp, 'doc.md')]);
ok(count(x, SIXEL) === 2, 'two Markdown images each emit a sixel DCS escape under --graphics sixel');

// ── 3. --no-graphics degrades to a labelled placeholder, ZERO escapes ────────────────────────────────────
const none = run(['--no-graphics', path.join(tmp, 'doc.md')]);
ok(!none.includes(KITTY) && !none.includes(SIXEL), '--no-graphics emits ZERO image escapes');
ok(none.includes('🖼 pic.png') && none.includes('🖼 badge.svg'), '--no-graphics labels each image by name');
// piped stdout (this harness) is not a TTY → auto-detection must also yield no graphics
ok(!run([path.join(tmp, 'doc.md')]).includes(KITTY), 'piped (non-TTY) output auto-degrades — no escapes without --graphics');

// ── 4. standalone image file (F: openLocal has no image branch → CLI reads it directly) ──────────────────
ok(count(run(['--graphics', 'kitty', pic]), KITTY) === 1, 'a standalone image file draws one kitty escape');
ok(run(['--no-color', pic]).trim() === '🖼 pic.png', 'a standalone image, no graphics → just its placeholder (not raw bytes)');

// ── 4b. every supported raster format decodes to an escape (PNG above; JPEG + GIF here) ──────────────────
const jpgData = Buffer.alloc(16 * 8 * 4);
for (let i = 0; i < 16 * 8; i++) { jpgData[i * 4] = 180; jpgData[i * 4 + 1] = 90; jpgData[i * 4 + 2] = 30; jpgData[i * 4 + 3] = 255; }
const jpg = path.join(tmp, 'photo.jpg');
fs.writeFileSync(jpg, jpeg.encode({ data: jpgData, width: 16, height: 8 }, 85).data);
ok(count(run(['--graphics', 'kitty', jpg]), KITTY) === 1, 'a JPEG file decodes and draws (jpeg-js)');
const gif = path.join(tmp, 'anim.gif');
{ const gbuf = Buffer.alloc(2048);
  const gw = new GifWriter(gbuf, 16, 8, { palette: [0xff5028, 0x28a0dc] });
  gw.addFrame(0, 0, 16, 8, Array.from({ length: 16 * 8 }, (_, i) => (i % 3 === 0 ? 1 : 0)));
  fs.writeFileSync(gif, gbuf.slice(0, gw.end())); }
ok(count(run(['--graphics', 'kitty', gif]), KITTY) === 1, 'a GIF file decodes (first frame) and draws (omggif)');

// ── 5. SVG rasterised at INTRINSIC size, not blown to full width (F7) ────────────────────────────────────
const svgWide = run(['--graphics', 'kitty', '--width', '100', path.join(tmp, 'badge.svg')]);
const cols = Number((svgWide.match(/_Ga=T,f=32,s=\d+,v=\d+,c=(\d+)/) || [])[1]);
ok(cols > 0 && cols <= 8, `a 32px-wide SVG badge stays small (c=${cols} cols, not the full 100) — intrinsic sizing`);

// ── 6. tall image reserves its row footprint so following text can't overprint (F3) ──────────────────────
fs.writeFileSync(path.join(tmp, 'tall.md'), '![tall](tall.png)\n\nAFTER\n');
const t = run(['--graphics', 'kitty', '--width', '40', path.join(tmp, 'tall.md')]);
const rows = Number((t.match(/_Ga=T,f=32,s=\d+,v=\d+,c=\d+,r=(\d+)/) || [])[1]);
const between = t.slice(t.lastIndexOf(ST) + ST.length, t.indexOf('AFTER'));
ok(rows >= 3, `the tall image spans multiple rows (r=${rows})`);
ok((between.match(/\n/g) || []).length >= rows, `the image is followed by ≥ r newlines (${(between.match(/\n/g) || []).length}) reserving its height — no overprint`);

// ── 7. degradation: unsupported format + remote src → labelled placeholders, no crash ────────────────────
const webp = path.join(tmp, 'fake.webp');
fs.writeFileSync(webp, Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WEBP'), Buffer.alloc(64)]));
ok(run(['--graphics', 'kitty', webp]).includes('webp not supported'), 'an undecodable webp degrades with a format-labelled placeholder');
fs.writeFileSync(path.join(tmp, 'remote.md'), '![r](https://example.com/x.png)\n');
ok(run(['--graphics', 'kitty', path.join(tmp, 'remote.md')]).includes('remote image not fetched'),
   'an http(s) image is not fetched — a labelled placeholder (no network)');

console.log('\ngraphics-smoke: ' + passed + ' checks passed');
fs.rmSync(tmp, { recursive: true, force: true });
