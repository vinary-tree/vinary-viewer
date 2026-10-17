'use strict';
// Slide-deck detection on a NATURAL corpus — the local PDFs of a working machine (docs/scientific/12, ADR-0045).
//
// QUESTION. On PDFs nobody made for this experiment, what does the detector call a deck — by tier and rule — and
// what does it leave alone? Which kinds of document could be misread as decks (landscape reports, posters, forms),
// and which kinds of real presentation does it not recognize?
//
// PRIVACY. The corpus is personal. File names, titles, paths and contents are never reported. The raw per-file
// output (it carries paths) goes only to --raw, which must lie OUTSIDE the repository. The repository receives
// COUNTS by generic category: the detector's verdict, a producer family from a fixed list, and a page-size class.
//
// METHOD. A list of PDF paths (one per line) is classified by `vv --cli --deck-info` (the GUI's own evidence, page
// measure and classifier) in batches. A batch that fails or times out is retried one file at a time, so one
// unreadable file costs only itself.
//
//   fd -t f -e pdf --no-ignore --exclude node_modules --exclude .git . ~ > /tmp/pdfs.txt
//   node scripts/exp-deck-local-corpus.cjs --list=/tmp/pdfs.txt --raw=/tmp/deck-info.jsonl --json=summary.json
//
// --from-raw=<jsonl> re-summarizes an earlier run without classifying again: its classified rows are reused, and only
// the listed files it has no row for are run again, one at a time, to record why they could not be read. Each
// unreadable file's reason is reduced to a class from a fixed list (the CLI's message names the path, so the
// message itself never leaves the raw file).

const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const CLI = path.join(ROOT, 'dist', 'cli', 'vv-cli.js');

function args(raw) {
  const o = { list: null, raw: null, json: null, fromRaw: null, batch: 25, timeoutMs: 180000 };
  for (const a of raw) {
    const [k, v] = a.replace(/^--/, '').split('=');
    if (k === 'list') o.list = v;
    else if (k === 'raw') o.raw = v;
    else if (k === 'json') o.json = v;
    else if (k === 'from-raw') o.fromRaw = v;
    else if (k === 'batch') o.batch = Number(v);
  }
  if (!o.list || !o.raw) throw new Error('--list=<paths file> and --raw=<jsonl outside the repo> are required');
  const rawAbs = path.resolve(o.raw);
  if (rawAbs === ROOT || rawAbs.startsWith(ROOT + path.sep)) throw new Error('--raw must lie outside the repository (it carries file paths)');
  return o;
}

function deckInfo(files, timeoutMs) {
  const r = cp.spawnSync('node', [CLI, '--deck-info', ...files],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: timeoutMs, maxBuffer: 64 * 1024 * 1024 });
  const lines = (r.stdout || '').split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch (_e) { return null; } }).filter(Boolean);
  return { lines, failed: r.status !== 0 || r.error, stderr: r.stderr || '', timedOut: Boolean(r.error && r.error.code === 'ETIMEDOUT') };
}

// Why one file could not be read, as a class from a fixed list, decided in this order:
//   timed out · refused by the file system (ENOENT, EACCES, …) · not a PDF (no `%PDF-` in its first KiB — read here,
//   and only the yes/no is kept) · encrypted · incomplete (its tail failed the integrity check — content_service's
//   "the PDF looks incomplete" — and pdf.js could not open it) · damaged structure · a feature pdf.js rejected ·
//   another pdf.js error.
// `vv-cli: <uri>: <message>` names the path; it is masked before any pattern is tried, so a file NAME can never decide
// the class (a "password-reset.pdf" is not thereby encrypted), and only the class is ever reported.
const FS_ERROR = /\b(ENOENT|EACCES|EISDIR|EPERM|ELOOP|ENOTDIR)\b/;
function hasPdfHeader(file) {
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const b = Buffer.alloc(1029);
      const n = fs.readSync(fd, b, 0, b.length, 0);
      return b.subarray(0, n).includes('%PDF-');
    } finally { fs.closeSync(fd); }
  } catch (_e) {
    return null;                                          // unreadable here too: the file-system class decides
  }
}
function reasonOf(one, file) {
  if (one.timedOut) return 'timed out';
  const lines = one.stderr.split('\n').filter(Boolean).map((l) => l.split(file).join('<file>'));
  const mine = lines.filter((l) => l.includes('<file>'));
  const last = mine[mine.length - 1] || lines[lines.length - 1] || '';
  if (FS_ERROR.test(last)) return 'refused by the file system (missing, permissions, a directory)';
  if (hasPdfHeader(file) === false) return 'not a PDF (no %PDF- header in its first KiB)';
  if (/password/i.test(last)) return 'encrypted (a password is required)';
  if (mine.some((l) => /looks incomplete/.test(l))) return 'incomplete: no valid trailer at the end, and pdf.js could not open it';
  if (/invalid pdf|xref|startxref|trailer/i.test(last)) return 'damaged structure (a complete-looking file pdf.js could not parse)';
  if (/unknown|unsupported|format ?error|expected|missing/i.test(last)) return 'a PDF feature or object pdf.js rejected';
  return last ? 'another pdf.js error' : 'no message (the process failed)';
}

