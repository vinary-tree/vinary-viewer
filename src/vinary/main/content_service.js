'use strict';

const fs = require('fs');
const path = require('path');
const readline = require('readline');
const stream = require('stream');
const zlib = require('zlib');
const yauzl = require('yauzl');
const tar = require('tar-stream');
const Papa = require('papaparse');
const mammoth = require('mammoth');
const { XMLParser } = require('fast-xml-parser');

const SMALL_LOG_BYTES = 5 * 1024 * 1024;
const SMALL_TABLE_BYTES = 2 * 1024 * 1024;
const MAX_ARCHIVE_DEPTH = 3;
const MAX_ARCHIVE_ENTRIES = 50000;
const MAX_ENTRY_BYTES = 512 * 1024 * 1024;
const LOG_PAGE_LINES = 2000;
const TABLE_PAGE_ROWS = 500;
const TABLE_COLS = 100;
const TABLE_CELL_CHARS = 4000;
const OFFICE_PREVIEW_BYTES = 64 * 1024 * 1024;
const WORKBOOK_PREVIEW_BYTES = 64 * 1024 * 1024;
const PAGE_CACHE_LIMIT = 96;

const pageCache = new Map();
const pageOrder = [];

const textExts = new Set(['.txt', '.text', '.rst', '.adoc', '.asciidoc']);
// Emacs Org-mode. MUST classify as its own kind, not 'text': the renderer dispatches :org/render on it, and the
// CLI/TUI would otherwise upgrade a "text" file that has a bundled tree-sitter grammar to "source" and print
// highlighted Org markup instead of rendering it. Mirrors vinary.main.file-kind/kind-of, its ClojureScript twin.
const orgExts = new Set(['.org']);
// LaTeX. MUST classify as its own kind, not 'text': the renderer dispatches :latex/render on it, and (as with
// org) the CLI/TUI would otherwise upgrade a "text" file that has a bundled tree-sitter-latex grammar to "source"
// and print highlighted LaTeX markup instead of rendering it. Mirrors vinary.main.file-kind/kind-of, its twin.
// NOT .sty/.cls/.bib — those are LaTeX support files, better shown as highlighted source than rendered.
const latexExts = new Set(['.tex', '.latex', '.ltx']);
const officeExts = new Set(['.docx', '.odt', '.odp', '.odf']);
const workbookExts = new Set(['.xlsx', '.xlsm', '.ods', '.fods']);
const delimitedExts = new Set(['.csv', '.tsv', '.tab', '.psv', '.dsv']);
const logExts = new Set(['.log', '.out', '.err', '.trace']);
const zipExts = new Set(['.zip', '.jar', '.war', '.ear', '.epub']);
const tarExts = new Set(['.tar', '.tgz', '.tar.gz']);
const imageExts = new Set(['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp', '.ico', '.avif']);
const markdownExts = new Set(['.md', '.markdown', '.mdx']);
const htmlExts = new Set(['.html', '.htm', '.xhtml']);
const mermaidExts = new Set(['.mmd', '.mermaid']);
// unified/git diffs — rendered (colored unified in the terminal; colored + side-by-side in the GUI) via the
// diff IR front-end. Its own kind, not 'text': a diff's `@@`/`+`/`-`/tabbed hunks otherwise trip sniffDelimited.
const diffExts = new Set(['.diff', '.patch']);
// Standard repo files that carry no (useful) extension. `wellKnownKind` below classifies them deterministically,
// INDEPENDENT of any bundled grammar — the CLI/TUI twin of vinary.main.file-kind/well-known-kind. Without this a
// GNU Makefile (its `target:` and tab-indented recipes) mis-detects as a delimited table via sniffDelimited.
const wellKnownTextNames = new Set(['license', 'licence', 'copying', 'copying.lesser', 'copyright', 'authors',
  'contributors', 'notice', 'patents', 'install', 'news', 'thanks', 'maintainers', 'todo', 'version', 'readme',
  'changelog', 'codeowners']);
const wellKnownSourceNames = new Set(['makefile', 'gnumakefile', 'dockerfile', 'containerfile', 'cmakelists.txt',
  'gemfile', 'rakefile', 'guardfile', 'podfile', 'vagrantfile', 'brewfile', 'capfile', 'berksfile', 'thorfile',
  'jenkinsfile', 'pkgbuild', '.gitignore', '.gitattributes', '.gitmodules', '.gitconfig', '.dockerignore',
  '.npmignore', '.prettierignore', '.eslintignore', '.stylelintignore', '.editorconfig', '.npmrc', '.inputrc',
  '.bashrc', '.zshrc', '.bash_profile', '.bash_aliases', '.bash_logout', '.zprofile', '.zshenv', '.profile']);
const wellKnownSourceSuffixes = ['.mk', '.make', '.mak', '.dockerfile', '.gemspec', '.podspec', '.rake'];

function archiveUri(root, entries) {
  return 'vv-archive://open?chain=' + encodeURIComponent(JSON.stringify([root].concat(entries || [])));
}

function isArchiveUri(uri) {
  return typeof uri === 'string' && uri.startsWith('vv-archive://');
}

function parseArchiveUri(uri) {
  const u = new URL(uri);
  const chain = JSON.parse(u.searchParams.get('chain') || '[]');
  if (!Array.isArray(chain) || chain.length === 0 || typeof chain[0] !== 'string') {
    throw new Error('Invalid archive URI');
  }
  return { root: chain[0], entries: chain.slice(1).map(String) };
}

function displayArchiveUri(root, entries) {
  return 'file://' + root + '!/' + (entries || []).join('!/');
}

function extname(name) {
  const s = String(name || '').toLowerCase();
  if (s.endsWith('.tar.gz')) return '.tar.gz';
  return path.extname(s);
}

function basename(name) {
  return String(name || '').split(/[\\/]/).pop() || String(name || '');
}

// Deterministic kind for a well-known repo file (Makefile / LICENSE / .gitignore / git config / …), or null.
// Twin of vinary.main.file-kind/well-known-kind. Grammar-independent, so classification is stable even before
// (or without) a bundled grammar; highlighting is layered separately via grammar-catalog/built-in-filetypes.
function wellKnownKind(name) {
  const norm = String(name).replace(/\\/g, '/');
  const base = basename(norm).toLowerCase();
  if (wellKnownTextNames.has(base)) return 'text';
  if (wellKnownSourceNames.has(base)) return 'source';
  if (wellKnownSourceSuffixes.some(s => base.endsWith(s))) return 'source';
  if (base === '.env' || base.startsWith('.env.')) return 'source';   // .env, .env.local, .env.production …
  if (/(?:^|\/)\.git\/config$/.test(norm)) return 'source';           // a repo's .git/config
  return null;
}

