'use strict';
// Where did a V8 CPU profile (.cpuprofile — Chrome DevTools / inspector Profiler format) spend its time, and what was
// running during its longest uninterrupted stretches? Written to attribute main-process stalls (docs/scientific/12 §S3).
//
//   node scripts/analyze-cpuprofile.cjs main-tree-filter.cpuprofile [--top=25] [--runs=8]
//
// • self time by function (name @ file:line), idle and program time reported separately;
// • the longest RUNS: consecutive samples with the same leaf frame. A synchronous call that blocks the thread — a
//   busy loop or a blocking syscall alike — shows as one long run, printed with its whole stack.
// The time of sample i is the gap to sample i + 1 (timeDeltas[i + 1], microseconds).

const fs = require('fs');
const path = require('path');

const file = process.argv[2];
if (!file) throw new Error('usage: analyze-cpuprofile.cjs <file.cpuprofile> [--top=N] [--runs=N]');
const opt = (k, d) => Number((process.argv.find((a) => a.startsWith(`--${k}=`)) || '').slice(k.length + 3)) || d;
const TOP = opt('top', 25);
const RUNS = opt('runs', 8);

const prof = JSON.parse(fs.readFileSync(file, 'utf8'));
const byId = new Map(prof.nodes.map((n) => [n.id, n]));
const parent = new Map();
for (const n of prof.nodes) for (const c of n.children || []) parent.set(c, n.id);
const label = (n) => {
  const f = n.callFrame;
  const where = f.url ? `${path.basename(f.url)}:${f.lineNumber + 1}` : '';
  return `${f.functionName || '(anonymous)'}${where ? ` @ ${where}` : ''}`;
};
const stackOf = (id) => {
  const out = [];
  for (let cur = id; cur !== undefined; cur = parent.get(cur)) out.push(label(byId.get(cur)));
  return out;                                                 // leaf first
};

const samples = prof.samples;
const deltas = prof.timeDeltas;
const dur = (i) => (i + 1 < deltas.length ? deltas[i + 1] : 0) / 1000;   // ms
const total = samples.reduce((acc, _s, i) => acc + dur(i), 0);

const self = new Map();
for (let i = 0; i < samples.length; i++) {
  const k = label(byId.get(samples[i]));
  self.set(k, (self.get(k) || 0) + dur(i));
}

const runs = [];
for (let i = 0; i < samples.length;) {
  let j = i;
  let t = 0;
  while (j < samples.length && samples[j] === samples[i]) { t += dur(j); j += 1; }
  runs.push({ id: samples[i], ms: t, at: deltas.slice(0, i + 1).reduce((a, b) => a + b, 0) / 1000, count: j - i });
  i = j;
}
runs.sort((a, b) => b.ms - a.ms);

console.log(`# ${path.basename(file)}: ${samples.length} samples over ${total.toFixed(0)} ms`);
console.log(`\n## self time, top ${TOP}`);
for (const [k, ms] of [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, TOP)) {
  console.log(`${ms.toFixed(1).padStart(9)} ms  ${(100 * ms / total).toFixed(1).padStart(5)} %  ${k}`);
}
console.log(`\n## the ${RUNS} longest runs on one leaf (excluding (idle))`);
for (const r of runs.filter((x) => byId.get(x.id).callFrame.functionName !== '(idle)').slice(0, RUNS)) {
  console.log(`${r.ms.toFixed(1).padStart(9)} ms  from ${r.at.toFixed(0)} ms into the profile, ${r.count} samples`);
  for (const frame of stackOf(r.id).slice(0, 14)) console.log(`             ${frame}`);
}
