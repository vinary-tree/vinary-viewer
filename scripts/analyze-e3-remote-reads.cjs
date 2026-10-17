'use strict';
// Experiment 11, E3 (docs/scientific/11 §9.5): the re-measurement with E0–E4 in place, judged against the criteria
// pre-registered in docs/scientific/data/11/e3-prediction.txt. Reads the bench-remote-read JSON files of the four
// E3 runs and prints, per cell, the measured median, the model, and the verdict.
//
//   node scripts/analyze-e3-remote-reads.cjs docs/scientific/data/11 > docs/scientific/data/11/e3-analysis.txt
//
// MODEL (H2). A whole-file read pays its fixed round trips, then the body: D requests of C bytes per round trip,
// unless the channel window W caps the bytes in flight first; plus the client's CPU time, taken from the same cell
// at R = 0:
//     t̂ = f·R + R · max(⌈S / (D·C)⌉, S / W) + t(R = 0)
// with f = 2 for every reader here (OPEN + FSTAT: neither `stream` nor `pipelined:D:C` passes a size hint) and
// W = 2 MiB. The pre-registration modelled pipelined:D:C with f = 1 (it assumed a size hint): that model is printed
// too, so the verdict can be read under the model as it was written.

const fs = require('fs');
const path = require('path');

const dir = process.argv[2] || path.join(__dirname, '..', 'docs', 'scientific', 'data', '11');
const MiB = 1024 * 1024;
const W = 2 * MiB;
const load = (name) => JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8')).results;
const L = (ident) => (ident === 'openssh' ? 260096 : 31952);
const autoDepth = (ident) => Math.min(64, Math.max(4, Math.ceil(W / L(ident))));
const rounds = (S, D, C) => Math.max(Math.ceil(S / (D * C)), S / W);
const pct = (x) => `${(100 * (x - 1)).toFixed(1).padStart(6)} %`;

function matrix() {
  const rows = load('e3-matrix.json');
  const zero = new Map(rows.filter((r) => r.rtt === 0).map((r) => [`${r.ident}|${r.size}|${r.reader}`, r.wall.median]));
  const stream = new Map(rows.map((r) => [`${r.ident}|${r.rtt}|${r.size}|${r.reader}`, r.wall.median]));
  console.log('## 1. Main matrix — stream (shipped reader) against H2; fastGet against stream');
  console.log('ident    rtt  size  reader      median_ms   model_ms   off_by   verdict');
  let refuted = 0, fastGetChecks = 0, fastGetOut = 0;
  for (const r of rows) {
    if (r.rtt === 0) continue;
    const S = r.bytes, R = r.rtt, cpu = zero.get(`${r.ident}|${r.size}|${r.reader}`) || 0;
    if (r.reader === 'stream') {
      const model = 2 * R + R * rounds(S, autoDepth(r.ident), L(r.ident)) + cpu;
      const ratio = r.wall.median / model;
      const bad = Math.abs(ratio - 1) > 0.25;
      if (bad) refuted += 1;
      console.log(`${r.ident.padEnd(8)} ${String(R).padStart(3)} ${r.size.padStart(5)}  stream     ${r.wall.median.toFixed(1).padStart(10)} ${model.toFixed(1).padStart(10)} ${pct(ratio)}   ${bad ? 'REFUTES H2 (> 25 %)' : 'holds'}`);
    } else if (r.reader === 'fastGet') {
      const s = stream.get(`${r.ident}|${R}|${r.size}|stream`);
      const ratio = r.wall.median / s;
      let verdict = '(S ≤ 1 MiB: predicted slower)';
      if (S >= 8 * MiB) {
        fastGetChecks += 1;
        const out = Math.abs(ratio - 1) > 0.15;
        if (out) fastGetOut += 1;
        verdict = out ? 'outside ±15 % of stream' : 'within ±15 % of stream';
      } else if (ratio <= 1) verdict = 'NOT slower than stream (prediction missed)';
      console.log(`${r.ident.padEnd(8)} ${String(R).padStart(3)} ${r.size.padStart(5)}  fastGet    ${r.wall.median.toFixed(1).padStart(10)} ${s.toFixed(1).padStart(10)} ${pct(ratio)}   ${verdict}`);
    }
  }
  console.log(`\nstream cells with R >= 10 ms more than 25 % off H2: ${refuted}`);
  console.log(`fastGet cells (S >= 8 MiB, R >= 10 ms) outside ±15 % of stream: ${fastGetOut} of ${fastGetChecks}\n`);
}