function classifyName(name) {
  const ext = extname(name);
  const base = basename(name).toLowerCase();
  if (officeExts.has(ext)) return 'office';
  if (workbookExts.has(ext) || delimitedExts.has(ext)) return 'table';
  if (zipExts.has(ext) || tarExts.has(ext)) return 'archive';
  if (logExts.has(ext) || isLogBasename(base)) return 'log';
  if (markdownExts.has(ext)) return 'markdown';
  if (orgExts.has(ext)) return 'org';
  if (latexExts.has(ext)) return 'latex';
  if (diffExts.has(ext)) return 'diff';
  if (htmlExts.has(ext)) return 'html';
  if (mermaidExts.has(ext)) return 'mermaid';
  if (imageExts.has(ext)) return 'image';
  if (ext === '.pdf') return 'pdf';
  // well-known repo files BEFORE the plain-text fallback, so a Makefile is 'source' (not sniffed as a table)
  // and a LICENSE is deterministically 'text'.
  const wk = wellKnownKind(name);
  if (wk) return wk;
  if (textExts.has(ext)) return 'text';
  return 'text';
}

function isLogBasename(base) {
  return base === 'syslog' || base === 'messages' || base === 'secure' ||
    base === 'debug' || base === 'kern.log' || base === 'auth.log' ||
    /^.+\.(log|out|err|trace)\.gz$/.test(base) ||
    /^.+\.log\.\d+(\.gz)?$/.test(base);
}

function archiveDepth(entries) {
  return (entries || []).filter(entry => classifyName(entry) === 'archive').length;
}

function directoryEntryPath(entryPath) {
  return String(entryPath || '').replace(/\/?$/, '/');
}

function isArchiveDirectoryEntry(entryPath) {
  return String(entryPath || '').endsWith('/');
}

function gzipLogName(name) {
  const s = String(name || '').toLowerCase();
  return s.endsWith('.gz') && classifyName(s) === 'log';
}

function sniffLog(text) {
  const lines = String(text || '').split(/\r?\n/).slice(0, 80).filter(Boolean);
  if (lines.length < 3) return false;
  let hits = 0;
  for (const line of lines) {
    if (/^\d{4}-\d\d-\d\d[ T]\d\d:\d\d:\d\d/.test(line) ||
        /^\[\d{4}-\d\d-\d\d[ T]\d\d:\d\d:\d\d/.test(line) ||
        /^[A-Z][a-z]{2}\s+\d{1,2}\s+\d\d:\d\d:\d\d/.test(line) ||
        /\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|CRITICAL)\b/i.test(line) ||
        /^\S+\s+\S+\s+\S+\s+\[[^\]]+\]\s+"[A-Z]+\s+[^"]+\s+HTTP\/\d(?:\.\d)?"/.test(line) ||
        /^\s*\{.*"(time|timestamp|level|severity)"\s*:/.test(line) ||
        /^\[[^\]]*(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|CRITICAL)[^\]]*\]/i.test(line)) {
      hits += 1;
    }
  }
  return hits >= Math.max(2, Math.ceil(lines.length * 0.25));
}

function sniffDelimited(text) {
  const sample = String(text || '').split(/\r?\n/).filter(Boolean).slice(0, 20);
  if (sample.length < 3) return null;
  const delims = [',', '\t', '|', ';'];
  for (const delimiter of delims) {
    const counts = sample.map(line => line.split(delimiter).length).filter(n => n > 1);
    if (counts.length >= 3) {
      const first = counts[0];
      const stable = counts.filter(n => Math.abs(n - first) <= 1).length;
      if (stable >= Math.ceil(counts.length * 0.75)) return delimiter;
    }
  }
  return null;
}

function cacheKey(req) {
  return [req.path, req.stamp, req.kind, req.page || 0, req.sheet || 0].join('\u0000');
}

function rememberPage(req, value) {
  const key = cacheKey(req);
  if (!pageCache.has(key)) pageOrder.push(key);
  pageCache.set(key, value);
  while (pageOrder.length > PAGE_CACHE_LIMIT) pageCache.delete(pageOrder.shift());
  return value;
}

function cachedPage(req) {
  return pageCache.get(cacheKey(req));
}

function statPayload(filePath) {
  const stat = fs.statSync(filePath);
  return { size: stat.size, mtime: stat.mtimeMs };
}

function readPrefix(filePath, n) {
  const fd = fs.openSync(filePath, 'r');
  try {
    const buf = Buffer.alloc(Math.min(n, fs.fstatSync(fd).size));
    fs.readSync(fd, buf, 0, buf.length, 0);
    return buf.toString('utf8');
  } finally {
    fs.closeSync(fd);
  }
}

function readFileLimited(filePath, maxBytes) {
  const stat = fs.statSync(filePath);
  if (stat.size > maxBytes) {
    throw new Error(`File is too large for this preview (${formatBytes(stat.size)} > ${formatBytes(maxBytes)})`);
  }
  return fs.readFileSync(filePath);
}

function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

// RETIRED (ADR-0017): office HTML is now sanitized by the single GitHub-allowlist schema in the renderer's
// IR back-end (vinary.ir.backend.sanitize) via render-office-ir, replacing this weaker main-process regex
// sanitizer. Kept commented for reference rather than deleted.
// function sanitizeHtml(html) {
//   return String(html || '')
//     .replace(/<\s*(script|style|iframe|object|embed|link|meta)\b[\s\S]*?<\s*\/\s*\1\s*>/gi, '')
//     .replace(/<\s*(script|style|iframe|object|embed|link|meta)\b[^>]*>/gi, '')
//     .replace(/\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
//     .replace(/\s+(href|src)\s*=\s*(['"]?)\s*javascript:[^'"\s>]*/gi, ' $1=$2#');
// }

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function nodeText(x) {
  if (x == null) return '';
  if (typeof x === 'string' || typeof x === 'number' || typeof x === 'boolean') return String(x);
  if (Array.isArray(x)) return x.map(nodeText).filter(Boolean).join(' ');
  if (typeof x === 'object') {
    const out = [];
    for (const [k, v] of Object.entries(x)) {
      if (k === '#text' || !k.startsWith('@_')) out.push(nodeText(v));
    }
    return out.filter(Boolean).join(' ');
  }
  return '';
}

function xmlParser() {
  return new XMLParser({ ignoreAttributes: false, attributeNamePrefix: '@_', textNodeName: '#text' });
}

function toArray(x) {
  if (x == null) return [];
  return Array.isArray(x) ? x : [x];
}

