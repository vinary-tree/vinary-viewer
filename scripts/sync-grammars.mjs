#!/usr/bin/env node
'use strict';

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const lockPath = path.join(root, 'scripts', 'grammars.lock.json');
const catalogPath = path.join(root, 'resources', 'grammars', 'catalog.edn');
const publicRoot = path.join(root, 'resources', 'public');
const grammarOutRoot = path.join(publicRoot, 'grammars');
const cacheRoot = path.join(root, '.cache', 'tree-sitter-grammars');
const buildCacheRoot = path.join(root, '.cache', 'tree-sitter-build');
const buildTimeoutMs = Number(process.env.TREE_SITTER_BUILD_TIMEOUT_MS || 180000);
const only = new Set((process.argv.find(arg => arg.startsWith('--only=')) || '')
  .replace(/^--only=/, '')
  .split(',')
  .map(s => s.trim())
  .filter(Boolean));

// Flags:
//   --verbose        print the per-command `$ …` echo and the child git/tree-sitter stdout. OFF by default —
//                    sync is quiet: one concise line per built grammar + a final summary. (Failures are ALWAYS
//                    surfaced; in quiet mode the captured child stderr is appended to the error message.)
//   --skip-existing  leave already-built grammars (grammar.wasm + highlights.scm both present) untouched: no
//                    network fetch, no wasm rebuild, no source.json re-pin. Makes re-installs fast + idempotent.
//   --allow-failures do NOT exit non-zero when some grammars fail to download/build. The per-grammar failures
//                    are still surfaced (and listed at the end), but the run exits 0 so a tolerant caller —
//                    install.sh — can skip the unavailable grammar(s) and finish the install instead of
//                    aborting. OFF by default: build/CI (release:cli, compile:cli, npm test) stay strict and
//                    still exit 1 on a broken grammar, so real regressions are caught.
// Also settable via GRAMMARS_VERBOSE=1 / GRAMMARS_SKIP_EXISTING=1 / GRAMMARS_ALLOW_FAILURES=1.
const cliFlags = new Set(process.argv.slice(2).filter(arg => arg.startsWith('--') && !arg.startsWith('--only=')));
const VERBOSE = cliFlags.has('--verbose') || process.env.GRAMMARS_VERBOSE === '1';
const SKIP_EXISTING = cliFlags.has('--skip-existing') || process.env.GRAMMARS_SKIP_EXISTING === '1';
const ALLOW_FAILURES = cliFlags.has('--allow-failures') || process.env.GRAMMARS_ALLOW_FAILURES === '1';

const lock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
const entries = (only.size ? lock.entries.filter(entry => only.has(entry.id)) : lock.entries)
  .filter(entry => entry.enabled !== false);

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function repoPath(...parts) {
  return path.resolve(root, ...parts);
}

function run(cmd, args, opts = {}) {
  if (VERBOSE) console.log(`$ ${[cmd, ...args].join(' ')}`);
  // Quiet (default) captures the child's stdout/stderr instead of inheriting the terminal, so git's
  // "From github.com/…" / "HEAD is now at …" chatter and tree-sitter's build log stay hidden. On failure the
  // captured stderr is re-attached to the thrown error (below) so a broken grammar is never silent.
  const capture = opts.capture || !VERBOSE;
  try {
    return execFileSync(cmd, args, {
      cwd: opts.cwd || root,
      env: opts.env || process.env,
      timeout: opts.timeout,
      stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
      encoding: capture ? 'utf8' : undefined
    });
  } catch (err) {
    if (capture && err.stderr) err.message = `${err.message}\n${String(err.stderr).trim()}`;
    throw err;
  }
}

function safeId(value) {
  return value.replace(/[^a-zA-Z0-9_.-]/g, '_');
}

function sourceDir(entry) {
  const source = entry.source;
  if (source.type === 'local') return repoPath(source.path);
  if (source.type === 'existing') return root;
  if (source.type !== 'git') throw new Error(`unsupported source type for ${entry.id}: ${source.type}`);

  ensureDir(cacheRoot);
  const dir = path.join(cacheRoot, safeId(entry.id));
  if (fs.existsSync(path.join(dir, '.git'))) {
    run('git', ['remote', 'set-url', 'origin', source.url], { cwd: dir });
    run('git', ['fetch', '--depth', '1', 'origin', source.ref || 'HEAD'], { cwd: dir });
    run('git', ['checkout', '--detach', 'FETCH_HEAD'], { cwd: dir });
  } else {
    const args = ['clone', '--depth', '1'];
    if (source.ref) args.push('--branch', source.ref);
    args.push(source.url, dir);
    run('git', args);
  }
  return dir;
}

function findGrammarDirs(dir) {
  const result = [];
  const skip = new Set(['.git', 'node_modules', 'target', 'build', 'dist']);
  function walk(current, depth) {
    if (depth > 4) return;
    for (const name of fs.readdirSync(current)) {
      if (skip.has(name)) continue;
      const child = path.join(current, name);
      const stat = fs.statSync(child);
      if (!stat.isDirectory()) continue;
      if (fs.existsSync(path.join(child, 'grammar.js'))) result.push(child);
      walk(child, depth + 1);
    }
  }
  if (fs.existsSync(path.join(dir, 'grammar.js'))) result.push(dir);
  walk(dir, 0);
  return [...new Set(result)];
}

