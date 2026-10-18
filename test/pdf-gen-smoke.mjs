// pdf-gen smoke — checks test/fixtures/pdf-gen.js's speaker-notes output against REAL TeX output, so that a test
// built on a generated deck means what it would mean on a deck from Beamer or pdfpc.
//
// What is compared, generated deck against real deck, through pdf.js (the reader the app uses):
//   • `show notes` note pages (templates default and compressed, labelled and label-less, and `show only notes`):
//     the page count and sizes, the labels, and on every note page the thumbnail's scale and offset, the rotated
//     date's transform, and the title, frame-title and notes runs (string, size, position);
//   • a deck with one page per frame: its labels;
//   • pdfpc decks from LuaLaTeX and pdfLaTeX: each page's \pdfpcnote annotations (subtype, flags, contents as pdf.js
//     decodes them, pdfTeX's mojibake included), the labels, and the pdfpc keys in the raw XMP;
//   • the pdfpc sidecar writer against the committed sidecar;
// and, on generated decks alone, that pdf.js reads the five annotation types pdfpc reads, frameNumbers, and the
// option errors. The real decks and how they were compiled: the .tex beside each (test/fixtures/beamer-*.tex).
//
// Run: node test/pdf-gen-smoke.mjs   (part of `npm test`)

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const pdfjs = await import(require.resolve('pdfjs-dist/legacy/build/pdf.mjs'));
const { makeDeckPdf, makePdfpcSidecar } = require('./fixtures/pdf-gen.js');

const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures');
let passed = 0;
function ok(cond, msg) { assert.ok(cond, msg); console.log('  ✓ ' + msg); passed++; }

/** Every page of a PDF, as pdf.js reads it: its size, its text runs, its annotations; plus labels and raw XMP. */
async function read(bytes) {
  const doc = await pdfjs.getDocument({ data: new Uint8Array(bytes), verbosity: 0, isEvalSupported: false }).promise;
  const pages = [];
  for (let n = 1; n <= doc.numPages; n++) {
    const page = await doc.getPage(n);
    const [x0, y0, x1, y1] = page.view;
    const runs = (await page.getTextContent()).items.filter((it) => it.str.trim())
      .map((it) => ({ str: it.str, size: it.height, t: it.transform }));
    const annots = (await page.getAnnotations()).filter((a) => a.subtype !== 'Link')
      .map((a) => ({ subtype: a.subtype, flags: a.annotationFlags, contents: a.contentsObj ? a.contentsObj.str : '' }));
    pages.push({ w: x1 - x0, h: y1 - y0, runs, annots });
  }
  const labels = await doc.getPageLabels();
  const meta = await doc.getMetadata();
  const xmp = meta.metadata ? meta.metadata.getRaw() : null;
  await doc.destroy();
  return { pages, labels, xmp };
}
const readFixture = (name) => read(fs.readFileSync(path.join(FIXTURES, name)));

const near = (a, b, eps) => Math.abs(a - b) <= eps;
const rotated = (r) => near(r.t[0], 0, 1e-6) && near(Math.abs(r.t[1]), r.size, 1e-6);

/**
 * A note page's layout relative to the slide page it annotates: the thumbnail is the set of runs that repeat a
 * slide run's string at a scale σ ≤ 0.4 (the bound ADR-0048 §2.2 uses), all sharing one σ and one offset t, with
 * run = σ · slide-run + t; the date is the rotated run; the rest are the template's own runs.
 */
function layout(note, slide) {
  const copies = [];
  const thumbRuns = new Set();
  for (const r of note.runs) {
    if (rotated(r)) continue;
    for (const s of slide.runs) {
      const sigma = r.size / s.size;
      if (r.str === s.str && sigma <= 0.4) {
        copies.push({ sigma, tx: r.t[4] - sigma * s.t[4], ty: r.t[5] - sigma * s.t[5] });
        thumbRuns.add(r);
      }
    }
  }
  if (copies.length) {
    const { sigma, tx, ty } = copies[0];
    assert.ok(copies.every((c) => near(c.sigma, sigma, 1e-6) && near(c.tx, tx, 0.002) && near(c.ty, ty, 0.002)),
      'every thumbnail run shares one scale and one offset');
  }
  const date = note.runs.find(rotated) || null;
  const rest = note.runs.filter((r) => !thumbRuns.has(r) && r !== date).map((r) => ({ str: r.str, size: r.size, x: r.t[4], y: r.t[5] }));
  return { thumb: copies.length ? copies[0] : null, date, rest };
}

