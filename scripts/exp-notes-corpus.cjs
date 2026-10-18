'use strict';
// Speaker-note detection on REAL TeX output -- the pre-registered corpus experiment of docs/scientific/13
// (H-NOTES-CORPUS), for ADR-0048 §2 (docs/design-decisions/0048-speaker-notes-and-linked-presenter-windows.md).
//
// QUESTION. Beamer's `\setbeameroption{show notes}` interleaves NOTE PAGES with the slides. vinary.slides.notes and
// vinary.slides.evidence decide which pages are note pages (the label automaton, the probe, the scan, the
// confirmation), and the slide show hides them from the audience. On what pdfLaTeX, LuaLaTeX and XeLaTeX really
// write, does the detector (1) reach the decision that ADR-0048's rules predict, and (2) stay SAFE: never hide a page
// that is a slide, and, when it hides pages, hide exactly the note pages?
//
// METHOD.
//   1. Compile every source of scripts/notes-corpus/ with pdfLaTeX (1 pass), LuaLaTeX (1 pass) and XeLaTeX (1 pass, and
//      2 passes), each artifact in its own fresh directory, every TeX run pinned with `taskset -c <cpus>`; plus the
//      stale-labels case (stale-v1 compiled twice, replaced by stale-v2, compiled once) under XeLaTeX and LuaLaTeX.
//   2. THE ORACLE, independent of the detector: pdf.js (the legacy build) reads every page's text. Every \note in the
//      corpus carries a marker token NOTEMARK<k>X, so a page is a note page iff its text holds one. Two rules refine it:
//        twin        the `lined` template draws no note text, so a lined artifact takes the note pages of its `default`
//                    twin (same source, engine and passes) -- valid only if the template does not change which pages
//                    are note pages, which is CHECKED on the templates whose text is drawn (default, compressed, plain)
//        two-screen  a `show notes on second screen` deck has no note pages (every page is a slide half plus a notes
//                    half, ADR-0048 §1), checked by its geometry (every page doubles the Beamer paper)
//   3. THE DETECTOR, two readings of the same compiled code (dist/cli/vv-cli.js, a shadow-cljs development build):
//        - `vv --cli --deck-info` (`node dist/cli/vv-cli.js --deck-info <pdf>… < /dev/null`), the instrument: notes,
//          notePages (a COUNT), notesProof, notesReason, frames;
//        - the hidden SET: --deck-info prints only a count, so this script loads the same bundle's namespaces in a
//          child process (the bundle's main is not run) and calls vinary.slides.evidence/detect exactly as
//          vinary.cli.core/deck-info does, reading :note-pages. Its count, style, proof and reason are checked against
//          the CLI's line. A release build (:simple) hides its namespaces: the sets are then reported as unavailable.
//   4. Compare with scripts/notes-corpus/expectations.json (written, and hashed in PREREGISTRATION.md, BEFORE the
//      detector ever ran on the corpus) and with the oracle: S1 hidden ⊆ note pages; S2 hidden ≠ ∅ ⇒ hidden = note pages.
//
//   npm run compile:cli:test      # once: the development build of the CLI
//   TMPDIR=<scratch> node scripts/exp-notes-corpus.cjs --out=<fresh dir> --json=<results.json> | tee <run.txt>
//     --out=<dir>     where everything is compiled: a new or empty directory (a non-empty one is refused)
//     --json=<file>   the full results, with every path relative to --out
//     --cpus=<list>   the CPU list for taskset (default 16-23)
//     --no-detector   compile and read the oracle only, then stop: what the pre-registration was allowed to look at
//
// Needs TeX Live with Beamer, appendixnumberbeamer and pgfpages, and the `taskset` of util-linux.

const fs = require('fs');
const path = require('path');
const cp = require('child_process');
const crypto = require('crypto');
const { pathToFileURL } = require('url');

const ROOT = path.resolve(__dirname, '..');
const CORPUS = path.join(__dirname, 'notes-corpus');
const EXPECTATIONS = path.join(CORPUS, 'expectations.json');
const PREREGISTRATION = path.join(CORPUS, 'PREREGISTRATION.md');
const CLI = path.join(ROOT, 'dist', 'cli', 'vv-cli.js');
const PDFJS = path.join(ROOT, 'node_modules', 'pdfjs-dist', 'legacy', 'build', 'pdf.mjs');
const PDFJS_PACKAGE = path.join(ROOT, 'node_modules', 'pdfjs-dist', 'package.json');
// the compiled detector and its sources, hashed into the results so a run names exactly the code it measured
const DETECTOR_FILES = [
  'dist/cli/vv-cli.js',
  '.shadow-cljs/builds/cli/dev/out/cljs-runtime/vinary.slides.notes.js',
  '.shadow-cljs/builds/cli/dev/out/cljs-runtime/vinary.slides.evidence.js',
  '.shadow-cljs/builds/cli/dev/out/cljs-runtime/vinary.slides.deck.js',
  '.shadow-cljs/builds/cli/dev/out/cljs-runtime/vinary.cli.core.js',
  'src/vinary/slides/notes.cljs',
  'src/vinary/slides/evidence.cljs',
  'src/vinary/slides/deck.cljs',
  'src/vinary/cli/core.cljs',
];

