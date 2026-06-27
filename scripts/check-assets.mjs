#!/usr/bin/env node
'use strict';

// Integrity check for the vendored static assets: every file recorded in
// scripts/assets.lock.json must exist under resources/public/assets/ and match
// its recorded sha256, and every url() in fonts.css must resolve to a vendored
// file. Run after `assets:sync` (or in CI) to catch partial copies / drift.
// Mirrors scripts/check-grammars.mjs.

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import crypto from 'node:crypto';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const assetsRoot = path.join(root, 'resources', 'public', 'assets');
const lockPath = path.join(root, 'scripts', 'assets.lock.json');

if (!fs.existsSync(lockPath)) {
  console.error('✗ scripts/assets.lock.json missing — run `npm run assets:sync`.');
  process.exit(1);
}

const lock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
const sha256 = buf => crypto.createHash('sha256').update(buf).digest('hex');

let failures = 0;

for (const f of lock.files) {
  const dest = path.join(assetsRoot, f.to);
  if (!fs.existsSync(dest)) {
    console.error(`✗ ${f.to}: missing (run \`npm run assets:sync\`)`);
    failures++;
    continue;
  }
  const hash = sha256(fs.readFileSync(dest));
  if (hash !== f.sha256) {
    console.error(`✗ ${f.to}: sha256 mismatch (expected ${f.sha256.slice(0, 12)}…, got ${hash.slice(0, 12)}…)`);
    failures++;
    continue;
  }
  console.log(`✓ ${f.to}`);
}

// fonts.css is hand-authored and tracked; verify its url() refs resolve to
// vendored files so a missing subset surfaces here rather than at runtime.
const fontsCss = path.join(assetsRoot, 'fonts', 'fonts.css');
if (fs.existsSync(fontsCss)) {
  const css = fs.readFileSync(fontsCss, 'utf8');
  for (const m of css.matchAll(/url\(\s*["']?\.\/([^"')]+)["']?\s*\)/g)) {
    const ref = path.join(assetsRoot, 'fonts', m[1]);
    if (!fs.existsSync(ref)) {
      console.error(`✗ fonts.css references a missing file: ${m[1]}`);
      failures++;
    } else {
      console.log(`✓ fonts.css → ${m[1]}`);
    }
  }
} else {
  console.error('✗ resources/public/assets/fonts/fonts.css missing');
  failures++;
}

console.log(failures ? `\n${failures} asset failure(s).` : `\n${lock.files.length} vendored asset(s) OK.`);
process.exit(failures ? 1 : 0);