function grammarDir(entry, srcDir) {
  if (entry.source.subdir) return path.join(srcDir, entry.source.subdir);
  const dirs = findGrammarDirs(srcDir);
  if (dirs.length === 1) return dirs[0];
  const names = dirs.map(dir => path.relative(srcDir, dir) || '.').join(', ');
  throw new Error(`cannot infer grammar directory for ${entry.id}; candidates: ${names}`);
}

function queryPath(entry, srcDir, gramDir) {
  if (entry.source.queryText) return { text: entry.source.queryText };
  if (entry.source.queryFile) return { path: repoPath(entry.source.queryFile) };
  const query = entry.source.query;
  const candidates = [
    query && path.resolve(srcDir, query),
    query && path.resolve(gramDir, query),
    path.join(gramDir, 'queries', 'highlights.scm'),
    path.join(srcDir, 'queries', 'highlights.scm')
  ].filter(Boolean);
  const found = candidates.find(candidate => fs.existsSync(candidate));
  if (!found) throw new Error(`missing highlights.scm for ${entry.id}`);
  return { path: found };
}

function writeQuery(query, outFile) {
  if (query.text != null) {
    fs.writeFileSync(outFile, query.text.endsWith('\n') ? query.text : `${query.text}\n`);
  } else {
    copyIfDifferent(query.path, outFile);
  }
}

function copyIfDifferent(from, to) {
  ensureDir(path.dirname(to));
  if (path.resolve(from) !== path.resolve(to)) fs.copyFileSync(from, to);
}

function sourceRev(srcDir) {
  // A meaningful rev exists only when srcDir belongs to a DIFFERENT git repo than this one: an upstream
  // grammar cloned into .cache/…, or a grammar sourced from a sibling project (e.g. metta ←
  // ../MeTTa-Compiler/tree-sitter-metta), where HEAD is that repo's real, stable rev. When srcDir is a plain
  // subdirectory of THIS (vinary-viewer) repo (tree-sitter-bnfc / tree-sitter-d2), `git rev-parse HEAD` just
  // echoes vinary-viewer's own HEAD — a self-referential value that re-pins on every commit (a churn
  // treadmill). Those grammars' versions are already tracked by this repo's history, so record null (as for
  // `existing` grammars), keeping source.json stable.
  try {
    const top = run('git', ['rev-parse', '--show-toplevel'], { cwd: srcDir, capture: true }).trim();
    if (fs.realpathSync(top) === fs.realpathSync(root)) return null;
    return run('git', ['rev-parse', 'HEAD'], { cwd: srcDir, capture: true }).trim();
  } catch (_) {
    return null;
  }
}

function copyLicense(entry, srcDir, outDir) {
  const names = ['LICENSE', 'LICENSE.md', 'LICENSE-MIT', 'COPYING'];
  const found = entry.source.type === 'existing'
    ? null
    : names.map(name => path.join(srcDir, name)).find(candidate => fs.existsSync(candidate));
  if (found) copyIfDifferent(found, path.join(outDir, path.basename(found)));
  fs.writeFileSync(
    path.join(outDir, 'source.json'),
    `${JSON.stringify({
      id: entry.id,
      language: entry.language,
      extensions: entry.extensions,
      source: entry.source,
      sourceRev: entry.source.type === 'existing' ? null : sourceRev(srcDir)
    }, null, 2)}\n`
  );
}

function buildWasm(entry, srcDir, gramDir, outFile) {
  const env = {
    ...process.env,
    XDG_CACHE_HOME: buildCacheRoot
  };
  if (!fs.existsSync(path.join(gramDir, 'src', 'parser.c'))) {
    run('tree-sitter', ['generate'], { cwd: gramDir, env, timeout: buildTimeoutMs });
  }
  run('tree-sitter', ['build', '--wasm', '--output', outFile, gramDir], { env, timeout: buildTimeoutMs });
}

function ednString(value) {
  if (Array.isArray(value)) return `[${value.map(ednString).join(' ')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.entries(value).map(([k, v]) => `:${k} ${ednString(v)}`).join(' ')}}`;
  }
  return JSON.stringify(value);
}

function catalogEdn(catalog) {
  return `[\n${catalog.map(entry => ednString(entry)).join('\n')}\n]\n`;
}

function readExistingCatalog() {
  if (!fs.existsSync(catalogPath)) return [];
  const text = fs.readFileSync(catalogPath, 'utf8');
  const entries = [];
  for (const [mapText] of text.matchAll(/\{[^\n]*\}/g)) {
    const field = name => {
      const match = mapText.match(new RegExp(`:${name} "([^"]*)"`));
      return match && match[1];
    };
    const extMatch = mapText.match(/:extensions \[([^\]]*)\]/);
    const id = field('id');
    if (!id) continue;
    entries.push({
      id,
      language: field('language') || id,
      extensions: extMatch ? [...extMatch[1].matchAll(/"([^"]+)"/g)].map(m => m[1]) : [],
      'wasm-url': field('wasm-url'),
      'scm-url': field('scm-url'),
      'source-kind': field('source-kind'),
      source: field('source')
    });
  }
  return entries;
}

