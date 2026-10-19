'use strict';
// How long does ONE note-page comparison take (vinary.slides.notes/note-page-signature?)? The micro-benchmark behind
// docs/scientific/14 (H-PAIRWORK, theory/12 finding F11): the comparison runs synchronously on the calling thread —
// the renderer's, in the GUI — and its caps (max-signature-runs, max-signature-matches) were said to keep a hostile
// PDF from making it slow, without a measurement.
//
//   npx shadow-cljs compile test && taskset -c 3 node scripts/bench-note-signature.cjs [--runs=<k>] [--only=A,B,C,D]
//   npx shadow-cljs release cli  && taskset -c 3 node scripts/bench-note-signature.cjs --bundle=release
//
// --bundle=dev (default) loads the COMPILED test build (dev-mode ClojureScript, as the renderer's dev build runs it)
// without running its tests: dist/test/test.js minus its last module, the test runner's main. --bundle=release loads
// the :simple-compiled cli build, dist/cli/vv-cli.js, without calling its main: the code as the shipped bundles
// compile it. The cases (docs/scientific/14 §3.1):
//   A(n)  slide and note page of n upright runs "x" at one height (σ = 1 > max-thumbnail-scale): every pair of equal
//         strings is visited, and none counts toward max-signature-matches. n² visits, result false.
//   B     the note page's runs at σ = 0.1: the match cap trips (after (⌊cap / 4096⌋ + 1) · 4096 visits), result nil.
//   C     as many matches as the cap allows that no copy can cover (need 508 characters, 16 to be had):
//         copy-covers?'s eight grid passes run in full, result false.
//   D     a real-sized pair: 60 words and their thumbnail at σ = 0.25, plus 30 words of note text, result true.
// and three cases of one shape that aim at the cells' hashing (docs/scientific/14 §6.3, H-FLOOD and H-FAR): 16 slide
// runs "x" at height 10 at the origin, plus the run of 1000 "z" (need 508 is unreachable: every pass runs in full),
// and 4096 note runs "x", each placed so that its cell is one chosen triple (a, b, c) in all eight grids — height
// 10·1.02^(a + ¾), origin ((b + ¾)·tol, (c + ¾)·tol). The 16 slide runs share each cell: 65 536 matches, 4096 cells.
//   rand  the triples at random, within the packing range of the cell key (|index| ≤ 65535).
//   flood triples within the range whose packed keys share one bucket of a 4096-entry js/Map under V8's number hash
//         as this script models it (ComputeLongHash, Thomas Wang's 64-bit mix, of the double's bits), found by search.
//   far   triples whose x index lies beyond the range (100 000 … 2 000 000).
// --matches=<X> (a multiple of 16, at most the match cap in force) gives C, rand, flood and far X matches instead of
// the cap's: X/16 note runs. --cap=<X> rebinds max-signature-matches for the run (the comparison reads it at call
// time), so that a shape above the bundle's own cap can be timed.
// Two shapes of one kind aim at the grouping of the note page's strings (docs/scientific/14 §6.6, H-PAIRWORK-FIX″):
// both pages hold 4096 upright runs at height 10 (σ = 1: no match, result false), so only the grouping and the
// slide's look-ups are timed.
//   S-index  the strings String(k·2048): array indices whose values share their low 11 bits — one bucket of a
//            string-keyed js/Map in Electron's V8.
//   S-rand   String(k·2048 + u), u in [1, 2047] at random: distinct, the same lengths, random low bits.
// And four that aim at the text: both pages 4096 upright runs of ℓ = T/4096 code units, T = --chars (default the
// bundle's max-signature-chars, or 32 768 when it has none; the cap is raised to T for the run when below it).
//   L-prefix distinct strings with a common prefix of ℓ − 2 code units: every comparison reads the prefix.
//   L-equal  one string, repeated: every comparison reads all of it.
//   L-wide   as L-prefix with every code unit two-byte (U+043F …, U+0410 …).
//   L-mixed  as L-prefix with half the strings two-byte (a last unit at U+0100 …): a one-byte string against a
//            two-byte one is compared unit by unit (docs/scientific/14 §6.7, H-TEXT2).
// Last, four that load the three caps at once (docs/scientific/14 §6.8, H-COMBINED): X-prefix, X-equal, X-wide and X-mixed
// are L's pages at T with C's match block put in — slide runs 0 … 15 the string Xs at height 10, origin (20 i, 20 i),
// note runs 0 … M/16 − 1 Xs at height 1 (σ = 0.1) at C's random origins, M = --matches; Xs is the kind's prefix of
// ℓ − 2 units and two units no other string of the kind uses. 4096 runs and T code units a page, exactly M matches,
// and no copy covers (16 ℓ characters matched of about T): result false.
// Each case runs its warm-ups, then its timed runs; median, p95 (nearest rank), min and max in milliseconds.
// VV_BENCH_JSON=<path> writes every sample.

