'use strict';
// The pair work of the note-page comparison on REAL pages — the census behind docs/scientific/14 §3.2 (H-PAIRWORK-FIX).
//
// QUESTION. vinary.slides.notes/note-page-signature? pairs every upright slide run with the note page's upright runs
// of the same string: those are its VISITS. It keeps a visit as a MATCH when the scale σ = note height / slide height
// is in (0, max-thumbnail-scale]. How many visits and matches do real pages ask for? Every pair the detector can
// compare — the probe's (a − 1, a), the scan's (last slide, j), CONFIRM's (s, j) — is an ordered pair (s, j) with
// s < j of one deck, so the maxima over all such pairs bound what any real decision asks of the comparison's caps.
//
// METHOD. pdf.js (the legacy build, as vv --cli reads pages) reads every page's text items; each becomes a run
// {:str :t} as vinary.slides.evidence/page-runs builds it, and the compiled namespace's own predicates (upright?,
// glyphs, run-height) select the runs the comparison uses. For each pair, uncapped:
//   visits  = Σ over the slide's upright runs with a glyph: the note page's upright runs of the same string
//   matches = the visits whose σ is in (0, max-thumbnail-scale]
// The comparison gives up (nil) when matches exceed max-signature-matches, or when either page has more than
// max-signature-runs runs; the census counts the pairs where that happens. Per PAGE it also counts the text's size in
// UTF-16 code units — the sum of the lengths of all its runs' strings, and the longest run — the measure a cap on a
// page's text would bound.
// --differential also calls, on every pair, both the namespace's note-page-signature? and the reference
// implementation of the test build (vinary.slides.notes-reference, the code before H-PAIRWORK-FIX), with the note
// page's own diagonal, and counts their verdicts and every pair on which they differ (check E2 of docs/scientific/14 §7.2).
//
// PRIVACY. A file is named only in the --list file, which must lie outside the repository. The output carries counts
// by GROUP, the label each line of the list gives its file, never a path, a title or a page's text.
//
//   npx shadow-cljs compile test
//   node scripts/exp-note-signature-census.cjs --list=<file of "group<TAB>path" lines> [--json=<summary.json>]
//                                             [--differential]

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { pathToFileURL } = require('url');

const ROOT = path.resolve(__dirname, '..');
const PDFJS = path.join(ROOT, 'node_modules', 'pdfjs-dist', 'legacy', 'build', 'pdf.mjs');
const opt = (k, d) => {
  const a = process.argv.find((x) => x.startsWith(`--${k}=`));
  return a ? a.slice(k.length + 3) : d;
};
const LIST = opt('list', null);
const JSON_OUT = opt('json', null);
const DIFFERENTIAL = process.argv.includes('--differential');
if (!LIST) throw new Error('--list=<file of "group<TAB>path" lines> is required');
const listAbs = path.resolve(LIST);
if (listAbs.startsWith(ROOT + path.sep)) throw new Error('--list must lie outside the repository (it carries paths)');

// ---- the compiled notes model (the test build, its tests not run) --------------------------------------------------