/** The generated note page j and the real one lay out the same: same thumbnail, date and template runs. */
function sameLayout(gen, real, eps = 0.002) {
  const g = layout(gen.note, gen.slide), r = layout(real.note, real.slide);
  if (!g.thumb !== !r.thumb) return `thumbnail ${JSON.stringify(g.thumb)} vs ${JSON.stringify(r.thumb)}`;
  if (g.thumb && !(near(g.thumb.sigma, r.thumb.sigma, 1e-6) && near(g.thumb.tx, r.thumb.tx, eps) && near(g.thumb.ty, r.thumb.ty, eps)))
    return `thumbnail ${JSON.stringify(g.thumb)} vs ${JSON.stringify(r.thumb)}`;
  if (!g.date !== !r.date) return 'date present in one only';
  if (g.date && !(g.date.str === r.date.str && g.date.t.every((v, i) => near(v, r.date.t[i], eps))))
    return `date ${JSON.stringify(g.date)} vs ${JSON.stringify(r.date)}`;
  if (g.rest.length !== r.rest.length) return `runs ${JSON.stringify(g.rest)} vs ${JSON.stringify(r.rest)}`;
  for (let i = 0; i < g.rest.length; i++) {
    const a = g.rest[i], b = r.rest[i];
    if (!(a.str === b.str && near(a.size, b.size, eps) && near(a.x, b.x, eps) && near(a.y, b.y, eps)))
      return `run ${JSON.stringify(a)} vs ${JSON.stringify(b)}`;
  }
  return null;
}

// ── 1. `show notes` — the fixture deck, generated ─────────────────────────────────────────────────────────────────
// beamer-shownotes.tex: Intro (2 overlays, an in-frame \note), a \note between frames, Middle, Outro (2 overlays,
// \note<2>), \againframe{middle}.
const NOTE_TEXT = ({ frame, between }) => (between ? 'A note written between two frames'
  : frame === 0 ? 'Notes for the introduction' : 'Notes for the second overlay of the outro');
const SHOWNOTES = {
  size: 43, frames: [2, 1, 2, 1], frameTitles: ['Intro', 'Middle', 'Outro', 'Middle'], title: 'Speaker notes fixture',
  notes: { inFrame: { 0: true, 2: [1] }, between: [0], text: NOTE_TEXT },
};
// the note pages and the slide page each one annotates, written down before the fixture was compiled
const NOTE_PAGES = [[1, 0], [3, 2], [4, 2], [8, 7]];

// boxEps and eps: how far a page box and a run may be from the real deck's. LuaTeX's are matched to 0.001 and
// 0.002 pt. xdvipdfmx (XeLaTeX) writes page boxes rounded to 0.01 pt (362.83 × 272.13) and places runs within
// 0.002 pt of LuaTeX's (measured on beamer-shownotes-xelatex.pdf against beamer-shownotes.pdf).
async function compareNotes(name, opts, notePages, fixture, { boxEps = 0.001, eps = 0.002 } = {}) {
  const gen = await read(makeDeckPdf(opts));
  const real = await readFixture(fixture);
  ok(gen.pages.length === real.pages.length && gen.pages.every((p, i) => near(p.w, real.pages[i].w, boxEps) && near(p.h, real.pages[i].h, boxEps)),
     `${name}: ${real.pages.length} pages of ${real.pages[0].w.toFixed(3)} × ${real.pages[0].h.toFixed(3)}, as ${fixture}`);
  ok(JSON.stringify(gen.labels) === JSON.stringify(real.labels),
     `${name}: the labels are ${real.labels ? real.labels.join(' ') : 'absent'}, as ${fixture}'s`);
  for (const [j, s] of notePages) {
    const why = sameLayout({ note: gen.pages[j], slide: gen.pages[s] }, { note: real.pages[j], slide: real.pages[s] }, eps + 1e-9);
    assert.equal(why, null, `${name}: note page ${j} lays out as ${fixture}'s: ${why}`);
  }
  ok(true, `${name}: note pages ${notePages.map(([j]) => j).join(', ')} lay out as ${fixture}'s (thumbnail, date, title, frame title, notes)`);
  return { gen, real };
}

