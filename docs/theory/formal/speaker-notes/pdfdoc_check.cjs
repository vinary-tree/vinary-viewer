'use strict';
// docs/theory/12 §6: pdf.js 5.4.149's decoding of a PDF string, as the worker runs it on an annotation's /Contents
// (`_parseStringHelper` → `stringToPDFString(data)`, keepEscapeSequence = false): its table branch, its byte-order-mark
// branch, and which strings reach the table branch whole (§6.1); and the inverse PDFDOC⁻¹ and REPAIR of
// vinary.slides.notes. The worker's own source text of `PDFStringTranslateTable` and `stringToPDFString` is evaluated
// in a sandbox; the model's tables are read from notes.cljs, and `pdfdoc-bytes` and `repair` are transcribed.
// In the messages below, P is pdf.js's decoding (the document's Δ) and m is its byte map (the document's δ).
//
// usage: node pdfdoc_check.cjs <repo-root> <pdfjs-worker.mjs> [--three]
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const [root, workerPath] = process.argv.slice(2);
const three = process.argv.includes('--three');
let failures = 0;
const check = (ok, msg) => { console.log((ok ? 'ok   ' : 'FAIL ') + msg); if (!ok) failures++; };

// ---- pdf.js's own code ----------------------------------------------------------------------------------------------
const src = fs.readFileSync(workerPath, 'utf8');
check(/pdfjsVersion = 5\.4\.149\b/.test(src.slice(0, 4096)), 'the worker is pdf.js 5.4.149, the version docs/theory/12 is about');
const tStart = src.indexOf('const PDFStringTranslateTable = [');
const tEnd = src.indexOf('];', tStart) + 2;
const fStart = src.indexOf('function stringToPDFString(');
const fEnd = src.indexOf('function stringToUTF8String(', fStart);
if (tStart < 0 || fStart < 0 || fEnd < 0) throw new Error('pdf.js definitions not found');
const sandbox = {
  TextDecoder,
  stringToBytes: (s) => Uint8Array.from(s, (c) => c.charCodeAt(0)),
  warn: () => {},
};
vm.createContext(sandbox);
vm.runInContext(src.slice(tStart, tEnd) + '\n' + src.slice(fStart, fEnd) +
                '\nthis.T = PDFStringTranslateTable; this.P = stringToPDFString;', sandbox);
const { T, P } = sandbox;
const str = (bytes) => String.fromCharCode(...bytes);

// ---- the table -------------------------------------------------------------------------------------------------------
check(T.length === 0xa1, `PDFStringTranslateTable has ${T.length} entries (bytes 0x00–0xA0)`);
const remapped = [];                                    // RM: the bytes the table maps to another character
for (let b = 0; b < T.length; b++) if (T[b] && b !== 0x1b) remapped.push(b);
const rmExpected = [0x18, 0x19, 0x1a, 0x1c, 0x1d, 0x1e, 0x1f];
for (let b = 0x80; b <= 0x9e; b++) rmExpected.push(b);
rmExpected.push(0xa0);
check(JSON.stringify(remapped) === JSON.stringify(rmExpected),
  `the remapped bytes RM are 0x18–0x1A, 0x1C–0x1F, 0x80–0x9E and 0xA0 (${remapped.length} bytes)`);
check(T[0x1b] === 0x2d9, 'the table has an entry for 0x1B (U+02D9), which the escape branch never reaches');
check(T[0x9f] === 0, 'T[0x9F] is 0: byte 0x9F decodes to U+009F');
const rmValues = remapped.map((b) => T[b]);
check(rmValues.every((v) => v >= 0x100), 'every remapped byte decodes to a code unit ≥ 0x100');
check(new Set(rmValues).size === rmValues.length, 'the remapped bytes decode to pairwise distinct code units');
check(rmValues.every((v) => v < 0xd800 || v > 0xdfff), 'no remapped byte decodes to a surrogate');

