'use strict';
// pdf_integrity.js — is this byte buffer a COMPLETE PDF, or one caught mid-write? (ADR-0046 §Torn reads)
//
// WHY. pdflatex / lualatex / latexmk rewrite a PDF in place while a live-refresh read may already be running, so
// a read can return a prefix of the new file (a "torn" PDF). pdf.js then fails to open it — or worse, opens a
// partial document. A writer finishes a PDF with its cross-reference pointer and the end-of-file marker, so a
// file whose tail does not end that way is not finished yet.
//
// THE CHECKS (PDF 32000-1:2008):
//   1. header   `%PDF-` within the first 1024 bytes — §7.5.2 puts it on the first line; Annex H notes readers
//               accept it anywhere in the first 1024 bytes (some producers emit a byte-order mark or junk first)
//   2. EOF      the LAST `%%EOF` (§7.5.5) within the final 1024 bytes. LAST, because an incrementally updated
//               file (§7.5.6) carries one %%EOF per revision, and a torn incremental update ends after an
//               earlier revision's marker. Within 1024 bytes, because writers may append whitespace or junk.
//   3. startxref the `startxref` keyword before that %%EOF, followed by a decimal byte offset that lies INSIDE the
//               file and points at a cross-reference section: `xref` (a classic table, §7.5.4) or `N G obj` (a
//               cross-reference stream object, §7.5.8). A truncated file's pointer runs past its end; a file
//               whose bytes were rewritten around a stale tail points at something else.
//
// Pure and dependency-free: the content service runs it main-side, tests run it on fixtures, and `tailVerdict`
// lets a REMOTE reader judge a file from its last KiB before downloading the rest.

const HEAD_WINDOW = 1024;
const TAIL_WINDOW = 1024;

// The last index of the ASCII `needle` in buf[from, to), or -1.
function lastIndexOf(buf, needle, from, to) {
  const n = Buffer.from(needle, 'latin1');
  for (let i = Math.min(to, buf.length) - n.length; i >= from; i -= 1) {
    let j = 0;
    while (j < n.length && buf[i + j] === n[j]) j += 1;
    if (j === n.length) return i;
  }
  return -1;
}

const isWs = (c) => c === 0x20 || c === 0x0a || c === 0x0d || c === 0x09 || c === 0x0c || c === 0x00;
const isDigit = (c) => c >= 0x30 && c <= 0x39;

// Parse the startxref offset in `tail` (the file's final bytes, `tail[0]` at file offset `tailStart`): the last
// `startxref` before the last `%%EOF`. → { offset } or { reason }.
function parseTail(tail, tailStart, fileSize) {
  const eof = lastIndexOf(tail, '%%EOF', Math.max(0, tail.length - TAIL_WINDOW), tail.length);
  if (eof < 0) return { reason: 'no-eof' };
  const sx = lastIndexOf(tail, 'startxref', 0, eof);
  if (sx < 0) return { reason: 'no-startxref' };
  let i = sx + 'startxref'.length;
  while (i < eof && isWs(tail[i])) i += 1;
  const digitsAt = i;
  while (i < eof && isDigit(tail[i])) i += 1;
  if (i === digitsAt || i - digitsAt > 15) return { reason: 'bad-startxref' };
  const offset = Number(tail.toString('latin1', digitsAt, i));
  if (!Number.isSafeInteger(offset) || offset >= Math.min(fileSize, tailStart + sx)) return { reason: 'bad-startxref' };
  return { offset };
}

// Judge a file from its LAST bytes only: `tail` = its final min(size, 1024 + slack) bytes, `fileSize` = its size.
// → { ok: true, offset } when the tail ends like a finished PDF (the pointer is in range), else { ok: false, reason }.
// The pointer's TARGET is not checked here (it may lie anywhere in the file) — `verdict` does that on the whole file.
function tailVerdict(tail, fileSize) {
  if (!Buffer.isBuffer(tail) || !(fileSize >= 0)) return { ok: false, reason: 'no-data' };
  const r = parseTail(tail, fileSize - tail.length, fileSize);
  return r.reason ? { ok: false, reason: r.reason } : { ok: true, offset: r.offset };
}

// Does buf[offset…] start a cross-reference section: `xref`, or an indirect object header `N G obj` (a
// cross-reference stream)? Leading whitespace is allowed (some writers point at the line before).
function xrefAt(buf, offset) {
  let i = offset;
  while (i < buf.length && isWs(buf[i])) i += 1;
  if (buf.toString('latin1', i, i + 4) === 'xref') return true;
  const m = /^(\d{1,10})[ \t\r\n\f\0]+(\d{1,5})[ \t\r\n\f\0]+obj\b/.exec(buf.toString('latin1', i, i + 40));
  return Boolean(m);
}

// Judge a whole file. → { ok: true } or { ok: false, reason } with reason one of
//   'no-data'        not a buffer, or empty
//   'no-header'      no `%PDF-` in the first 1024 bytes
//   'no-eof'         no `%%EOF` in the final 1024 bytes (a torn write, the usual case)
//   'no-startxref'   a `%%EOF` without a `startxref` before it
//   'bad-startxref'  the pointer is not a number, or points outside the file
//   'bad-xref'       the pointer is in range but not at a cross-reference section
function verdict(buf) {
  if (!Buffer.isBuffer(buf) || buf.length === 0) return { ok: false, reason: 'no-data' };
  if (lastIndexOf(buf, '%PDF-', 0, Math.min(buf.length, HEAD_WINDOW + 5)) < 0) return { ok: false, reason: 'no-header' };
  const tailStart = Math.max(0, buf.length - TAIL_WINDOW - 64);
  const r = parseTail(buf.subarray(tailStart), tailStart, buf.length);
  if (r.reason) return { ok: false, reason: r.reason };
  if (!xrefAt(buf, r.offset)) return { ok: false, reason: 'bad-xref' };
  return { ok: true };
}

module.exports = { verdict, tailVerdict, HEAD_WINDOW, TAIL_WINDOW };
