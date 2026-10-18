'use strict';
// Derive scripts/notes-corpus/expectations.json -- the PRE-REGISTERED decisions of docs/scientific/13 (H-NOTES-CORPUS)
// -- from ADR-0048 §2's rules, applied to what the TeX engines wrote. Run BEFORE the detector ever runs on the corpus:
//
//   node scripts/exp-notes-corpus.cjs --no-detector --out=<dir> --json=<prelook.json>   # compile + oracle only
//   node scripts/notes-corpus/derive-expectations.cjs <prelook.json> scripts/notes-corpus/expectations.json
//
// This file is an INDEPENDENT reading of the ADR, not the detector: it loads nothing from src/ or dist/. Its inputs are
// facts about the input PDFs that the pre-registration was allowed to look at -- each artifact's page labels and
// Producer, and the oracle's note pages (the NOTEMARK tokens in the page text, docs/scientific/13 §4) -- and it applies:
//
//   §2.1  the label automaton, by BRUTE FORCE over every slide/note assignment of the pages (rules 1-3 verbatim),
//         instead of the gap decomposition vinary.slides.notes uses, then the result table (none / proven / ambiguous /
//         undecidable / invalid);
//   §2.3  the probe (walk the candidates from the last; pass → that candidate; fail but dated → the previous one;
//         otherwise the parse without note pages in the plain run, when it is valid);
//   §2.4  the scan; §2.5 the confirmation and its trust rule (pdfTeX, LuaTeX, LuaHBTeX);
//   the plan/settle table of vinary.slides.notes/plan and vinary.slides.evidence/settle as ADR-0048 and their
//   docstrings state it (which work is pending, and which proof and reason each outcome gets);
//   ADR-0045's frames (maximal runs of adjacent equal labels, over the shown pages when some are hidden).
//
// SIG and ROTATED-DATE are not computed from the pages. They are PREDICTED by a content model of Beamer's note
// templates (beamerouterthemedefault.sty, beamerbaseauxtemplates.sty, beamerbasenotes.sty), stated in
// PREREGISTRATION.md §4 and recorded with every predicted read:
//   M1  under `default` and `compressed`, a note page j shows a thumbnail of t(j), the last slide page before it;
//       `plain` and `lined` draw none
//   M2  ROTATED-DATE(j) holds exactly for the note pages of the `default` template
//   M3  SIG(s, j):
//       M3t  s has no text (chars = 0): ROTATED-DATE(j) when j has text, nil (no evidence) when it has none
//       M3a  j is a note page with a thumbnail of t(j) = s: true
//       M3b  j is a note page whose thumbnail is a LATER overlay of s's own frame (which repeats s's text): true
//       M3c  s is a note page: false (its thumbnail repeats on j at σ = 1 or not at all; only its short frame title can
//            match j's thumbnail below the bound, far less than half of its characters)
//       M3d  j is a slide: false (it repeats s's strings at σ = 1 or not at all)
//       M3e  j is a note page without a thumbnail (`plain`, `lined`), or whose thumbnail is another frame's: false
//   M4  the template does not change which pages are note pages (checked by the experiment, §4 of the record)

const fs = require('fs');

const TRUSTED = /\b(pdftex|luatex|luahbtex)\b/i;   // ADR-0048 §2.5
const THUMBNAIL = new Set(['default', 'compressed']);
const DATED = new Set(['default']);

// ---- §2.1 by brute force -------------------------------------------------------------------------------------------

// Every valid parse of the integer labels L, as the sorted array of its note pages. Rules (ADR-0048 §2.1):
//   1. page 0 is a slide, f := L0;
//   2. a slide carries f or f + 1, then f := its label;
//   3. a note page is a page after page 0 with L_i = i + 1; f is unchanged.
function validParses(L) {
  const n = L.length;
  const out = [];
  const walk = (i, f, notes) => {
    if (i === n) { out.push(notes.slice()); return; }
    if (L[i] === f || L[i] === f + 1) walk(i + 1, L[i], notes);                       // a slide
    if (L[i] === i + 1) { notes.push(i); walk(i + 1, f, notes); notes.pop(); }        // a note page
  };
  walk(1, L[0], []);
  return out;
}