// ---- m, the byte map ---------------------------------------------------------------------------------------------------
const image = new Map();                                // code unit → byte
let oneUnit = true;
for (let b = 0; b < 256; b++) {
  if (b === 0x1b) continue;
  const out = P(str([b]));
  if (out.length !== 1) oneUnit = false;
  const c = out.charCodeAt(0);
  if (image.has(c)) check(false, `bytes ${image.get(c)} and ${b} decode to the same code unit`);
  image.set(c, b);
}
check(oneUnit, 'every byte but 0x1B decodes, alone, to exactly one UTF-16 code unit');
check(image.size === 255, `m is injective: 255 bytes, ${image.size} distinct code units`);
check(P(str([0x1b])) === '', 'the byte 0x1B alone decodes to the empty string');
let identity = 0;
for (const [c, b] of image) if (c === b) identity++;
check(identity === 255 - remapped.length, `the other ${identity} bytes decode to the Latin-1 character of their own number`);

// ---- P on escape-free strings: a length-preserving homomorphism, hence injective -----------------------------------
const bomPrefix = (bytes) => (bytes[0] === 0xfe && bytes[1] === 0xff) || (bytes[0] === 0xff && bytes[1] === 0xfe) ||
                             (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf);
const letterwise = (bytes) => bytes.map((b) => String.fromCharCode(T[b] && b !== 0x1b ? T[b] : b)).join('');
const escFree = [];
for (let b = 0; b < 256; b++) if (b !== 0x1b) escFree.push(b);
{
  const seen = new Map();
  let homomorphic = true, collisions = 0, n = 0;
  for (const a of escFree) for (const b of escFree) {
    const bytes = [a, b];
    if (bomPrefix(bytes)) continue;
    const out = P(str(bytes));
    n++;
    if (out !== letterwise(bytes)) homomorphic = false;
    if (seen.has(out)) collisions++;
    seen.set(out, bytes);
  }
  check(homomorphic, `on all ${n} escape-free two-byte strings without a BOM, P is the letterwise image of m`);
  check(collisions === 0, `…and injective (${collisions} collisions)`);
}
if (three) {
  const seen = new Set();
  let homomorphic = true, collisions = 0, n = 0;
  for (const a of escFree) for (const b of escFree) for (const c of escFree) {
    const bytes = [a, b, c];
    if (bomPrefix(bytes)) continue;
    const out = P(str(bytes));
    n++;
    if (out !== letterwise(bytes)) homomorphic = false;
    if (seen.has(out)) collisions++;
    seen.add(out);
  }
  check(homomorphic && collisions === 0, `on all ${n} escape-free three-byte strings without a BOM, P is letterwise and injective`);
}

// ---- with 0x1B: an escape span is removed, so P is NOT injective on all byte strings ---------------------------------
check(P(str([0x1b, 0x41, 0x1b])) === '' && P('') === '', 'P(1B 41 1B) = P(ε) = "": two byte strings, one decoding');
check(P(str([0x41, 0x1b, 0x78, 0x78, 0x1b, 0x42])) === 'AB' && P('AB') === 'AB', 'P(41 1B 78 78 1B 42) = P(41 42) = "AB"');
check(P(str([0x41, 0x1b, 0x42, 0x43])) === 'A', 'an escape without a closing 1B runs to the end: P(41 1B 42 43) = "A"');
const strip = (bytes) => {                              // written from the definition, not from pdf.js
  const out = [];
  for (let i = 0; i < bytes.length; i++) {
    if (bytes[i] !== 0x1b) { out.push(bytes[i]); continue; }
    i++;
    while (i < bytes.length && bytes[i] !== 0x1b) i++;
  }
  return out;
};
{
  let ok = true, seed = 12345;
  const rnd = (k) => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed % k; };
  const alphabet = [0x1b, 0x1b, 0x41, 0x42, 0x80, 0xa0, 0xe9, 0x18, 0x00, 0xff];
  for (let t = 0; t < 200000; t++) {
    const len = 1 + rnd(9);
    const bytes = Array.from({ length: len }, () => alphabet[rnd(alphabet.length)]);
    if (bomPrefix(bytes)) continue;
    const s = strip(bytes);
    if (P(str(bytes)) !== letterwise(s)) { ok = false; console.log('  strip counterexample', bytes); break; }
  }
  check(ok, 'P(b) = m*(strip(b)) on 200,000 random strings over an alphabet with 0x1B (strip written independently)');
}