const cp = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const opt = (k, d) => {
  const a = process.argv.find((x) => x.startsWith(`--${k}=`));
  return a ? a.slice(k.length + 3) : d;
};
const BUNDLE = opt('bundle', 'dev');
const RUNS_SCALE = Number(opt('runs', 1));
const ONLY = opt('only', 'A,B,C,D').split(',');
const MATCHES_OPT = opt('matches', null);
const CAP_OPT = opt('cap', null);
const CHARS_OPT = opt('chars', null);

// ---- loading the compiled notes model --------------------------------------------------------------------------------

const loadDev = () => {
  const file = path.join(ROOT, 'dist', 'test', 'test.js');
  const runner = 'SHADOW_IMPORT("shadow.module.main.append.js");';
  const src = fs.readFileSync(file, 'utf8').replace(/^#!.*\n/, '');
  if (!src.includes(runner)) throw new Error(`${file} no longer ends with the test runner's module`);
  vm.runInThisContext(`(function (require, module, exports, __dirname, __filename) {${src.replace(runner, '')}\n})`,
    { filename: file })(require, module, module.exports, path.dirname(file), file);
  return { file, cljs: global.cljs.core, notes: global.vinary.slides.notes };
};

const loadRelease = () => {
  // A :simple release is one function scope that ends by calling the cli's main; that call is replaced by an export
  // of the two namespaces the benchmark needs, so loading the bundle runs its namespaces' top levels and nothing else.
  const file = path.join(ROOT, 'dist', 'cli', 'vv-cli.js');
  const main = 'cljs.core.apply.cljs$core$IFn$_invoke$arity$2(vinary.cli.core.main,process.argv.slice(2));';
  const src = fs.readFileSync(file, 'utf8').replace(/^#!.*\n/, '');
  if (src.includes('SHADOW_IMPORT(')) throw new Error(`${file} is a dev build: run npx shadow-cljs release cli`);
  if (src.split(main).length !== 2) throw new Error(`${file}: the cli's main call is not found exactly once`);
  const exported = 'global.__vvNoteSignatureBench = { cljs: cljs.core, notes: vinary.slides.notes };';
  vm.runInThisContext(`(function (require, module, exports, __dirname, __filename) {${src.replace(main, exported)}\n})`,
    { filename: file })(require, module, module.exports, path.dirname(file), file);
  return { file, ...global.__vvNoteSignatureBench };
};

const { file: BUNDLE_FILE, cljs, notes } = BUNDLE === 'release' ? loadRelease() : loadDev();
if (!notes || typeof notes.note_page_signature_QMARK_ !== 'function') {
  throw new Error(`vinary.slides.notes is not in ${BUNDLE_FILE}: compile it first (see the header)`);
}

// ---- the caps in force -----------------------------------------------------------------------------------------------

const BUNDLE_CAP = notes.max_signature_matches;
if (CAP_OPT !== null) {
  const x = Number(CAP_OPT);
  if (!(Number.isInteger(x) && x >= 16)) throw new Error('--cap must be an integer of at least 16');
  notes.max_signature_matches = x;
}
const CAP = notes.max_signature_matches;
const MATCHES = MATCHES_OPT === null ? Math.min(CAP, 65536) : Number(MATCHES_OPT);
if (!(Number.isInteger(MATCHES) && MATCHES % 16 === 0 && MATCHES >= 16 && MATCHES <= Math.min(CAP, 65536))) {
  throw new Error(`--matches must be a multiple of 16 in [16, ${Math.min(CAP, 65536)}] (the match cap in force, and ` +
                  '4096 note runs at most)');
}
const SHAPED_NOTE_RUNS = MATCHES / 16;
const BUNDLE_TEXT_CAP = typeof notes.max_signature_chars === 'number' ? notes.max_signature_chars : null;
const CHARS = CHARS_OPT === null ? (BUNDLE_TEXT_CAP === null ? 32768 : BUNDLE_TEXT_CAP) : Number(CHARS_OPT);
if (!(Number.isInteger(CHARS) && CHARS % 4096 === 0 && CHARS >= 2 * 4096)) {
  throw new Error('--chars must be a multiple of 4096, at least 8192');
}
if ((ONLY.includes('L') || ONLY.includes('X')) && BUNDLE_TEXT_CAP !== null && CHARS > BUNDLE_TEXT_CAP) {
  notes.max_signature_chars = CHARS;
}
const TEXT_CAP = BUNDLE_TEXT_CAP === null ? null : notes.max_signature_chars;

// ---- the pages -----------------------------------------------------------------------------------------------------

const kw = (s) => cljs.keyword(s);
const K_STR = kw('str');
const K_T = kw('t');
const vec = (xs) => cljs.PersistentVector.fromArray(xs, true);
// a run as vinary.slides.evidence/page-runs gives it: {:str s :t [a b c d e f]}, upright at height h, origin (x, y)
const run = (s, h, x, y) => cljs.PersistentArrayMap.fromArray([K_STR, s, K_T, vec([h, 0, 0, h, x, y])], true);
const page = (runs) => vec(runs);

const W = 362.835;                     // Beamer's 128 mm × 96 mm, in page units (bp)
const H = 272.126;
const DIAG = Math.hypot(W, H);

const mulberry32 = (seed) => () => {   // a seeded PRNG: the same pages on every run
  let t = (seed += 0x6D2B79F5);
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};

const grid = (n, h) => {               // n runs "x" at height h, on a grid over the page
  const cols = Math.ceil(Math.sqrt(n));
  const out = new Array(n);
  for (let i = 0; i < n; i++) out[i] = run('x', h, (i % cols) * (W / cols), Math.floor(i / cols) * (H / cols));
  return page(out);
};

const caseA = (n) => ({ slide: grid(n, 10), note: grid(n, 10), want: false, visits: n * n });
const caseB = () => ({ slide: grid(4096, 10), note: grid(4096, 1), want: null, visits: (Math.floor(CAP / 4096) + 1) * 4096 });
const caseC = () => {
  const rnd = mulberry32(14);
  const slide = [];
  for (let i = 0; i < 16; i++) slide.push(run('x', 10, 20 * i, 20 * i));
  slide.push(run('z'.repeat(1000), 10, 0, 250));
  const note = new Array(SHAPED_NOTE_RUNS);     // at --matches=65536, the 4096 runs of the first (pre-fix) runs
  for (let k = 0; k < SHAPED_NOTE_RUNS; k++) note[k] = run('x', 1, rnd() * W, rnd() * H);
  return { slide: page(slide), note: page(note), want: false, visits: 16 * SHAPED_NOTE_RUNS };
};

// ---- the shaped cases: each note run's cell chosen, the same in all eight grids ------------------------------------

const TOL = 0.005 * DIAG;                      // offset-tolerance × diag (the max with 1e-9 does not bind here)
const LOG_CELL = Math.log(1.02);               // ln(1 + scale-tolerance)
const PACK = 65535;                            // the cell key's packing range

// V8's hash of a number outside the int32 range, as this script models it: ComputeLongHash (Thomas Wang's 64-bit
// integer mix) of the double's bits, masked to a Smi. A js/Map's bucket is its low bits.
const F64 = new Float64Array(1);
const U64 = new BigUint64Array(F64.buffer);
const M64 = (1n << 64n) - 1n;
const v8NumberHash = (x) => {
  F64[0] = x;
  let h = U64[0];
  h = ((~h & M64) + (h << 18n)) & M64;
  h ^= h >> 31n;
  h = (h * 21n) & M64;
  h ^= h >> 11n;
  h = (h + (h << 6n)) & M64;
  h ^= h >> 22n;
  return Number(h & 0x3fffffffn);
};
const packed = (a, b, c) => (a + 65536) * 17179869184 + (b + 65536) * 131072 + (c + 65536);

const shapedTriples = (kind) => {
  const n = SHAPED_NOTE_RUNS;
  const rnd = mulberry32({ rand: 15, flood: 16, far: 17 }[kind]);
  const int = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));
  const seen = new Set();
  const out = [];
  // a js/Map of n entries has n / 2 buckets (V8's OrderedHashMap: capacity the least power of two ≥ n, two entries
  // per bucket); keys that share their low log2(n / 2) hash bits share a bucket at that size and at every smaller one
  const bucketMask = Math.max(1, n / 2) - 1;
  let draws = 0;
  while (out.length < n) {
    draws++;
    const a = int(-200, -48);
    const b = kind === 'far' ? int(100000, 2000000) : int(-60000, 60000);
    const c = int(-60000, 60000);
    const p = packed(a, b, c);
    if (seen.has(p)) continue;
    if (kind === 'flood' && (v8NumberHash(p) & bucketMask) !== 0) continue;
    seen.add(p);
    out.push([a, b, c]);
  }
  return { triples: out, draws, bucketMask };
};