const FIELDS = ['notes', 'notePages', 'notesProof', 'notesReason', 'frames'];
const MARKER = /NOTEMARK\d+X/g;
const BEAMER_43 = [362.835, 272.126];   // beamer.cls, aspectratio=43 (the default), in bp
const SIZE_TOLERANCE = 0.5;             // bp, as vinary.slides.deck/size-tolerance

// id → the program and how many passes it gets (xelatex2: XeLaTeX run twice in the same directory)
const ENGINES = [
  { id: 'pdflatex', cmd: 'pdflatex', passes: 1 },
  { id: 'lualatex', cmd: 'lualatex', passes: 1 },
  { id: 'xelatex', cmd: 'xelatex', passes: 1 },
  { id: 'xelatex2', cmd: 'xelatex', passes: 2 },
];

// The corpus. `templates`: the source reads \vvnotetemplate, and each template is its own variant
// (`<base>-<template>`); otherwise Beamer's default note template applies.
const SOURCES = [
  { base: 'a-last-note', why: 'one page per frame, a note on the last frame only: the plain run, the probe from the last candidate' },
  { base: 'b-no-notes', why: 'one page per frame, show notes on, no notes: the probe reads two pages' },
  { base: 'c-overlays', why: 'overlays (\\pause, <2->) and per-overlay notes: the labels prove the parse' },
  { base: 'd-between', why: 'a \\note between two frames, after a frame with a note: two note pages in a row' },
  { base: 'e-againframe', why: '\\againframe of an overlay that has a note' },
  { base: 'f-noframenumbering', why: 'a noframenumbering frame with a note' },
  { base: 'f0-unnumbered-first', why: 'the first frame noframenumbering (label 0), with a note' },
  { base: 'g-appendix-no-notes', why: 'appendixnumberbeamer restarts the counter, no notes: the restartable? counterexample' },
  { base: 'h-appendix-notes', why: 'the same restart with notes: a label drops' },
  { base: 'i-templates', templates: ['default', 'compressed', 'plain', 'lined'], why: 'the four note-page templates on one deck' },
  { base: 'j-only-notes', why: 'show only notes: a notes handout' },
  { base: 'k-counter-set', why: '\\setcounter{framenumber}{1}: the first label is 2' },
  { base: 'l-second-screen', secondScreen: true, why: 'control: show notes on second screen=right (split notes)' },
  { base: 'm-empty-slide', templates: ['default', 'compressed'], why: 'an empty last slide with a note: the date decides' },
  { base: 'n-trailing-notes', templates: ['default', 'compressed'], why: 'two note pages after the last slide: the probe walks on the date' },
  { base: 'o-run-between', templates: ['default', 'compressed'], why: 'two note pages in a row inside the plain run, no valid parse without them: the probe walks on the date, or is undecided' },
  { base: 'p-roman-numbers', why: 'Roman frame numbers: labels that are not all integers' },
];
// stale-v1 twice, stale-v2 once, in one directory: XeLaTeX carries v1's labels into v2 (ADR-0048 §2.5); LuaLaTeX is
// the control, writing v2's own labels in the run itself
const STALE = {
  v1: 'stale-v1', v2: 'stale-v2', job: 'stale', v1Passes: 2, v2Passes: 1,
  engines: [{ id: 'xelatex', cmd: 'xelatex' }, { id: 'lualatex', cmd: 'lualatex' }],
  why: 'stale labels: v1 compiled twice, v2 (a frame inserted) compiled once over v1\'s .aux',
};

// ---- arguments ------------------------------------------------------------------------------------------------------