// ---- the model's tables and PDFDOC⁻¹ ----------------------------------------------------------------------------------
const notes = fs.readFileSync(path.join(root, 'src/vinary/slides/notes.cljs'), 'utf8');
const vec = (name) => {
  const m = notes.match(new RegExp(`\\(def \\^:private ${name}\\n[^\\[]*\\[([^\\]]*)\\]`));
  if (!m) throw new Error(`${name} not found in notes.cljs`);
  return m[1].trim().split(/\s+/).map((x) => parseInt(x.replace(/^0x/, ''), 16));
};
const low = vec('pdfdoc-low');
const high = vec('pdfdoc-high');
check(JSON.stringify(low) === JSON.stringify(Array.from(T.slice(0x18, 0x20))), 'notes.cljs pdfdoc-low = pdf.js table 0x18–0x1F');
check(JSON.stringify(high) === JSON.stringify(Array.from(T.slice(0x80, 0xa1))), 'notes.cljs pdfdoc-high = pdf.js table 0x80–0xA0');
// pdfdoc-inverse and never-decoded, transcribed from notes.cljs (keep-indexed, pos?, k ≠ 3)
const inverse = new Map();
low.forEach((c, k) => { if (c > 0 && k !== 3) inverse.set(c, 0x18 + k); });
high.forEach((c, k) => { if (c > 0) inverse.set(c, 0x80 + k); });
const neverDecoded = new Set([0x1b, ...inverse.values()]);
const pdfdocBytes = (s) => {
  const out = [];
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    const b = inverse.has(c) ? inverse.get(c) : (c <= 0xff && !neverDecoded.has(c) ? c : undefined);
    if (b === undefined) return null;
    out.push(b);
  }
  return out;
};
{
  let ok = true;
  for (let c = 0; c <= 0xffff; c++) {
    const b = pdfdocBytes(String.fromCharCode(c));
    const inImage = image.has(c);
    if ((b !== null) !== inImage || (inImage && b[0] !== image.get(c))) { ok = false; console.log('  unit', c.toString(16)); break; }
  }
  check(ok, 'for every code unit 0x0000–0xFFFF: PDFDOC⁻¹ is defined exactly on the image of m, and inverts m there');
}
{
  let ok = true;
  for (const a of escFree) for (const b of escFree) {
    const bytes = [a, b];
    if (bomPrefix(bytes)) continue;
    const back = pdfdocBytes(P(str(bytes)));
    if (!back || back[0] !== a || back[1] !== b) { ok = false; break; }
  }
  check(ok, 'PDFDOC⁻¹(P(b)) = b for every escape-free two-byte string b without a BOM');
}

// ---- the BOM branch, and which strings reach the table branch whole (§6.1, Theorem 34) --------------------------------
const lostByteOutputs = [];                             // P(b) for the UTF-16 strings that lose their last byte
check(P(str([0xfe, 0xff, 0x00, 0x41])) === 'A' && P(str([0xff, 0xfe, 0x41, 0x00])) === 'A' &&
      P(str([0xfe, 0xff, 0x00, 0x41, 0x00])) === 'A' && P(str([0x41])) === 'A',
  'P is not injective within the BOM branch: FE FF 00 41, FF FE 41 00, FE FF 00 41 00 and 41 all decode to "A"');