const restartable = (L) => L.every((l, i) => i === 0 || l <= L[i - 1] + 1);

// The ADR's result table, with the plain run and the candidates of an ambiguous deck.
function automaton(L) {
  const parses = validParses(L);
  const withNotes = parses.filter((p) => p.length);
  if (!parses.length) return { status: 'invalid', parses };
  if (parses.length === 1) return withNotes.length ? { status: 'proven', parses, notes: parses[0] } : { status: 'none', parses };
  if (L[0] >= 2) return { status: 'undecidable', parses, why: 'several valid parses and L0 >= 2' };
  // L0 <= 1: the plain run is pages 1..m with L_j = j + 1; every parse reads [a, m] ∪ base, or base (the fallback)
  let m = 0;
  while (m + 1 < L.length && L[m + 1] === m + 2) m++;
  const inRun = (j) => j >= 1 && j <= m;
  const bases = parses.map((p) => JSON.stringify(p.filter((j) => !inRun(j))));
  const runs = parses.map((p) => p.filter(inRun));
  const shaped = runs.every((r) => !r.length || (r[r.length - 1] === m && r.length === m - r[0] + 1));
  const firsts = runs.filter((r) => r.length).map((r) => r[0]);
  if (new Set(bases).size !== 1 || !shaped || new Set(firsts).size !== firsts.length) {
    return { status: 'undecidable', parses, why: 'the parses do not differ only by a first note page in the plain run' };
  }
  return { status: 'ambiguous', parses, runEnd: m, base: JSON.parse(bases[0]), candidates: firsts.sort((x, y) => x - y),
           fallback: runs.some((r) => !r.length) };
}

// ---- the content model (M1-M3) --------------------------------------------------------------------------------------

function contentModel(art, trueLabels) {
  const n = art.pages;
  const notes = new Set(art.oracle.notePages);
  const chars = art.pageInfo.map((p) => p.chars);
  const thumb = THUMBNAIL.has(art.template);
  const lastSlideBefore = (j) => { for (let s = j - 1; s >= 0; s--) if (!notes.has(s)) return s; return null; };
  // s and t (> s) are overlays of one frame: slides with equal true labels and only such slides (or note pages)
  // between them
  const sameFrame = (s, t) => {
    if (!trueLabels || notes.has(s) || notes.has(t)) return false;
    for (let k = s + 1; k <= t; k++) if (!notes.has(k) && trueLabels[k] !== trueLabels[s]) return false;
    return true;
  };
  const dated = (j) => DATED.has(art.template) && notes.has(j);                                       // M2
  const sig = (s, j) => {
    if (chars[s] === 0) return { sig: chars[j] > 0 ? dated(j) : null, model: 'M3t' };
    if (notes.has(s)) return { sig: false, model: 'M3c' };
    if (!notes.has(j)) return { sig: false, model: 'M3d' };
    const t = thumb ? lastSlideBefore(j) : null;                                                       // M1
    if (t === s) return { sig: true, model: 'M3a' };
    if (t !== null && t > s && sameFrame(s, t)) return { sig: true, model: 'M3b' };
    return { sig: false, model: 'M3e' };
  };
  return { n, sig, dated };
}

// ---- §2.3 the probe, §2.4 the scan, §2.5 the confirmation -----------------------------------------------------------

function probe(il, cm, reads) {
  const desc = il.candidates.slice().reverse();
  for (const a of desc) {
    const r = { step: 'probe', pair: [a - 1, a], ...cm.sig(a - 1, a), dated: cm.dated(a) };
    reads.push(r);
    if (r.sig === true) return { notes: il.base.concat(range(a, il.runEnd)).sort((x, y) => x - y), via: a };
    if (!r.dated) break;
  }
  return { notes: il.fallback ? il.base : null, via: 'fallback' };
}