function arg(name) {
  const hit = process.argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (!hit) return null;
  return hit.includes('=') ? hit.slice(hit.indexOf('=') + 1) : true;
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

// ---- the corpus -----------------------------------------------------------------------------------------------------

function corpusArtifacts() {
  const out = [];
  for (const s of SOURCES) {
    const variants = s.templates
      ? s.templates.map((t) => ({ variant: `${s.base}-${t}`, template: t, templated: true }))
      : [{ variant: s.base, template: 'default', templated: false }];
    for (const v of variants) {
      for (const e of ENGINES) {
        out.push({ id: `${v.variant}.${e.id}`, kind: 'corpus', source: s.base, variant: v.variant, template: v.template,
                   templated: v.templated, engine: e.id, cmd: e.cmd, passes: e.passes,
                   secondScreen: Boolean(s.secondScreen), why: s.why });
      }
    }
  }
  for (const e of STALE.engines) {
    out.push({ id: `stale.${e.id}`, kind: 'stale', source: 'stale', variant: 'stale', template: 'default', templated: false,
               engine: e.id, cmd: e.cmd, passes: STALE.v1Passes + STALE.v2Passes, secondScreen: false, why: STALE.why });
  }
  return out;
}

// the `lined` template draws no note text: its oracle is the `default` variant of the same source and engine
const twinOf = (a) => (a.template === 'lined' ? `${a.source}-default.${a.engine}` : null);

// ---- compiling ------------------------------------------------------------------------------------------------------

function runTex(cmd, args, dir, cpus, consoleFile) {
  const r = cp.spawnSync('taskset', ['-c', cpus, cmd, ...args],
    { cwd: dir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300000, maxBuffer: 64 * 1024 * 1024 });
  fs.appendFileSync(consoleFile, `$ taskset -c ${cpus} ${cmd} ${args.join(' ')}\n${r.stdout || ''}${r.stderr || ''}` +
                                 `\n[exit ${r.status}${r.error ? ' ' + r.error.message : ''}]\n\n`);
  return r.status;
}

// Compile one artifact into its own directory → { pdf, status, passPdfs }. Multi-pass runs keep a copy of the PDF that
// each pass wrote (<job>.pass<k>.pdf), so the labels of every pass can be read.
function compile(a, outDir, cpus) {
  const dir = path.join(outDir, a.id);
  fs.mkdirSync(dir);
  const consoleFile = path.join(dir, 'console.txt');
  const flags = ['-interaction=nonstopmode', '-halt-on-error'];
  const passPdfs = [];
  let status = 0;
  let job;
  const pass = (args, k) => {
    status = runTex(a.cmd, args, dir, cpus, consoleFile);
    const pdf = path.join(dir, `${job}.pdf`);
    if (a.passes > 1 && fs.existsSync(pdf)) {
      const copy = path.join(dir, `${job}.pass${k}.pdf`);
      fs.copyFileSync(pdf, copy);
      passPdfs.push(copy);
    }
  };
  if (a.kind === 'stale') {
    job = STALE.job;
    const tex = path.join(dir, `${job}.tex`);
    let k = 0;
    fs.copyFileSync(path.join(CORPUS, `${STALE.v1}.tex`), tex);
    for (let i = 0; i < STALE.v1Passes && status === 0; i++) pass([...flags, `${job}.tex`], ++k);
    if (status === 0) fs.copyFileSync(path.join(dir, `${job}.aux`), path.join(dir, `${job}.v1.aux`));
    if (status === 0) fs.copyFileSync(path.join(CORPUS, `${STALE.v2}.tex`), tex);
    for (let i = 0; i < STALE.v2Passes && status === 0; i++) pass([...flags, `${job}.tex`], ++k);
  } else {
    fs.copyFileSync(path.join(CORPUS, `${a.source}.tex`), path.join(dir, `${a.source}.tex`));
    job = a.variant;
    const args = a.templated
      ? [...flags, `-jobname=${job}`, `\\def\\vvnotetemplate{${a.template}}\\input{${a.source}}`]
      : [...flags, `${a.source}.tex`];
    for (let k = 1; k <= a.passes && status === 0; k++) pass(args, k);
  }
  const pdf = path.join(dir, `${job}.pdf`);
  return { dir, pdf: status === 0 && fs.existsSync(pdf) ? pdf : null, status, passPdfs };
}

// ---- the oracle: pdf.js page text, independent of the detector ------------------------------------------------------

function quarterTurn(t) {
  const h = Math.hypot(t[2], t[3]);
  return h > 0 && Math.abs(t[0]) <= 1e-3 * h && Math.abs(t[3]) <= 1e-3 * h && Math.abs(t[1]) >= 0.999 * h;
}

async function readPdf(pdfjs, file, withPages) {
  const data = new Uint8Array(fs.readFileSync(file));
  const doc = await pdfjs.getDocument({ data, verbosity: 0, useSystemFonts: true, isEvalSupported: false }).promise;
  try {
    const labels = await doc.getPageLabels();
    const meta = await doc.getMetadata().catch(() => null);
    const info = (meta && meta.info) || {};
    const out = { pages: doc.numPages, labels: labels ? Array.from(labels) : null,
                  producer: info.Producer || null, creator: info.Creator || null };
    if (!withPages) return out;
    out.pageInfo = [];
    for (let p = 1; p <= doc.numPages; p++) {
      const page = await doc.getPage(p);
      const vp = page.getViewport({ scale: 1 });
      const items = (await page.getTextContent()).items.filter((it) => typeof it.str === 'string');
      const joined = items.map((it) => it.str).join('');
      const turned = items.filter((it) => Array.isArray(it.transform) && quarterTurn(it.transform)).map((it) => it.str).join('');
      out.pageInfo.push({
        w: vp.width, h: vp.height,
        markers: [...new Set(joined.match(MARKER) || [])],
        chars: joined.replace(/\s/g, '').length,
        // a diagnostic of the input, not the oracle: a run turned by ±90° that reads as a date (the default note
        // template draws one, ADR-0048 §2.2)
        rotatedDate: /\d{4}-\d{2}-\d{2}/.test(turned.replace(/\s/g, '')),
        text: items.map((it) => it.str).join(' ').replace(/\s+/g, ' ').trim().slice(0, 140),
      });
      page.cleanup();
    }
    return out;
  } finally {
    await doc.destroy().catch(() => {});
  }
}

const markerPages = (read) => read.pageInfo.flatMap((p, i) => (p.markers.length ? [i] : []));
const near = (x, y) => Math.abs(x - y) <= SIZE_TOLERANCE;

function groundTruth(a, byId) {
  if (!a.read) return null;
  if (a.secondScreen) {
    const doubled = a.read.pageInfo.every((p) => near(p.w, 2 * BEAMER_43[0]) && near(p.h, BEAMER_43[1]));
    return doubled ? { notePages: [], rule: 'two-screen' } : { notePages: null, rule: 'two-screen', error: 'the pages do not double the paper' };
  }
  const twin = twinOf(a);
  if (twin) {
    const t = byId.get(twin);
    if (!t || !t.read) return { notePages: null, rule: `twin:${twin}`, error: 'the twin did not compile' };
    if (t.read.pages !== a.read.pages) return { notePages: null, rule: `twin:${twin}`, error: 'the twin has another page count' };
    return { notePages: markerPages(t.read), rule: `twin:${twin}` };
  }
  return { notePages: markerPages(a.read), rule: 'markers' };
}

// The twin rule's assumption, checked: for each templated source and engine, the templates whose note text is drawn
// mark the same pages, and every template gives the same page count.
function templateInvariance(arts) {
  const groups = new Map();
  for (const a of arts.filter((x) => x.templated && x.read)) {
    const k = `${a.source}.${a.engine}`;
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(a);
  }
  return [...groups].map(([group, as]) => {
    const drawn = as.filter((a) => a.template !== 'lined');
    const sets = Object.fromEntries(drawn.map((a) => [a.template, markerPages(a.read)]));
    const pages = Object.fromEntries(as.map((a) => [a.template, a.read.pages]));
    const one = (xs) => xs.every((x) => JSON.stringify(x) === JSON.stringify(xs[0]));
    return { group, noteSetsByTemplate: sets, pagesByTemplate: pages,
             setsEqual: one(Object.values(sets)), pagesEqual: one(Object.values(pages)) };
  });
}

// ---- the detector ---------------------------------------------------------------------------------------------------

function runCli(pdfs, cpus) {
  const r = cp.spawnSync('taskset', ['-c', cpus, process.execPath, CLI, '--deck-info', ...pdfs],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024, timeout: 900000 });
  const byPath = new Map();
  for (const line of (r.stdout || '').split('\n').filter(Boolean)) {
    const o = JSON.parse(line);
    byPath.set(o.path, o);
  }
  return { status: r.status, stderr: r.stderr || '', byPath };
}