const caseShaped = (kind) => () => {
  const t0 = process.hrtime.bigint();
  const { triples, draws, bucketMask } = shapedTriples(kind);
  const searchMs = Number(process.hrtime.bigint() - t0) / 1e6;
  const slide = [];
  for (let i = 0; i < 16; i++) slide.push(run('x', 10, 0, 0));
  slide.push(run('z'.repeat(1000), 10, 0, 250));
  // each note run's height and origin; run() writes exactly these doubles into its transform [h 0 0 h x y]
  const placed = triples.map(([a, b, c]) => [10 * Math.exp((a + 0.75) * LOG_CELL), (b + 0.75) * TOL, (c + 0.75) * TOL]);
  const note = placed.map(([h, x, y]) => run('x', h, x, y));
  // the cells, recomputed as the comparison computes them (σ = h / 10, t = note origin − σ · 0), checked in every grid
  const cells = new Set();
  triples.forEach(([a, b, c], k) => {
    const [h, x, y] = placed[k];
    const sigma = h / 10;
    if (!(sigma > 0 && sigma <= notes.max_thumbnail_scale)) throw new Error(`${kind}: note run ${k} has σ ${sigma}`);
    const q = [Math.log(sigma) / LOG_CELL, (x - sigma * 0) / TOL, (y - sigma * 0) / TOL];
    for (const s of [0, 0.5]) {
      const got = q.map((v) => Math.floor(v - s));
      if (got[0] !== a || got[1] !== b || got[2] !== c) {
        throw new Error(`${kind}: note run ${k} lands in (${got}) at shift ${s}, not (${a},${b},${c})`);
      }
    }
    const inRange = [a, b, c].every((v) => Math.abs(v) <= PACK);
    if (inRange !== (kind !== 'far')) throw new Error(`${kind}: note run ${k}'s cell is ${inRange ? 'inside' : 'outside'} the packing range`);
    cells.add(packed(a, b, c));
  });
  if (cells.size !== SHAPED_NOTE_RUNS) throw new Error(`${kind}: ${cells.size} distinct cells, not ${SHAPED_NOTE_RUNS}`);
  if (kind === 'flood') {
    const buckets = new Set(triples.map(([a, b, c]) => v8NumberHash(packed(a, b, c)) & bucketMask));
    if (buckets.size !== 1) throw new Error(`flood: the modelled hashes fill ${buckets.size} buckets, not 1`);
  }
  console.log(`${kind.padEnd(8)} : ${SHAPED_NOTE_RUNS} cells in ${(searchMs / 1000).toFixed(1)} s (${draws} draws; ` +
              `bucket mask ${bucketMask})`);
  return { slide: page(slide), note: page(note), want: false, visits: 16 * SHAPED_NOTE_RUNS };
};
const caseD = () => {
  const slide = [];
  const note = [];
  for (let i = 0; i < 60; i++) {
    const x = 20 + (i % 6) * 55;
    const y = 240 - Math.floor(i / 6) * 22;
    slide.push(run(`word${i}`, 10, x, y));
    note.push(run(`word${i}`, 2.5, 10 + 0.25 * x, 180 + 0.25 * y));    // the thumbnail: σ = 0.25 at (10, 180)
  }
  for (let i = 0; i < 30; i++) note.push(run(`note${i}`, 10, 20 + (i % 5) * 60, 150 - Math.floor(i / 5) * 22));
  return { slide: page(slide), note: page(note), want: true, visits: 60 };
};