const TEST_JS = path.join(ROOT, 'dist', 'test', 'test.js');
const RUNNER = 'SHADOW_IMPORT("shadow.module.main.append.js");';
const src = fs.readFileSync(TEST_JS, 'utf8').replace(/^#!.*\n/, '');
if (!src.includes(RUNNER)) throw new Error(`${TEST_JS} no longer ends with the test runner's module`);
vm.runInThisContext(`(function (require, module, exports, __dirname, __filename) {${src.replace(RUNNER, '')}\n})`,
  { filename: TEST_JS })(require, module, module.exports, path.dirname(TEST_JS), TEST_JS);
const cljs = global.cljs.core;
const notes = global.vinary.slides.notes;
for (const f of ['upright_QMARK_', 'glyphs', 'run_height', 'note_page_signature_QMARK_']) {
  if (typeof notes[f] !== 'function') throw new Error(`vinary.slides.notes/${f} is not in ${TEST_JS}`);
}
const reference = global.vinary.slides.notes_reference;
if (DIFFERENTIAL && !(reference && typeof reference.note_page_signature_reference_QMARK_ === 'function')) {
  throw new Error(`--differential: vinary.slides.notes-reference is not in ${TEST_JS}`);
}
const MAX_SCALE = notes.max_thumbnail_scale;
const MAX_RUNS = notes.max_signature_runs;
const MAX_MATCHES = notes.max_signature_matches;

const K_STR = cljs.keyword('str');
const K_T = cljs.keyword('t');
const asRun = (s, t) => cljs.PersistentArrayMap.fromArray([K_STR, s, K_T, cljs.PersistentVector.fromArray(t, true)], true);

// ---- one page: the runs the comparison uses, grouped ----------------------------------------------------------------

function pageModel(items, diag) {
  const runs = [];
  let chars = 0;                          // the page's text, in UTF-16 code units: every run's string
  let longest = 0;
  for (const it of items) {
    if (typeof it.str === 'string' && Array.isArray(it.transform) && it.transform.length === 6) {
      runs.push(asRun(it.str, it.transform.slice()));
      chars += it.str.length;
      longest = Math.max(longest, it.str.length);
    }
  }
  const slide = [];                       // [string, height] of the upright runs with a glyph: the slide's side
  const byStr = new Map();                // string → ascending heights of the upright runs: the note page's side
  for (const r of runs) {
    if (!notes.upright_QMARK_(r)) continue;
    const s = cljs.get(r, K_STR);
    const h = notes.run_height(r);
    if (notes.glyphs(r) > 0) slide.push([s, h]);
    let hs = byStr.get(s);
    if (!hs) { hs = []; byStr.set(s, hs); }
    hs.push(h);
  }
  for (const hs of byStr.values()) hs.sort((a, b) => a - b);
  return { runs: runs.length, chars, longest, slide, byStr, diag,
    page: DIFFERENTIAL ? cljs.PersistentVector.fromArray(runs, true) : null };
}

// how many of the ascending heights `hs` give σ = h / hSlide in (0, MAX_SCALE]: σ is monotone in h, so a prefix
function keptPrefix(hs, hSlide) {
  let lo = 0;
  let hi = hs.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (hs[mid] / hSlide <= MAX_SCALE) lo = mid + 1; else hi = mid;
  }
  return lo;                              // upright? guarantees every height is positive, so σ > 0 throughout
}

function pairWork(sPage, jPage) {
  let visits = 0;
  let matches = 0;
  for (const [s, h] of sPage.slide) {
    const hs = jPage.byStr.get(s);
    if (!hs) continue;
    visits += hs.length;
    matches += keptPrefix(hs, h);
  }
  return { visits, matches };
}

// ---- the census ----------------------------------------------------------------------------------------------------

const THRESHOLDS = [256, 1024, 4096, 16384, 65536];
const newGroup = () => ({ files: 0, unreadable: 0, pages: 0, pairs: 0, adjacent: 0, maxRuns: 0, pageChars: [], maxLongest: 0,
  runCapPairs: 0, matchCapPairs: 0, visits: [], matches: [], adjVisits: [], adjMatches: [],
  verdicts: { true: 0, false: 0, null: 0 }, disagreements: 0 });

const quantile = (xs, p) => {
  if (xs.length === 0) return 0;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.max(0, Math.ceil(p * s.length) - 1))];
};

