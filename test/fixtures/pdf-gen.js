'use strict';
// Deterministic, dependency-free PDF builders shared by the smoke/e2e harnesses and benchmarks.
//
// The repository's test strategy prefers fixtures GENERATED at test time over committed binaries
// (docs/engineering/03-test-strategy.md §4): every byte below is a pure function of the arguments — no
// /CreationDate, no wall clock, and padding comes from a seeded PRNG — so two runs produce identical files
// and a failing assertion can be reproduced exactly.
//
//   assemblePdf(objs, opts)   the low-level writer: numbered objects + classic xref table (or an xref STREAM),
//                             trailer, startxref, %%EOF; optional incremental-update section.
//   makeTextPdf(n)            n Letter pages of extractable Helvetica text (the find / input-latency harnesses).
//   makeOutlinePdf()          two portrait pages + a nested /Outlines tree (the Contents / scroll-spy smoke).
//   makeDeckPdf(opts)         a Beamer-like slide deck: frames with overlay pages, /PageLabels, /PageMode,
//                             /Info + XMP CreatorTool, per-page colour fills, text, named-action/GoTo/URI links,
//                             outline, padding, PDF version, incremental update, xref stream; and speaker notes:
//                             `show notes` note pages laid out by Beamer's four note-page templates, frame numbers
//                             set by hand, \pdfpcnote-style annotations, and pdfpc's XMP keys.
//   makePdfpcSidecar(spec)    the JSON text of a pdfpc sidecar (`talk.pdfpc`), in pdfpc 4.7.0's writer order.
//   truncatePdf(buf, frac)    a torn copy (the first ⌊frac·len⌋ bytes) — a half-written file.
//   pageColour(i)             the RGB fill makeDeckPdf paints on page i, so a test can identify the shown page
//                             from a single pixel.
//
// test/pdf-gen-smoke.mjs checks the note pages, the labels, the annotations and the XMP against real TeX output
// (test/fixtures/beamer-shownotes*.pdf, beamer-onlynotes.pdf, beamer-plain.pdf, beamer-pdfpc*.pdf).

// ---- PDF string helpers -------------------------------------------------------------------------------

/** A PDF text string: a literal `( … )` for printable ASCII (with `\ ( )` escaped), else UTF-16BE hex with a
 *  BOM — the encoding PDF 32000-1 §7.9.2.2 prescribes for text strings outside PDFDocEncoding. */
function pdfText(s) {
  const str = String(s);
  if (/^[\x20-\x7e]*$/.test(str)) return '(' + str.replace(/[\\()]/g, (c) => '\\' + c) + ')';
  const units = [];
  for (const ch of str) {
    const cp = ch.codePointAt(0);
    if (cp > 0xffff) {
      const v = cp - 0x10000;
      units.push(0xd800 + (v >> 10), 0xdc00 + (v & 0x3ff));
    } else units.push(cp);
  }
  return '<FEFF' + units.map((u) => u.toString(16).toUpperCase().padStart(4, '0')).join('') + '>';
}

/** A text string for an annotation's /Contents, written the way a given TeX engine writes `\pdfpcnote`:
 *    'auto'          pdfText — a literal for printable ASCII, else UTF-16BE hex (what a careful writer does)
 *    'utf16'         always UTF-16BE hex with a BOM, upper-case digits — LuaTeX (pdfpc.sty converts the note first)
 *    'utf8-literal'  the note's UTF-8 BYTES in a literal string, a newline as the escape \n — pdfTeX. A reader decodes
 *                    a literal text string as PDFDocEncoding (PDF 32000-1 §7.9.2.2), so `café` reads as `cafÃ©`.
 *  (XeTeX's xdvipdfmx re-encodes a non-ASCII note as UTF-16BE itself, which 'auto' covers.) */
function annotText(s, encoding = 'auto') {
  const str = String(s);
  if (encoding === 'auto') return pdfText(str);
  if (encoding === 'utf16') {
    const units = [];
    for (let i = 0; i < str.length; i++) units.push(str.charCodeAt(i));   // UTF-16 code units, surrogates as they are
    return '<FEFF' + units.map((u) => u.toString(16).toUpperCase().padStart(4, '0')).join('') + '>';
  }
  if (encoding === 'utf8-literal') {
    // the bytes as latin1 characters: assemblePdf writes every object body as latin1, one byte per character
    const bytes = Buffer.from(str, 'utf8').toString('latin1');
    return '(' + bytes.replace(/[\\()]/g, (c) => '\\' + c).replace(/\n/g, '\\n') + ')';
  }
  throw new Error(`annotText: unknown encoding ${encoding}`);
}

/** A PDF name with characters outside the regular set #-escaped (PDF 32000-1 §7.3.5). */
function pdfName(s) {
  return '/' + String(s).replace(/[^A-Za-z0-9._\-]/g, (c) => '#' + c.charCodeAt(0).toString(16).padStart(2, '0'));
}

/** A string shown by a content stream in the decks' Helvetica: printable ASCII only, with `\ ( )` escaped. A
 *  content-stream string is bytes in the font's encoding, not a text string, so pdfText's UTF-16 would draw
 *  garbage. (Helvetica's StandardEncoding maps ' and ` to the curly quotes, so text extraction reads them back
 *  as ’ and ‘.) */
function showText(s) {
  const str = String(s);
  if (!/^[\x20-\x7e]*$/.test(str)) throw new Error(`makeDeckPdf: page text must be printable ASCII: ${JSON.stringify(str)}`);
  return '(' + str.replace(/[\\()]/g, (c) => '\\' + c) + ')';
}