{
  const { gen } = await compareNotes('show notes [default]', SHOWNOTES, NOTE_PAGES, 'beamer-shownotes.pdf');
  const thumb = layout(gen.pages[1], gen.pages[0]).thumb;
  ok(near(thumb.sigma, 0.25, 1e-9) && near(thumb.tx, 0.75 * 362.835, 0.001) && near(thumb.ty, 0.75 * 272.126, 0.001),
     'show notes [default]: the thumbnail is the slide at a quarter of its size, flush with the top-right corner');
  const between = layout(gen.pages[4], gen.pages[2]);
  ok(between.thumb && between.rest.some((r) => r.str === 'A note written between two frames'),
     'show notes [default]: the page of a \\note between frames shows the last slide page (Intro, overlay 2)');
  await compareNotes('show notes [compressed]', { ...SHOWNOTES, notes: { ...SHOWNOTES.notes, template: 'compressed' } },
    NOTE_PAGES, 'beamer-shownotes-compressed.pdf');
  await compareNotes('one XeLaTeX pass', { ...SHOWNOTES, labels: null }, NOTE_PAGES, 'beamer-shownotes-xelatex.pdf',
    { boxEps: 0.005 + 1e-9, eps: 0.002 });
  // `show only notes`: the four note pages alone; each one still annotates a slide page of the full deck, which the
  // generated and the real deck both lack, so the layout is compared against the full decks' slide pages
  const onlyGen = await read(makeDeckPdf({ ...SHOWNOTES, notes: { ...SHOWNOTES.notes, only: true } }));
  const onlyReal = await readFixture('beamer-onlynotes.pdf');
  const full = await readFixture('beamer-shownotes.pdf');
  const fullGen = await read(makeDeckPdf(SHOWNOTES));
  ok(onlyGen.pages.length === 4 && JSON.stringify(onlyGen.labels) === JSON.stringify(onlyReal.labels)
     && onlyReal.labels.join(' ') === '1 1 3 3',
     'show only notes: 4 pages labelled 1 1 3 3 (in-frame note pages carry their frame number, the between one its page number), as beamer-onlynotes.pdf');
  NOTE_PAGES.forEach(([j, s], n) => {
    const why = sameLayout({ note: onlyGen.pages[n], slide: fullGen.pages[s] }, { note: onlyReal.pages[n], slide: full.pages[s] });
    assert.equal(why, null, `show only notes: page ${n} lays out as beamer-onlynotes.pdf's: ${why}`);
  });
  ok(true, 'show only notes: every page is the `show notes` note page, as beamer-onlynotes.pdf');
}

// ── 2. a deck with one page per frame, and frame numbers set by hand ─────────────────────────────────────────────
{
  const gen = await read(makeDeckPdf({ size: 43, pages: 5 }));
  const real = await readFixture('beamer-plain.pdf');
  ok(JSON.stringify(gen.labels) === JSON.stringify(real.labels) && real.labels.join(' ') === '1 2 3 4 5',
     'one page per frame: labels 1 2 3 4 5, as beamer-plain.pdf');
  const numbered = await read(makeDeckPdf({ frames: [1, 1, 2], frameNumbers: [0, 0, 1],
    notes: { inFrame: { 2: [1] } } }));
  ok(numbered.labels.join(' ') === '0 0 1 1 5',
     'frameNumbers: a first frame marked noframenumbering is 0 and the next repeats it; a note page keeps its page number');
}