// ---- the strings' shapes: S (the grouping) and L (the text) ---------------------------------------------------------

const textOf = (strs) => strs.reduce((n, x) => n + x.length, 0);
const stringsPage = (strs) => page(strs.map((x, i) => run(x, 10, (i % 64) * (W / 64), Math.floor(i / 64) * (H / 64))));
const checkText = (name, strs) => {
  const units = textOf(strs);
  if (TEXT_CAP !== null && units > TEXT_CAP) throw new Error(`${name}: ${units} code units, above the text cap ${TEXT_CAP}`);
  return units;
};
const caseS = (kind) => () => {
  const rnd = mulberry32(18);
  const strs = new Array(4096);
  for (let k = 0; k < 4096; k++) strs[k] = String(k * 2048 + (kind === 'rand' ? 1 + Math.floor(rnd() * 2047) : 0));
  if (new Set(strs).size !== 4096) throw new Error(`S-${kind}: the strings are not distinct`);
  if (kind === 'index' && !strs.every((x) => (Number(x) & 2047) === 0 && String(Number(x)) === x)) {
    throw new Error('S-index: a string is not an array index with its low 11 bits zero');
  }
  const units = checkText(`S-${kind}`, strs);
  console.log(`S-${kind.padEnd(6)} : 4096 strings, ${units} code units a page, first ${strs.slice(0, 3).join(', ')}`);
  return { slide: stringsPage(strs), note: stringsPage(strs), want: false, visits: 4096 };
};
const lStrings = (kind, n, len) => {   // L(kind)'s n strings of len code units, checked
  const strs = new Array(n);
  const prefix = 'p'.repeat(len - 2);
  const widePrefix = '\u043f'.repeat(len - 2);
  for (let k = 0; k < n; k++) {
    strs[k] = kind === 'equal' ? 'e'.repeat(len)
      : kind === 'wide' ? widePrefix + String.fromCharCode(0x0410 + (k >> 6), 0x0410 + (k & 63))
      : kind === 'mixed' ? prefix + String.fromCharCode(0x41 + (k >> 6), ((k & 1) ? 0x0100 : 0x41) + ((k >> 1) & 31))
      : prefix + String.fromCharCode(0x41 + (k >> 6), 0x41 + (k & 63));
  }
  if (kind !== 'equal' && new Set(strs).size !== n) throw new Error(`L-${kind}: the strings are not distinct`);
  const wide = strs.filter((x) => [...x].some((c) => c.charCodeAt(0) > 0xff)).length;
  const wantWide = { prefix: 0, equal: 0, wide: n, mixed: n / 2 }[kind];
  if (wide !== wantWide) throw new Error(`L-${kind}: ${wide} strings wider than one byte, not ${wantWide}`);
  return strs;
};
const caseL = (kind) => () => {
  const n = 4096;
  const len = CHARS / n;
  const strs = lStrings(kind, n, len);
  const units = checkText(`L-${kind}`, strs);
  console.log(`L-${kind.padEnd(6)} : ${n} runs of ${len} code units, ${units} a page (text cap in force ${TEXT_CAP})`);
  return { slide: stringsPage(strs), note: stringsPage(strs), want: false, visits: kind === 'equal' ? n * n : n };
};
const caseX = (kind) => () => {
  const n = 4096;
  const len = CHARS / n;
  const strs = lStrings(kind, n, len);
  const xs = kind === 'wide' ? '\u043f'.repeat(len - 2) + '\u040f\u040f'
    : (kind === 'equal' ? 'e' : 'p').repeat(len - 2) + '@@';
  if (strs.includes(xs)) throw new Error(`X-${kind}: the match block's string is one of L's`);
  const at = (x, i) => run(x, 10, (i % 64) * (W / 64), Math.floor(i / 64) * (H / 64));   // L's grid
  const rnd = mulberry32(14);                                                          // C's origins, in C's order
  const slideRuns = strs.map((x, i) => (i < 16 ? run(xs, 10, 20 * i, 20 * i) : at(x, i)));
  const noteRuns = new Array(n);
  for (let i = 0; i < n; i++) noteRuns[i] = i < SHAPED_NOTE_RUNS ? run(xs, 1, rnd() * W, rnd() * H) : at(strs[i], i);
  // the instrument's own count of what the caps see: runs, code units, and the pairs of equal strings at 0 < \u03c3 \u2264 0.4
  const plain = (r) => ({ s: cljs.get(r, K_STR), h: cljs.nth(cljs.get(r, K_T), 0) });
  const slidePlain = slideRuns.map(plain);
  const notePlain = noteRuns.map(plain);
  const heightsOf = new Map();
  for (const { s, h } of notePlain) heightsOf.set(s, [...(heightsOf.get(s) || []), h]);
  let pairs = 0;
  for (const { s, h } of slidePlain) {
    for (const g of heightsOf.get(s) || []) if (g / h > 0 && g / h <= notes.max_thumbnail_scale) pairs++;
  }
  const slideUnits = checkText(`X-${kind} slide`, slidePlain.map((r) => r.s));
  const noteUnits = checkText(`X-${kind} note`, notePlain.map((r) => r.s));
  if (slideRuns.length !== n || noteRuns.length !== n) throw new Error(`X-${kind}: not ${n} runs a page`);
  if (slideUnits !== CHARS || noteUnits !== CHARS) {
    throw new Error(`X-${kind}: ${slideUnits} and ${noteUnits} code units, not ${CHARS} a page`);
  }
  if (pairs !== MATCHES) throw new Error(`X-${kind}: ${pairs} pairs at a thumbnail's scale, not ${MATCHES}`);
  console.log(`X-${kind.padEnd(6)} : ${n} runs of ${len} code units a page, ${pairs} matches (cap in force ${CAP}; ` +
              `text cap in force ${TEXT_CAP})`);
  return { slide: page(slideRuns), note: page(noteRuns), want: false, visits: pairs };
};