const num = (x) => (Number.isInteger(x) ? String(x) : String(Math.round(x * 1000) / 1000));

// ---- the writer ---------------------------------------------------------------------------------------

/**
 * Assemble numbered objects (1-based: objs[0] is object 1) into a complete PDF file.
 *
 * opts:
 *   version      header version, default "1.4"
 *   root         catalog object number (default 1)
 *   info         object number of the /Info dictionary (optional)
 *   xrefStream   true → write a cross-reference STREAM object (PDF 1.5 §7.5.8) instead of a classic table; the
 *                trailer keys then live in that stream's dictionary and startxref points at "N 0 obj"
 *   incremental  { objs: {objectNumber: body, …} } → append one incremental-update section (§7.5.6): the given
 *                objects re-written, a second xref with /Prev, and a second %%EOF — so the file has TWO %%EOFs
 * Every object body may be a string (latin1) or { dict, stream: Buffer } for binary stream data.
 */
function assemblePdf(objs, opts = {}) {
  const version = opts.version || '1.4';
  const root = opts.root || 1;
  const parts = [];
  let length = 0;
  const push = (b) => { const buf = Buffer.isBuffer(b) ? b : Buffer.from(b, 'latin1'); parts.push(buf); length += buf.length; };

  // the binary-comment line after the header marks the file as binary for transfer tools (§7.5.2)
  push(`%PDF-${version}\n%\xe2\xe3\xcf\xd3\n`);
  const writeObj = (n, body) => {
    const offset = length;
    if (typeof body === 'string') push(`${n} 0 obj\n${body}\nendobj\n`);
    else {
      push(`${n} 0 obj\n${body.dict.replace(/>>\s*$/, '')} /Length ${body.stream.length} >>\nstream\n`);
      push(body.stream);
      push('\nendstream\nendobj\n');
    }
    return offset;
  };
  const offsets = new Array(objs.length);
  objs.forEach((body, i) => { offsets[i] = writeObj(i + 1, body); });

  const trailerKeys = (size, prev) =>
    `/Size ${size} /Root ${root} 0 R` + (opts.info ? ` /Info ${opts.info} 0 R` : '') + (prev !== undefined ? ` /Prev ${prev}` : '');

  let xrefStart;
  if (opts.xrefStream) {
    // xref stream: /W [1 4 2] — type (0 free, 1 in-use), byte offset, generation; object 0 is the free head.
    const n = objs.length + 1;            // the xref stream itself is object n
    xrefStart = length;
    const rows = Buffer.alloc((n + 1) * 7);
    const row = (i, type, off, gen) => { rows.writeUInt8(type, i * 7); rows.writeUInt32BE(off, i * 7 + 1); rows.writeUInt16BE(gen, i * 7 + 5); };
    row(0, 0, 0, 0xffff);
    offsets.forEach((off, i) => row(i + 1, 1, off, 0));
    row(n, 1, xrefStart, 0);
    writeObj(n, { dict: `<< /Type /XRef ${trailerKeys(n + 1)} /W [1 4 2] >>`, stream: rows });
  } else {
    xrefStart = length;
    let xref = `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
    for (const off of offsets) xref += `${String(off).padStart(10, '0')} 00000 n \n`;
    push(xref + `trailer\n<< ${trailerKeys(objs.length + 1)} >>\n`);
  }
  push(`startxref\n${xrefStart}\n%%EOF\n`);

  if (opts.incremental) {
    const updated = Object.entries(opts.incremental.objs || {}).map(([k, body]) => [Number(k), body]).sort((a, b) => a[0] - b[0]);
    const newOffsets = updated.map(([n, body]) => [n, writeObj(n, body)]);
    const prev = xrefStart;
    xrefStart = length;
    let xref = 'xref\n0 1\n0000000000 65535 f \n';
    for (const [n, off] of newOffsets) xref += `${n} 1\n${String(off).padStart(10, '0')} 00000 n \n`;
    push(xref + `trailer\n<< ${trailerKeys(objs.length + 1, prev)} >>\nstartxref\n${xrefStart}\n%%EOF\n`);
  }
  return Buffer.concat(parts, length);
}

// ---- the historical harness builders (byte-identical to the copies they replace) ------------------------

/**
 * A multi-page PDF with REAL extractable text. test/fixtures/smoke.pdf is a single short page, so it can
 * never be taller than the viewport and cannot exercise scrolling at all. Each page emits the phrase
 * "quick brown" as TWO separate Tj operators at the same baseline: pdf.js renders one <span> per text run,
 * so that phrase is the PDF analogue of a Markdown match straddling an inline element.
 * (Moved from test/find-e2e.js and scripts/bench-input-latency.cjs; output is byte-identical to each. The two
 * copies differed only in the first line's wording, so `purpose` reproduces either: the find harness's default
 * "in-page find harness", or the benchmark's "input-latency benchmark" — keeping the benchmark's fixture, and so
 * its historical numbers, comparable.)
 */
function makeTextPdf(pageCount, purpose = 'in-page find harness') {
  const content = (i) => [
    'BT', '/F1 14 Tf', '72 720 Td',
    `(Page ${i} paragraph text for the ${purpose}) Tj`,
    '0 -28 Td', '(quick ) Tj', '(brown fox jumps over the lazy dog) Tj',
    '0 -28 Td', `(needle phrase ${i}) Tj`,
    'ET'
  ].join('\n');

  const objs = [];
  const kids = [];
  const pageObjFirst = 3;                                   // 1 = Catalog, 2 = Pages
  for (let i = 0; i < pageCount; i++) kids.push(`${pageObjFirst + i * 2} 0 R`);
  objs.push('<< /Type /Catalog /Pages 2 0 R >>');
  objs.push(`<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${pageCount} >>`);
  for (let i = 0; i < pageCount; i++) {
    const streamObj = pageObjFirst + i * 2 + 1;
    objs.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${streamObj} 0 R ` +
              `/Resources << /Font << /F1 ${pageObjFirst + pageCount * 2} 0 R >> >> >>`);
    const body = content(i);
    objs.push(`<< /Length ${body.length} >>\nstream\n${body}\nendstream`);
  }
  objs.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  return legacyAssemble(objs);
}

