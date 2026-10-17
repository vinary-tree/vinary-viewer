'use strict';
// Pool and compare bench-slide-flip runs (docs/scientific/12 §Flip latency): per build, the pooled distribution of
// every measure, the per-run medians (run-to-run spread), and — against a reference build — the difference of the
// pooled medians with a bootstrap 95 % percentile interval (B = 10 000 resamples, seeded, so it reproduces exactly).
//
//   node scripts/analyze-flip-runs.cjs --ref=A docs/scientific/data/12/flip-ABC-*.json | tee analysis.txt
//
// A file's build is the letter before its run number: flip-ABC-<build>-<run>.json.

const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const ref = ((args.find((a) => a.startsWith('--ref=')) || '--ref=A').slice(6));
const files = args.filter((a) => !a.startsWith('--')).sort();
if (!files.length) throw new Error('usage: analyze-flip-runs.cjs --ref=A <flip-*.json>…');

// mulberry32: a small, well-mixed 32-bit PRNG — the seed makes the bootstrap reproducible
function prng(seed) {
  let t = seed >>> 0;
  return () => {
    t = (t + 0x6D2B79F5) >>> 0;
    let x = Math.imul(t ^ (t >>> 15), t | 1);
    x ^= x + Math.imul(x ^ (x >>> 7), x | 61);
    return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
  };
}
// nearest-rank quantile: the ⌈p·n⌉-th smallest sample (as the benchmark itself reports)
const quantile = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))];
const median = (xs) => quantile([...xs].sort((a, b) => a - b), 0.5);
const summary = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  return { n: s.length, min: s[0], median: quantile(s, 0.5), p95: quantile(s, 0.95), max: s[s.length - 1] };
};
function bootstrapMedianDiff(a, b, resamples = 10000, seed = 12) {
  const rnd = prng(seed);
  const draw = (xs) => { const out = new Array(xs.length); for (let i = 0; i < xs.length; i++) out[i] = xs[Math.floor(rnd() * xs.length)]; return out; };
  const diffs = new Array(resamples);
  for (let r = 0; r < resamples; r++) diffs[r] = median(draw(a)) - median(draw(b));
  diffs.sort((x, y) => x - y);
  return { diff: median(a) - median(b), lo: quantile(diffs, 0.025), hi: quantile(diffs, 0.975) };
}

const runs = files.map((f) => {
  const m = path.basename(f).match(/-([A-Z])-(\d+)\.json$/);
  if (!m) throw new Error(`cannot tell the build of ${f}`);
  return { file: path.basename(f), build: m[1], run: Number(m[2]), report: JSON.parse(fs.readFileSync(f, 'utf8')) };
});
const builds = [...new Set(runs.map((r) => r.build))].sort();
const MEASURES = [
  ['keyToFrameMs', 'key → frame that shows it'],
  ['keyToRenderedMs', 'key → that frame rendered'],
  ['sendToRenderedMs', 'send → rendered (end to end)'],
  ['toScrollMs', 'dispatch → scrollTop written'],
];
const fmt = (x) => (x === undefined || x === null || Number.isNaN(x) ? '—' : x.toFixed(1));
const pooled = (build, kind, key) => runs.filter((r) => r.build === build)
  .flatMap((r) => r.report[kind].map((m) => m[key]).filter((v) => typeof v === 'number'));

console.log(`# analyze-flip-runs: ${runs.length} runs (${builds.map((b) => `${b} ×${runs.filter((r) => r.build === b).length}`).join(', ')}); reference ${ref}`);
for (const [kind, title] of [['flips', 'neighbour flips (→)'], ['jumps', 'jumps (Home / End)']]) {
  console.log(`\n## ${title}`);
  for (const [key, label] of MEASURES) {
    console.log(`  ${label}`);
    for (const b of builds) {
      const xs = pooled(b, kind, key);
      if (!xs.length) { console.log(`    ${b}: no samples`); continue; }
      const s = summary(xs);
      const perRun = runs.filter((r) => r.build === b).map((r) => median(r.report[kind].map((m) => m[key]).filter((v) => typeof v === 'number')));
      let cmp = '';
      if (b !== ref) {
        const rs = pooled(ref, kind, key);
        if (rs.length) {
          const d = bootstrapMedianDiff(rs, xs);
          cmp = `   ${ref} − ${b}: ${fmt(d.diff)} ms [95 % bootstrap ${fmt(d.lo)}, ${fmt(d.hi)}]`;
        }
      }
      console.log(`    ${b}: n=${s.n}  median ${fmt(s.median)}  p95 ${fmt(s.p95)}  max ${fmt(s.max)}  min ${fmt(s.min)} ms;  per-run medians ${perRun.map(fmt).join(' / ')}${cmp}`);
    }
  }
  for (const b of builds) {
    const ms = runs.filter((r) => r.build === b).flatMap((r) => r.report[kind]);
    console.log(`  ${b}: a canvas already in the target's slot ${ms.filter((m) => m.preRendered).length} of ${ms.length}; frame-ambiguous ${ms.filter((m) => m.frameAmbiguous).length}`);
  }
}
console.log('\n## live page canvases');
for (const b of builds) {
  const rs = runs.filter((r) => r.build === b);
  const after = rs.flatMap((r) => r.report.flips.concat(r.report.jumps).map((m) => m.canvasesAfter));
  console.log(`  ${b}: max over every frame of every run ${Math.max(...rs.map((r) => r.report.maxCanvases))}; after each move max ${Math.max(...after)}`);
}
