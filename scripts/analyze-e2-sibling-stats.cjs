'use strict';
// Experiment 11, E2 / H3 (docs/scientific/11 §9.3): compare two bench-remote-read JSON files of the open-pdf /
// open-md readers — the baseline (sequential sibling stats) and the run with E2 (concurrent, overlapped) — cell by
// cell: the saving, the saving in round trips, and H3's prediction  after ≈ before − 12 R.  H3 is refuted by any
// cell with R ≥ 10 ms whose saving differs from 12 R by more than R; the last line counts such cells.
//
//   node scripts/analyze-e2-sibling-stats.cjs docs/scientific/data/11/e2-baseline.json \
//        docs/scientific/data/11/e2-after.json > docs/scientific/data/11/e2-analysis.txt
const fs = require('fs');
const [bPath, aPath] = process.argv.slice(2);
const load = (p) => JSON.parse(fs.readFileSync(p, 'utf8')).results;
const key = (r) => `${r.ident}|${r.rtt}|${r.size}|${r.reader}`;
const before = new Map(load(bPath).map((r) => [key(r), r]));
const rows = load(aPath);
console.log('ident    rtt  size  reader     base_ms  after_ms   saved_ms  saved/R  pred_after_ms  after/pred  |saved-12R|/R');
let refuted = 0;
for (const a of rows) {
  const b = before.get(key(a));
  if (!b) continue;
  const saved = b.wall.median - a.wall.median;
  const pred = b.wall.median - 12 * a.rtt;
  const dev = a.rtt > 0 ? Math.abs(saved - 12 * a.rtt) / a.rtt : NaN;
  if (a.rtt >= 10 && dev > 1) refuted += 1;
  console.log(`${a.ident.padEnd(8)} ${String(a.rtt).padStart(3)} ${a.size.padStart(5)}  ${a.reader.padEnd(9)}`
    + ` ${b.wall.median.toFixed(1).padStart(8)} ${a.wall.median.toFixed(1).padStart(9)} ${saved.toFixed(1).padStart(10)}`
    + ` ${(a.rtt > 0 ? (saved / a.rtt).toFixed(2) : '—').padStart(8)} ${(a.rtt > 0 ? pred.toFixed(1) : '—').padStart(14)}`
    + ` ${(a.rtt > 0 ? (a.wall.median / pred).toFixed(3) : '—').padStart(11)} ${(Number.isNaN(dev) ? '—' : dev.toFixed(2)).padStart(14)}`);
}
console.log(`\ncells with R >= 10 ms refuting H3 (|saved - 12R| > 1R): ${refuted}`);