function dataUrlFor(name, bytes) {
  const ext = extname(name);
  const type = ext === '.svg' ? 'image/svg+xml' :
    ext === '.png' ? 'image/png' :
    ext === '.gif' ? 'image/gif' :
    ext === '.webp' ? 'image/webp' :
    ext === '.avif' ? 'image/avif' :
    ext === '.ico' ? 'image/x-icon' : 'image/jpeg';
  return `data:${type};base64,${Buffer.from(bytes).toString('base64')}`;
}

function bufferToPayload(uri, name, bytes, stamp, meta) {
  const kind = classifyName(name);
  if (kind === 'image') {
    return { path: uri, kind: 'image', dataUrl: dataUrlFor(name, bytes), stamp, meta };
  }
  if (kind === 'pdf') {
    return { path: uri, kind: 'pdf', bytes, stamp, meta };
  }
  if (kind === 'markdown' || kind === 'mermaid' || kind === 'org' || kind === 'latex') {
    return { path: uri, kind, text: bytes.toString('utf8'), stamp, sourceable: true, meta };
  }
  if (kind === 'office') return officeBufferPayload(uri, name, bytes, stamp, meta);
  if (kind === 'table') return tableBufferPayload(uri, name, bytes, stamp, meta);
  if (kind === 'log') return logBufferPayload(uri, name, bytes, stamp, meta);
  const text = bytes.toString('utf8');
  if (sniffLog(text)) return logTextPayload(uri, name, text, stamp, meta);
  const delimiter = sniffDelimited(text);
  if (delimiter) return delimitedTextPayload(uri, name, text, stamp, Object.assign({}, meta, { delimiter }));
  return { path: uri, kind: 'text', text, stamp, sourceable: true, meta };
}

async function officeBufferPayload(uri, name, bytes, stamp, meta) {
  const ext = extname(name);
  if (bytes.length > OFFICE_PREVIEW_BYTES) {
    throw new Error(`Office document is too large for preview (${formatBytes(bytes.length)})`);
  }
  if (ext === '.docx') {
    const result = await mammoth.convertToHtml({ buffer: bytes });
    const text = (await mammoth.extractRawText({ buffer: bytes })).value || '';
    return {
      path: uri,
      kind: 'office',
      html: result.value,   // sanitized downstream by the IR back-end (render-office-ir); ADR-0017
      text,
      stamp,
      meta: Object.assign({}, meta, { warnings: (result.messages || []).map(m => m.message || String(m)) })
    };
  }
  return openDocumentPayload(uri, name, bytes, stamp, meta);
}

async function openDocumentPayload(uri, name, bytes, stamp, meta) {
  const content = extname(name) === '.fods' ? bytes.toString('utf8') : await zipText(bytes, 'content.xml');
  const parsed = xmlParser().parse(content);
  const pieces = [];
  collectOpenDocumentBlocks(parsed, pieces);
  const blockText = pieces.map(p => p.text).filter(Boolean).join('\n');
  const fallbackText = blockText ? '' : nodeText(parsed).trim();
  const text = blockText || fallbackText;
  const html = pieces.length > 0
    ? pieces.map(p => p.kind === 'heading'
      ? `<h${Math.min(6, Math.max(1, p.level || 1))}>${escapeHtml(p.text)}</h${Math.min(6, Math.max(1, p.level || 1))}>`
      : `<p>${escapeHtml(p.text)}</p>`).join('\n')
    : `<pre class="vv-plain">${escapeHtml(fallbackText)}</pre>`;
  return { path: uri, kind: 'office', html, text, stamp, meta };
}

function collectOpenDocumentBlocks(node, out) {
  if (node == null) return;
  if (Array.isArray(node)) {
    node.forEach(n => collectOpenDocumentBlocks(n, out));
    return;
  }
  if (typeof node !== 'object') return;
  for (const [k, v] of Object.entries(node)) {
    if (k === 'text:h') {
      for (const h of toArray(v)) out.push({ kind: 'heading', level: Number(h['@_text:outline-level'] || 1), text: nodeText(h) });
    } else if (k === 'text:p') {
      for (const p of toArray(v)) {
        const text = nodeText(p).trim();
        if (text) out.push({ kind: 'p', text });
      }
    } else {
      collectOpenDocumentBlocks(v, out);
    }
  }
}

async function tableBufferPayload(uri, name, bytes, stamp, meta) {
  const ext = extname(name);
  if (delimitedExts.has(ext)) {
    const text = bytes.toString('utf8');
    if (bytes.length > SMALL_TABLE_BYTES) {
      const page = parseDelimitedPage(text, delimiterFor(name, meta), 0);
      return { path: uri, kind: 'table', paged: true, page, stamp, sourceable: true, meta: Object.assign({}, meta, { pageSize: TABLE_PAGE_ROWS }) };
    }
    return delimitedTextPayload(uri, name, text, stamp, meta);
  }
  if (bytes.length > WORKBOOK_PREVIEW_BYTES) {
    throw new Error(`Workbook is too large for preview (${formatBytes(bytes.length)})`);
  }
  if (ext === '.xlsx' || ext === '.xlsm') return xlsxPayload(uri, name, bytes, stamp, meta);
  if (ext === '.ods' || ext === '.fods') return odsPayload(uri, name, bytes, stamp, meta);
  return delimitedTextPayload(uri, name, bytes.toString('utf8'), stamp, meta);
}

function delimitedTextPayload(uri, name, text, stamp, meta) {
  const delimiter = delimiterFor(name, meta);
  const parsed = Papa.parse(text, { delimiter, skipEmptyLines: false });
  const rawRows = parsed.data || [];
  const rows = normalizeRows(rawRows);
  const sheet = { name: basename(name), rows, truncated: rowsTruncated(rawRows, rows) };
  return { path: uri, kind: 'table', sheets: [sheet], text, stamp, sourceable: true, meta: Object.assign({}, meta, { delimiter }) };
}

function parseDelimitedPage(text, delimiter, pageIndex) {
  const start = Math.max(0, pageIndex || 0) * TABLE_PAGE_ROWS;
  const end = start + TABLE_PAGE_ROWS;
  const parsed = Papa.parse(text, { delimiter, skipEmptyLines: false });
  const rows = (parsed.data || []).map(normalizeRow);
  return { index: pageIndex || 0, rows: rows.slice(start, end), hasPrev: start > 0, hasNext: rows.length > end };
}

function normalizeRows(rows, limit) {
  return rows.slice(0, limit == null ? 2000 : limit).map(normalizeRow);
}

function normalizeRow(row) {
  const arr = Array.isArray(row) ? row : [row];
  return arr.slice(0, TABLE_COLS).map(cell => {
    const s = String(cell == null ? '' : cell);
    return s.length > TABLE_CELL_CHARS ? s.slice(0, TABLE_CELL_CHARS) : s;
  });
}