// The bundle's last line runs vinary.cli.core/main over process.argv. Everything before it only defines namespaces,
// which a development build puts on `global` (shadow-cljs: global.$CLJS = global). So the bundle is evaluated with that
// one line left out, with the require, module and __dirname it would have had as dist/cli/vv-cli.js.
const MAIN_CALL = 'SHADOW_IMPORT("shadow.module.main.append.js");';

function loadDetector() {
  const Module = require('module');
  const vm = require('vm');
  const src = fs.readFileSync(CLI, 'utf8');
  if (!src.includes('SHADOW_IMPORT_PATH') || !src.includes(MAIN_CALL)) {
    throw new Error('dist/cli/vv-cli.js is not a shadow-cljs development build: npm run compile:cli:test makes one');
  }
  const body = src.replace(/^#!.*\n/, '').replace(MAIN_CALL, '/* main is not run: the namespaces are called directly */');
  const fn = vm.runInThisContext(`(function (require, module, __filename, __dirname) {${body}\n})`, { filename: CLI });
  fn(Module.createRequire(CLI), { exports: {} }, CLI, path.dirname(CLI));
  const v = global.vinary;
  const c = global.cljs;
  if (!(v && v.slides && v.slides.evidence && typeof v.slides.evidence.detect === 'function' &&
        v.terminal && v.terminal.pdf && typeof v.terminal.pdf.open_document === 'function' && c && c.core)) {
    throw new Error('the bundle did not define vinary.slides.evidence/detect and vinary.terminal.pdf/open-document');
  }
  return { v, c };
}

// What vinary.cli.core/deck-info does for one PDF, up to the notes model: open with the headless pdf.js, gather the
// evidence and the page sizes, evidence/detect with the parsed sidecar (none in this corpus: checked).
async function hiddenSet({ v, c }, file) {
  const K = (s) => c.core.keyword(s);
  const js = (x) => c.core.clj__GT_js(x);
  const doc = await v.terminal.pdf.open_document(fs.readFileSync(file));
  try {
    const both = await Promise.all([v.slides.evidence.gather(doc), v.terminal.pdf.page_sizes(doc)]);
    const ev = c.core.assoc(both[0], K('sizes'), both[1]);
    const model = await v.slides.evidence.detect(doc, ev, v.slides.notes.sidecar_of(null));
    const np = js(c.core.get(model, K('note-pages')));
    return { notePageList: Array.from(np || []).sort((x, y) => x - y), style: js(c.core.get(model, K('style'))),
             proof: js(c.core.get(model, K('proof'))), reason: js(c.core.get(model, K('reason'))) };
  } finally {
    await doc.destroy().catch(() => {});
  }
}

async function hiddenSetsChild(listFile, outFile) {
  const files = JSON.parse(fs.readFileSync(listFile, 'utf8'));
  const lines = [];
  let det;
  try {
    det = loadDetector();
  } catch (e) {
    fs.writeFileSync(outFile, JSON.stringify({ loadError: String((e && e.message) || e) }) + '\n');
    return;
  }
  for (const f of files) {
    try {
      lines.push(JSON.stringify({ path: f, ...(await hiddenSet(det, f)) }));
    } catch (e) {
      lines.push(JSON.stringify({ path: f, error: String((e && e.message) || e) }));
    }
  }
  fs.writeFileSync(outFile, lines.join('\n') + '\n');
}

function runHiddenSets(pdfs, outDir, cpus) {
  const listFile = path.join(outDir, 'hidden-sets.input.json');
  const outFile = path.join(outDir, 'hidden-sets.jsonl');
  fs.writeFileSync(listFile, JSON.stringify(pdfs));
  const r = cp.spawnSync('taskset', ['-c', cpus, process.execPath, __filename, `--hidden-sets=${listFile}`, `--hidden-out=${outFile}`],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024, timeout: 900000 });
  const byPath = new Map();
  let loadError = null;
  if (fs.existsSync(outFile)) {
    for (const line of fs.readFileSync(outFile, 'utf8').split('\n').filter(Boolean)) {
      const o = JSON.parse(line);
      if (o.loadError) loadError = o.loadError;
      else byPath.set(o.path, o);
    }
  } else {
    loadError = `the child wrote nothing (exit ${r.status}): ${(r.stderr || '').slice(0, 400)}`;
  }
  return { status: r.status, loadError, byPath };
}

