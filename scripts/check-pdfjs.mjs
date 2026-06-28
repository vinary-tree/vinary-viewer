#!/usr/bin/env node
'use strict';

// Verify the vendored PDF.js assets (resources/public/pdf/) match scripts/pdfjs.lock.json,
// catching an un-synced tree in CI / `npm run` checks. Exit non-zero on any drift.
// Mirrors scripts/check-grammars.mjs / check-assets.mjs.

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import crypto from 'node:crypto';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const pdfOutRoot = path.join(root, 'resources', 'public', 'pdf');
const lockPath = path.join(root, 'scripts', 'pdfjs.lock.json');

if (!fs.existsSync(lockPath)) {
  console.error('✗ scripts/pdfjs.lock.json missing — run `npm run pdfjs:sync`.');
  process.exit(1);
}

const lock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

let failures = 0;
for (const f of lock.files) {
  const dest = path.join(pdfOutRoot, f.to);
  if (!fs.existsSync(dest)) {
    console.error(`✗ missing: pdf/${f.to}`);
    failures++;
    continue;
  }
  if (sha256(fs.readFileSync(dest)) !== f.sha256) {
    console.error(`✗ drift: pdf/${f.to}`);
    failures++;
  }
}

console.log(failures
  ? `\n${failures} pdf.js asset failure(s) — run \`npm run pdfjs:sync\`.`
  : `\n${lock.files.length} pdf.js asset(s) OK (pdfjs-dist@${lock.version}).`);
process.exit(failures ? 1 : 0);