function rowsTruncated(rawRows, normalizedRows) {
  return rawRows.length > normalizedRows.length ||
    rawRows.some(row => {
      const arr = Array.isArray(row) ? row : [row];
      return arr.length > TABLE_COLS ||
        arr.some(cell => String(cell == null ? '' : cell).length > TABLE_CELL_CHARS);
    });
}

function delimiterFor(name, meta) {
  if (meta && meta.delimiter) return meta.delimiter;
  const ext = extname(name);
  if (ext === '.tsv' || ext === '.tab') return '\t';
  if (ext === '.psv') return '|';
  if (ext === '.dsv') return '';
  return '';
}

function contentStreamForName(name, rs) {
  return gzipLogName(name) ? rs.pipe(zlib.createGunzip()) : rs;
}

function localContentStream(filePath) {
  return contentStreamForName(filePath, fs.createReadStream(filePath));
}

function entryContentStream(source) {
  return contentStreamForName(source.name, source.stream);
}

function streamLogPage(rs, pageIndex) {
  const index = Math.max(0, Number(pageIndex || 0));
  const start = index * LOG_PAGE_LINES;
  const end = start + LOG_PAGE_LINES;
  const lines = [];
  let lineIndex = 0;
  let settled = false;
  return new Promise((resolve, reject) => {
    const done = value => {
      if (!settled) {
        settled = true;
        resolve(value);
      }
    };
    const fail = err => {
      if (!settled) {
        settled = true;
        reject(err);
      }
    };
    const rl = readline.createInterface({ input: rs, crlfDelay: Infinity });
    rl.on('line', line => {
      if (lineIndex >= start && lineIndex < end) lines.push(line);
      lineIndex += 1;
      if (lineIndex > end) {
        done({ index, lines, hasPrev: start > 0, hasNext: true });
        rl.close();
        rs.destroy();
      }
    });
    rl.on('close', () => done({ index, lines, hasPrev: start > 0, hasNext: false }));
    rl.on('error', fail);
    rs.on('error', fail);
  });
}

function streamDelimitedPage(rs, delimiter, pageIndex) {
  const index = Math.max(0, Number(pageIndex || 0));
  const start = index * TABLE_PAGE_ROWS;
  const end = start + TABLE_PAGE_ROWS;
  const rows = [];
  let rowIndex = 0;
  let settled = false;
  return new Promise((resolve, reject) => {
    const done = value => {
      if (!settled) {
        settled = true;
        resolve(value);
      }
    };
    const fail = err => {
      if (!settled) {
        settled = true;
        reject(err);
      }
    };
    const parser = Papa.parse(Papa.NODE_STREAM_INPUT, { delimiter, skipEmptyLines: false });
    parser.on('data', row => {
      if (rowIndex >= start && rowIndex < end) rows.push(normalizeRow(row));
      rowIndex += 1;
      if (rowIndex > end) {
        done({ index, rows, hasPrev: start > 0, hasNext: true });
        rs.destroy();
        parser.destroy();
      }
    });
    parser.on('finish', () => done({ index, rows, hasPrev: start > 0, hasNext: false }));
    parser.on('error', fail);
    rs.on('error', fail);
    rs.pipe(parser);
  });
}

async function zipText(bytes, entryName) {
  const entry = await zipEntryBuffer(bytes, entryName);
  return entry.toString('utf8');
}