/** Two 300×800 portrait pages with a nested outline (Section One ▸ Subsection A, Section Two) — the
 *  Contents/scroll-spy smoke fixture. (Moved verbatim from test/electron-smoke.js; byte-identical output.) */
function makeOutlinePdf() {
  const content = 'q 0 0 1 rg 20 20 120 120 re f Q';
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R >>',
    '<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 800] /Contents 5 0 R /Resources << >> >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 800] /Contents 5 0 R /Resources << >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Outlines /First 7 0 R /Last 8 0 R /Count 2 >>',
    '<< /Title (Section One) /Parent 6 0 R /Next 8 0 R /First 9 0 R /Last 9 0 R /Count 1 /Dest [3 0 R /XYZ 0 800 0] >>',
    '<< /Title (Section Two) /Parent 6 0 R /Prev 7 0 R /Dest [4 0 R /XYZ 0 800 0] >>',
    '<< /Title (Subsection A) /Parent 7 0 R /Dest [3 0 R /XYZ 0 400 0] >>'   // nested under Section One → 1.1
  ];
  return legacyAssemble(objs);
}

/** The original harnesses' writer: "%PDF-1.4\n" header (no binary comment), classic xref, no /Info. Kept so
 *  the moved builders stay byte-identical to the fixtures existing assertions were written against. */
function legacyAssemble(objs) {
  let pdf = '%PDF-1.4\n';
  const offsets = [];
  objs.forEach((body, i) => { offsets[i] = Buffer.byteLength(pdf, 'latin1'); pdf += `${i + 1} 0 obj\n${body}\nendobj\n`; });
  const xrefStart = Buffer.byteLength(pdf, 'latin1');
  pdf += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  offsets.forEach((off) => { pdf += `${String(off).padStart(10, '0')} 00000 n \n`; });
  pdf += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}

// ---- slide decks ----------------------------------------------------------------------------------------

/** Beamer paper sizes in PDF points (bp), from beamer.cls:312-345 (aspectratio option → \paperwidth×\paperheight,
 *  cm × 72/2.54). The deck detector (vinary.slides.deck) recognises the same table. */
const BEAMER_SIZES = {
  43:   [362.835, 272.126],   // 12.80 × 9.60 cm (default)
  169:  [453.543, 255.118],   // 16.00 × 9.00 cm
  1610: [453.543, 283.465],   // 16.00 × 10.00 cm
  149:  [396.850, 255.118],   // 14.00 × 9.00 cm
  141:  [420.945, 297.638],   // 14.85 × 10.50 cm
  54:   [354.331, 283.465],   // 12.50 × 10.00 cm
  32:   [382.677, 255.118],   // 13.50 × 9.00 cm
  2013: [396.850, 257.953],   // 14.00 × 9.10 cm
};

/** Slide-tool export canvases (PDF points) — the keys and sizes of vinary.slides.deck/export-canvases. */
const EXPORT_SIZES = {
  'powerpoint-169': [960, 540], 'powerpoint-43': [720, 540], 'google-169': [720, 405], 'on-screen-1610': [720, 450],
  'impress-43': [793.7, 595.3], 'impress-169': [793.7, 446.5], 'impress-1610': [793.7, 496.1],
  'keynote-43': [1024, 768], 'keynote-169': [1920, 1080],
};

/** The RGB fill (0–255) makeDeckPdf paints on page index i: well-separated hues, mid lightness, never close
 *  to black/white (so a blank-screen overlay or an unpainted canvas is distinguishable from any page). */
function pageColour(i) {
  const h = (i * 137.508) % 360;          // golden-angle hue walk: consecutive pages differ strongly
  const s = 0.62, l = 0.52;
  const c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = l - c / 2;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [r, g, b].map((v) => Math.round((v + m) * 255));
}

/** The RGB fill (0–255) of a second-screen page's NOTES half: a pale yellow no pageColour(i) comes near. */
const NOTES_COLOUR = [244, 236, 170];

