'use strict';
// Context for F′ (no prediction): does V8's js/Map with STRING keys slow down for strings that are array indices,
// which V8 hashes from their numeric value? 4096 keys per Map; each run builds a fresh Map, sets every key, gets every key.
const N = 4096, WARM = 3, RUNS = 15;
const rnd = ((seed) => () => { let t = (seed += 0x6D2B79F5); t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; })(18);
const sets = {
  'index strings k·2048 ("0", "2048", …)': Array.from({ length: N }, (_, k) => String(k * 2048)),
  'index strings, random values below 8 388 608': (() => { const s = new Set(); while (s.size < N) s.add(String(Math.floor(rnd() * 8388608))); return [...s]; })(),
  'non-index strings "x" + k·2048': Array.from({ length: N }, (_, k) => 'x' + (k * 2048)),
};
const once = (keys) => { const t0 = process.hrtime.bigint(); const m = new Map(); for (const k of keys) m.set(k, 1);
  let n = 0; for (const k of keys) n += m.get(k); if (n !== N) throw new Error('lost a key');
  return Number(process.hrtime.bigint() - t0) / 1e6; };
for (const [name, keys] of Object.entries(sets)) {
  for (let i = 0; i < WARM; i++) once(keys);
  const xs = Array.from({ length: RUNS }, () => once(keys)).sort((a, b) => a - b);
  console.log(`${name.padEnd(48)}: median ${xs[Math.ceil(0.5 * RUNS) - 1].toFixed(3)} ms, min ${xs[0].toFixed(3)} ms, max ${xs[RUNS - 1].toFixed(3)} ms`);
}