const CASES = [
  ...['A'].flatMap(() => [512, 1024, 2048, 4096].map((n) => ({ name: `A(${n})`, group: 'A', build: () => caseA(n),
    warm: n >= 2048 ? 2 : 3, runs: { 512: 30, 1024: 20, 2048: 10, 4096: 10 }[n] }))),
  { name: 'B', group: 'B', build: caseB, warm: 3, runs: 30 },
  { name: 'C', group: 'C', build: caseC, warm: 3, runs: 15 },
  { name: 'D', group: 'D', build: caseD, warm: 50, runs: 500 },
  ...['rand', 'flood', 'far'].map((kind) => ({ name: kind, group: kind, build: caseShaped(kind), warm: 3, runs: 15 })),
  ...['index', 'rand'].map((kind) => ({ name: `S-${kind}`, group: 'S', build: caseS(kind), warm: 3, runs: 15 })),
  ...['prefix', 'equal', 'wide', 'mixed'].map((kind) => ({ name: `L-${kind}`, group: 'L', build: caseL(kind), warm: 3, runs: 15 })),
  ...['prefix', 'equal', 'wide', 'mixed'].map((kind) => ({ name: `X-${kind}`, group: 'X', build: caseX(kind), warm: 3, runs: 15 })),
].filter((c) => ONLY.includes(c.group));