/**
 * Beamer's note-page templates, laid out as TeX lays them out: beamerouterthemedefault.sty's `note page` [default],
 * and beamerbaseauxtemplates.sty's [compressed], [plain] and [lined] (Beamer 3.77 at its default 11 pt). TeX stacks
 * a template's boxes down from the top of the page with fixed lengths, so every position below is a fixed distance
 * from the top edge or from the left margin, and only the slide's thumbnail scales with the paper. Lengths are in PDF
 * points (TeX points × 72/72.27). test/pdf-gen-smoke.mjs checks them against real LuaLaTeX output
 * (test/fixtures/beamer-shownotes.pdf, beamer-shownotes-compressed.pdf).
 *
 *   thumbnail   \insertslideintonotes{σ}: the slide scaled by σ, flush with the top-right corner, so at the offset
 *               ((1−σ)·w, (1−σ)·h). The template's top box is σ·h tall.
 *   date        [default] the compile date YYYY-MM-DD in 9 pt, rotated by +90°. pgf's origin is the top of the left
 *               margin, and the text is shifted (−2 cm, 0.2 cm) in the rotated frame: 0.2 cm left of the margin
 *               and 2 cm down.
 *   title       [default] \insertshorttitle in 10 pt, when the deck has a title.
 *   frameTitle  \insertshortframetitle. [default]: 10 pt, indented by 30.8 pt, the corner rule's 9.4 pt and 1 pt,
 *               one line higher when the deck has no title. [compressed]: 6 pt, two quads in, three 1 em boxes down
 *               (section, subsection, frame title).
 *   notes       \insertnote in 10 pt: its first baseline `gap` below the template's top box. [plain], with nothing
 *               above the note, measures the box-to-baseline distance alone: 13.549. [default] adds \vskip.25em
 *               (2.491 at 10 pt) and [compressed] \vskip0.5em (2.989 at 6 pt). Later lines are 12 pt apart: 10 pt
 *               type's \baselineskip, which was not measured and which nothing reads.
 *   rules       [lined] draws five rules spaced by \vfill, and no text. They are spaced evenly here, ignoring their
 *               own height: nothing reads them.
 */
const NOTE_MARGIN = 28.346;                                    // \Gm@lmargin: Beamer's 1 cm left margin
const NOTE_TEMPLATES = {
  default: {
    thumbnail: 0.25,
    date: { size: 8.966, x: NOTE_MARGIN - 5.669, fromTop: 56.692 },   // 2 cm, as TeX rounds it here
    title: { size: 9.963, x: NOTE_MARGIN, fromTop: 12.114 },
    frameTitle: { size: 9.963, x: NOTE_MARGIN + 41.047, fromTop: 47.98, fromTopUntitled: 40.847 },
    notes: { size: 9.963, x: NOTE_MARGIN, gap: 13.549 + 2.491 },
  },
  compressed: {
    thumbnail: 0.125,
    frameTitle: { size: 5.978, x: NOTE_MARGIN + 11.956, fromTop: 17.933, fromTopUntitled: 17.933 },
    notes: { size: 9.963, x: NOTE_MARGIN, gap: 13.549 + 2.989 },
  },
  plain: { thumbnail: 0, notes: { size: 9.963, x: NOTE_MARGIN, gap: 13.549 } },
  lined: { thumbnail: 0, rules: 5 },
};
const NOTE_LINE_SKIP = 11.955;                                 // 12 pt
/** Beamer's default frame title on a slide: 14.4 pt, 0.3 cm in, its baseline 21.063 below the top (measured). */
const FRAME_TITLE = { size: 14.346, x: 8.504, fromTop: 21.063 };