async function main() {
  const pdfjs = await import(pathToFileURL(PDFJS).href);
  const lines = fs.readFileSync(listAbs, 'utf8').split('\n').filter(Boolean);
  const groups = new Map();
  for (const line of lines) {
    const tab = line.indexOf('\t');
    if (tab < 0) throw new Error('every --list line is "group<TAB>path"');
    const group = line.slice(0, tab);
    const file = line.slice(tab + 1);
    if (!groups.has(group)) groups.set(group, newGroup());
    const g = groups.get(group);
    g.files++;
    let pages;
    try {
      const data = new Uint8Array(fs.readFileSync(file));
      const doc = await pdfjs.getDocument({ data, verbosity: 0, useSystemFonts: true, isEvalSupported: false }).promise;
      pages = [];
      for (let p = 1; p <= doc.numPages; p++) {
        const page = await doc.getPage(p);
        const [x0, y0, x1, y1] = page.view;
        pages.push(pageModel((await page.getTextContent()).items, Math.hypot(x1 - x0, y1 - y0)));
      }
      await doc.destroy();
    } catch (_e) {
      g.unreadable++;                     // the reason is not reported: pdf.js's message can name the file
      continue;
    }
    g.pages += pages.length;
    for (const pg of pages) {
      g.maxRuns = Math.max(g.maxRuns, pg.runs);
      g.pageChars.push(pg.chars);
      g.maxLongest = Math.max(g.maxLongest, pg.longest);
    }
    for (let j = 1; j < pages.length; j++) {
      for (let s = 0; s < j; s++) {
        g.pairs++;
        if (DIFFERENTIAL) {
          const got = notes.note_page_signature_QMARK_(pages[s].page, pages[j].page, pages[j].diag);
          const want = reference.note_page_signature_reference_QMARK_(pages[s].page, pages[j].page, pages[j].diag);
          g.verdicts[String(want)]++;
          if (got !== want) g.disagreements++;
        }
        if (pages[s].runs > MAX_RUNS || pages[j].runs > MAX_RUNS) { g.runCapPairs++; continue; }
        const { visits, matches } = pairWork(pages[s], pages[j]);
        if (matches > MAX_MATCHES) g.matchCapPairs++;
        g.visits.push(visits);
        g.matches.push(matches);
        if (s === j - 1) { g.adjacent++; g.adjVisits.push(visits); g.adjMatches.push(matches); }
      }
    }
  }
  const summary = { caps: { maxThumbnailScale: MAX_SCALE, maxSignatureRuns: MAX_RUNS, maxSignatureMatches: MAX_MATCHES },
    thresholds: THRESHOLDS, groups: {} };
  console.log(`caps: max-thumbnail-scale ${MAX_SCALE}, max-signature-runs ${MAX_RUNS}, max-signature-matches ${MAX_MATCHES}`);
  for (const [name, g] of groups) {
    const over = (xs) => THRESHOLDS.map((t) => xs.filter((x) => x > t).length);
    const row = {
      files: g.files, unreadable: g.unreadable, pages: g.pages, pairs: g.pairs, adjacentPairs: g.adjacent,
      maxRunsPerPage: g.maxRuns, runCapPairs: g.runCapPairs, matchCapPairs: g.matchCapPairs,
      pageChars: { max: quantile(g.pageChars, 1), p99: quantile(g.pageChars, 0.99), median: quantile(g.pageChars, 0.5),
        longestRun: g.maxLongest },
      visits: { max: quantile(g.visits, 1), p99: quantile(g.visits, 0.99), median: quantile(g.visits, 0.5), over: over(g.visits) },
      matches: { max: quantile(g.matches, 1), p99: quantile(g.matches, 0.99), median: quantile(g.matches, 0.5), over: over(g.matches) },
      adjacent: { visitsMax: quantile(g.adjVisits, 1), matchesMax: quantile(g.adjMatches, 1) },
      ...(DIFFERENTIAL ? { differential: { verdicts: g.verdicts, disagreements: g.disagreements } } : {}),
    };
    summary.groups[name] = row;
    console.log(`${name}: ${row.files} files (${row.unreadable} unreadable), ${row.pages} pages, ${row.pairs} pairs ` +
      `(${row.adjacentPairs} adjacent); most runs on a page ${row.maxRunsPerPage}; pairs past the run cap ` +
      `${row.runCapPairs}, past the match cap ${row.matchCapPairs}`);
    console.log(`  text    : code units per page max ${row.pageChars.max}, p99 ${row.pageChars.p99}, median ` +
      `${row.pageChars.median}; longest run ${row.pageChars.longestRun}`);
    console.log(`  visits  : max ${row.visits.max}, p99 ${row.visits.p99}, median ${row.visits.median}; pairs over ` +
      `${THRESHOLDS.join('/')}: ${row.visits.over.join('/')}; adjacent max ${row.adjacent.visitsMax}`);
    console.log(`  matches : max ${row.matches.max}, p99 ${row.matches.p99}, median ${row.matches.median}; pairs over ` +
      `${THRESHOLDS.join('/')}: ${row.matches.over.join('/')}; adjacent max ${row.adjacent.matchesMax}`);
    if (DIFFERENTIAL) {
      console.log(`  E2      : the reference's verdicts true ${g.verdicts.true}, false ${g.verdicts.false}, ` +
        `nil ${g.verdicts.null}; pairs where the fixed signature differs: ${g.disagreements}`);
    }
  }
  if (JSON_OUT) fs.writeFileSync(JSON_OUT, JSON.stringify(summary, null, 1));
}

main().catch((e) => { console.error(e && e.stack || e); process.exit(1); });