function mergeCatalog(existing, updates) {
  const byId = new Map();
  for (const entry of existing) byId.set(entry.id, entry);
  for (const entry of updates) byId.set(entry.id, entry);

  const ordered = [];
  const seen = new Set();
  for (const entry of lock.entries.filter(entry => entry.enabled !== false)) {
    if (byId.has(entry.id)) {
      ordered.push(byId.get(entry.id));
      seen.add(entry.id);
    }
  }
  for (const entry of existing) {
    if (!seen.has(entry.id)) ordered.push(entry);
  }
  return ordered;
}

function catalogEntry(entry) {
  return {
    id: entry.id,
    language: entry.language,
    extensions: entry.extensions,
    'wasm-url': `grammars/${entry.id}/grammar.wasm`,
    'scm-url': `grammars/${entry.id}/highlights.scm`,
    'source-kind': entry.source.type,
    source: entry.source.url || entry.source.path || entry.source.wasm
  };
}

ensureDir(grammarOutRoot);
ensureDir(path.dirname(catalogPath));
ensureDir(path.join(publicRoot, 'js'));
copyIfDifferent(
  path.join(root, 'node_modules', 'web-tree-sitter', 'tree-sitter.wasm'),
  path.join(publicRoot, 'js', 'tree-sitter.wasm')
);

const catalog = [];
const failures = [];
let built = 0;
let cached = 0;
for (const entry of entries) {
  const outDir = path.join(grammarOutRoot, entry.id);
  const wasmOut = path.join(outDir, 'grammar.wasm');
  const scmOut = path.join(outDir, 'highlights.scm');

  // --skip-existing: an already-built grammar (wasm + query both present) is left untouched — no fetch, no
  // rebuild, no source.json re-pin. Its catalog entry is still emitted so the catalog stays complete.
  if (SKIP_EXISTING && fs.existsSync(wasmOut) && fs.existsSync(scmOut)) {
    catalog.push(catalogEntry(entry));
    cached += 1;
    if (VERBOSE) console.log(`\n== ${entry.id} == (present — skipped)`);
    continue;
  }

  if (VERBOSE) console.log(`\n== ${entry.id} ==`);
  else console.log(`  building ${entry.id}…`);
  try {
    ensureDir(outDir);

    if (entry.source.type === 'existing') {
      copyIfDifferent(repoPath(entry.source.wasm), wasmOut);
      copyIfDifferent(repoPath(entry.source.query), scmOut);
      copyLicense(entry, root, outDir);
    } else {
      const srcDir = sourceDir(entry);
      const gramDir = grammarDir(entry, srcDir);
      const scm = queryPath(entry, srcDir, gramDir);
      buildWasm(entry, srcDir, gramDir, wasmOut);
      writeQuery(scm, scmOut);
      copyLicense(entry, srcDir, outDir);
    }

    catalog.push(catalogEntry(entry));
    built += 1;
  } catch (error) {
    failures.push({ id: entry.id, error });
    console.error(`!! ${entry.id} failed: ${error.message || error}`);
  }
}

const finalCatalog = only.size ? mergeCatalog(readExistingCatalog(), catalog) : catalog;
fs.writeFileSync(catalogPath, catalogEdn(finalCatalog));

// Machine-readable outcome sidecar, written every run so a tolerant caller (install.sh, with --allow-failures)
// can report exactly which grammars were skipped — and tailor its remediation (e.g. the GitHub-PAT setup for
// the private `rholang` package, or the ../MeTTa-Compiler checkout for `metta`) — without parsing this stdout.
// Lives under the gitignored .cache/, so it never churns the tracked tree.
ensureDir(cacheRoot);
fs.writeFileSync(
  path.join(cacheRoot, 'last-sync.json'),
  `${JSON.stringify({
    built,
    cached,
    failed: failures.map(failure => ({ id: failure.id, message: String(failure.error && failure.error.message || failure.error) }))
  }, null, 2)}\n`
);

const failLabel = ALLOW_FAILURES ? 'skipped' : 'failed';
console.log(`\nwrote ${path.relative(root, catalogPath)} (${finalCatalog.length} grammar${finalCatalog.length === 1 ? '' : 's'}; ${built} built, ${cached} cached, ${failures.length} ${failLabel})`);
if (failures.length) {
  console.error(ALLOW_FAILURES ? '\nskipped grammars (continuing — these file types just miss syntax highlighting):' : '\nfailed grammars:');
  for (const failure of failures) console.error(`- ${failure.id}: ${failure.error.message || failure.error}`);
  // Strict by default so build/CI catch a genuinely broken grammar; --allow-failures downgrades to exit 0.
  if (!ALLOW_FAILURES) process.exit(1);
}