// ---- comparing ------------------------------------------------------------------------------------------------------

const same = (x, y) => JSON.stringify(x === undefined ? null : x) === JSON.stringify(y === undefined ? null : y);
const setText = (xs) => (xs == null ? '?' : `{${xs.join(',')}}`);
const proofText = (p) => (p && p.length ? p.join('+') : '-');
const decisionText = (d) => (d ? `${d.notes || '-'} ${d.notePages} ${proofText(d.notesProof)} ${d.notesReason || '-'} f${d.frames}` : 'NONE');
const labelsText = (l) => (l ? l.join(' ') : 'null');

function safetyOf(hidden, notes) {
  if (!hidden || !notes) return { class: 'unmeasured', subset: null, exact: null };
  const subset = hidden.every((p) => notes.includes(p));
  const exact = hidden.length ? subset && hidden.length === notes.length : null;
  const cls = !subset ? 'UNSAFE' : !hidden.length ? (notes.length ? 'shown-as-is' : 'no-notes')
    : hidden.length === notes.length ? 'exact' : 'partial';
  return { class: cls, subset, exact };
}

function preregisteredHash() {
  if (!fs.existsSync(PREREGISTRATION)) return null;
  const m = /expectations\.json[^\n]*?\b([0-9a-f]{64})\b/.exec(fs.readFileSync(PREREGISTRATION, 'utf8'));
  return m ? m[1] : null;
}

