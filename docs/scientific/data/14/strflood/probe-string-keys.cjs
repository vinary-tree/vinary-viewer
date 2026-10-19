'use strict';
// EXPLORATORY (no prediction): which string keys pile up in one bucket of a js/Map, in this engine? 4 096 keys of each
// pattern are set and then got in a fresh js/Map; the median of 15 timed runs (after 5 warm-ups) is printed, with the
// pattern's first keys. A pattern that floods costs tens of ms; one that spreads costs about 0.1 ms.
const N = 4096;
const patterns = [
  ['index k·2048 (≤ 7 digits)',         (k) => String(k * 2048)],
  ['index k (consecutive)',              (k) => String(k)],
  ['index k·4096',                       (k) => String(k * 4096)],
  ['index k·2048 + 2^24 (8 digits)',     (k) => String(k * 2048 + 16777216)],
  ['index k·65536 (up to 9 digits)',     (k) => String(k * 65536)],
  ['integer k·2048 + 2^32 (not index)',  (k) => String(k * 2048 + 4294967296)],
  ['"-" + k·2048',                       (k) => '-' + String(k * 2048)],
  ['"0" + k·2048 (leading zero)',        (k) => '0' + String(k * 2048)],
  ['k·2048 + ".5"',                      (k) => String(k * 2048) + '.5'],
  ['"x" + k·2048',                       (k) => 'x' + String(k * 2048)],
  ['random index below 2^23',            (() => { let s = 7; return () => String((s = (s * 1103515245 + 12345) >>> 0) % 8388608); })()],
];
const median = (xs) => { const s = [...xs].sort((a, b) => a - b); return s[(s.length - 1) >> 1]; };
console.log(`engine: node ${process.versions.node}, v8 ${process.versions.v8}${process.versions.electron ? `, electron ${process.versions.electron}` : ''}`);
for (const [name, gen] of patterns) {
  const keys = Array.from({ length: N }, (_, k) => gen(k));
  if (new Set(keys).size !== N) { console.log(`${name}: keys not distinct, skipped`); continue; }
  const times = [];
  for (let run = 0; run < 20; run++) {
    const fresh = keys.map((s) => (' ' + s).slice(1));          // new string objects: no hash cached from a run before
    const t0 = process.hrtime.bigint();
    const m = new Map();
    for (let k = 0; k < N; k++) m.set(fresh[k], k);
    let sum = 0;
    for (let k = 0; k < N; k++) sum += m.get(fresh[k]);
    const t1 = process.hrtime.bigint();
    if (sum !== (N * (N - 1)) / 2) throw new Error('lookup failed');
    if (run >= 5) times.push(Number(t1 - t0) / 1e6);
  }
  console.log(`${name.padEnd(38)}: median ${median(times).toFixed(3)} ms (min ${Math.min(...times).toFixed(3)}); first keys ${keys.slice(0, 3).join(', ')}`);
}
