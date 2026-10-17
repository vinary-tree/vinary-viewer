'use strict';
// How long does the renderer take to derive a project's directory scopes (vinary.app.tree-state)? The micro-benchmark
// behind docs/scientific/10 §11's hypothesis H-R1: the Files tree derives them on every tree payload and every render,
// and for this repository that derivation was the renderer's longest task while typing in the Files filter.
//
//   npx shadow-cljs compile test && taskset -c 2 node scripts/bench-tree-scopes.cjs [--runs=40] [--repo=<dir>]
//
// It loads the COMPILED test build (dev-mode ClojureScript, as the renderer's dev build runs it) without running its
// tests — dist/test/test.js minus its last module, which is the test runner's main — and times, on the repository's
// own `git ls-files` listing:
//   definition   the union of file-directory-scopes over the files (what project-directory-scopes computed before)
//   derive       project-directory-scopes on a fresh project map (the index-scanning derivation, memo missed)
//   memo hit     directory-scopes on the same projects vector again (what an unchanged project now costs)
// Each case runs `--runs` times after 5 warm-up runs; the median, p95 and min are reported in milliseconds.

const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const opt = (k, d) => {
  const a = process.argv.find((x) => x.startsWith(`--${k}=`));
  return a ? a.slice(k.length + 3) : d;
};
const RUNS = Number(opt('runs', 40));
const REPO = path.resolve(opt('repo', ROOT));

const TEST_JS = path.join(ROOT, 'dist', 'test', 'test.js');
const RUNNER = 'SHADOW_IMPORT("shadow.module.main.append.js");';
const src = fs.readFileSync(TEST_JS, 'utf8').replace(/^#!.*\n/, '');
if (!src.includes(RUNNER)) throw new Error(`${TEST_JS} no longer ends with the test runner's module`);
vm.runInThisContext(`(function (require, module, exports, __dirname, __filename) {${src.replace(RUNNER, '')}\n})`,
  { filename: TEST_JS })(require, module, module.exports, path.dirname(TEST_JS), TEST_JS);

const cljs = global.cljs.core;
const ts = global.vinary.app.tree_state;
const tst = global.vinary.app.tree_state_test;
if (!ts || !tst || !tst.by_definition) throw new Error('compile the test build first: npx shadow-cljs compile test');

const files = cp.execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard'],
  { cwd: REPO, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).split('\n').filter(Boolean);
const kw = (s) => cljs.keyword(s);
const project = () => cljs.PersistentArrayMap.fromArray([kw('root'), REPO, kw('files'), cljs.vec(files)], true);

const time = (fn) => {
  for (let i = 0; i < 5; i++) fn();
  const xs = [];
  for (let i = 0; i < RUNS; i++) {
    const t0 = process.hrtime.bigint();
    fn();
    xs.push(Number(process.hrtime.bigint() - t0) / 1e6);
  }
  xs.sort((a, b) => a - b);
  const q = (p) => xs[Math.min(xs.length - 1, Math.ceil(p * xs.length) - 1)];   // nearest rank
  return { median: q(0.5), p95: q(0.95), min: xs[0] };
};

const p = project();
const definition = tst.by_definition(p);
const derived = ts.project_directory_scopes(project());
if (!cljs._EQ_(definition, derived)) throw new Error('the derivation disagrees with the definition on this listing');

const fixed = cljs.vector(project());
ts.directory_scopes(fixed);
const results = {
  definition: time(() => tst.by_definition(project())),
  derive:     time(() => ts.project_directory_scopes(project())),
  'memo hit': time(() => ts.directory_scopes(fixed)),
};

console.log(`repository   : ${REPO}`);
console.log(`files        : ${files.length}; directory scopes: ${cljs.count(definition)}`);
console.log(`node         : ${process.version}; runs ${RUNS} after 5 warm-ups; cpu affinity ${
  (() => { try { return cp.execFileSync('taskset', ['-cp', String(process.pid)], { encoding: 'utf8' }).trim(); } catch (_) { return 'unknown'; } })()}`);
for (const [k, r] of Object.entries(results)) {
  console.log(`${k.padEnd(12)} : median ${r.median.toFixed(3)} ms, p95 ${r.p95.toFixed(3)} ms, min ${r.min.toFixed(3)} ms`);
}
console.log(`speed-up (median, definition / derive): ${(results.definition.median / results.derive.median).toFixed(1)}×`);
if (process.env.VV_BENCH_JSON) fs.writeFileSync(process.env.VV_BENCH_JSON, JSON.stringify({ files: files.length, results }, null, 2));