function depth() {
  console.log('## 2. Depth sweep — 32 MiB at 50 ms: depth-bound below W, window-bound above (H2\'s structure)');
  console.log('ident    reader                 D·C_MiB  median_ms  model_ms(f=2)  off_by  model_ms(f=1, as pre-registered)  off_by');
  for (const ident of ['openssh', 'ssh2']) {
    const rows = load(`e3-depth-${ident}.json`).sort((a, b) => a.reader.localeCompare(b.reader, 'en', { numeric: true }));
    const cpu = (load('e3-matrix.json').find((r) => r.ident === ident && r.rtt === 0 && r.size === '32M' && r.reader === 'stream') || { wall: { median: 0 } }).wall.median;
    const byDC = [];
    for (const r of rows) {
      const [, D, C] = r.reader.split(':').map(Number);
      const S = r.bytes, R = r.rtt;
      const body = R * rounds(S, D, Math.min(C, L(ident)));
      const m2 = 2 * R + body + cpu, m1 = R + body + cpu;
      byDC.push({ dc: D * Math.min(C, L(ident)), t: r.wall.median });
      console.log(`${ident.padEnd(8)} ${r.reader.padEnd(22)} ${((D * Math.min(C, L(ident))) / MiB).toFixed(2).padStart(7)} ${r.wall.median.toFixed(1).padStart(10)} ${m2.toFixed(1).padStart(14)} ${pct(r.wall.median / m2)} ${m1.toFixed(1).padStart(34)} ${pct(r.wall.median / m1)}`);
    }
    byDC.sort((a, b) => a.dc - b.dc);
    const two = byDC.find((x) => Math.abs(x.dc / MiB - 2) < 0.1), four = byDC.find((x) => Math.abs(x.dc / MiB - 4) < 0.1);
    if (two && four) {
      const gain = 1 - four.t / two.t;
      console.log(`${ident}: D·C ≈ 4 MiB vs ≈ 2 MiB: ${(100 * gain).toFixed(1)} % faster — ${gain > 0.10 ? 'REFUTES (gain > 10 %)' : 'holds (gain ≤ 10 %)'}`);
    }
  }
  console.log('');
}

function memory() {
  console.log('## 3. H6 — live footprint (peak − rest, gc() before every sample), R = 10 ms');
  console.log('ident    size  reader          n  footprint_MiB(min–max)   in S (max)');
  const rows = load('e3-memory.json');
  const at = new Map();
  for (const r of rows) {
    const S = r.bytes / MiB;
    const fp = r.readArrayBuffersMiB;
    at.set(`${r.ident}|${r.size}|${r.reader}`, fp.max / S);
    console.log(`${r.ident.padEnd(8)} ${r.size.padStart(4)}  ${r.reader.padEnd(14)} ${String(fp.n).padStart(2)}  ${(fp.min.toFixed(2) + '–' + fp.max.toFixed(2)).padStart(20)}   ${(fp.max / S).toFixed(3).padStart(8)} S`);
  }
  for (const ident of ['openssh', 'ssh2']) {
    for (const size of ['8M', '32M']) {
      const st = at.get(`${ident}|${size}|stream`), lg = at.get(`${ident}|${size}|stream-legacy`);
      if (st === undefined || lg === undefined) continue;
      const v1 = st <= 1.25, v2 = (lg - st) >= 0.5;
      console.log(`${ident} ${size}: stream ${st.toFixed(3)} S (≤ 1.25 S: ${v1 ? 'yes' : 'NO'}); legacy − stream = ${(lg - st).toFixed(3)} S (≥ 0.5 S: ${v2 ? 'yes' : 'NO'}) → H6 ${v1 && v2 ? 'holds' : 'REFUTED'}`);
    }
  }
}

matrix();
depth();
memory();