// ---- generic categories (a fixed list; anything else is "other") -------------------------------------------
const FAMILIES = [
  ['Beamer (Creator tag)', (c, p) => /\bbeamer\b/i.test(c)],
  ['Microsoft PowerPoint', (c, p) => /powerpoint/i.test(c + ' ' + p)],
  ['Apple Keynote', (c, p) => /keynote/i.test(c + ' ' + p)],
  ['LibreOffice / OpenOffice Impress', (c, p) => /impress/i.test(c) && /libreoffice|openoffice/i.test(c + ' ' + p)],
  ['Google (Slides / Docs)', (c, p) => /google/i.test(c + ' ' + p)],
  ['TeX engine (other)', (c, p) => /\b(pdftex|luatex|luahbtex|xetex|xdvipdfmx|dvipdfmx?|tectonic)\b/i.test(p) || /latex|tex\b/i.test(c)],
  ['Microsoft Office (other)', (c, p) => /microsoft|\bword\b|excel/i.test(c + ' ' + p)],
  ['LibreOffice / OpenOffice (other)', (c, p) => /libreoffice|openoffice/i.test(c + ' ' + p)],
  ['Apple (Quartz / Pages / macOS)', (c, p) => /quartz|mac ?os|\bpages\b|cocoa/i.test(c + ' ' + p)],
  ['Chromium / Skia print', (c, p) => /skia|chrom/i.test(c + ' ' + p)],
  ['Ghostscript', (c, p) => /ghostscript/i.test(c + ' ' + p)],
  ['Adobe', (c, p) => /adobe|acrobat|distiller|indesign|illustrator/i.test(c + ' ' + p)],
  ['Cairo', (c, p) => /cairo/i.test(c + ' ' + p)],
  ['Typst', (c, p) => /typst/i.test(c + ' ' + p)],
];
function family(r) {
  const c = r.creator || '', p = r.producer || '';
  if (!c && !p) return 'no Creator / Producer';
  for (const [name, test] of FAMILIES) if (test(c, p)) return name;
  return 'other';
}
const near = (a, b) => Math.abs(a - b) <= 3;
function sizeClass(r) {
  if (!r.pages) return 'no pages';
  if (r.distinctSizes > 1) return 'mixed sizes';
  const [w, h] = r.sizes[0] || [0, 0];
  const is = (a, b) => (near(w, a) && near(h, b));
  if (is(960, 540) || is(720, 540) || is(720, 405)) return 'slide-export canvas';
  if (r.secondScreen) return 'Beamer paper, doubled (second screen)';
  if ([[362.835, 272.126], [453.543, 255.118], [453.543, 283.465], [396.85, 255.118], [420.945, 297.638],
       [354.331, 283.465], [382.677, 255.118], [396.85, 257.953]].some(([a, b]) => Math.abs(w - a) <= 0.5 && Math.abs(h - b) <= 0.5)) return 'Beamer paper';
  if (is(595.28, 841.89) || is(595, 842) || is(612, 792)) return 'A4 / Letter portrait';
  if (is(841.89, 595.28) || is(842, 595) || is(792, 612)) return 'A4 / Letter landscape';
  return w > h ? 'other landscape' : 'other portrait';
}
const verdict = (r) => (r.deck ? `deck: ${r.tier}/${r.source}` : 'not a deck');
const bump = (m, k, n = 1) => { m[k] = (m[k] || 0) + n; };

const fmtSize = ([w, h]) => `${Number(w).toFixed(1)}×${Number(h).toFixed(1)} bp`;
const PRESENTATION_TOOLS = ['Microsoft PowerPoint', 'Apple Keynote', 'LibreOffice / OpenOffice Impress', 'Google (Slides / Docs)'];