check(P(str([0xfe, 0xff, 0xd8, 0x00, 0x41])) === P(str([0xfe, 0xff, 0xd8, 0x00])) &&
      P(str([0xfe, 0xff, 0xd8, 0x00])) === letterwise([0xfe, 0xff, 0xd8, 0x00]),
  'a UTF-16 string of odd length that the fatal decoder rejects reaches the table branch without its last byte: ' +
  'P(FE FF D8 00 41) = P(FE FF D8 00)');
{
  // §6.1's case split, written from its statement, against pdf.js on every string of length ≤ 5 (6 with --three) over
  // an alphabet that reaches every branch: both bytes of each BOM, a surrogate's high byte, 0x1B, ASCII, UTF-8 bytes
  const alphabet = [0x00, 0x1b, 0x41, 0xa9, 0xbb, 0xbf, 0xc3, 0xd8, 0xef, 0xfe, 0xff];
  const maxLen = three ? 6 : 5;
  const decode = (enc, bytes) => {
    try { return new TextDecoder(enc, { fatal: true }).decode(Uint8Array.from(bytes)); } catch (_e) { return null; }
  };
  const bomOf = (b) => (b[0] === 0xfe && b[1] === 0xff ? 'utf-16be'
                        : b[0] === 0xff && b[1] === 0xfe ? 'utf-16le'
                        : b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf ? 'utf-8' : null);
  const tally = { noBom: 0, bomDecoded: 0, bomRejectedWhole: 0, bomRejectedOdd: 0 };
  const image = new Map();                              // P(b) → b, over the escape-free strings of D_T
  let agrees = true, collisions = 0;
  const visit = (bytes) => {
    const enc = bomOf(bytes);
    const odd = (enc === 'utf-16be' || enc === 'utf-16le') && bytes.length % 2 === 1;
    const kept = odd ? bytes.slice(0, -1) : bytes;      // a UTF-16 string of odd length loses its last byte first
    let expected, inDT = false;
    if (!enc) {
      expected = letterwise(strip(bytes)); inDT = true; tally.noBom++;
    } else {
      const decoded = decode(enc, kept);                // the decoder removes the BOM itself
      if (decoded !== null) {
        expected = decoded.replace(/\x1b[^\x1b]*(?:\x1b|$)/g, ''); tally.bomDecoded++;
      } else if (odd) {
        expected = letterwise(strip(kept)); tally.bomRejectedOdd++; lostByteOutputs.push(expected);
      } else {
        expected = letterwise(strip(bytes)); inDT = true; tally.bomRejectedWhole++;
      }
    }
    const out = P(str(bytes));
    if (out !== expected && agrees) { agrees = false; console.log('  case-split counterexample', bytes); }
    if (inDT && !bytes.includes(0x1b)) { if (image.has(out)) collisions++; else image.set(out, bytes); }
  };
  const walk = (prefix) => {
    if (prefix.length > 0) visit(prefix);
    if (prefix.length < maxLen) for (const a of alphabet) walk([...prefix, a]);
  };
  walk([]);
  check(agrees, `§6.1's case split agrees with pdf.js on every string of length ≤ ${maxLen} over ${alphabet.length} bytes ` +
    `(no BOM ${tally.noBom}; BOM, decoded ${tally.bomDecoded}; BOM, rejected whole ${tally.bomRejectedWhole}; ` +
    `BOM, rejected after losing a byte ${tally.bomRejectedOdd})`);
  check(collisions === 0 && tally.bomRejectedWhole > 0 && tally.bomRejectedOdd > 0,
    `P is injective on the escape-free strings of D_T among them (${image.size} strings, ${collisions} collisions)`);
}

// ---- REPAIR's condition -------------------------------------------------------------------------------------------------
const utf8 = (bytes) => { try { return new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(bytes)); } catch (_e) { return null; } };
{
  let changed = 0, mismatch = 0;
  for (const a of escFree) for (const b of escFree) {
    const bytes = [a, b];
    if (bomPrefix(bytes)) continue;
    const s = P(str(bytes));
    const back = pdfdocBytes(s);
    const repaired = back.some((x) => x >= 0x80) ? (utf8(back) ?? s) : s;
    const predicted = bytes.some((x) => x >= 0x80) && utf8(bytes) !== null;
    if ((repaired !== s) !== predicted) mismatch++;
    if (repaired !== s) changed++;
  }
  check(mismatch === 0, 'REPAIR changes P(b) exactly when b has a byte ≥ 0x80 and is valid UTF-8 (two-byte strings)');
  check(changed === 30 * 64, `two-byte strings REPAIR changes: ${changed} = 30 × 64 (C2–DF then 80–BF)`);
}
check(P(str([0xef, 0xbb, 0xbf, 0x63, 0x61, 0x66, 0xc3, 0xa9])) === 'café',
  'a string starting EF BB BF is decoded as UTF-8 by pdf.js itself (the BOM branch), not as PDFDocEncoding');
check(P(str([0xfe, 0xff])) === '', 'FE FF alone is a UTF-16BE BOM: the BOM branch decodes it to "" (outside the theorem)');
check(P(str([0xfe, 0xff, 0xd8, 0x00])) === letterwise([0xfe, 0xff, 0xd8, 0x00]),
  'a BOM string the fatal decoder rejects (FE FF D8 00: an unpaired surrogate) falls through to the table branch');
