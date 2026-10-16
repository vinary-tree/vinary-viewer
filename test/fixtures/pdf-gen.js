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
//                             outline, padding, PDF version, incremental update, xref stream.
//   truncatePdf(buf, frac)    a torn copy (the first ⌊frac·len⌋ bytes) — a half-written file.
//   pageColour(i)             the RGB fill makeDeckPdf paints on page i, so a test can identify the shown page
//                             from a single pixel.

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

/** A PDF name with characters outside the regular set #-escaped (PDF 32000-1 §7.3.5). */
function pdfName(s) {
  return '/' + String(s).replace(/[^A-Za-z0-9._\-]/g, (c) => '#' + c.charCodeAt(0).toString(16).padStart(2, '0'));
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

/** Slide-tool export canvases (PDF points): PowerPoint 16:9 / 4:3, Google Slides 16:9. */
const EXPORT_SIZES = { 'powerpoint-169': [960, 540], 'powerpoint-43': [720, 540], 'google-169': [720, 405] };

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
 *                a label — beamer's \thispdfpagelabel{\insertframenumber}); an explicit string array; or null →
 *                no /PageLabels at all (what xdvipdfmx-built decks look like)
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
  const pages = frames.reduce((a, b) => a + b, 0);
  const resolveSize = (s) => (Array.isArray(s) ? s : BEAMER_SIZES[s] || EXPORT_SIZES[s] || BEAMER_SIZES[169]);
  const second = opts.secondScreen || null;
  const paper = resolveSize(opts.size === undefined ? 169 : opts.size);
  const doubled = !second ? paper
    : (second === 'right' || second === 'left') ? [2 * paper[0], paper[1]] : [paper[0], 2 * paper[1]];
  const sizes = opts.sizes || Array.from({ length: pages }, () => doubled);
  const frameOf = [];
  const overlayOf = [];
  frames.forEach((k, f) => { for (let j = 0; j < k; j++) { frameOf.push(f); overlayOf.push(j); } });
  const colours = opts.colours !== false;
  const text = opts.text !== false;
  const links = opts.links || [];

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

  for (let i = 0; i < pages; i++) {
    const [w, h] = sizes[i];
    const ops = [];
    // the slide's rect [x y w h] in PDF space (y UP): the whole page, or — on a second-screen page — the half
    // opposite the notes (notes right → slide left; notes top → slide at the BOTTOM, i.e. y ∈ [0, h/2])
    const half = !second ? [0, 0, w, h]
      : second === 'right' ? [0, 0, w / 2, h] : second === 'left' ? [w / 2, 0, w / 2, h]
      : second === 'top' ? [0, 0, w, h / 2] : [0, h / 2, w, h / 2];
    const notes = !second ? null
      : second === 'right' ? [w / 2, 0, w / 2, h] : second === 'left' ? [0, 0, w / 2, h]
      : second === 'top' ? [0, h / 2, w, h / 2] : [0, 0, w, h / 2];
    const [sx, sy, sw, sh] = half;
    if (colours) {
      const [r, g, b] = pageColour(i).map((v) => num(v / 255));
      ops.push('q', `${r} ${g} ${b} rg`, `${num(sx)} ${num(sy)} ${num(sw)} ${num(sh)} re f`, 'Q');
      if (notes) {
        const [nr, ng, nb] = NOTES_COLOUR.map((v) => num(v / 255));
        ops.push('q', `${nr} ${ng} ${nb} rg`, `${notes.map(num).join(' ')} re f`, 'Q');
      }
    }
    if (text) {
      const f = frameOf[i], k = overlayOf[i], K = frames[f];
      ops.push('BT', '0 0 0 rg', '/F1 18 Tf', `${num(sx + 24)} ${num(sy + sh - 48)} Td`,
               `(Frame ${f + 1} overlay ${k + 1} of ${K}) Tj`, '0 -26 Td', `(deck page ${i + 1}) Tj`, 'ET');
      if (notes) {
        ops.push('BT', '0 0 0 rg', '/F1 14 Tf', `${num(notes[0] + 24)} ${num(notes[1] + notes[3] - 48)} Td`,
                 `(Speakernotes for frame ${f + 1}) Tj`, 'ET');
      }
    }
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
  if (opts.creatorTool) {
    const xmp = '<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>' +
      '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">' +
      '<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">' +
      `<xmp:CreatorTool>${String(opts.creatorTool).replace(/&/g, '&amp;').replace(/</g, '&lt;')}</xmp:CreatorTool>` +
      '</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>';
    const bytes = Buffer.from(xmp, 'utf8');
    catalog.push(`/Metadata ${add({ dict: '<< /Type /Metadata /Subtype /XML >>', stream: bytes })} 0 R`);
  }
  const labels = opts.labels === undefined ? 'beamer' : opts.labels;
  if (labels) {
    const list = labels === 'beamer' ? frameOf.map((f) => String(f + 1)) : labels;
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

/** A torn copy of `buf`: its first ⌊frac·length⌋ bytes (what a reader sees mid-write). */
function truncatePdf(buf, frac) {
  return Buffer.from(buf.subarray(0, Math.max(0, Math.floor(buf.length * frac))));
}

module.exports = {
  assemblePdf, makeTextPdf, makeOutlinePdf, makeDeckPdf, truncatePdf, pageColour, NOTES_COLOUR,
  pdfText, pdfName, BEAMER_SIZES, EXPORT_SIZES,
};
