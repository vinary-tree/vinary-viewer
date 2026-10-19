'use strict';
// docs/theory/12 §3 and §4.5. (1) The shifted grids of `copy-covers?`, with its cell formula. note-page-signature?
// stores each match's quotients ls / w, tx / tol and ty / tol, and copy-covers? floors each one less its shift, so
//   cell = (floor(ls / w - s0), floor(tx / tol - s1), floor(ty / tol - s2)),  w = ln(1 + 0.02),  s ∈ {0, 0.5}³
// in the same doubles (docs/scientific/14 Lemma 6). The string key below stands for `same-index?`: two cells' keys
// are equal exactly when their three indices are SameValueZero (docs/scientific/14 Lemma 7). It checks
// soundness (two matches in one cell agree within a cell width per axis), completeness (a set whose per-axis spread
// is below half a cell lies in one cell of some grid) and tightness (just above half a cell, all eight grids can
// split a set). (2) The page reader of vinary.slides.evidence (`runs-reader`, a 4-page cache evicting the OLDEST
// insertion) under CONFIRM's walk, to count page reads against `confirm-page-count`.
let failures = 0;
const check = (ok, msg) => { console.log((ok ? 'ok   ' : 'FAIL ') + msg); if (!ok) failures++; };
let seed = 20261017;
const rnd = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };

const w = Math.log(1 + 0.02);
const diag = Math.hypot(362.835, 272.126);
const tol = Math.max(1e-9, 0.005 * diag);
const shifts = [];
for (const a of [0, 0.5]) for (const b of [0, 0.5]) for (const c of [0, 0.5]) shifts.push([a, b, c]);
const cell = ([ls, tx, ty], [s0, s1, s2]) =>
  `${Math.floor(ls / w - s0)},${Math.floor(tx / tol - s1)},${Math.floor(ty / tol - s2)}`;

// soundness: any two points in one cell differ by less than a cell width on every axis
{
  let ok = true, pairs = 0;
  for (let t = 0; t < 200000 && ok; t++) {
    const p = [Math.log(0.05 + 0.4 * rnd()), 400 * rnd(), 300 * rnd()];
    const q = [p[0] + (rnd() - 0.5) * 2 * w, p[1] + (rnd() - 0.5) * 2 * tol, p[2] + (rnd() - 0.5) * 2 * tol];
    for (const s of shifts) {
      if (cell(p, s) !== cell(q, s)) continue;
      pairs++;
      if (!(Math.abs(p[0] - q[0]) < w && Math.abs(p[1] - q[1]) < tol && Math.abs(p[2] - q[2]) < tol)) ok = false;
      if (!(Math.exp(Math.abs(p[0] - q[0])) < 1.02)) ok = false;
    }
  }
  check(ok, `soundness: ${pairs} same-cell pairs, every one within ratio 1.02 in scale and tol in each offset`);
}

// completeness: random sets whose spread per axis is below half a cell, placed anywhere (including on boundaries)
{
  let ok = true;
  for (let t = 0; t < 200000 && ok; t++) {
    const base = [Math.log(0.05 + 0.4 * rnd()), 400 * rnd(), 300 * rnd()];
    if (t % 3 === 0) {                                  // put the set on a boundary of the unshifted grid
      base[0] = Math.round(base[0] / w) * w;
      base[1] = Math.round(base[1] / tol) * tol;
      base[2] = Math.round(base[2] / tol) * tol;
    }
    const spread = [w, tol, tol].map((x) => 0.4999 * x * rnd());
    const pts = Array.from({ length: 2 + Math.floor(6 * rnd()) },
      () => base.map((v, k) => v - spread[k] / 2 + spread[k] * rnd()));
    if (!shifts.some((s) => new Set(pts.map((p) => cell(p, s))).size === 1)) ok = false;
  }
  check(ok, 'completeness: 200,000 sets with per-axis spread < half a cell each lie in one cell of some grid');
}

// tightness: a spread a little above half a cell, straddling one boundary of each 1-D grid, is split by all eight
{
  const eps = 1e-6;
  const lo = [w / 2 - eps, tol / 2 - eps, tol / 2 - eps];
  const hi = [w + eps, tol + eps, tol + eps];       // spread w/2 + 2·eps on every axis
  const corners = [];
  for (const a of [lo[0], hi[0]]) for (const b of [lo[1], hi[1]]) for (const c of [lo[2], hi[2]]) corners.push([a, b, c]);
  check(shifts.every((s) => new Set(corners.map((p) => cell(p, s))).size > 1),
    'tightness: a set of spread w/2 + 2e-6 per axis is split by every one of the eight grids');
}

// ---- the page reader under CONFIRM ------------------------------------------------------------------------------------
function runsReader(pageRuns, cap) {
  const kept = new Map();
  let reads = 0;
  const read = (p) => {
    if (kept.has(p)) return Promise.resolve(kept.get(p));
    if (reads >= cap) return Promise.reject(new Error('too-many-pages'));
    reads++;
    return pageRuns(p).then((runs) => {
      kept.set(p, runs);
      if (kept.size > 4) kept.delete(kept.keys().next().value);
      return runs;
    });
  };
  return { read, reads: () => reads };
}
const confirmPairs = (n, notes) => {                    // vinary.slides.notes/confirm-pairs
  const acc = [];
  for (let j = 1, slide = 0; j < n; j++) { if (notes.has(j)) acc.push([slide, j]); else slide = j; }
  return acc;
};
async function confirmReads(n, notes, order) {
  const delay = (p) => (order === 'slide-first' ? 0 : order === 'note-first' ? 1 : Math.floor(3 * rnd()));
  const pageRuns = (p) => new Promise((res) => setTimeout(() => res({ p }),
    order === 'note-first' ? (notes.has(p) ? 0 : 2) : order === 'slide-first' ? (notes.has(p) ? 2 : 0) : delay(p)));
  const r = runsReader(pageRuns, 1e9);
  for (const [s, j] of confirmPairs(n, notes)) await Promise.all([r.read(s), r.read(j)]);
  return r.reads();
}
(async () => {
  const rows = [];
  for (const k of [1, 2, 3, 4, 5, 6, 8, 9, 12]) {
    const n = k + 1, notes = new Set(Array.from({ length: k }, (_, i) => i + 1));
    const distinct = new Set(confirmPairs(n, notes).flat()).size;
    const a = await confirmReads(n, notes, 'slide-first');
    const b = await confirmReads(n, notes, 'note-first');
    rows.push({ k, distinct, slideFirst: a, noteFirst: b });
  }
  for (const r of rows) console.log(`     slide with ${r.k} note pages: ${r.distinct} distinct pages; reads ${r.slideFirst} (slide's text first), ${r.noteFirst} (note's text first)`);
  check(rows.filter((r) => r.k <= 4).every((r) => r.slideFirst === r.distinct && r.noteFirst === r.distinct),
    'up to four note pages per slide, CONFIRM reads each page once, in either order');
  check(rows.filter((r) => r.k === 5).every((r) => r.slideFirst === r.distinct + 1),
    'with five, the slide\'s text read first, CONFIRM reads the slide twice: its reads exceed confirm-page-count');
  check(rows.every((r) => r.slideFirst <= 2 * r.k && r.noteFirst <= 2 * r.k),
    'in every row, CONFIRM reads at most two pages per note page');
  console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
  process.exit(failures ? 1 : 0);
})();