async function xlsxPayload(uri, name, bytes, stamp, meta) {
  const workbookXml = await zipText(bytes, 'xl/workbook.xml');
  const relsXml = await zipText(bytes, 'xl/_rels/workbook.xml.rels');
  const strings = await sharedStrings(bytes);
  const parser = xmlParser();
  const wb = parser.parse(workbookXml);
  const rels = parser.parse(relsXml);
  const relMap = {};
  for (const rel of toArray(rels.Relationships && rels.Relationships.Relationship)) {
    relMap[rel['@_Id']] = rel['@_Target'];
  }
  const sheets = [];
  for (const sh of toArray(wb.workbook && wb.workbook.sheets && wb.workbook.sheets.sheet).slice(0, 20)) {
    const relId = sh['@_r:id'];
    const target = relMap[relId];
    if (!target) continue;
    const entry = target.startsWith('xl/') ? target : 'xl/' + target.replace(/^\//, '');
    const sheetXml = await zipText(bytes, entry);
    sheets.push(Object.assign({ name: sh['@_name'] || `Sheet ${sheets.length + 1}` }, parseXlsxSheet(sheetXml, strings)));
  }
  return { path: uri, kind: 'table', sheets, stamp, meta };
}

async function sharedStrings(bytes) {
  try {
    const xml = await zipText(bytes, 'xl/sharedStrings.xml');
    const parsed = xmlParser().parse(xml);
    return toArray(parsed.sst && parsed.sst.si).map(nodeText);
  } catch (_) {
    return [];
  }
}

function parseXlsxSheet(xml, strings) {
  const parsed = xmlParser().parse(xml);
  const rawRows = toArray(parsed.worksheet && parsed.worksheet.sheetData && parsed.worksheet.sheetData.row);
  let truncated = rawRows.length > 2000;
  const rows = rawRows.slice(0, 2000).map(row => {
    const cells = [];
    const rawCells = toArray(row.c);
    if (rawCells.length > TABLE_COLS) truncated = true;
    for (const c of rawCells.slice(0, TABLE_COLS)) {
      const ref = c['@_r'] || '';
      const idx = colIndex(ref);
      const value = xlsxCellValue(c, strings);
      if (String(value).length > TABLE_CELL_CHARS) truncated = true;
      cells[idx] = value;
    }
    return compactRow(cells);
  });
  return { rows, truncated };
}

function colIndex(ref) {
  const m = String(ref).match(/^[A-Z]+/);
  if (!m) return 0;
  let n = 0;
  for (const ch of m[0]) n = n * 26 + (ch.charCodeAt(0) - 64);
  return Math.max(0, n - 1);
}

function compactRow(cells) {
  const n = Math.min(TABLE_COLS, cells.length);
  const out = [];
  for (let i = 0; i < n; i += 1) {
    const s = String(cells[i] == null ? '' : cells[i]);
    out.push(s.length > TABLE_CELL_CHARS ? s.slice(0, TABLE_CELL_CHARS) : s);
  }
  return out;
}

function xlsxCellValue(c, strings) {
  const t = c['@_t'];
  const v = c.v == null ? '' : c.v;
  if (t === 's') return strings[Number(v)] || '';
  if (t === 'inlineStr') return nodeText(c.is);
  return String(v == null ? '' : v);
}

async function odsPayload(uri, name, bytes, stamp, meta) {
  const xml = extname(name) === '.fods' ? bytes.toString('utf8') : await zipText(bytes, 'content.xml');
  const parsed = xmlParser().parse(xml);
  const tables = [];
  collectOdsTables(parsed, tables);
  return { path: uri, kind: 'table', sheets: tables.slice(0, 20), stamp, meta };
}

function collectOdsTables(node, out) {
  if (!node || typeof node !== 'object') return;
  for (const [k, v] of Object.entries(node)) {
    if (k === 'table:table') {
      for (const table of toArray(v)) out.push(parseOdsTable(table));
    } else if (typeof v === 'object') {
      collectOdsTables(v, out);
    }
  }
}

function parseOdsTable(table) {
  const rows = [];
  let truncated = false;
  for (const r of toArray(table['table:table-row'])) {
    const repeatedRows = Math.min(2000, Math.max(1, Number(r['@_table:number-rows-repeated'] || 1)));
    const row = [];
    const cells = toArray(r['table:table-cell']);
    if (cells.length > TABLE_COLS) truncated = true;
    for (const c of cells.slice(0, TABLE_COLS)) {
      const rawRepeated = Number(c['@_table:number-columns-repeated'] || 1);
      const repeated = Math.min(32, rawRepeated);
      if (rawRepeated > repeated) truncated = true;
      const rawText = nodeText(c['text:p'] || c['@_office:value'] || c['@_office:string-value']);
      const text = rawText.length > TABLE_CELL_CHARS ? rawText.slice(0, TABLE_CELL_CHARS) : rawText;
      if (rawText.length > TABLE_CELL_CHARS) truncated = true;
      for (let i = 0; i < repeated && row.length < TABLE_COLS; i += 1) row.push(text);
      if (repeated > 1 && row.length >= TABLE_COLS) truncated = true;
    }
    for (let i = 0; i < repeatedRows && rows.length < 2000; i += 1) {
      rows.push(row.slice());
    }
    if (rows.length >= 2000) {
      truncated = true;
      break;
    }
  }
  return { name: table['@_table:name'] || 'Sheet', rows, truncated };
}

function logBufferPayload(uri, name, bytes, stamp, meta) {
  const text = bytes.toString('utf8');
  if (bytes.length > SMALL_LOG_BYTES) {
    return { path: uri, kind: 'log', paged: true, page: parseLogPage(text, 0), stamp, sourceable: true, meta: Object.assign({}, meta, { pageSize: LOG_PAGE_LINES }) };
  }
  return logTextPayload(uri, name, text, stamp, meta);
}

function logTextPayload(uri, name, text, stamp, meta) {
  return { path: uri, kind: 'log', text, stamp, sourceable: true, meta };
}

function parseLogPage(text, pageIndex) {
  const lines = String(text || '').split(/\r?\n/);
  const start = Math.max(0, pageIndex || 0) * LOG_PAGE_LINES;
  const end = start + LOG_PAGE_LINES;
  return { index: pageIndex || 0, lines: lines.slice(start, end), hasPrev: start > 0, hasNext: lines.length > end };
}

function imageOrBinaryEntryPayload(uri, name, bytes, stamp, meta) {
  return bufferToPayload(uri, name, bytes, stamp, meta);
}

function pageForTextPayload(req, text, name) {
  if (req.kind === 'log') return parseLogPage(text, Number(req.page || 0));
  if (req.kind === 'table') return parseDelimitedPage(text, delimiterFor(name, req.meta), Number(req.page || 0));
  throw new Error('Unsupported paged content kind');
}

function pageFromStream(req, rs, name) {
  if (req.kind === 'log') return streamLogPage(rs, req.page || 0);
  if (req.kind === 'table') return streamDelimitedPage(rs, delimiterFor(name, req.meta), req.page || 0);
  throw new Error('Unsupported paged content kind');
}

async function contentPage(req) {
  const request = req || {};
  const hit = cachedPage(request);
  if (hit) return hit;
  const uri = request.path;
  let name = uri;
  let page;
  if (isArchiveUri(uri)) {
    const source = await archiveEntrySource(uri);
    name = source.name;
    page = await pageFromStream(request, entryContentStream(source), name);
  } else {
    name = uri;
    const stat = fs.statSync(uri);
    if (stat.size > SMALL_LOG_BYTES || stat.size > SMALL_TABLE_BYTES || gzipLogName(uri)) {
      page = await pageFromStream(request, localContentStream(uri), name);
    } else {
      const text = fs.readFileSync(uri, 'utf8');
      page = pageForTextPayload(request, text, name);
    }
  }
  return rememberPage(request, page);
}

async function openUri(uri) {
  if (isArchiveUri(uri)) return openArchiveUri(uri);
  return openLocal(uri);
}

async function openLocal(filePath) {
  const stamp = Date.now();
  const fileStat = fs.statSync(filePath);
  // A directory must never reach the file parser: fs.readSync on a directory fd throws EISDIR
  // (readPrefix, below). The renderer already routes directories to the in-pane listing
  // (main/service.cljs send-content!); this guards the content-service path for any direct caller.
  if (fileStat.isDirectory()) {
    throw new Error(`Cannot preview a directory as a file: ${filePath}`);
  }
  const stat = { size: fileStat.size, mtime: fileStat.mtimeMs };
  const kind = classifyName(filePath);
  if (kind === 'archive') return archiveListingPayload(filePath, [], stamp);
  if (kind === 'office') return officeBufferPayload(filePath, filePath, readFileLimited(filePath, OFFICE_PREVIEW_BYTES), stamp, stat);
  if (kind === 'table') {
    if (fs.statSync(filePath).size > SMALL_TABLE_BYTES && delimitedExts.has(extname(filePath))) {
      const page = await streamDelimitedPage(localContentStream(filePath), delimiterFor(filePath), 0);
      return { path: filePath, kind: 'table', paged: true, page, stamp, sourceable: true, meta: Object.assign({}, stat, { pageSize: TABLE_PAGE_ROWS }) };
    }
    return tableBufferPayload(filePath, filePath, readFileLimited(filePath, WORKBOOK_PREVIEW_BYTES), stamp, stat);
  }
  if (kind === 'log') {
    if (fs.statSync(filePath).size > SMALL_LOG_BYTES || gzipLogName(filePath)) {
      const page = await streamLogPage(localContentStream(filePath), 0);
      return { path: filePath, kind: 'log', paged: true, page, stamp, sourceable: true, meta: Object.assign({}, stat, { pageSize: LOG_PAGE_LINES }) };
    }
    return logTextPayload(filePath, filePath, fs.readFileSync(filePath, 'utf8'), stamp, stat);
  }
  // Org and LaTeX must short-circuit BEFORE the content sniff below: an Org table (`| a | b |`) or a LaTeX
  // tabular (`a & b \\`) otherwise trips sniffDelimited and the document is previewed as a delimited table
  // rather than rendered through uniorg / unified-latex.
  if (kind === 'org' || kind === 'latex') {
    return { path: filePath, kind, text: fs.readFileSync(filePath, 'utf8'), stamp, sourceable: true, meta: stat };
  }
  // Source / diff, and well-known plain-text repo files (LICENSE/COPYING/…), likewise short-circuit the sniff:
  // a GNU Makefile's `target:`/tab-indented recipes and a diff's `@@`/`+`/`-` hunks are classic sniffDelimited
  // false positives. Generic extensionless `text` still falls through to the sniff (unknown logs / CSVs).
  if (kind === 'source' || kind === 'diff' || (kind === 'text' && wellKnownKind(filePath) === 'text')) {
    return { path: filePath, kind, text: fs.readFileSync(filePath, 'utf8'), stamp, sourceable: true, meta: stat };
  }
  const sample = readPrefix(filePath, 64 * 1024);
  if (sniffLog(sample)) {
    if (fs.statSync(filePath).size > SMALL_LOG_BYTES || gzipLogName(filePath)) {
      const page = await streamLogPage(localContentStream(filePath), 0);
      return { path: filePath, kind: 'log', paged: true, page, stamp, sourceable: true, meta: Object.assign({}, stat, { pageSize: LOG_PAGE_LINES }) };
    }
    return logTextPayload(filePath, filePath, fs.readFileSync(filePath, 'utf8'), stamp, stat);
  }
  const delimiter = sniffDelimited(sample);
  if (delimiter) {
    if (fs.statSync(filePath).size > SMALL_TABLE_BYTES) {
      const page = await streamDelimitedPage(localContentStream(filePath), delimiter, 0);
      return { path: filePath, kind: 'table', paged: true, page, stamp, sourceable: true, meta: Object.assign({}, stat, { delimiter, pageSize: TABLE_PAGE_ROWS }) };
    }
    return tableBufferPayload(filePath, filePath, fs.readFileSync(filePath), stamp, Object.assign({}, stat, { delimiter }));
  }
  return { path: filePath, kind: 'text', text: fs.readFileSync(filePath, 'utf8'), stamp, sourceable: true, meta: stat };
}

async function openArchiveUri(uri) {
  const { root, entries } = parseArchiveUri(uri);
  if (archiveDepth(entries) > MAX_ARCHIVE_DEPTH) throw new Error(`Archive nesting limit exceeded (${MAX_ARCHIVE_DEPTH})`);
  const stamp = Date.now();
  if (entries.length === 0 ||
      isArchiveDirectoryEntry(entries[entries.length - 1]) ||
      classifyName(entries[entries.length - 1]) === 'archive') {
    return archiveListingPayload(root, entries, stamp);
  }
  const source = await archiveEntrySource(uri);
  const kind = classifyName(source.name);
  if (kind === 'log' && (source.size > SMALL_LOG_BYTES || gzipLogName(source.name))) {
    const page = await streamLogPage(entryContentStream(source), 0);
    return { path: uri, kind: 'log', paged: true, page, stamp, sourceable: true, meta: Object.assign({}, source.meta, { pageSize: LOG_PAGE_LINES }) };
  }
  if (kind === 'table' && delimitedExts.has(extname(source.name)) && source.size > SMALL_TABLE_BYTES) {
    const page = await streamDelimitedPage(entryContentStream(source), delimiterFor(source.name), 0);
    return { path: uri, kind: 'table', paged: true, page, stamp, sourceable: true, meta: Object.assign({}, source.meta, { pageSize: TABLE_PAGE_ROWS }) };
  }
  const bytes = await readAll(entryContentStream(source), source.size);
  return imageOrBinaryEntryPayload(uri, source.name, bytes, stamp, source.meta);
}

async function archiveListingPayload(root, entries, stamp) {
  if (archiveDepth(entries) > MAX_ARCHIVE_DEPTH) throw new Error(`Archive nesting limit exceeded (${MAX_ARCHIVE_DEPTH})`);
  const display = displayArchiveUri(root, entries);
  const { source, prefix } = await archiveListingContext(root, entries);
  const archiveEntries = await listArchiveEntries(source, prefix);
  return {
    path: archiveUri(root, entries),
    kind: 'archive',
    entries: archiveEntries.map(entry => {
      const baseEntries = entries.length > 0 && isArchiveDirectoryEntry(entries[entries.length - 1])
        ? entries.slice(0, -1)
        : entries;
      const nextEntries = baseEntries.concat(entry.path);
      const nestedArchive = classifyName(entry.path) === 'archive';
      return {
        name: entry.name,
        path: archiveUri(root, nextEntries),
        dir: entry.dir,
        dir_: entry.dir,
        'dir?': entry.dir || nestedArchive,
        size: entry.size,
        compressedSize: entry.compressedSize,
        mtime: entry.mtime,
        symlink: false
      };
    }),
    stamp,
    meta: { display, depth: entries.length }
  };
}

async function archiveLayerSource(root, entries) {
  if (entries.length === 0) {
    return { name: root, kind: classifyName(root), filePath: root };
  }
  let layer = { name: root, kind: classifyName(root), filePath: root };
  for (const entryPath of entries) {
    if (isArchiveDirectoryEntry(entryPath)) throw new Error('Directory entries cannot contain nested archive layers');
    if (classifyName(entryPath) !== 'archive') throw new Error(`Archive entry is not an archive: ${entryPath}`);
    const entry = await openArchiveEntry(layer, entryPath);
    if (entry.size > MAX_ENTRY_BYTES) throw new Error(`Archive entry is too large (${formatBytes(entry.size)})`);
    layer = { name: entry.name, kind: classifyName(entry.name), buffer: await readAll(entry.stream, entry.size) };
  }
  return layer;
}

async function archiveListingContext(root, entries) {
  const chain = entries || [];
  if (chain.length === 0) return { source: await archiveLayerSource(root, []), prefix: '' };
  const last = chain[chain.length - 1];
  if (isArchiveDirectoryEntry(last)) {
    return { source: await archiveLayerSource(root, chain.slice(0, -1)), prefix: last };
  }
  if (classifyName(last) === 'archive') {
    return { source: await archiveLayerSource(root, chain), prefix: '' };
  }
  throw new Error(`Archive URI does not name a directory or archive: ${last}`);
}

async function archiveEntrySource(uri) {
  const { root, entries } = parseArchiveUri(uri);
  if (entries.length === 0) throw new Error('Archive URI does not name an entry');
  if (archiveDepth(entries) > MAX_ARCHIVE_DEPTH) throw new Error(`Archive nesting limit exceeded (${MAX_ARCHIVE_DEPTH})`);
  if (isArchiveDirectoryEntry(entries[entries.length - 1])) throw new Error('Archive URI names a directory');
  const layer = await archiveLayerSource(root, entries.slice(0, -1));
  const entry = await openArchiveEntry(layer, entries[entries.length - 1]);
  if (entry.size > MAX_ENTRY_BYTES) throw new Error(`Archive entry is too large (${formatBytes(entry.size)})`);
  return entry;
}

async function listArchiveEntries(source, prefix) {
  if (zipExts.has(extname(source.name))) return listZipEntries(source, prefix || '');
  if (tarExts.has(extname(source.name))) return listTarEntries(source, prefix || '');
  throw new Error('Unsupported archive format');
}

async function openArchiveEntry(source, entryPath) {
  if (zipExts.has(extname(source.name))) return openZipEntry(source, entryPath);
  if (tarExts.has(extname(source.name))) return openTarEntry(source, entryPath);
  throw new Error('Unsupported archive format');
}

function zipSource(source) {
  return new Promise((resolve, reject) => {
    const cb = (err, zip) => err ? reject(err) : resolve(zip);
    if (source.buffer) yauzl.fromBuffer(source.buffer, { lazyEntries: true }, cb);
    else yauzl.open(source.filePath, { lazyEntries: true }, cb);
  });
}

function archiveDisplayName(entryPath) {
  const clean = String(entryPath || '').replace(/\/$/, '');
  return clean.split('/').pop() || clean;
}

function normalizeArchiveMemberPath(entryPath, dir) {
  const clean = String(entryPath || '').replace(/^\/+/, '');
  return dir ? directoryEntryPath(clean.replace(/\/+$/, '')) : clean.replace(/\/+$/, '');
}

function immediateArchiveEntries(entries, prefix) {
  const pre = prefix || '';
  const byPath = new Map();
  for (const entry of entries) {
    const fullPath = normalizeArchiveMemberPath(entry.path, entry.dir);
    if (!fullPath || (pre && !fullPath.startsWith(pre))) continue;
    const rest = fullPath.slice(pre.length);
    if (!rest) continue;
    const slash = rest.indexOf('/');
    if (slash >= 0) {
      const directDir = entry.dir && slash === rest.length - 1;
      const dirPath = directDir ? fullPath : pre + rest.slice(0, slash + 1);
      if (!byPath.has(dirPath)) {
        byPath.set(dirPath, {
          path: dirPath,
          name: archiveDisplayName(dirPath),
          dir: true,
          size: directDir ? entry.size : null,
          compressedSize: directDir ? entry.compressedSize : null,
          mtime: directDir ? entry.mtime : null
        });
      }
    } else {
      byPath.set(fullPath, Object.assign({}, entry, {
        path: fullPath,
        name: archiveDisplayName(fullPath),
        dir: false
      }));
    }
  }
  return Array.from(byPath.values()).sort((a, b) => {
    if (a.dir !== b.dir) return a.dir ? -1 : 1;
    return String(a.name).localeCompare(String(b.name));
  });
}

async function listZipEntries(source, prefix) {
  const zip = await zipSource(source);
  return new Promise((resolve, reject) => {
    const out = [];
    let settled = false;
    const done = value => {
      if (!settled) {
        settled = true;
        zip.close();
        resolve(value);
      }
    };
    const fail = err => {
      if (!settled) {
        settled = true;
        zip.close();
        reject(err);
      }
    };
    zip.readEntry();
    zip.on('entry', entry => {
      if (out.length >= MAX_ARCHIVE_ENTRIES) {
        done(immediateArchiveEntries(out, prefix));
        return;
      }
      const dir = /\/$/.test(entry.fileName);
      const entryPath = normalizeArchiveMemberPath(entry.fileName, dir);
      out.push({
        path: entryPath,
        name: archiveDisplayName(entryPath),
        dir,
        size: entry.uncompressedSize || 0,
        compressedSize: entry.compressedSize || 0,
        mtime: entry.getLastModDate ? entry.getLastModDate().getTime() : null
      });
      zip.readEntry();
    });
    zip.on('end', () => done(immediateArchiveEntries(out, prefix)));
    zip.on('error', fail);
  });
}

async function openZipEntry(source, entryPath) {
  const zip = await zipSource(source);
  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = err => {
      if (!settled) {
        settled = true;
        zip.close();
        reject(err);
      }
    };
    zip.readEntry();
    zip.on('entry', entry => {
      const normalized = normalizeArchiveMemberPath(entry.fileName, /\/$/.test(entry.fileName)).replace(/\/$/, '');
      if (normalized === entryPath) {
        zip.openReadStream(entry, (err, rs) => {
          if (err) fail(err);
          else {
            settled = true;
            rs.on('end', () => zip.close());
            rs.on('error', () => zip.close());
            resolve({
              name: entryPath,
              size: entry.uncompressedSize || 0,
              stream: rs,
              meta: { size: entry.uncompressedSize || 0, compressedSize: entry.compressedSize || 0 }
            });
          }
        });
      } else {
        zip.readEntry();
      }
    });
    zip.on('end', () => fail(new Error(`Archive entry not found: ${entryPath}`)));
    zip.on('error', fail);
  });
}