function confirm(notes, cm, reads) {
  const set = new Set(notes);
  let slide = 0;
  for (let j = 1; j < cm.n; j++) {
    if (!set.has(j)) { slide = j; continue; }
    const r = { step: 'confirm', pair: [slide, j], ...cm.sig(slide, j), dated: cm.dated(j) };
    reads.push(r);
    if (!(r.sig === true || r.dated)) return false;
  }
  return true;
}

function scan(cm, reads) {
  const notes = [];
  let slide = 0;
  for (let j = 1; j < cm.n; j++) {
    const r = { step: 'scan', pair: [slide, j], ...cm.sig(slide, j) };
    reads.push(r);
    if (r.sig === true) notes.push(j); else slide = j;
  }
  return notes;
}

const range = (a, b) => Array.from({ length: b - a + 1 }, (_, k) => a + k);

// ---- frames (ADR-0045, vinary.slides.deck) --------------------------------------------------------------------------

function frames(n, labels, hidden, split) {
  if (split) return n;                                  // a two-screen deck: one frame per page, no labels
  const shown = range(0, n - 1).filter((p) => !hidden.includes(p));
  if (!labels) return shown.length;
  let count = 0;
  shown.forEach((p, o) => {
    const a = o ? labels[shown[o - 1]] : null;
    if (!(o && a && a.trim() !== '' && a === labels[p])) count++;
  });
  return count;
}

// ---- the plan/settle table ------------------------------------------------------------------------------------------

function decide(art, trueLabels) {
  const reads = [];
  const n = art.pages;
  const split = Boolean(art.secondScreen);
  const L = art.labels && art.labels.every((l) => /^(0|[1-9]\d{0,8})$/.test(l)) ? art.labels.map(Number) : null;
  const trusted = TRUSTED.test(art.producer || '');
  const cm = contentModel(art, trueLabels);
  const shownAsIs = (reason, extra = {}) => ({ notes: 'none', hidden: [], notesProof: null, notesReason: reason, ...extra });
  const interleaved = (hidden, proof, extra = {}) => ({ notes: 'interleaved', hidden, notesProof: proof, notesReason: null, ...extra });
  let d;
  if (split) d = { notes: 'split', hidden: [], notesProof: null, notesReason: null, path: 'two-screen deck: split notes, the automaton never runs' };
  else if (n < 2) d = shownAsIs('one-page', { path: 'one page: nothing to hide' });
  else if (!art.labels) {
    const found = scan(cm, reads);
    d = found.length ? interleaved(found, ['scan'], { path: 'no labels → the scan finds note pages' })
                     : shownAsIs('no-note-pages', { path: 'no labels → the scan finds none' });
  } else if (!L) d = shownAsIs('labels-not-integers', { path: 'the labels are not all integers: the automaton does not run' });
  else {
    const il = automaton(L);
    const restart = restartable(L);
    if (il.status === 'proven') {
      if (trusted && !restart) d = interleaved(il.notes, ['labels'], { path: 'proven by the labels; trusted producer, not restartable → hidden at once' });
      else if (confirm(il.notes, cm, reads)) d = interleaved(il.notes, ['labels', 'confirm'], { path: `proven; ${trusted ? 'restartable' : 'untrusted producer'} → confirmed` });
      else d = shownAsIs('confirm-failed', { path: `proven; ${trusted ? 'restartable' : 'untrusted producer'} → confirmation fails` });
    } else if (il.status === 'ambiguous') {
      const chosen = probe(il, cm, reads);
      if (chosen.notes === null) d = shownAsIs('probe-undecided', { path: 'ambiguous → the probe stops and the fallback is invalid' });
      else if (!chosen.notes.length) d = shownAsIs('no-note-pages', { path: 'ambiguous → the probe keeps the parse without note pages' });
      else if (trusted) d = interleaved(chosen.notes, ['labels', 'probe'], { path: `ambiguous → the probe chooses candidate ${chosen.via}` });
      else if (confirm(chosen.notes, cm, reads)) d = interleaved(chosen.notes, ['labels', 'probe', 'confirm'], { path: `ambiguous → candidate ${chosen.via}, confirmed` });
      else d = shownAsIs('confirm-failed', { path: `ambiguous → candidate ${chosen.via}, the confirmation fails` });
    } else {
      d = shownAsIs(il.status === 'none' ? 'no-note-pages' : il.status, { path: `the automaton: ${il.status}` });
    }
    d.automaton = { status: il.status, validParses: il.parses, restartable: restart, trusted,
                    ...(il.status === 'ambiguous' ? { candidates: il.candidates, runEnd: il.runEnd, base: il.base, fallback: il.fallback } : {}),
                    ...(il.why ? { why: il.why } : {}) };
  }
  return {
    labels: art.labels, notes: d.notes, notePages: d.hidden.length, notesProof: d.notesProof, notesReason: d.notesReason,
    frames: frames(n, split ? null : art.labels, d.hidden, split), hidden: d.hidden,
    derivation: { path: d.path, trusted, template: art.template, ...(d.automaton ? { automaton: d.automaton } : {}), reads },
  };
}

