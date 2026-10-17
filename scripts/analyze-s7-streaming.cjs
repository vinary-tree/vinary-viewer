'use strict';
// Experiment 11, S7 / H7 (docs/scientific/11 §9.6): the streaming source (remoteCreateReadStream) before and after
// it was pipelined, judged against docs/scientific/data/11/s7-prediction.txt.
//
//   node scripts/analyze-s7-streaming.cjs docs/scientific/data/11 > docs/scientific/data/11/s7-analysis.txt
//
// MODELS (t0 = the same reader's cell at R = 0, the client's CPU time):
//   H1, a stream with ONE request in flight:   t = R · (1 + ⌈S / 64 KiB⌉ · k + 1) + t0,  k = ⌈64 KiB / L⌉
//   H7, D·C in flight, no size hint:            t = R + R · max(⌈S / (D·C)⌉, S / W_eff) + t0,  W_eff = W − 3·32 KiB

const fs = require('fs');
const path = require('path');

const dir = process.argv[2] || path.join(__dirname, '..', 'docs', 'scientific', 'data', '11');
const MiB = 1024 * 1024;
const W_EFF = 2 * MiB - 3 * 32 * 1024;
const L = (ident) => (ident === 'openssh' ? 260096 : 31952);
const D = (ident) => Math.min(64, Math.max(4, Math.ceil((2 * MiB) / L(ident))));
const load = (name) => JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8')).results;
const key = (r) => `${r.ident}|${r.rtt}|${r.size}|${r.reader}`;
const pct = (x) => `${(100 * (x - 1)).toFixed(1).padStart(6)} %`;
const h1 = (S, R, ident) => R * (1 + Math.ceil(S / 65536) * Math.ceil(65536 / L(ident)) + 1);
const h7 = (S, R, ident) => R + R * Math.max(Math.ceil(S / (D(ident) * L(ident))), S / W_EFF);

function judge(rows, model, label, tolerance) {
  const zero = new Map(rows.filter((r) => r.rtt === 0).map((r) => [`${r.ident}|${r.size}|${r.reader}`, r.wall.median]));
  let out = 0;
  for (const r of rows) {
    if (r.rtt === 0) continue;
    const t0 = zero.get(`${r.ident}|${r.size}|${r.reader}`) || 0;
    const m = model(r.bytes, r.rtt, r.ident) + t0;
    const ratio = r.wall.median / m;
    const bad = Math.abs(ratio - 1) > tolerance;
    if (bad) out += 1;
    console.log(`${r.ident.padEnd(8)} ${String(r.rtt).padStart(3)} ${r.size.padStart(4)}  ${r.reader.padEnd(16)} ${r.wall.median.toFixed(1).padStart(10)} ${m.toFixed(1).padStart(10)} ${pct(ratio)}   ${bad ? `OFF (> ${100 * tolerance} %)` : 'holds'}`);
  }
  console.log(`${label}: cells with R >= 10 ms more than ${100 * tolerance} % off: ${out}\n`);
  return out;
}

console.log('## 1. BEFORE (a52a86b — ssh2\'s createReadStream behind remoteCreateReadStream) against H1');
console.log('ident    rtt size  reader            median_ms   model_ms   off_by   verdict');
const before = load('s7-before.json');
judge(before, h1, 'BEFORE vs H1', 0.25);

console.log('## 2. AFTER (8d20617 — pipelinedReadStream) against H7, and readable-legacy unchanged');
console.log('ident    rtt size  reader            median_ms   model_ms   off_by   verdict');
const after = load('s7-after.json');
judge(after, h7, 'AFTER readable vs H7', 0.25);
const legacyAfter = load('s7-after-legacy.json');
const beforeByKey = new Map(before.map((r) => [key(r), r]));
let moved = 0;
console.log('ident    rtt size  readable-legacy: before → after (ms)   change   verdict');
for (const r of legacyAfter) {
  const b = beforeByKey.get(key(r));
  if (!b) continue;
  const ratio = r.wall.median / b.wall.median;
  const bad = r.rtt >= 10 && Math.abs(ratio - 1) > 0.10;
  if (bad) moved += 1;
  console.log(`${r.ident.padEnd(8)} ${String(r.rtt).padStart(3)} ${r.size.padStart(4)}  ${b.wall.median.toFixed(1).padStart(10)} → ${r.wall.median.toFixed(1).padStart(10)}   ${pct(ratio)}   ${r.rtt === 0 ? '(R = 0: not judged)' : bad ? 'MOVED (> 10 %)' : 'unchanged'}`);
}
console.log(`readable-legacy cells (R >= 10 ms) that moved more than 10 %: ${moved}\n`);

// the re-measurement after the end-of-file straddle fix (73e40ed), when its data exists
const after2 = fs.existsSync(path.join(dir, 's7-after2.json')) ? load('s7-after2.json') : null;
if (after2) {
  console.log('## 2b. AFTER the end-of-file straddle fix (73e40ed) against H7');
  console.log('ident    rtt size  reader            median_ms   model_ms   off_by   verdict');
  judge(after2, h7, 'AFTER (straddle fixed) readable vs H7', 0.25);
}

console.log(`## 3. Speed-up of the streaming source (BEFORE readable → ${after2 ? 'AFTER, straddle fixed' : 'AFTER'} readable)`);
const afterByKey = new Map((after2 || after).map((r) => [key(r), r]));
for (const b of before.filter((r) => r.reader === 'readable')) {
  const a = afterByKey.get(key(b));
  if (a) console.log(`${b.ident.padEnd(8)} ${String(b.rtt).padStart(3)} ${b.size.padStart(4)}  ${b.wall.median.toFixed(1).padStart(10)} → ${a.wall.median.toFixed(1).padStart(8)} ms   ${(b.wall.median / a.wall.median).toFixed(1).padStart(6)}×`);
}

console.log('\n## 4. Memory — readable-discard\'s live footprint (gc() before every sample), R = 10 ms');
const mem = load('s7-memory.json');
const fp = new Map(mem.map((r) => [key(r), r.readArrayBuffersMiB.max]));
for (const r of mem) {
  console.log(`${r.ident.padEnd(8)} ${r.size.padStart(4)}  ${r.reader.padEnd(16)} n=${r.readArrayBuffersMiB.n}  footprint ${r.readArrayBuffersMiB.min.toFixed(2)}–${r.readArrayBuffersMiB.max.toFixed(2)} MiB`);
}
for (const ident of ['openssh', 'ssh2']) {
  const f8 = fp.get(`${ident}|10|8M|readable-discard`), f32 = fp.get(`${ident}|10|32M|readable-discard`);
  if (f8 === undefined || f32 === undefined) continue;
  const ok = f8 <= 3 && f32 <= 3 && (f32 - f8) <= 1;
  console.log(`${ident}: 8 MiB ${f8.toFixed(2)} MiB, 32 MiB ${f32.toFixed(2)} MiB (≤ 3 MiB, growth ${(f32 - f8).toFixed(2)} ≤ 1 MiB) → ${ok ? 'holds' : 'REFUTED'}`);
}