function versions(arts) {
  const first = (cmd) => cp.execFileSync(cmd, ['--version'], { encoding: 'utf8' }).split('\n')[0];
  const env = { node: process.version, pdfjs: JSON.parse(fs.readFileSync(PDFJS_PACKAGE, 'utf8')).version,
                pdflatex: first('pdflatex'), lualatex: first('lualatex'), xelatex: first('xelatex') };
  const log = arts.map((a) => a.out && a.out.pdf && a.out.pdf.replace(/\.pdf$/, '.log')).find((f) => f && fs.existsSync(f));
  if (log) {
    const text = fs.readFileSync(log, 'latin1');
    env.beamer = (/^Document Class: beamer (.*)$/m.exec(text) || [])[1] || null;
    env.hyperref = (/^Package: hyperref (.*)$/m.exec(text) || [])[1] || null;
  }
  try {
    env.gitHead = cp.execFileSync('git', ['-C', ROOT, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
    env.detectorDirty = cp.execFileSync('git', ['-C', ROOT, 'status', '--porcelain', '--', 'src/vinary/slides', 'src/vinary/cli'],
      { encoding: 'utf8' }).trim().split('\n').filter(Boolean);
  } catch (_) { /* not a checkout */ }
  env.detectorSha256 = Object.fromEntries(DETECTOR_FILES.filter((f) => fs.existsSync(path.join(ROOT, f)))
    .map((f) => [f, sha256(path.join(ROOT, f))]));
  env.corpusSha256 = Object.fromEntries(fs.readdirSync(CORPUS).filter((f) => f.endsWith('.tex')).sort()
    .map((f) => [f, sha256(path.join(CORPUS, f))]));
  return env;
}

// ---- main -----------------------------------------------------------------------------------------------------------

async function main() {
  const out = arg('out');
  const json = arg('json');
  const cpus = arg('cpus') || '16-23';
  const noDetector = Boolean(arg('no-detector'));
  if (typeof out !== 'string') throw new Error('--out=<a new or empty directory> is required');
  const outDir = path.resolve(out);
  if (fs.existsSync(outDir) && fs.readdirSync(outDir).length) throw new Error(`refusing to reuse the non-empty ${outDir}`);
  fs.mkdirSync(outDir, { recursive: true });

  const arts = corpusArtifacts();
  let expectations = null;
  let expectationsSha256 = null;
  if (!noDetector) {
    expectations = JSON.parse(fs.readFileSync(EXPECTATIONS, 'utf8'));
    expectationsSha256 = sha256(EXPECTATIONS);
    const ids = new Set(arts.map((a) => a.id));
    const missing = arts.filter((a) => !expectations.artifacts[a.id]).map((a) => a.id);
    const extra = Object.keys(expectations.artifacts).filter((k) => !ids.has(k));
    if (missing.length || extra.length) throw new Error(`expectations.json does not match the corpus: missing ${missing}, extra ${extra}`);
  }
  const registered = preregisteredHash();
  const started = new Date().toISOString();
  console.log(`# exp-notes-corpus ${started}  node ${process.version}  taskset -c ${cpus}`);
  if (expectationsSha256) {
    console.log(`# expectations.json sha256 ${expectationsSha256}` +
                (registered ? `  ${registered === expectationsSha256 ? 'matches' : 'DOES NOT MATCH'} PREREGISTRATION.md` : '  (no registered hash found)'));
  }

  // 1. compile
  for (const a of arts) {
    a.out = compile(a, outDir, cpus);
    if (!a.out.pdf) console.log(`# ${a.id}: COMPILE FAILED (exit ${a.out.status}); see ${path.relative(outDir, a.out.dir)}/console.txt`);
  }
  const env = versions(arts);
  for (const k of ['pdflatex', 'lualatex', 'xelatex', 'beamer', 'hyperref', 'pdfjs']) console.log(`# ${k}: ${env[k]}`);

  // 2. the oracle
  const pdfjs = await import(pathToFileURL(PDFJS).href);
  for (const a of arts.filter((x) => x.out.pdf)) {
    a.read = await readPdf(pdfjs, a.out.pdf, true);
    a.passReads = [];
    for (const p of a.out.passPdfs) a.passReads.push({ pdf: path.relative(outDir, p), ...(await readPdf(pdfjs, p, false)) });
    if (fs.existsSync(a.out.pdf.replace(/\.pdf$/, '.pdfpc'))) throw new Error(`${a.id}: a pdfpc sidecar beside the PDF`);
  }
  const byId = new Map(arts.map((a) => [a.id, a]));
  for (const a of arts) a.truth = groundTruth(a, byId);
  const invariance = templateInvariance(arts);

  // 3. the detector
  const built = arts.filter((a) => a.out.pdf);
  let cli = null;
  let sets = null;
  if (!noDetector) {
    cli = runCli(built.map((a) => a.out.pdf), cpus);
    sets = runHiddenSets(built.map((a) => a.out.pdf), outDir, cpus);
  }

  // 4. compare and print
  const rel = (p) => (p ? path.relative(outDir, p) : null);
  const rows = [];
  for (const a of arts) {
    const row = { id: a.id, kind: a.kind, source: a.source, variant: a.variant, template: a.template, templated: a.templated,
                  engine: a.engine, passes: a.passes, secondScreen: a.secondScreen, why: a.why, pdf: rel(a.out.pdf),
                  compiled: Boolean(a.out.pdf), exit: a.out.status };
    if (a.read) {
      Object.assign(row, { pages: a.read.pages, labels: a.read.labels, producer: a.read.producer, creator: a.read.creator,
                           passReads: a.passReads, pageInfo: a.read.pageInfo, oracle: a.truth });
    }
    if (!noDetector) {
      const exp = expectations.artifacts[a.id];
      row.expected = Object.fromEntries(FIELDS.map((f) => [f, exp[f] === undefined ? null : exp[f]]));
      row.expectedLabels = exp.labels === undefined ? null : exp.labels;
      row.expectedHidden = exp.hidden === undefined ? null : exp.hidden;
      const got = a.out.pdf ? cli.byPath.get(a.out.pdf) : null;
      row.observed = got ? Object.fromEntries(FIELDS.map((f) => [f, got[f] === undefined ? null : got[f]])) : null;
      row.deckInfo = got ? { ...got, path: rel(got.path) } : null;
      row.mismatched = got ? FIELDS.filter((f) => !same(got[f], exp[f])) : FIELDS.slice();
      row.labelsAsRegistered = a.read ? same(a.read.labels, exp.labels) : false;
      const s = a.out.pdf && sets && !sets.loadError ? sets.byPath.get(a.out.pdf) : null;
      row.hidden = s && !s.error ? s.notePageList : null;
      row.hiddenError = s && s.error ? s.error : null;
      row.setAgreesWithCli = s && !s.error && got
        ? s.notePageList.length === got.notePages && same(s.style, got.notes) && same(s.proof, got.notesProof) && same(s.reason, got.notesReason)
        : null;
      row.hiddenAsRegistered = row.hidden ? same(row.hidden, row.expectedHidden) : null;
      row.safety = safetyOf(row.hidden, a.truth && a.truth.notePages);
    }
    rows.push(row);
  }

  console.log('');
  if (noDetector) {
    console.log(`${'artifact'.padEnd(34)} ${'pages'.padStart(5)}  ${'labels'.padEnd(30)} ${'note pages (oracle)'.padEnd(22)} rule`);
    for (const r of rows) {
      console.log(`${r.id.padEnd(34)} ${String(r.pages == null ? '-' : r.pages).padStart(5)}  ${(r.compiled ? labelsText(r.labels) : 'COMPILE FAILED').padEnd(30)} ` +
                  `${(r.oracle ? setText(r.oracle.notePages) : '-').padEnd(22)} ${r.oracle ? r.oracle.rule + (r.oracle.error ? ' ' + r.oracle.error : '') : ''}`);
    }
  } else {
    console.log(`${'artifact'.padEnd(34)} ${'labels'.padEnd(26)} ${'expected'.padEnd(40)} ${'observed (vv --cli --deck-info)'.padEnd(40)} ${'match'.padEnd(6)} ${'oracle N'.padEnd(12)} ${'hidden H'.padEnd(12)} safety`);
    for (const r of rows) {
      console.log(`${r.id.padEnd(34)} ${(r.compiled ? labelsText(r.labels) : 'COMPILE FAILED').padEnd(26)} ${decisionText(r.expected).padEnd(40)} ` +
                  `${decisionText(r.observed).padEnd(40)} ${(r.mismatched.length ? 'NO' : 'yes').padEnd(6)} ` +
                  `${(r.oracle ? setText(r.oracle.notePages) : '-').padEnd(12)} ${setText(r.hidden).padEnd(12)} ${r.safety.class}` +
                  `${r.mismatched.length ? '  mismatched: ' + r.mismatched.join(',') : ''}` +
                  `${r.labelsAsRegistered ? '' : '  LABELS DIFFER FROM THE REGISTRATION'}` +
                  `${r.hiddenAsRegistered === false ? '  HIDDEN SET DIFFERS FROM THE REGISTRATION' : ''}` +
                  `${r.setAgreesWithCli === false ? '  SET/CLI DISAGREE' : ''}`);
    }
  }

  console.log('\n# the twin rule\'s assumption: the templates whose note text is drawn mark the same pages');
  for (const g of invariance) {
    console.log(`#   ${g.group.padEnd(30)} pages ${JSON.stringify(g.pagesByTemplate)}  note pages ${JSON.stringify(g.noteSetsByTemplate)}  ` +
                `${g.setsEqual && g.pagesEqual ? 'equal' : 'DIFFERENT'}`);
  }

  const summary = { artifacts: rows.length, compiled: rows.filter((r) => r.compiled).length,
                    twinAssumptionHolds: invariance.filter((g) => g.setsEqual && g.pagesEqual).length, twinGroups: invariance.length };
  if (!noDetector) {
    const measured = rows.filter((r) => r.safety.class !== 'unmeasured');
    const hiding = measured.filter((r) => r.hidden.length);
    Object.assign(summary, {
      decisionsAsExpected: rows.filter((r) => r.compiled && !r.mismatched.length).length,
      fieldMismatches: rows.reduce((s, r) => s + r.mismatched.length, 0),
      labelsAsRegistered: rows.filter((r) => r.labelsAsRegistered).length,
      hiddenSetsMeasured: measured.length,
      hiddenSetsAsRegistered: rows.filter((r) => r.hiddenAsRegistered === true).length,
      setAgreesWithCli: rows.filter((r) => r.setAgreesWithCli === true).length,
      unsafe: measured.filter((r) => r.safety.class === 'UNSAFE').map((r) => r.id),
      s1Holds: measured.filter((r) => r.safety.subset).length,
      hidingArtifacts: hiding.length,
      s2Holds: hiding.filter((r) => r.safety.exact).length,
      classes: measured.reduce((m, r) => ({ ...m, [r.safety.class]: (m[r.safety.class] || 0) + 1 }), {}),
      cliExit: cli.status, cliStderr: cli.stderr.trim() || null, hiddenSetsLoadError: sets.loadError,
    });
    console.log(`\n# ${summary.compiled} of ${summary.artifacts} artifacts compiled`);
    console.log(`# decisions as expected (all of ${FIELDS.join(', ')}): ${summary.decisionsAsExpected} of ${summary.artifacts}; field mismatches ${summary.fieldMismatches}`);
    console.log(`# page labels as registered: ${summary.labelsAsRegistered} of ${summary.artifacts}`);
    if (sets.loadError) console.log(`# hidden sets UNAVAILABLE: ${sets.loadError}`);
    console.log(`# hidden sets read: ${summary.hiddenSetsMeasured} of ${summary.compiled}; as registered ${summary.hiddenSetsAsRegistered}; ` +
                `count/style/proof/reason agree with the CLI ${summary.setAgreesWithCli}`);
    console.log(`# SAFETY S1 (hidden ⊆ note pages): ${summary.s1Holds} of ${summary.hiddenSetsMeasured}` +
                `${summary.unsafe.length ? '  -- UNSAFE: ' + summary.unsafe.join(', ') : ''}`);
    console.log(`# SAFETY S2 (hidden ≠ ∅ ⇒ hidden = note pages): ${summary.s2Holds} of ${summary.hidingArtifacts}`);
    console.log(`# classes: ${JSON.stringify(summary.classes)}`);
    console.log(`# twin assumption: ${summary.twinAssumptionHolds} of ${summary.twinGroups} groups`);
    if (cli.status !== 0) console.log(`# vv --cli exited ${cli.status}: ${summary.cliStderr}`);
  }

  if (typeof json === 'string') {
    const result = { experiment: 'H-NOTES-CORPUS', record: 'docs/scientific/13-speaker-note-detection.md', started,
                     finished: new Date().toISOString(), cpus, noDetector, expectationsSha256, registeredSha256: registered,
                     environment: env, summary, templateInvariance: invariance, artifacts: rows };
    // every path in the results is relative to --out (or names the repository): no absolute path of this machine
    const text = JSON.stringify(result, null, 1).split(`${outDir}/`).join('').split(outDir).join('.')
      .split(`${ROOT}/`).join('').split(ROOT).join('<repo>');
    fs.writeFileSync(path.resolve(json), text + '\n');
    console.log(`# wrote ${path.resolve(json)}`);
  }
}

if (arg('hidden-sets')) {
  hiddenSetsChild(arg('hidden-sets'), arg('hidden-out')).catch((e) => { console.error(e); process.exitCode = 1; });
} else {
  main().catch((e) => { console.error(e && e.stack ? e.stack : e); process.exitCode = 1; });
}
