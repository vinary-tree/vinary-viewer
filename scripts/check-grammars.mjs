#!/usr/bin/env node
'use strict';

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { Parser, Language, Query } from 'web-tree-sitter';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const catalogPath = path.join(root, 'resources', 'grammars', 'catalog.edn');
const publicRoot = path.join(root, 'resources', 'public');

function readCatalog() {
  const edn = fs.readFileSync(catalogPath, 'utf8');
  const entries = [];
  const re = /\{:id "([^"]+)"[\s\S]*?:extensions \[([^\]]*)\][\s\S]*?:wasm-url "([^"]+)"[\s\S]*?:scm-url "([^"]+)"/g;
  for (const match of edn.matchAll(re)) {
    entries.push({
      id: match[1],
      extensions: [...match[2].matchAll(/"([^"]+)"/g)].map(m => m[1]),
      wasmUrl: match[3],
      scmUrl: match[4]
    });
  }
  return entries;
}

const entries = readCatalog();
if (!entries.length) {
  console.error('No grammar entries found in resources/grammars/catalog.edn');
  process.exit(1);
}

await Parser.init({
  locateFile() {
    return path.join(root, 'node_modules', 'web-tree-sitter', 'tree-sitter.wasm');
  }
});

let failures = 0;
for (const entry of entries) {
  const wasmPath = path.join(publicRoot, entry.wasmUrl);
  const scmPath = path.join(publicRoot, entry.scmUrl);
  const okFiles = fs.existsSync(wasmPath) && fs.existsSync(scmPath);
  if (!okFiles) {
    console.error(`✗ ${entry.id}: missing ${!fs.existsSync(wasmPath) ? entry.wasmUrl : entry.scmUrl}`);
    failures++;
    continue;
  }
  try {
    const language = await Language.load(wasmPath);
    const query = fs.readFileSync(scmPath, 'utf8');
    new Query(language, query);
    console.log(`✓ ${entry.id}: ${entry.extensions.join(', ')}`);
  } catch (error) {
    console.error(`✗ ${entry.id}: ${error.message || error}`);
    failures++;
  }
}

console.log(failures ? `\n${failures} grammar failure(s).` : `\n${entries.length} grammar(s) OK.`);
process.exit(failures ? 1 : 0);