function sourceStream(source) {
  const rs = source.buffer ? stream.Readable.from(source.buffer) : fs.createReadStream(source.filePath);
  return extname(source.name) === '.tar.gz' || extname(source.name) === '.tgz' ? rs.pipe(zlib.createGunzip()) : rs;
}

async function listTarEntries(source, prefix) {
  const extract = tar.extract();
  const out = [];
  return new Promise((resolve, reject) => {
    let settled = false;
    const done = value => {
      if (!settled) {
        settled = true;
        resolve(value);
      }
    };
    const fail = err => {
      if (!settled) {
        settled = true;
        reject(err);
      }
    };
    extract.on('entry', (header, entryStream, next) => {
      if (out.length < MAX_ARCHIVE_ENTRIES) {
        const dir = header.type === 'directory';
        const entryPath = normalizeArchiveMemberPath(header.name, dir);
        out.push({
          path: entryPath,
          name: archiveDisplayName(entryPath),
          dir,
          size: header.size || 0,
          compressedSize: null,
          mtime: header.mtime ? header.mtime.getTime() : null
        });
      }
      entryStream.resume();
      entryStream.on('end', next);
    });
    extract.on('finish', () => done(immediateArchiveEntries(out, prefix)));
    extract.on('error', fail);
    sourceStream(source).on('error', fail).pipe(extract);
  });
}

