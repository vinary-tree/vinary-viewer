#!/usr/bin/env node
// Idempotently patch vmd's renderer/main.js so file history grows on EVERY new document, not just
// the first. vmd only pushes to its history inside handleLink (link clicks); the sidebar routes
// navigations via setFilePath (tree/image/link opens) and suppresses handleLink, so without this
// vmd's history never grows and the Back/Forward menu (Alt+Left/Right) stays disabled.
// Marker-guarded ([vmd-hist]) + backed up; a safe no-op (with a warning) if the anchor isn't found.
// The vmd package dir comes from the environment (VV_VMD_DIR, set by apply.sh).
const fs = require('fs');
const F = (process.env.VV_VMD_DIR || '') + '/renderer/main.js';
const BACKUP = F + '.vv.bak';

let s;
try { s = fs.readFileSync(F, 'utf8'); } catch (e) { process.exit(0); }   // vmd absent / mid-upgrade
if (s.indexOf('[vmd-hist]') !== -1) process.exit(0);                      // already patched

const ANCHOR = `    if (hist.current() === null) {
      hist.push({ filePath: data.filePath });
    }`;
const REPL = `    // [vmd-hist] grow history on every NEW file (tree/image/link opened via setFilePath), not just
    // the first, so the Back/Forward menu (Alt+Left/Right) + mouse work for all navigations. There is
    // no double-push for handleLink / back / forward — their target is already hist.current().
    const vmdHistCur = hist.current();
    if (!vmdHistCur || vmdHistCur.filePath !== data.filePath) {
      hist.push({ filePath: data.filePath });
    }`;

if (s.indexOf(ANCHOR) === -1) {
  console.error('[vmd-hist] anchor not found in renderer/main.js; left unchanged');
  process.exit(0);
}
try { fs.writeFileSync(BACKUP, s); } catch (e) { /* best-effort backup */ }
fs.writeFileSync(F, s.replace(ANCHOR, REPL));
console.log('[vmd-hist] renderer/main.js patched');