check(P(str([0xef, 0xbb, 0xbf, 0x63, 0x61, 0x66, 0xe9])) === letterwise([0xef, 0xbb, 0xbf, 0x63, 0x61, 0x66, 0xe9]),
  '…and so does EF BB BF followed by bytes that are not UTF-8 (EF BB BF 63 61 66 E9)');
check(P(str([0x63, 0x61, 0x66, 0xe9])) === 'café' && P(str([0xef, 0xbb, 0xbf, 0x63, 0x61, 0x66, 0xc3, 0xa9])) === 'café',
  'P is not injective across its branches: 63 61 66 E9 and EF BB BF 63 61 66 C3 A9 both decode to "café"');

// ---- REPAIR, transcribed from notes.cljs `repair` with a Producer that names pdfTeX -----------------------------------
const repair = (s) => {
  const b = pdfdocBytes(s);
  if (!b || !b.some((x) => x >= 0x80)) return s;
  const u = utf8(b);
  return u === null ? s : u;
};
check(utf8([0xef, 0xbb, 0xbf, 0x41]) === 'A', "the WHATWG UTF-8 decoder (ignoreBOM false) removes a leading BOM: EF BB BF 41 → \"A\"");
{
  // what REPAIR is for: pdfTeX writes a note's UTF-8 bytes b into a literal string; then REPAIR(P(b)) = UTF-8(b)
  let ok = true, n = 0;
  const lead2 = [], lead3 = [];
  for (let a = 0xc2; a <= 0xdf; a++) for (let c = 0x80; c <= 0xbf; c++) lead2.push([a, c]);
  for (let a = 0xe0; a <= 0xef; a++) for (let c = 0x80; c <= 0xbf; c++) for (let d = 0x80; d <= 0xbf; d++) {
    const bytes = [a, c, d];
    if (utf8(bytes) !== null) lead3.push(bytes);
  }
  for (const seq of [...lead2, ...lead3]) for (const pre of [[], [0x41], [0x18]]) for (const post of [[], [0x42], [0x9f]]) {
    const bytes = [...pre, ...seq, ...post];
    const u = utf8(bytes);
    if (u === null || bomPrefix(bytes)) continue;
    n++;
    if (repair(P(str(bytes))) !== u) { ok = false; console.log('  repair counterexample', bytes); break; }
  }
  check(ok, `REPAIR(P(b)) = UTF-8(b) for all ${n} valid non-ASCII UTF-8 strings b built from every 2- and 3-byte sequence (no 0x1B, no BOM)`);
}
check(repair(P(str([0x41, 0x18, 0x42]))) === 'A˘B' && utf8([0x41, 0x18, 0x42]) === 'A\u0018B',
  'an all-ASCII b with a control byte 0x18–0x1F keeps pdf.js\'s ˘: REPAIR leaves ASCII alone (A 18 B → "A˘B")');
check(repair(P(str([0x41, 0x1b, 0x6a, 0x61, 0x1b, 0xc3, 0xa9]))) === 'Aé',
  'with an escape span, REPAIR decodes strip(b): 41 1B 6A 61 1B C3 A9 → "Aé"');
check(P(str([0xc3, 0xa9, 0xc3, 0xa9])) === 'Ã©Ã©' && repair('Ã©Ã©') === 'éé',
  'genuine PDFDocEncoding text with two UTF-8-shaped pairs is mis-decoded too: "Ã©Ã©" (C3 A9 C3 A9) → "éé"');
check(P(str([0xc3, 0xa9, 0x18])) === 'Ã©\u02d8' && repair('Ã©\u02d8') === 'é\u0018',
  'an accent that a byte below 0x20 decodes to need not pair: "Ã©˘" (C3 A9 18) → "é" and U+0018');
check(lostByteOutputs.length > 0 && lostByteOutputs.every((u) => repair(u) === u),
  `REPAIR leaves alone every string that reached the table branch without its last byte (${lostByteOutputs.length} ` +
  'of them, from the case split): their bytes start with FE or FF, which never occur in UTF-8');
check(P(str([0xfe, 0xff, 0x00, 0xc3, 0x00, 0xa9])) === 'Ã©' && repair('Ã©') === 'é',
  'a UTF-16BE string that really reads "Ã©" (FE FF 00 C3 00 A9) is mis-decoded by REPAIR to "é"');

console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
process.exit(failures ? 1 : 0);