function main() {
  const o = args(process.argv.slice(2));
  if (!fs.existsSync(CLI)) throw new Error('build the CLI first: npm run compile:cli:test');
  const files = fs.readFileSync(o.list, 'utf8').split('\n').filter(Boolean);
  const results = [];
  const failures = [];                                    // {path, unreadable, reason} — to the raw file only
  const reasons = {};
  const unreadableOne = (f) => {
    const one = deckInfo([f], o.timeoutMs);
    if (one.lines.length) { results.push(...one.lines); return; }
    const reason = reasonOf(one, f);
    bump(reasons, reason);
    failures.push({ path: f, unreadable: true, reason });
  };
  if (o.fromRaw) {
    const prior = fs.readFileSync(o.fromRaw, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
    const listed = new Set(files);
    results.push(...prior.filter((r) => !r.unreadable && listed.has(r.path)));
    const seen = new Set(results.map((r) => r.path));
    const missing = files.filter((f) => !seen.has(f));
    missing.forEach((f, i) => { unreadableOne(f); process.stderr.write(`\rre-running the unclassified: ${i + 1} / ${missing.length}`); });
  } else {
    for (let i = 0; i < files.length; i += o.batch) {
      const batch = files.slice(i, i + o.batch);
      const { lines, failed } = deckInfo(batch, o.timeoutMs);
      if (failed && lines.length < batch.length) {
        const seen = new Set(lines.map((l) => l.path));
        results.push(...lines);
        for (const f of batch.filter((f) => !seen.has(f))) unreadableOne(f);
      } else {
        results.push(...lines);
      }
      process.stderr.write(`\r${Math.min(files.length, i + o.batch)} / ${files.length}`);
    }
  }
  process.stderr.write('\n');
  const unreadable = failures.length;
  fs.writeFileSync(o.raw, results.concat(failures).map((r) => JSON.stringify(r)).join('\n') + '\n');

  // ---- counts only ----
  const byVerdict = {}, byFamily = {}, bySize = {}, risky = {}, landscapeMisses = {}, toolMisses = {};
  for (const r of results) {
    const v = verdict(r), f = family(r), s = sizeClass(r);
    bump(byVerdict, v);
    byFamily[f] = byFamily[f] || {}; bump(byFamily[f], v);
    bySize[s] = bySize[s] || {}; bump(bySize[s], v);
    // the categories detection must get right, and the ones it cannot see
    if (s === 'A4 / Letter landscape' || s === 'other landscape') bump(risky, `uniform landscape (${r.pages >= 2 ? '≥ 2 pages' : '1 page'}): ${v}`);
    if (f === 'Beamer (Creator tag)' && r.pages === 1) bump(risky, `Beamer Creator, 1 page: ${v}`);
    if (r.pageMode === 'FullScreen') bump(risky, `PageMode FullScreen: ${v}`);
    if (PRESENTATION_TOOLS.includes(f)) {
      bump(risky, `presentation-tool producer (${f}), size class "${s}": ${v}`);
    }
    // the candidates for a missed deck: what a slide tool's export looks like, and is not called a deck
    const [w, h] = (r.sizes && r.sizes[0]) || [0, 0];
    if (!r.deck && r.pages >= 2 && r.distinctSizes === 1 && w > h) bump(landscapeMisses, `${f} @ ${fmtSize([w, h])}`);
    if (!r.deck && PRESENTATION_TOOLS.includes(f)) {
      bump(toolMisses, `${f} @ ${fmtSize([w, h])}${r.distinctSizes > 1 ? ' (first page; mixed sizes)' : ''}, ${r.pages >= 2 ? '≥ 2 pages' : '1 page'}`);
    }
  }
  const summary = { date: new Date().toISOString(), files: files.length, classified: results.length, unreadable,
                    reasons, byVerdict, byFamily, bySize, risky, landscapeMisses, toolMisses };
  console.log(`# exp-deck-local-corpus ${summary.date}: ${files.length} PDFs listed, ${results.length} classified, ${unreadable} unreadable`);
  const show = (title, m) => {
    console.log(`\n## ${title}`);
    for (const [k, v] of Object.entries(m).sort((a, b) => (typeof b[1] === 'number' ? b[1] - a[1] : 0))) {
      console.log(typeof v === 'number' ? `${String(v).padStart(6)}  ${k}` : `        ${k}: ${Object.entries(v).map(([vk, n]) => `${vk} ${n}`).join('; ')}`);
    }
  };
  show('Verdicts', byVerdict);
  show('By producer family', byFamily);
  show('By page-size class', bySize);
  show('The cases detection must get right', risky);
  show('Unreadable, by reason', reasons);
  show('Uniform multi-page landscape not called a deck: producer family @ page size', landscapeMisses);
  show('Presentation-tool exports not called a deck: producer family @ first page size', toolMisses);
  if (o.json) fs.writeFileSync(o.json, JSON.stringify(summary, null, 1));
}

main();