async function openTarEntry(source, entryPath) {
  const extract = tar.extract();
  return new Promise((resolve, reject) => {
    let found = false;
    extract.on('entry', (header, entryStream, next) => {
      if (normalizeArchiveMemberPath(header.name, header.type === 'directory').replace(/\/$/, '') === entryPath) {
        found = true;
        const pass = new stream.PassThrough();
        entryStream.pipe(pass);
        entryStream.on('end', next);
        resolve({ name: entryPath, size: header.size || 0, stream: pass, meta: { size: header.size || 0 } });
      } else {
        entryStream.resume();
        entryStream.on('end', next);
      }
    });
    extract.on('finish', () => {
      if (!found) reject(new Error(`Archive entry not found: ${entryPath}`));
    });
    extract.on('error', reject);
    sourceStream(source).on('error', reject).pipe(extract);
  });
}

function zipEntryBuffer(bytes, name) {
  return new Promise((resolve, reject) => {
    yauzl.fromBuffer(Buffer.from(bytes), { lazyEntries: true }, (err, zip) => {
      if (err) { reject(err); return; }
      let settled = false;
      const done = value => {
        if (!settled) {
          settled = true;
          zip.close();
          resolve(value);
        }
      };
      const fail = readErr => {
        if (!settled) {
          settled = true;
          zip.close();
          reject(readErr);
        }
      };
      zip.readEntry();
      zip.on('entry', entry => {
        if (entry.fileName === name) {
          zip.openReadStream(entry, (streamErr, rs) => {
            if (streamErr) fail(streamErr);
            else readAll(rs, entry.uncompressedSize).then(done, fail);
          });
        } else {
          zip.readEntry();
        }
      });
      zip.on('end', () => fail(new Error(`ZIP entry not found: ${name}`)));
      zip.on('error', fail);
    });
  });
}