// ── 3. the note templates without a thumbnail, and the default template's evidence on an empty slide ────────────
{
  const plain = await read(makeDeckPdf({ size: 43, frames: [1, 1], notes: { inFrame: { 0: true }, template: 'plain', text: () => 'Alpha notes text' } }));
  const plainNote = plain.pages[1].runs;
  ok(plainNote.length === 1 && plainNote[0].str === 'Alpha notes text' && near(plainNote[0].t[5], 258.577, 0.002) && near(plainNote[0].t[4], 28.346, 0.001),
     '[plain]: the notes alone, at the baseline real LuaLaTeX output puts them (28.346, 258.577)');
  const lined = await read(makeDeckPdf({ size: 43, frames: [1, 1], notes: { inFrame: { 0: true }, template: 'lined' } }));
  ok(lined.pages[1].runs.length === 0 && lined.labels.join(' ') === '1 2 2', '[lined]: no text at all, as real LuaLaTeX output');
  const empty = await read(makeDeckPdf({ size: 43, frames: [1, 1], text: false, notes: { inFrame: { 0: true } } }));
  const l = layout(empty.pages[1], empty.pages[0]);
  ok(empty.pages[0].runs.length === 0 && !l.thumb && l.date && l.date.str === '2026-10-17',
     'an empty slide: its note page has no thumbnail text, and the default template\'s rotated date');
}

// ── 4. pdfpc: \pdfpcnote annotations and the XMP keys ────────────────────────────────────────────────────────────
{
  const XMP_KEYS = [['Duration', '20'], ['EndTime', '10:30'], ['EndUserSlide', '3'], ['LastMinutes', '3'],
                    ['NotesPosition', 'none'], ['DisableMarkdown', 'false']];
  const keys = (xmp) => [...xmp.matchAll(/<pdfpc:(\w+)>([^<]*)<\/pdfpc:\1>/g)].map((m) => [m[1], m[2]]);
  const nested = (xmp) => /<rdf:Description rdf:about=""[^>]*>[\s\S]*<rdf:Description xmlns:pdfpc="https:\/\/github\.com\/pdfpc\/pdfpc">/.test(xmp);
  // LuaTeX: pdfpc.sty converts the note to UTF-16BE hex before it redefines `\\`, so `\\` stays two characters
  const luaNote = 'A pdfpc note on both overlays of frame one\\\\with a second line, café and naïve';
  const link = 'Markdown with *emphasis* and a [link](https://example.org/)';
  const pdfpcDeck = (note, encoding) => makeDeckPdf({ size: 43, frames: [2, 1, 1, 1], title: 'pdfpc fixture', pdfpc: XMP_KEYS,
    textNotes: [{ page: 0, contents: note, encoding }, { page: 1, contents: note, encoding }, { page: 2, contents: link, encoding }] });
  const lua = await read(pdfpcDeck(luaNote, 'utf16'));
  const luaReal = await readFixture('beamer-pdfpc.pdf');
  ok(JSON.stringify(lua.pages.map((p) => p.annots)) === JSON.stringify(luaReal.pages.map((p) => p.annots)),
     'pdfpc (LuaLaTeX): hidden /Text annotations (/F 6) on pages 0, 1 and 2 with the same contents, as beamer-pdfpc.pdf');
  ok(luaReal.pages[0].annots[0].contents.includes('\\\\with') && luaReal.pages[0].annots[0].contents.includes('café'),
     '…where LuaTeX keeps `\\\\` as two characters and writes café correctly (UTF-16BE)');
  ok(JSON.stringify(lua.labels) === JSON.stringify(luaReal.labels) && luaReal.labels.join(' ') === '1 1 2 3 4',
     'pdfpc (LuaLaTeX): labels 1 1 2 3 4, as beamer-pdfpc.pdf');
  ok(JSON.stringify(keys(lua.xmp)) === JSON.stringify(keys(luaReal.xmp)) && JSON.stringify(keys(luaReal.xmp)) === JSON.stringify(XMP_KEYS),
     'pdfpc: the six pdfpc: keys of the raw XMP, in order, as beamer-pdfpc.pdf');
  ok(nested(lua.xmp) && nested(luaReal.xmp), '…each in a pdfpc rdf:Description NESTED in the packet\'s own, as pdfpc.sty writes it');
  // pdfTeX: `\\` is a newline, and the note's UTF-8 bytes go into a literal string, which pdf.js decodes as
  // PDFDocEncoding — the mojibake an annotation reader gets from a pdfTeX deck
  const texNote = 'A pdfpc note on both overlays of frame one\nwith a second line, café and naïve';
  const tex = await read(pdfpcDeck(texNote, 'utf8-literal'));
  const texReal = await readFixture('beamer-pdfpc-pdftex.pdf');
  ok(JSON.stringify(tex.pages.map((p) => p.annots)) === JSON.stringify(texReal.pages.map((p) => p.annots)),
     'pdfpc (pdfLaTeX): the same annotations, decoded the same way, as beamer-pdfpc-pdftex.pdf');
  ok(texReal.pages[0].annots[0].contents.includes('cafÃ©') && texReal.pages[0].annots[0].contents.includes('one\nwith'),
     '…where pdfTeX\'s UTF-8 reads back as PDFDocEncoding mojibake (cafÃ©) and `\\\\` is a newline');
}