/** mulberry32 — a tiny seeded PRNG so padding bytes are reproducible. */
function prng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * A Beamer-like slide deck.
 *
 * opts (all optional):
 *   frames       overlay-page counts per frame, e.g. [1, 3, 1] → 5 pages (default [1, 2, 1])
 *   pages        total pages when `frames` is omitted (one page per frame)
 *   size         [w, h] in points, or a key of BEAMER_SIZES / EXPORT_SIZES (default 169)
 *   sizes        per-page [w, h] array (mixed sizes); overrides `size`
 *   labels       'beamer' (default) → /PageLabels with each page labelled by its frame number (overlays SHARE
 *                a label — beamer's \thispdfpagelabel{\insertframenumber}) and each note page by its physical page
 *                number (below); an explicit string array; or null → no /PageLabels at all (what a single XeLaTeX
 *                pass writes: its driver takes the labels from the previous run's .aux — docs/scientific/12 §5.1)
 *   frameNumbers each frame's number, the label of its slide pages (default: frame f is f + 1). A frame marked
 *                `noframenumbering` repeats the number before it (0 for a first frame); a restarted counter
 *                (`appendixnumberbeamer`) drops back.
 *   frameTitles  each frame's title, drawn on its slide pages as Beamer's default frame title (and repeated by the
 *                note pages' templates); omitted → slides carry only their "Frame f …" text
 *   notes        Beamer's `show notes`: a note page after the slide page it annotates, each a page of the paper's
 *                size laid out by one of NOTE_TEMPLATES, labelled by its physical page number, painted
 *                pageColour(page) like any page. An object:
 *                  inFrame   {frameIndex: true | [overlay, …]} — an in-frame \note: a note page after EVERY overlay
 *                            page of the frame (true, a \note without an overlay specification), or after the
 *                            listed 0-based overlays (\note<k+1>{…})
 *                  between   [frameIndex, …] — a \note written after that frame and before the next: one note page
 *                            each, after the frame's last page and its last note page, annotating its last overlay
 *                  template  'default' (default) | 'compressed' | 'plain' | 'lined'
 *                  date      the default template's rotated compile date (default "2026-10-17")
 *                  text      ({frame, overlay, between}) → the note's text ('\n' separates lines); default
 *                            "Notes for frame f" or "Notes after frame f"
 *                  only      true → `show only notes`: the note pages alone, exactly as they are in `show notes`,
 *                            an in-frame one labelled by its frame's number and one written between frames by its
 *                            physical page number (as Beamer labels them)
 *                `sizes`, `links` and `outline` count physical pages, note pages included.
 *   textNotes    [{page, contents, subtype, flags, encoding, rect}] → one annotation each, after the page's links in
 *                /Annots: subtype 'Text' (default; \pdfpcnote's), 'FreeText', 'Highlight', 'Underline', 'Squiggly' —
 *                the five pdfpc reads — or any other; flags the /F value (default 6, Hidden + Print, as \pdfpcnote
 *                writes; null → no /F); encoding as annotText ('auto' default, 'utf16', 'utf8-literal'); rect
 *                (default a zero-size rect, as \pdfpcnote's, or a small box for a markup or FreeText annotation)
 *   pdfpc        pdfpc's XMP keys, {Duration: 20, …} or [[key, value], …] (repeats allowed): written as pdfpc.sty
 *                writes them, `pdfpc:`-prefixed elements in a NESTED rdf:Description inside the packet's own (not
 *                valid RDF/XML), in one packet with creatorTool
 *   creator      /Info /Creator (default "LaTeX with Beamer class"); null → omitted
 *   producer     /Info /Producer (default "LuaTeX-1.18.0"); null → omitted
 *   title        /Info /Title
 *   creatorTool  XMP xmp:CreatorTool in a /Metadata stream (optional)
 *   pageMode     catalog /PageMode name, e.g. "FullScreen" (optional)
 *   colours      paint each page with pageColour(i) (default true)
 *   text         put "Frame f · overlay k/K" text on each page (default true; real extractable text)
 *   secondScreen 'right' | 'left' | 'top' | 'bottom' — Beamer's `show notes on second screen=<side>`: every page
 *                is the paper DOUBLED (2w × h for right/left, w × 2h for top/bottom) with the slide in one half
 *                (pageColour(i) + the slide text) and the notes in the side named (NOTES_COLOUR + the text
 *                "Speakernotes for frame f" — a word only the notes carry)
 *   links        [{page, rect:[x0,y0,x1,y1], action:"NextPage"|… | dest:pageIndex | uri:"https://…"}]
 *   outline      [{title, page, children:[…]}] → /Outlines (beamer writes section bookmarks)
 *   padBytes     append an unreferenced stream of this many seeded-random bytes (size the file)
 *   seed         PRNG seed for padding (default 1)
 *   version      PDF header version (default "1.5")
 *   xrefStream   write an xref stream instead of a classic table
 *   incremental  append an incremental update re-writing /Info (two %%EOF markers)
 */
function makeDeckPdf(opts = {}) {
  const frames = opts.frames || (opts.pages ? Array.from({ length: opts.pages }, () => 1) : [1, 2, 1]);
  const resolveSize = (s) => (Array.isArray(s) ? s : BEAMER_SIZES[s] || EXPORT_SIZES[s] || BEAMER_SIZES[169]);
  const second = opts.secondScreen || null;
  const notes = opts.notes || null;
  if (notes && second) throw new Error('makeDeckPdf: `notes` (note pages) and `secondScreen` (split pages) are two Beamer modes');
  const template = notes ? NOTE_TEMPLATES[notes.template || 'default'] : null;
  if (notes && !template) throw new Error(`makeDeckPdf: unknown note template ${notes.template}`);
  const frameNumber = (f) => (opts.frameNumbers ? opts.frameNumbers[f] : f + 1);

  // The page plan, in the order Beamer writes the pages: each frame's overlay pages, an in-frame note page after each
  // overlay that has one, then the pages of the notes written between this frame and the next. A note page records
  // the plan index of the slide page it annotates: the last slide page before it.
  const plan = [];
  const inFrame = (notes && notes.inFrame) || {};
  const between = (notes && notes.between) || [];
  for (const key of Object.keys(inFrame)) {
    const f = Number(key), spec = inFrame[key];
    if (!(f >= 0 && f < frames.length) || !(spec === true || (Array.isArray(spec) && spec.every((k) => k >= 0 && k < frames[f]))))
      throw new Error(`makeDeckPdf: notes.inFrame[${key}] names no frame or overlay of this deck`);
  }
  if (!between.every((f) => Number.isInteger(f) && f >= 0 && f < frames.length))
    throw new Error('makeDeckPdf: notes.between names a frame this deck does not have');
  let lastSlide = -1;
  frames.forEach((count, f) => {
    for (let k = 0; k < count; k++) {
      plan.push({ kind: 'slide', frame: f, overlay: k });
      lastSlide = plan.length - 1;
      const spec = inFrame[f];
      if (spec === true || (Array.isArray(spec) && spec.includes(k)))
        plan.push({ kind: 'note', frame: f, overlay: k, between: false, slide: lastSlide });
    }
    for (const b of between) {
      if (b !== f) continue;
      if (lastSlide < 0) throw new Error(`makeDeckPdf: notes.between ${f} follows no slide page`);
      plan.push({ kind: 'note', frame: f, overlay: null, between: true, slide: lastSlide });
    }
  });
  // the physical pages: every planned page, or — `show only notes` — the note pages alone; `at` is the plan index
  const physical = plan.map((e, at) => ({ ...e, at })).filter((e) => !(notes && notes.only) || e.kind === 'note');
  const pages = physical.length;
  const paper = resolveSize(opts.size === undefined ? 169 : opts.size);
  const doubled = !second ? paper
    : (second === 'right' || second === 'left') ? [2 * paper[0], paper[1]] : [paper[0], 2 * paper[1]];
  const sizes = opts.sizes || Array.from({ length: pages }, () => doubled);
  const colours = opts.colours !== false;
  const text = opts.text !== false;
  const links = opts.links || [];
  const textNotes = opts.textNotes || [];

  // object numbering: 1 Catalog, 2 Pages, 3 Font, then per page: Page, Contents (2 objects each),
  // then annotations, then optional Info / Metadata / Outlines / PageLabels / padding.
  const objs = [];
  const add = (body) => { objs.push(body); return objs.length; };
  add(null); add(null);                                     // 1, 2 reserved (filled below)
  const fontObj = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  const pageObjs = new Array(pages);
  const contentObjs = new Array(pages);
  for (let i = 0; i < pages; i++) { pageObjs[i] = add(null); contentObjs[i] = add(null); }

  const annotsFor = Array.from({ length: pages }, () => []);
  for (const l of links) {
    const [x0, y0, x1, y1] = l.rect || [10, 10, 60, 40];
    let target;
    if (l.action) target = `/A << /S /Named /N ${pdfName(l.action)} >>`;
    else if (l.uri) target = `/A << /S /URI /URI ${pdfText(l.uri)} >>`;
    else target = `/Dest [${pageObjs[l.dest]} 0 R /Fit]`;
    annotsFor[l.page].push(add(`<< /Type /Annot /Subtype /Link /Rect [${num(x0)} ${num(y0)} ${num(x1)} ${num(y1)}] /Border [0 0 0] ${target} >>`));
  }
  for (const t of textNotes) {
    const subtype = t.subtype || 'Text';
    const markup = subtype === 'Highlight' || subtype === 'Underline' || subtype === 'Squiggly';
    const [x0, y0, x1, y1] = t.rect || (markup || subtype === 'FreeText' ? [24, 24, 124, 44] : [24, 24, 24, 24]);
    const parts = [`/Type /Annot /Subtype ${pdfName(subtype)}`, `/Rect [${[x0, y0, x1, y1].map(num).join(' ')}]`];
    // a markup annotation marks text through its quadrilaterals (upper-left, upper-right, lower-left, lower-right);
    // a FreeText one draws its text in a default appearance
    if (markup) parts.push(`/QuadPoints [${[x0, y1, x1, y1, x0, y0, x1, y0].map(num).join(' ')}]`);
    if (subtype === 'FreeText') parts.push('/DA (/Helv 10 Tf 0 g)');
    parts.push(`/Contents ${annotText(t.contents, t.encoding)}`);
    const flags = t.flags === undefined ? 6 : t.flags;
    if (flags !== null) parts.push(`/F ${flags}`);
    annotsFor[t.page].push(add(`<< ${parts.join(' ')} >>`));
  }

  // A slide page's drawing, in the slide's own coordinates: plan index i paints pageColour(i) and says "deck page
  // i+1" (in a deck without `show only notes`, i is the page's physical index). A note page draws it again, scaled, as
  // its thumbnail.
  const slideOps = (i, e, w, h) => {
    const ops = [];
    // the slide's rect [x y w h] in PDF space (y UP): the whole page, or — on a second-screen page — the half
    // opposite the notes (notes right → slide left; notes top → slide at the BOTTOM, i.e. y ∈ [0, h/2])
    const half = !second ? [0, 0, w, h]
      : second === 'right' ? [0, 0, w / 2, h] : second === 'left' ? [w / 2, 0, w / 2, h]
      : second === 'top' ? [0, 0, w, h / 2] : [0, h / 2, w, h / 2];
    const notesHalf = !second ? null
      : second === 'right' ? [w / 2, 0, w / 2, h] : second === 'left' ? [0, 0, w / 2, h]
      : second === 'top' ? [0, h / 2, w, h / 2] : [0, 0, w, h / 2];
    const [sx, sy, sw, sh] = half;
    if (colours) {
      const [r, g, b] = pageColour(i).map((v) => num(v / 255));
      ops.push('q', `${r} ${g} ${b} rg`, `${num(sx)} ${num(sy)} ${num(sw)} ${num(sh)} re f`, 'Q');
      if (notesHalf) {
        const [nr, ng, nb] = NOTES_COLOUR.map((v) => num(v / 255));
        ops.push('q', `${nr} ${ng} ${nb} rg`, `${notesHalf.map(num).join(' ')} re f`, 'Q');
      }
    }
    if (text && opts.frameTitles) {
      ops.push('BT', '0 0 0 rg', `/F1 ${num(FRAME_TITLE.size)} Tf`,
               `${num(sx + FRAME_TITLE.x)} ${num(sy + sh - FRAME_TITLE.fromTop)} Td`, `${showText(opts.frameTitles[e.frame])} Tj`, 'ET');
    }
    if (text) {
      const f = e.frame, k = e.overlay, K = frames[f];
      ops.push('BT', '0 0 0 rg', '/F1 18 Tf', `${num(sx + 24)} ${num(sy + sh - 48)} Td`,
               `(Frame ${f + 1} overlay ${k + 1} of ${K}) Tj`, '0 -26 Td', `(deck page ${i + 1}) Tj`, 'ET');
      if (notesHalf) {
        ops.push('BT', '0 0 0 rg', '/F1 14 Tf', `${num(notesHalf[0] + 24)} ${num(notesHalf[1] + notesHalf[3] - 48)} Td`,
                 `(Speakernotes for frame ${f + 1}) Tj`, 'ET');
      }
    }
    return ops;
  };

  // A note page (physical page p), laid out by its template (NOTE_TEMPLATES): its own colour, the thumbnail of the
  // slide page it annotates, then the template's text — the text runs pdf.js reports, in this order.
  const notePageOps = (p, e, w, h) => {
    const ops = [];
    if (colours) {
      const [r, g, b] = pageColour(p).map((v) => num(v / 255));
      ops.push('q', `${r} ${g} ${b} rg`, `0 0 ${num(w)} ${num(h)} re f`, 'Q');
    }
    const s = template.thumbnail;
    if (s) ops.push('q', `${num(s)} 0 0 ${num(s)} ${num((1 - s) * w)} ${num((1 - s) * h)} cm`, ...slideOps(e.slide, plan[e.slide], w, h), 'Q');
    const run = (size, x, y, str, rotated) => ops.push('BT', '0 0 0 rg', `/F1 ${num(size)} Tf`,
      `${rotated ? '0 1 -1 0' : '1 0 0 1'} ${num(x)} ${num(y)} Tm`, `${showText(str)} Tj`, 'ET');
    if (template.date) run(template.date.size, template.date.x, h - template.date.fromTop, notes.date || '2026-10-17', true);
    if (template.title && opts.title) run(template.title.size, template.title.x, h - template.title.fromTop, opts.title, false);
    if (template.frameTitle && opts.frameTitles) {
      const ft = template.frameTitle;
      run(ft.size, ft.x, h - (opts.title ? ft.fromTop : ft.fromTopUntitled), opts.frameTitles[e.frame], false);
    }
    if (template.notes) {
      const body = notes.text ? notes.text({ frame: e.frame, overlay: e.overlay, between: e.between })
        : `Notes ${e.between ? 'after' : 'for'} frame ${e.frame + 1}`;
      String(body).split('\n').forEach((line, n) =>
        run(template.notes.size, template.notes.x, h - s * h - template.notes.gap - n * NOTE_LINE_SKIP, line, false));
    }
    if (template.rules) {
      ops.push('q', '0 0 0 RG', '0.4 w');
      for (let r = 1; r <= template.rules; r++) {
        const y = h - (r * h) / (template.rules + 1);
        ops.push(`${num(NOTE_MARGIN)} ${num(y)} m ${num(w - NOTE_MARGIN)} ${num(y)} l S`);
      }
      ops.push('Q');
    }
    return ops;
  };

  for (let i = 0; i < pages; i++) {
    const e = physical[i];
    const [w, h] = sizes[i];
    const ops = e.kind === 'slide' ? slideOps(e.at, e, w, h) : notePageOps(i, e, w, h);
    const body = ops.join('\n');
    objs[contentObjs[i] - 1] = `<< /Length ${body.length} >>\nstream\n${body}\nendstream`;
    const annots = annotsFor[i].length ? ` /Annots [${annotsFor[i].map((n) => `${n} 0 R`).join(' ')}]` : '';
    objs[pageObjs[i] - 1] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(w)} ${num(h)}] /Contents ${contentObjs[i]} 0 R ` +
                            `/Resources << /Font << /F1 ${fontObj} 0 R >> >>${annots} >>`;
  }
  objs[1] = `<< /Type /Pages /Kids [${pageObjs.map((n) => `${n} 0 R`).join(' ')}] /Count ${pages} >>`;

  // /Info
  const infoKeys = [];
  const creator = opts.creator === undefined ? 'LaTeX with Beamer class' : opts.creator;
  const producer = opts.producer === undefined ? 'LuaTeX-1.18.0' : opts.producer;
  if (creator !== null) infoKeys.push(`/Creator ${pdfText(creator)}`);
  if (producer !== null) infoKeys.push(`/Producer ${pdfText(producer)}`);
  if (opts.title) infoKeys.push(`/Title ${pdfText(opts.title)}`);
  const infoObj = infoKeys.length ? add(`<< ${infoKeys.join(' ')} >>`) : null;

  // catalog extras
  const catalog = ['/Type /Catalog', '/Pages 2 0 R'];
  if (opts.pageMode) catalog.push(`/PageMode ${pdfName(opts.pageMode)}`);
  const pdfpcKeys = !opts.pdfpc ? [] : Array.isArray(opts.pdfpc) ? opts.pdfpc : Object.entries(opts.pdfpc);
  if (opts.creatorTool || pdfpcKeys.length) {
    const xml = (v) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;');
    const xmp = '<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>' +
      '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">' +
      '<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">' +
      (opts.creatorTool ? `<xmp:CreatorTool>${xml(opts.creatorTool)}</xmp:CreatorTool>` : '') +
      // pdfpc.sty hooks hyperxmp's pdf schema and so nests its own rdf:Description inside hyperxmp's
      (pdfpcKeys.length ? '<rdf:Description xmlns:pdfpc="https://github.com/pdfpc/pdfpc">' +
        pdfpcKeys.map(([k, v]) => `<pdfpc:${k}>${xml(v)}</pdfpc:${k}>`).join('') + '</rdf:Description>' : '') +
      '</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>';
    const bytes = Buffer.from(xmp, 'utf8');
    catalog.push(`/Metadata ${add({ dict: '<< /Type /Metadata /Subtype /XML >>', stream: bytes })} 0 R`);
  }
  const labels = opts.labels === undefined ? 'beamer' : opts.labels;
  if (labels) {
    // a slide page carries its frame's number; a note page its physical page number — except in `show only notes`,
    // where an in-frame note page carries its frame's number too (measured: test/fixtures/beamer-onlynotes.pdf)
    const beamerLabel = (e, p) => (e.kind === 'slide' || (notes.only && !e.between) ? frameNumber(e.frame) : p + 1);
    const list = labels === 'beamer' ? physical.map((e, p) => String(beamerLabel(e, p))) : labels;
    const nums = list.map((lab, i) => `${i} << /P ${pdfText(lab)} >>`).join(' ');
    catalog.push(`/PageLabels << /Nums [${nums}] >>`);
  }
  if (opts.outline && opts.outline.length) {
    // two passes: reserve every item's object number, then write /Parent /Prev /Next /First /Last /Count
    const outlinesObj = add(null);
    const reserve = (items, parent) => items.map((it) => {
      const node = { it, parent, n: add(null) };
      node.kids = reserve(it.children || [], node.n);
      return node;
    });
    const tree = reserve(opts.outline, outlinesObj);
    const write = (nodes) => nodes.forEach((node, i) => {
      const parts = [`/Title ${pdfText(node.it.title)}`, `/Parent ${node.parent} 0 R`,
                     `/Dest [${pageObjs[node.it.page]} 0 R /Fit]`];
      if (i > 0) parts.push(`/Prev ${nodes[i - 1].n} 0 R`);
      if (i < nodes.length - 1) parts.push(`/Next ${nodes[i + 1].n} 0 R`);
      if (node.kids.length) {
        parts.push(`/First ${node.kids[0].n} 0 R`, `/Last ${node.kids[node.kids.length - 1].n} 0 R`, `/Count ${node.kids.length}`);
        write(node.kids);
      }
      objs[node.n - 1] = `<< ${parts.join(' ')} >>`;
    });
    write(tree);
    objs[outlinesObj - 1] = `<< /Type /Outlines /First ${tree[0].n} 0 R /Last ${tree[tree.length - 1].n} 0 R /Count ${tree.length} >>`;
    catalog.push(`/Outlines ${outlinesObj} 0 R`);
  }
  objs[0] = `<< ${catalog.join(' ')} >>`;

  if (opts.padBytes) {
    const rnd = prng(opts.seed || 1);
    const pad = Buffer.allocUnsafe(opts.padBytes);
    for (let i = 0; i < pad.length; i++) pad[i] = Math.floor(rnd() * 256);
    add({ dict: '<< >>', stream: pad });                     // unreferenced: readers skip it, size grows
  }

  const incremental = opts.incremental && infoObj
    ? { objs: { [infoObj]: `<< ${infoKeys.join(' ')} /Subject (incremental update) >>` } }
    : undefined;
  return assemblePdf(objs, { version: opts.version || '1.5', info: infoObj || undefined,
                             xrefStream: !!opts.xrefStream, incremental });
}

/**
 * The JSON text of a pdfpc sidecar file (`talk.pdfpc` beside `talk.pdf`), written as pdfpc 4.7.0's writer writes it
 * (src/classes/metadata/pdf.vala): its keys in its order, each only when pdfpc writes it — duration when > 0, endTime
 * and startTime when set, lastMinutes when ≠ 5, endSlide and savedSlide when ≥ 0, defaultTransition when set,
 * beamerNotePosition when not none (in upper case, as pdfpc writes it; its reader takes any case), noteFontSize when
 * > 0 — and pdfpcFormat, disableMarkdown and pages always. A page entry is {idx, label, overlay} with forcedOverlay
 * and hidden only when true and note only when given. The layout is JSON.stringify's with four spaces; pdfpc's own
 * indentation was not checked, and its reader takes any JSON.
 *
 * spec: {pdfpcFormat = 2, duration, endTime, startTime, lastMinutes, endSlide, savedSlide, defaultTransition,
 *        beamerNotePosition, disableMarkdown = false, noteFontSize = 20, pages = []}
 */
function makePdfpcSidecar(spec = {}) {
  const out = { pdfpcFormat: spec.pdfpcFormat === undefined ? 2 : spec.pdfpcFormat };
  if (spec.duration > 0) out.duration = spec.duration;
  if (spec.endTime != null) out.endTime = spec.endTime;
  if (spec.startTime != null) out.startTime = spec.startTime;
  if (spec.lastMinutes != null && spec.lastMinutes !== 5) out.lastMinutes = spec.lastMinutes;
  if (spec.endSlide >= 0) out.endSlide = spec.endSlide;
  if (spec.savedSlide >= 0) out.savedSlide = spec.savedSlide;
  if (spec.defaultTransition != null) out.defaultTransition = spec.defaultTransition;
  if (spec.beamerNotePosition && String(spec.beamerNotePosition).toLowerCase() !== 'none')
    out.beamerNotePosition = String(spec.beamerNotePosition).toUpperCase();
  out.disableMarkdown = !!spec.disableMarkdown;
  const fontSize = spec.noteFontSize === undefined ? 20 : spec.noteFontSize;
  if (fontSize > 0) out.noteFontSize = fontSize;
  out.pages = (spec.pages || []).map((p) => {
    const e = { idx: p.idx, label: p.label, overlay: p.overlay };
    if (p.forcedOverlay) e.forcedOverlay = true;
    if (p.hidden) e.hidden = true;
    if (p.note != null) e.note = p.note;
    return e;
  });
  return JSON.stringify(out, null, 4) + '\n';
}

/** A torn copy of `buf`: its first ⌊frac·length⌋ bytes (what a reader sees mid-write). */
function truncatePdf(buf, frac) {
  return Buffer.from(buf.subarray(0, Math.max(0, Math.floor(buf.length * frac))));
}

module.exports = {
  assemblePdf, makeTextPdf, makeOutlinePdf, makeDeckPdf, makePdfpcSidecar, truncatePdf, pageColour, NOTES_COLOUR,
  NOTE_TEMPLATES, pdfText, annotText, pdfName, BEAMER_SIZES, EXPORT_SIZES,
};
