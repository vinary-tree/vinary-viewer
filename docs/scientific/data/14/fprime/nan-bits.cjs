'use strict';
// Which NaN encodings does V8 produce here? (test (f)'s premise: ∞ − ∞ and σ·NaN differ in bits)
const f = new Float64Array(1), u = new Uint32Array(f.buffer);
const hex = (x) => { f[0] = x; return u[1].toString(16).padStart(8, '0') + u[0].toString(16).padStart(8, '0'); };
const vals = [Infinity, 0.25, NaN, 100, 2.27];
const inf = vals[0], sigma = vals[1], nan = vals[2], x = vals[3], tol = vals[4];
const cases = {
  'js NaN': nan,
  'sigma * NaN': sigma * nan,
  '(100 - sigma*NaN) / tol': (x - sigma * nan) / tol,
  'Infinity - Infinity': inf - inf,
  '(Infinity - sigma*Infinity) / tol': (inf - sigma * inf) / tol,
  'Math.floor((∞ − σ∞)/tol − 0.5)': Math.floor((inf - sigma * inf) / tol - 0.5),
};
for (let r = 0; r < 3; r++) {                       // also after warm-up (optimized code)
  for (const [k, v] of Object.entries(cases)) if (r === 2) console.log(k.padEnd(36), hex(v));
}
let acc = 0; for (let i = 0; i < 1e6; i++) { const v = (vals[0] - vals[1] * vals[0]) / vals[4]; f[0] = v; acc ^= u[1]; }
console.log('optimized loop ∞ − σ∞ hi word', (acc >>> 0).toString(16), '(xor of 1e6 equal words = 0 when even count)');
f[0] = (vals[0] - vals[1] * vals[0]) / vals[4]; console.log('one more, hi word', u[1].toString(16));