function main() {
  const [prelook, target] = process.argv.slice(2);
  if (!prelook || !target) throw new Error('usage: derive-expectations.cjs <prelook.json> <expectations.json>');
  const r = JSON.parse(fs.readFileSync(prelook, 'utf8'));
  if (!r.noDetector) throw new Error('the input must come from a --no-detector run');
  const byId = new Map(r.artifacts.map((a) => [a.id, a]));
  // the true frame structure of each variant: the labels pdfLaTeX writes in the run itself (stale: LuaLaTeX's, v2)
  const trueLabelsOf = (a) => (a.kind === 'stale' ? byId.get('stale.lualatex') : byId.get(`${a.variant}.pdflatex`)).labels;
  const artifacts = {};
  const m2 = [];
  for (const a of r.artifacts) {
    if (!a.compiled) throw new Error(`${a.id} did not compile`);
    if (!a.oracle || !a.oracle.notePages) throw new Error(`${a.id}: no oracle`);
    artifacts[a.id] = decide(a, trueLabelsOf(a));
    // M2 against the input: the rotated date is on exactly the note pages of the default template (a two-screen deck
    // draws its notes half with the template too, but no page of it is ever read: it is split, not interleaved)
    if (!a.secondScreen) {
      a.pageInfo.forEach((p, i) => {
        const predicted = DATED.has(a.template) && a.oracle.notePages.includes(i);
        if (p.rotatedDate !== predicted) m2.push(`${a.id} page ${i}: rotated date ${p.rotatedDate}, M2 predicts ${predicted}`);
      });
    }
  }
  const doc = {
    experiment: 'H-NOTES-CORPUS',
    record: 'docs/scientific/13-speaker-note-detection.md',
    registered: new Date().toISOString(),
    basis: 'ADR-0048 §2 (docs/design-decisions/0048-speaker-notes-and-linked-presenter-windows.md) and the plan/settle table, applied by scripts/notes-corpus/derive-expectations.cjs to the labels and oracle of a --no-detector run; SIG and ROTATED-DATE predicted by the content model M1-M4 (PREREGISTRATION.md §4)',
    fields: ['notes', 'notePages', 'notesProof', 'notesReason', 'frames'],
    extra: { labels: 'the page labels the pre-look read (an input fact, re-checked by the run)', hidden: 'the note pages the detector is expected to hide (0-based)' },
    contentModelM2Exceptions: m2,
    artifacts,
  };
  fs.writeFileSync(target, JSON.stringify(doc, null, 1) + '\n');
  console.log(`wrote ${Object.keys(artifacts).length} expectations; M2 exceptions in the input: ${m2.length}`);
  for (const [id, e] of Object.entries(artifacts)) {
    console.log(`${id.padEnd(36)} ${(e.labels ? e.labels.join(' ') : 'null').padEnd(24)} ${e.notes.padEnd(12)} ${String(e.notePages).padEnd(2)} ` +
                `${(e.notesProof ? e.notesProof.join('+') : '-').padEnd(20)} ${(e.notesReason || '-').padEnd(16)} f${String(e.frames).padEnd(3)} ` +
                `hidden {${e.hidden.join(',')}}  ${e.derivation.path}`);
  }
}

main();