// ── 5. the five annotation types pdfpc reads, flags, and the pdfpc sidecar ───────────────────────────────────────
{
  const types = ['Text', 'FreeText', 'Highlight', 'Underline', 'Squiggly', 'Square'];
  const many = await read(makeDeckPdf({ pages: 2, textNotes: [
    ...types.map((subtype) => ({ page: 0, subtype, contents: `a ${subtype} note`, flags: subtype === 'Text' ? 6 : 4 })),
    { page: 1, contents: 'no flags', flags: null }, { page: 1, contents: 'naïve', encoding: 'auto' }] }));
  ok(JSON.stringify(many.pages[0].annots.map((a) => [a.subtype, a.contents])) === JSON.stringify(types.map((t) => [t, `a ${t} note`])),
     'textNotes: pdf.js reads every annotation type, in /Annots order, with its contents');
  ok(many.pages[0].annots[0].flags === 6 && many.pages[1].annots[0].flags === 0 && many.pages[1].annots[1].contents === 'naïve',
     'textNotes: /F 6 (Hidden + Print) by default, none when null; a non-ASCII note in UTF-16BE');
  const sidecar = makePdfpcSidecar({ duration: 30, lastMinutes: 2, endSlide: 1, pages: [
    { idx: 0, label: '1', overlay: 0, note: 'A sidecar note that differs from the annotation' },
    { idx: 3, label: '3', overlay: 0, note: 'A sidecar note for frame three' },
    { idx: 4, label: '4', overlay: 0, hidden: true }] });
  ok(sidecar === fs.readFileSync(path.join(FIXTURES, 'beamer-pdfpc.pdfpc'), 'utf8'),
     'makePdfpcSidecar writes the committed sidecar, beamer-pdfpc.pdfpc, byte for byte');
  const writer = JSON.parse(makePdfpcSidecar({ duration: 0, lastMinutes: 5, endSlide: -1, beamerNotePosition: 'right', startTime: '10:00' }));
  ok(JSON.stringify(Object.keys(writer)) === JSON.stringify(['pdfpcFormat', 'startTime', 'beamerNotePosition', 'disableMarkdown', 'noteFontSize', 'pages'])
     && writer.beamerNotePosition === 'RIGHT',
     'makePdfpcSidecar: pdfpc\'s key order, defaults left out, the notes position in upper case');
}

// ── 6. option errors ─────────────────────────────────────────────────────────────────────────────────────────────
assert.throws(() => makeDeckPdf({ secondScreen: 'right', notes: {} }), /two Beamer modes/);
assert.throws(() => makeDeckPdf({ frames: [1, 2], notes: { inFrame: { 1: [2] } } }), /names no frame or overlay/);
assert.throws(() => makeDeckPdf({ frames: [1], notes: { between: [1] } }), /names a frame/);
assert.throws(() => makeDeckPdf({ frames: [1], notes: { template: 'fancy' } }), /unknown note template/);
assert.throws(() => makeDeckPdf({ frames: [1], frameTitles: ['Café'], notes: {} }), /printable ASCII/);
ok(true, 'option errors: notes with secondScreen, a missing frame or overlay, an unknown template, non-ASCII page text');

console.log(`\npdf-gen-smoke: ${passed} checks passed`);