// ---- timing --------------------------------------------------------------------------------------------------------

const sig = (c) => notes.note_page_signature_QMARK_(c.slide, c.note, DIAG);
const quantile = (xs, p) => xs[Math.min(xs.length - 1, Math.max(0, Math.ceil(p * xs.length) - 1))];   // nearest rank

const shell = (cmd, args) => { try { return cp.execFileSync(cmd, args, { encoding: 'utf8' }).trim(); } catch (_) { return 'unknown'; } };
const affinity = shell('taskset', ['-cp', String(process.pid)]);
const cpuOf = (/list: (\d+)/.exec(affinity) || [])[1];
const governor = cpuOf
  ? (() => { try { return fs.readFileSync(`/sys/devices/system/cpu/cpu${cpuOf}/cpufreq/scaling_governor`, 'utf8').trim(); } catch (_) { return 'unknown'; } })()
  : 'unknown';

console.log(`bundle       : ${BUNDLE} (${path.relative(ROOT, BUNDLE_FILE)})`);
console.log(`node         : ${process.version}; cpu ${os.cpus()[0].model}; affinity ${affinity}; governor ${governor}`);
console.log(`load average : ${os.loadavg().map((x) => x.toFixed(2)).join(' ')}`);
console.log(`page         : ${W} × ${H} (diag ${DIAG.toFixed(3)}); max-signature-runs ${notes.max_signature_runs}, ` +
            `max-signature-matches ${CAP}${CAP === BUNDLE_CAP ? '' : ` (rebound from the bundle's ${BUNDLE_CAP})`}, ` +
            `max-thumbnail-scale ${notes.max_thumbnail_scale}`);
console.log(`text cap     : max-signature-chars ${BUNDLE_TEXT_CAP === null ? 'none (the bundle has no text cap)' : TEXT_CAP}` +
            `${TEXT_CAP !== BUNDLE_TEXT_CAP ? ` (raised from the bundle's ${BUNDLE_TEXT_CAP} for the L shapes)` : ''}`);
console.log(`shaped cases : C, rand, flood and far with ${MATCHES} matches (${SHAPED_NOTE_RUNS} note runs); L with ${CHARS} ` +
            `code units a page; X with both`);

const results = {};
for (const c of CASES) {
  const data = c.build();
  const got = sig(data);
  if (got !== data.want) throw new Error(`${c.name}: the comparison returned ${got}, not ${data.want}`);
  for (let i = 0; i < c.warm; i++) sig(data);
  const runs = Math.max(1, Math.round(c.runs * RUNS_SCALE));
  const xs = [];
  for (let i = 0; i < runs; i++) {
    const t0 = process.hrtime.bigint();
    sig(data);
    xs.push(Number(process.hrtime.bigint() - t0) / 1e6);
  }
  const sorted = [...xs].sort((a, b) => a - b);
  const r = { visits: data.visits, result: got, runs, median: quantile(sorted, 0.5), p95: quantile(sorted, 0.95),
              min: sorted[0], max: sorted[sorted.length - 1], samples: xs };
  results[c.name] = r;
  console.log(`${c.name.padEnd(8)} : median ${r.median.toFixed(3)} ms, p95 ${r.p95.toFixed(3)} ms, ` +
              `min ${r.min.toFixed(3)} ms, max ${r.max.toFixed(3)} ms; ${runs} runs; ${data.visits} visits ` +
              `(${(r.median * 1e6 / data.visits).toFixed(1)} ns each at the median); result ${got}`);
}
const ratio = (a, b) => (results[a] && results[b] ? (results[a].median / results[b].median).toFixed(2) : 'n/a');
console.log(`scaling      : A(4096)/A(2048) ${ratio('A(4096)', 'A(2048)')}, A(2048)/A(1024) ${ratio('A(2048)', 'A(1024)')}, ` +
            `A(1024)/A(512) ${ratio('A(1024)', 'A(512)')}`);
if (process.env.VV_BENCH_JSON) {
  fs.writeFileSync(process.env.VV_BENCH_JSON, JSON.stringify({ bundle: BUNDLE, node: process.version, affinity,
    governor, page: { W, H, diag: DIAG }, matches: MATCHES, cap: CAP, bundleCap: BUNDLE_CAP, chars: CHARS,
    textCap: TEXT_CAP, bundleTextCap: BUNDLE_TEXT_CAP, results }, null, 2));
}