function readAll(rs, expectedSize) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    if (expectedSize && expectedSize > MAX_ENTRY_BYTES) {
      reject(new Error(`Content is too large (${formatBytes(expectedSize)})`));
      return;
    }
    rs.on('data', chunk => {
      total += chunk.length;
      if (total > MAX_ENTRY_BYTES) {
        rs.destroy(new Error(`Content is too large (${formatBytes(total)})`));
      } else {
        chunks.push(chunk);
      }
    });
    rs.on('end', () => resolve(Buffer.concat(chunks)));
    rs.on('error', reject);
  });
}

// ---- bounded-memory document streaming (session pull-cursor) ----------------------------------------
// A session opens a paused read of the file; the renderer pulls batches one at a time (credit-1 = backpressure:
// main only reads ahead one batch, the renderer holds at most two). The stream stays OPEN and PAUSED between
// pulls (O(N) total, unlike streamLogPage which re-scans per page). mode 'lines' → readline line batches;
// mode 'bytes' → decoded utf-8 text chunks (for the markdown streaming tokenizer).
const streams = new Map();                 // sessionId -> session
let streamSeq = 0;
const STREAM_BATCH_LINES = 1000;
const STREAM_BATCH_BYTES = 65536;
const STREAM_IDLE_MS = 60000;

function bufBytes(sess) { let n = 0; for (const s of sess.buf) n += s.length; return n; }

function streamOpen(req) {
  const filePath = req.path;
  const mode = req.mode === 'bytes' ? 'bytes' : 'lines';
  let size = 0;
  try { size = fs.statSync(filePath).size; } catch (e) { size = 0; }
  const raw = fs.createReadStream(filePath);
  const decoded = gzipLogName(filePath) ? raw.pipe(zlib.createGunzip()) : raw;
  const sessionId = 'st' + (++streamSeq);
  const sess = { raw, rl: null, decoded, buf: [], done: false, waiter: null,
                 bytesRead: 0, size, mode, seq: 0, lastPull: Date.now() };
  const wake = () => { const w = sess.waiter; if (w) { sess.waiter = null; w(); } };
  raw.on('error', () => { sess.done = true; wake(); });
  if (mode === 'lines') {
    const rl = readline.createInterface({ input: decoded, crlfDelay: Infinity });
    sess.rl = rl;
    rl.on('line', (line) => {
      sess.buf.push(line);
      sess.bytesRead += Buffer.byteLength(line, 'utf8') + 1;   // approx raw progress (fine for a progress bar)
      if (sess.buf.length >= STREAM_BATCH_LINES) { rl.pause(); wake(); }
    });
    rl.on('close', () => { sess.done = true; wake(); });
  } else {
    decoded.on('data', (chunk) => {
      const text = chunk.toString('utf8');
      sess.buf.push(text);
      sess.bytesRead += chunk.length;
      if (bufBytes(sess) >= STREAM_BATCH_BYTES) { decoded.pause(); wake(); }
    });
    decoded.on('end', () => { sess.done = true; wake(); });
  }
  streams.set(sessionId, sess);
  return { sessionId, size, mode };
}

async function streamPull(req) {
  const sess = streams.get(req.sessionId);
  if (!sess) return { seq: -1, done: true, error: 'no such stream session' };
  sess.lastPull = Date.now();
  if (sess.buf.length === 0 && !sess.done) {
    (sess.rl || sess.decoded).resume();                        // read one more batch, then the source re-pauses
    await new Promise((ok) => { sess.waiter = ok; });
  }
  const taken = sess.buf;
  sess.buf = [];
  const out = { seq: (sess.seq = sess.seq + 1),
                done: sess.done && sess.buf.length === 0,
                progress: sess.size > 0 ? Math.min(1, sess.bytesRead / sess.size) : (sess.done ? 1 : 0) };
  if (sess.mode === 'lines') out.lines = taken; else out.text = taken.join('');
  return out;
}

function streamClose(req) {
  const sess = streams.get(req && req.sessionId);
  if (sess) {
    try { if (sess.rl) sess.rl.close(); } catch (e) { /* already closed */ }
    try { sess.raw.destroy(); } catch (e) { /* already destroyed */ }
    streams.delete(req.sessionId);
  }
  return { ok: true };
}

// fd-leak guard: reap sessions the renderer stopped pulling (e.g. it crashed). unref so it never holds the app open.
setInterval(() => {
  const now = Date.now();
  for (const [id, s] of streams) {
    if (now - s.lastPull > STREAM_IDLE_MS) { streamClose({ sessionId: id }); }
  }
}, 30000).unref();

function streamCount() { return streams.size; }   // for the electron smoke's fd-leak assertion

module.exports = {
  archiveUri,
  classifyName,
  contentPage,
  displayArchiveUri,
  isArchiveUri,
  openUri,
  parseArchiveUri,
  streamOpen,
  streamPull,
  streamClose,
  streamCount
};
