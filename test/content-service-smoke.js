'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const tar = require('tar-stream');
const zlib = require('zlib');

const content = require('../src/vinary/main/content_service.js');

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buf) {
  let crc = 0xffffffff;
  for (const b of buf) crc = crcTable[(crc ^ b) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function zipStoreBuffer(entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name);
    const body = Buffer.isBuffer(entry.body) ? entry.body : Buffer.from(entry.body);
    const crc = crc32(body);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(body.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, name, body);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(body.length, 20);
    central.writeUInt32LE(body.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);

    offset += local.length + name.length + body.length;
  }

  const centralSize = centralParts.reduce((n, part) => n + part.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat(localParts.concat(centralParts, end));
}

function tarBuffer(entries) {
  return new Promise((resolve, reject) => {
    const pack = tar.pack();
    const chunks = [];
    pack.on('data', chunk => chunks.push(chunk));
    pack.on('error', reject);
    pack.on('end', () => resolve(Buffer.concat(chunks)));

    const write = index => {
      if (index >= entries.length) {
        pack.finalize();
        return;
      }
      const entry = entries[index];
      pack.entry({ name: entry.name }, entry.body, err => {
        if (err) reject(err);
        else write(index + 1);
      });
    };
    write(0);
  });
}

async function main() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-content-'));
  try {
    const logPath = path.join(tmp, 'service-output');
    const logLine = '2026-06-29T10:00:00Z INFO worker finished batch ';
    fs.writeFileSync(logPath, Array.from({ length: 2200 }, (_, i) => logLine + i + ' ' + 'x'.repeat(2600)).join('\n'));
    const log = await content.openUri(logPath);
    assert.strictEqual(log.kind, 'log');
    assert.strictEqual(log.paged, true);
    assert.strictEqual(log.page.lines.length, 2000);
    assert.strictEqual(log.page.hasNext, true);
    const logPage = await content.contentPage({ path: logPath, kind: 'log', stamp: log.stamp, page: 1, meta: log.meta });
    assert.strictEqual(logPage.index, 1);
    assert.ok(logPage.lines.length > 0);

    // Org (.org). content_service is the CLJS file-kind classifier's JavaScript twin; it once had no org arm,
    // so `.org` classified as 'text' and vv-cli / vv-tui rendered Org as highlighted source instead of
    // rendering it through uniorg. It also let an Org table trip the delimited-CSV content sniff.
    assert.strictEqual(content.classifyName('notes.org'), 'org', '.org classifies as its own kind');
    assert.strictEqual(content.classifyName('NOTES.ORG'), 'org', '.org classification is case-insensitive');
    const orgPath = path.join(tmp, 'tables.org');
    fs.writeFileSync(orgPath, '#+TITLE: Tables\n* Heading\n| a | b |\n|---+---|\n| 1 | 2 |\n');
    const org = await content.openUri(orgPath);
    assert.strictEqual(org.kind, 'org', 'an Org file with a pipe table is NOT sniffed as a delimited table');
    assert.ok(/#\+TITLE/.test(org.text), 'org payload carries the raw text for the uniorg pipeline');
    assert.strictEqual(org.sourceable, true, 'org has a source view');
    assert.ok(org.meta && typeof org.meta.size === 'number', 'org payload carries :size (the streaming gate)');

    // GNU Makefile. Its `target:` lines and tab-indented recipes used to trip the delimited-CSV content sniff, so
    // an extensionless Makefile (classified 'text') previewed as a table. well-known-kind now classifies it 'source'
    // and openLocal short-circuits the sniff — the exact bug fix.
    assert.strictEqual(content.classifyName('/proj/Makefile'), 'source', 'Makefile classifies as source');
    assert.strictEqual(content.classifyName('/proj/build.mk'), 'source', '*.mk classifies as source');
    const mkPath = path.join(tmp, 'Makefile');
    fs.writeFileSync(mkPath, '.PHONY: all clean\n\nall: build\n\tcargo build --release\n\nclean:\n\trm -rf target\n');
    const mk = await content.openUri(mkPath);
    assert.strictEqual(mk.kind, 'source', 'a Makefile is NOT sniffed as a delimited table');
    assert.ok(/cargo build/.test(mk.text), 'the Makefile payload carries its raw text for the source view');

    // Standard repo files: LICENSE → plain text (not delimited), .gitignore → source, git config → source.
    assert.strictEqual(content.classifyName('/proj/LICENSE'), 'text', 'LICENSE is plain text');
    assert.strictEqual(content.classifyName('/proj/.gitignore'), 'source', '.gitignore is source');
    assert.strictEqual(content.classifyName('/home/me/repo/.git/config'), 'source', 'a repo .git/config is source');
    const licPath = path.join(tmp, 'LICENSE');
    fs.writeFileSync(licPath, 'MIT License\n\nCopyright (c) 2026\n\nPermission is hereby granted, free of charge...\n');
    const lic = await content.openUri(licPath);
    assert.strictEqual(lic.kind, 'text', 'a LICENSE opens as plain text');

    // Plain text is NEVER delimiter-sniffed (the delimiter-sniff arms are disabled): prose whose lines share
    // a stable comma/pipe count used to flip the whole document to a column-aligned table. It must stay
    // literal text — sourceable, with meta.size intact (the streaming gate).
    const proseCsvPath = path.join(tmp, 'notes.txt');
    fs.writeFileSync(proseCsvPath, Array.from({ length: 12 }, (_, i) => `item ${i}, first thing, second thing`).join('\n'));
    const proseCsv = await content.openUri(proseCsvPath);
    assert.strictEqual(proseCsv.kind, 'text', 'comma-stable prose in a .txt stays plain text (not a table)');
    assert.strictEqual(proseCsv.sourceable, true, 'plain text keeps its source view');
    assert.ok(proseCsv.meta && typeof proseCsv.meta.size === 'number', 'plain text keeps :size (the streaming gate)');
    const pipeProsePath = path.join(tmp, 'layout-notes');
    fs.writeFileSync(pipeProsePath, Array.from({ length: 8 }, (_, i) => `cell ${i} | detail | remark`).join('\n'));
    const pipeProse = await content.openUri(pipeProsePath);
    assert.strictEqual(pipeProse.kind, 'text', 'pipe-stable extensionless prose stays plain text (not a table)');

    // …while an explicitly NAMED delimited file still parses as a table (classifyName, not the sniff).
    const smallCsvPath = path.join(tmp, 'people.csv');
    fs.writeFileSync(smallCsvPath, 'name,age\nAlice,30\nBob,25\n');
    const smallCsv = await content.openUri(smallCsvPath);
    assert.strictEqual(smallCsv.kind, 'table', 'a .csv still opens as a delimited table');
    assert.ok(smallCsv.sheets && smallCsv.sheets[0].rows.length >= 3, 'the .csv payload carries parsed sheet rows');

    // An EXPLICIT kind (openUri's second argument — `vv -t …`, Settings ▸ File Type) is authoritative:
    // no classifyName, no sniffing. The same .txt re-opens as a diff, as literal text, or as a table.
    const typedPath = path.join(tmp, 'typed.txt');
    fs.writeFileSync(typedPath, 'diff --git a/y b/y\n--- a/y\n+++ b/y\n@@ -1 +1 @@\n-a\n+b\n');
    const typedDiff = await content.openUri(typedPath, 'diff');
    assert.strictEqual(typedDiff.kind, 'diff', 'an explicit diff type forces the diff kind on a .txt');
    assert.ok(/\+b/.test(typedDiff.text), 'the explicit-diff payload carries the raw text');
    const typedText = await content.openUri(proseCsvPath, 'text');
    assert.strictEqual(typedText.kind, 'text', 'an explicit text type is literal by declaration');
    assert.strictEqual(typedText.sourceable, true);
    const typedTable = await content.openUri(proseCsvPath, 'table', { delimiter: ',' });
    assert.strictEqual(typedTable.kind, 'table', 'an explicit table type parses any file as delimited text');
    assert.ok(typedTable.sheets && typedTable.sheets[0].rows.length >= 3, 'explicit-table rows parsed');
    assert.strictEqual(typedTable.meta.delimiter, ',', 'the explicit delimiter rides meta for paging');
    const tabbedPath = path.join(tmp, 'tabbed');
    fs.writeFileSync(tabbedPath, 'a\tb\n1\t2\n3\t4\n');
    const typedTsv = await content.openUri(tabbedPath, 'table', { delimiter: '\t' });
    assert.strictEqual(typedTsv.kind, 'table');
    assert.deepStrictEqual(typedTsv.sheets[0].rows[0], ['a', 'b'], 'tab delimiter honored via meta.delimiter');
    // an explicit markdown type on an extensionless file returns kind markdown with the raw text
    const mdish = path.join(tmp, 'readme-ish');
    fs.writeFileSync(mdish, '# Title\n\nBody with **bold**.\n');
    const typedMd = await content.openUri(mdish, 'markdown');
    assert.strictEqual(typedMd.kind, 'markdown', 'an explicit markdown type forces the markdown kind');
    assert.ok(typedMd.meta && typeof typedMd.meta.size === 'number', 'explicit kinds keep :size (streaming gate)');

    // Diffs (.diff/.patch) classify as their own kind and carry raw text for the diff IR front-end.
    assert.strictEqual(content.classifyName('change.diff'), 'diff', '.diff classifies as diff');
    assert.strictEqual(content.classifyName('fix.patch'), 'diff', '.patch classifies as diff');
    const diffPath = path.join(tmp, 'change.diff');
    fs.writeFileSync(diffPath, 'diff --git a/x.txt b/x.txt\n--- a/x.txt\n+++ b/x.txt\n@@ -1 +1 @@\n-old\n+new\n');
    const df = await content.openUri(diffPath);
    assert.strictEqual(df.kind, 'diff', 'a diff opens as kind diff (not sniffed as a table)');
    assert.ok(/\+new/.test(df.text), 'the diff payload carries its raw text for the diff front-end');

    const gzLogPath = path.join(tmp, 'app.log.gz');
    fs.writeFileSync(gzLogPath, zlib.gzipSync('2026-06-29T10:00:00Z WARN compressed\n'.repeat(3)));
    const gzLog = await content.openUri(gzLogPath);
    assert.strictEqual(gzLog.kind, 'log');
    assert.strictEqual(gzLog.paged, true);
    assert.ok(gzLog.page.lines.some(line => line.includes('compressed')));

    const accessPath = path.join(tmp, 'access-output');
    fs.writeFileSync(accessPath, Array.from({ length: 5 }, (_, i) =>
      `127.0.0.${i} - - [29/Jun/2026:10:00:0${i} -0400] "GET /index.html HTTP/1.1" 200 123`).join('\n'));
    const accessLog = await content.openUri(accessPath);
    assert.strictEqual(accessLog.kind, 'log');
    assert.ok(accessLog.text.includes('GET /index.html'));

    const odfPath = path.join(tmp, 'formula.odf');
    fs.writeFileSync(odfPath, zipStoreBuffer([
      {
        name: 'content.xml',
        body: [
          '<?xml version="1.0" encoding="UTF-8"?>',
          '<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:math="http://www.w3.org/1998/Math/MathML">',
          '<office:body><office:formula><math:math><math:mi>E</math:mi><math:mo>=</math:mo><math:mi>m</math:mi><math:msup><math:mi>c</math:mi><math:mn>2</math:mn></math:msup></math:math></office:formula></office:body>',
          '</office:document-content>'
        ].join('')
      }
    ]));
    const odf = await content.openUri(odfPath);
    assert.strictEqual(odf.kind, 'office');
    assert.ok(odf.text.includes('E'));
    assert.ok(odf.html.includes('vv-plain'));

    const csvPath = path.join(tmp, 'large.csv');
    fs.writeFileSync(csvPath, Array.from({ length: 550 }, (_, i) => `${i},${'a'.repeat(4000)},${i + 1}`).join('\n'));
    const csv = await content.openUri(csvPath);
    assert.strictEqual(csv.kind, 'table');
    assert.strictEqual(csv.paged, true);
    assert.strictEqual(csv.page.rows.length, 500);
    assert.strictEqual(csv.page.hasNext, true);
    const csvPage = await content.contentPage({ path: csvPath, kind: 'table', stamp: csv.stamp, page: 1, meta: csv.meta });
    assert.strictEqual(csvPage.index, 1);
    assert.strictEqual(csvPage.rows.length, 50);

    const nested = await tarBuffer([
      { name: 'data.csv', body: Buffer.from('a,b\n1,2\n') }
    ]);
    const bundlePath = path.join(tmp, 'bundle.tar');
    const bundle = await tarBuffer([
      { name: 'logs/app.log', body: Buffer.from('2026-06-29T10:00:00Z ERROR failed\n') },
      { name: 'notes.org', body: Buffer.from('#+TITLE: Archived\n* Head\n| a | b |\n|---+---|\n| 1 | 2 |\n') },
      { name: 'notes.txt', body: Buffer.from(Array.from({ length: 10 }, (_, i) => `item ${i}, first, second`).join('\n')) },
      { name: 'nested.tar', body: nested }
    ]);
    fs.writeFileSync(bundlePath, bundle);

    const listing = await content.openUri(bundlePath);
    assert.strictEqual(listing.kind, 'archive');
    assert.ok(listing.entries.some(entry => entry.name === 'logs' && entry['dir?']));
    assert.ok(listing.entries.some(entry => entry.name === 'nested.tar' && entry['dir?']));

    const folder = await content.openUri(content.archiveUri(bundlePath, ['logs/']));
    assert.deepStrictEqual(folder.entries.map(entry => entry.name), ['app.log']);

    const archivedLog = await content.openUri(content.archiveUri(bundlePath, ['logs/app.log']));
    assert.strictEqual(archivedLog.kind, 'log');
    assert.ok(archivedLog.text.includes('ERROR failed'));

    // an .org nested in an archive reaches the renderer as kind "org" (→ :org/render), exactly like a nested
    // .md reaches it as "markdown" — bufferToPayload classifies archive entries with the same classifyName that
    // once had no org arm. Its pipe table must not be sniffed as a delimited CSV.
    const archivedOrg = await content.openUri(content.archiveUri(bundlePath, ['notes.org']));
    assert.strictEqual(archivedOrg.kind, 'org', 'an .org inside an archive is kind "org", not "text"/"table"');
    assert.ok(archivedOrg.text.includes('#+TITLE'), 'the nested org entry carries its raw text for uniorg');
    assert.strictEqual(archivedOrg.sourceable, true, 'a nested org entry has a source view');

    // bufferToPayload's disabled delimiter arm: a comma-stable .txt archive member stays literal plain text.
    const archivedTxt = await content.openUri(content.archiveUri(bundlePath, ['notes.txt']));
    assert.strictEqual(archivedTxt.kind, 'text', 'comma-stable prose inside an archive stays plain text (not a table)');
    assert.strictEqual(archivedTxt.sourceable, true, 'a nested text entry keeps its source view');

    const nestedListing = await content.openUri(content.archiveUri(bundlePath, ['nested.tar']));
    assert.strictEqual(nestedListing.kind, 'archive');
    assert.deepStrictEqual(nestedListing.entries.map(entry => entry.name), ['data.csv']);

    // A directory must never be parsed as a file. An extensionless directory name (e.g. "publication")
    // classifies as "text", which previously routed it into the parser → fs.readSync on a dir fd → EISDIR.
    const dirPath = path.join(tmp, 'publication');
    fs.mkdirSync(dirPath);
    fs.writeFileSync(path.join(dirPath, 'README'), 'hello');
    await assert.rejects(
      () => content.openUri(dirPath),
      err => {
        const msg = String((err && err.message) || err);
        assert.ok(!/EISDIR/.test(msg), 'directory open must not surface a raw EISDIR: ' + msg);
        assert.ok(/directory/i.test(msg), 'directory open should reject with a clear directory message: ' + msg);
        return true;
      });

    await testRemote();
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

// Remote SSH reads through openRemoteUri, against the hermetic in-process SFTP fixture (no network).
async function testRemote() {
  const { startSftpServer } = require('./fixtures/ssh-server.js');
  const transport = require('../src/vinary/main/ssh_transport.js');
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-cshome-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });

  // a small in-memory tar for the remote-archive test
  const pack = tar.pack();
  const chunks = [];
  pack.on('data', c => chunks.push(c));
  const packed = new Promise(res => pack.on('end', res));
  pack.entry({ name: 'inside.txt' }, 'archived content');
  pack.entry({ name: 'dir/nested.md' }, '# Nested');
  pack.finalize();
  await packed;

  const srv = await startSftpServer({
    password: 'pw',
    files: {
      'readme.md': '# Remote Doc\nhello world',
      'sub/a.txt': 'alpha',
      'pic.svg': '<svg xmlns="http://www.w3.org/2000/svg"><rect width="1" height="1"/></svg>',
      'data.csv': 'name,age\nAlice,30\nBob,25\n',
      'bundle.tar': Buffer.concat(chunks),
      'paper.tex': '\\documentclass{article}\\begin{document}Hi\\end{document}',
      // a VALID PDF (remote PDF reads are verified — ADR-0046 §Torn reads), larger than the 1088-byte tail probe so its
      // body READ (offset 0) is distinguishable from the tail READ
      'paper.pdf': require('./fixtures/pdf-gen.js').makeDeckPdf({ pages: 2, padBytes: 70000 }),
      'src/greet.js': 'function greet(){ return "hello"; }',
      'src/a file#日.js': 'export const special = true;',
      'doc.md': '# Doc\n![pic](./assets/pic.png)',
      'assets/pic.png': Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      'app.log': Array.from({ length: 20000 }, (_, i) => `log line ${i}`).join('\n') + '\n',
    },
  });
  let srvClosed = false;
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => 'pw', onError: () => {},
  });
  try {
    const md = await content.openRemoteUri(srv.url('/readme.md'), 'markdown');
    assert.strictEqual(md.kind, 'markdown', 'remote .md → markdown');
    assert.ok(/hello world/.test(md.text), 'remote markdown carries its text');
    assert.ok(md.meta && typeof md.meta.size === 'number', 'remote payload carries meta.size (the streaming gate)');

    const dir = await content.openRemoteUri(srv.url('/'), 'text');
    assert.strictEqual(dir.kind, 'directory', 'remote directory → listing');
    assert.ok(dir.entries.some(e => e.name === 'sub' && e['dir?']), 'listing marks subdirectories');
    assert.ok(dir.entries.every(e => /^ssh:\/\//.test(e.path)), 'entries carry child ssh:// URIs');

    const img = await content.openRemoteUri(srv.url('/pic.svg'), 'image');
    assert.ok(img.kind === 'image' && img.dataUrl.startsWith('data:image/svg+xml;base64,'), 'remote image → data URL (file:// cannot reach a remote host)');

    const csv = await content.openRemoteUri(srv.url('/data.csv'), 'table');
    assert.ok(csv.kind === 'table' && csv.sheets[0].rows.length >= 3, 'remote csv → parsed table');

    const arc = await content.openRemoteUri(srv.url('/bundle.tar'), 'archive');
    assert.ok(arc.kind === 'archive' && arc.entries.some(e => e.name === 'inside.txt'), 'remote archive browses (root read as a buffer)');

    const src = await content.openRemoteUri(srv.url('/sub/a.txt'), 'source');
    assert.strictEqual(src.kind, 'source', 'the grammar-aware CLJS kind is honored (source, not sniffed text)');

    // collocated document GROUP over remote (both directions): each member advertises the full :siblings group
    const texWithPdf = await content.openRemoteUri(srv.url('/paper.tex'), 'latex');
    assert.ok(Array.isArray(texWithPdf.siblings)
              && texWithPdf.siblings.some(s => s.kind === 'pdf' && s.path.endsWith('/paper.pdf')),
              'a remote .tex advertises its collocated .pdf in its siblings group');
    const pdfWithSrc = await content.openRemoteUri(srv.url('/paper.pdf'), 'pdf');
    assert.ok(Array.isArray(pdfWithSrc.siblings)
              && pdfWithSrc.siblings.some(s => s.kind === 'latex' && s.path.endsWith('/paper.tex')),
              'a remote .pdf advertises its collocated source in its siblings group');
    assert.ok(!pdfWithSrc.incomplete && pdfWithSrc.bytes.length > 70000, 'a complete remote PDF is delivered whole, not flagged');

    // E2 (H3): the group's candidates are stat'ed CONCURRENTLY, overlapped with the read. Every sibling stat reply is
    // held 400 ms, which makes both properties observable without timing anything: a sequential discovery never has
    // more than one stat in flight, and one run after the read has none left when the body is read. (The transport
    // stats with LSTAT. 400 ms, because this fixture leaves Nagle on — ~40 ms per reply on loopback, H0 — and the body
    // READ comes three replies after the stats: the OPEN, the tail READ and its own.)
    const statOp = (op) => op === 'STAT' || op === 'LSTAT';
    for (const [file, kind] of [['/paper.pdf', 'pdf'], ['/paper.tex', 'latex']]) {
      const sibling = (p) => p !== file && /^\/paper\.[a-z]+$/.test(p);
      let atBodyRead = null;
      let selfStats = 0;
      srv.setDelay((req) => (statOp(req.op) && sibling(req.path) ? 400 : 0));
      srv.onRequest((req) => {
        if (statOp(req.op) && req.path === file) selfStats += 1;
        if (req.op === 'READ' && req.path === file && req.offset === 0 && atBodyRead === null) atBodyRead = srv.stats.inFlightStats;
      });
      srv.resetStats();
      try {
        const opened = await content.openRemoteUri(srv.url(file), kind);
        // ≥ 11, not = 11: the PDF's own FSTAT (not held) may land while the 11 held STATs are in flight
        assert.ok(srv.stats.maxInFlightStats >= 11,
          `${file}: all 11 other group candidates are stat'ed at once (max in flight ${srv.stats.maxInFlightStats})`);
        assert.ok(atBodyRead >= 1, `${file}: …while the file itself is read (${atBodyRead} still in flight at its first body READ)`);
        assert.strictEqual(selfStats, 1, `${file}: the opened file is stat'ed once — its stat is reused for the group`);
        assert.deepStrictEqual(opened.siblings.map((sib) => sib.path.slice(sib.path.lastIndexOf('/') + 1)),
          file === '/paper.pdf' ? ['paper.pdf', 'paper.tex'] : ['paper.tex', 'paper.pdf'],
          `${file}: the group — the opened file first, then its same-stem members`);
      } finally {
        srv.setDelay(null);
        srv.onRequest(null);
      }
    }

    // remote diff on-disk enrichment (resolve a referenced file over SFTP, walking ancestors)
    const diffSrcs = await content.loadRemoteDiffSources(srv.url('/change.diff'), ['src/greet.js']);
    assert.ok(/hello/.test(diffSrcs['src/greet.js'] || ''), 'remote diff-source enrichment resolves a referenced file over SFTP');
    const absoluteDiffSrc = await content.loadRemoteDiffSources(srv.url('/change.diff'), ['/src/greet.js']);
    assert.ok(/hello/.test(absoluteDiffSrc['/src/greet.js'] || ''),
      'an absolute path in a plain remote unified diff resolves directly');
    const diffPaths = await content.loadRemoteDiffSources(
      srv.url('/change.diff'), ['src/greet.js', 'missing.js'], { includePaths: true, includeContent: false }
    );
    assert.strictEqual(diffPaths['src/greet.js'].path, srv.url('/src/greet.js'),
      'structured remote diff resolution returns the navigable SSH URI');
    assert.strictEqual(Object.prototype.hasOwnProperty.call(diffPaths['src/greet.js'], 'content'), false,
      'path-only diff resolution does not eagerly read source content');
    assert.strictEqual(diffPaths['missing.js'], undefined, 'unresolved diff paths are omitted');
    const diffEntries = await content.loadRemoteDiffSources(
      srv.url('/change.diff'), ['src/greet.js'], { includePaths: true, includeContent: true }
    );
    assert.ok(/hello/.test(diffEntries['src/greet.js'].content || ''),
      'structured Split enrichment returns content alongside the resolved path');
    const specialUri = new URL(srv.url('/'));
    specialUri.pathname = '/src/a file#日.js';
    const specialEntries = await content.loadRemoteDiffSources(
      srv.url('/change.diff'), ['src/a file#日.js'], { includePaths: true, includeContent: true }
    );
    assert.strictEqual(specialEntries['src/a file#日.js'].path, specialUri.href,
      'remote diff resolution percent-encodes filename syntax in the navigable SSH URI');
    assert.strictEqual(specialEntries['src/a file#日.js'].content, 'export const special = true;',
      'the encoded remote target still addresses the real SFTP file');

    // remote relative asset → data URL (neither the sandboxed renderer nor file:// can reach the host)
    const asset = await content.loadRemoteAsset('./assets/pic.png', srv.url('/doc.md'));
    assert.ok(asset.startsWith('data:image/png;base64,'), "a remote doc's relative image resolves to a data URL");

    // remote streaming (transport engine): open → pull batches → done, leak-free
    const so = await content.streamOpen({ path: srv.url('/app.log'), mode: 'lines' });
    assert.ok(so.sessionId && so.size > 0, 'remote streamOpen returns a session + size');
    let total = 0, seq = 0, done = false, maxBatch = 0;
    while (!done) {
      const b = await content.streamPull({ sessionId: so.sessionId });
      assert.ok(b.seq > seq, 'monotonic batch seq'); seq = b.seq;
      const nlines = (b.lines || []).length;
      maxBatch = Math.max(maxBatch, nlines);
      total += nlines;
      done = b.done;
    }
    assert.strictEqual(total, 20000, 'streamed every remote log line');
    assert.ok(maxBatch < 20000, 'streaming is incremental — no single pull returns the whole file (bounded memory)');
    content.streamClose({ sessionId: so.sessionId });
    assert.strictEqual(content.streamCount(), 0, 'no leaked remote stream session');

    // remote paged log nav (page 1 = lines 2000.., hasPrev)
    const pg = await content.contentPage({ path: srv.url('/app.log'), kind: 'log', page: 1, stamp: Date.now() });
    assert.ok(pg.index === 1 && pg.hasPrev && pg.lines.length > 0, 'remote paged log nav (page 1)');

    // mid-stream drop: abruptly reset the connection; the next pull ends the stream flagged partial (never a hang,
    // and never a silent EOF that would truncate content undetected)
    const sd = await content.streamOpen({ path: srv.url('/app.log'), mode: 'lines' });
    await content.streamPull({ sessionId: sd.sessionId });
    srv.destroyConnections();
    let ended = false, sawPartial = false;
    for (let i = 0; i < 100 && !ended; i++) {
      const b = await content.streamPull({ sessionId: sd.sessionId });
      if (b.done) { ended = true; sawPartial = b.partial === true; }
    }
    assert.ok(ended, 'a dropped remote stream terminates (no hang)');
    assert.ok(sawPartial, 'a dropped remote stream is flagged partial (truncation is visible, not a silent EOF)');
    content.streamClose({ sessionId: sd.sessionId });

    console.log('[ok] remote SSH: openRemoteUri + collocated group siblings + diff/asset + streaming/paging + mid-stream-drop partial');
  } finally {
    transport.closeAll();
    if (!srvClosed) await srv.close();
    fs.rmSync(home, { recursive: true, force: true });
  }
}

// ADR-0046 D5 — is a byte buffer a COMPLETE PDF, or one caught mid-write? (src/vinary/main/pdf_integrity.js)
function testPdfIntegrity() {
  const { verdict, tailVerdict, TAIL_WINDOW } = require('../src/vinary/main/pdf_integrity.js');
  const { makeDeckPdf, truncatePdf } = require('./fixtures/pdf-gen.js');
  const ok = (buf) => verdict(buf).ok;
  const reason = (buf) => verdict(buf).reason;
  const tail = (buf) => tailVerdict(buf.subarray(Math.max(0, buf.length - TAIL_WINDOW - 64)), buf.length);

  // complete files, every cross-reference form
  const classic = makeDeckPdf({ pages: 3 });
  const xstream = makeDeckPdf({ pages: 3, xrefStream: true });
  const incremental = makeDeckPdf({ pages: 3, incremental: true });
  const real = fs.readFileSync(path.join(__dirname, 'fixtures', 'beamer-notes-right.pdf'));   // LuaLaTeX output
  for (const [name, buf] of [['classic xref', classic], ['xref stream', xstream], ['incremental update', incremental], ['real LuaLaTeX', real]]) {
    assert.deepStrictEqual(verdict(buf), { ok: true }, `${name}: a complete PDF`);
    assert.strictEqual(tail(buf).ok, true, `${name}: its tail alone already says so`);
  }
  assert.strictEqual((incremental.toString('latin1').match(/%%EOF/g) || []).length, 2, '(the incremental fixture really has two %%EOF markers)');

  // torn copies — what a read racing pdflatex sees
  for (const [name, buf] of [['classic', classic], ['xref stream', xstream], ['real LuaLaTeX', real]]) {
    for (const frac of [0.1, 0.5, 0.9, 0.99]) {
      const torn = truncatePdf(buf, frac);
      assert.strictEqual(ok(torn), false, `${name} cut at ${frac}: incomplete (${reason(torn)})`);
      assert.strictEqual(tail(torn).ok, false, `${name} cut at ${frac}: the tail alone says so`);
    }
    const lastByteGone = buf.subarray(0, buf.lastIndexOf('%%EOF') + 4);       // "%%EO" — one byte short
    assert.strictEqual(ok(lastByteGone), false, `${name}: missing even the marker's last byte is incomplete`);
  }
  assert.strictEqual(ok(Buffer.alloc(0)), false, 'an empty read');
  assert.strictEqual(reason(Buffer.from('%%EOF\n')), 'no-header', 'no header');

  // trailing junk after %%EOF: tolerated within 1 KiB (writers pad), not beyond (that is no longer the file's end)
  assert.strictEqual(ok(Buffer.concat([classic, Buffer.alloc(500, 0x20)])), true, '500 bytes of padding after %%EOF');
  assert.strictEqual(reason(Buffer.concat([classic, Buffer.alloc(2000, 0x41)])), 'no-eof', '2000 bytes after %%EOF');

  // an incremental update torn shortly after the PREVIOUS revision's %%EOF is that revision plus junk — a complete
  // (older) PDF, which is what a reader would open; torn far beyond it, it is unfinished
  const firstEof = incremental.indexOf('%%EOF') + 6;
  assert.strictEqual(ok(Buffer.concat([incremental.subarray(0, firstEof), Buffer.from('3 0 obj\n<< /Partial')])), true,
    'an incremental update torn just after the previous revision: that revision');
  assert.strictEqual(reason(Buffer.concat([incremental.subarray(0, firstEof), Buffer.alloc(3000, 0x42)])), 'no-eof',
    'an update torn 3 KB into a large section: unfinished');

  // the pointer: out of range, not a number, or in range but not at a cross-reference section
  const repoint = (buf, to) => {
    const s = buf.toString('latin1');
    const i = s.lastIndexOf('startxref');
    return Buffer.from(s.slice(0, i) + s.slice(i).replace(/startxref\s+\d+/, `startxref\n${to}`), 'latin1');
  };
  assert.strictEqual(reason(repoint(classic, classic.length + 100)), 'bad-startxref', 'a pointer past the end of the file');
  assert.strictEqual(tail(repoint(classic, classic.length + 100)).reason, 'bad-startxref', '(the tail alone catches it)');
  assert.strictEqual(reason(repoint(classic, 'xyz')), 'bad-startxref', 'a pointer that is not a number');
  assert.strictEqual(reason(repoint(classic, 3)), 'bad-xref', 'a pointer into the middle of the header');
  assert.strictEqual(reason(Buffer.from(classic.toString('latin1').replace(/startxref/g, 'startxreF'), 'latin1')), 'no-startxref',
    'a %%EOF with no startxref before it');

  // a LINEARIZED-like file: the final startxref points at the first-page cross-reference section near the start
  const head = '%PDF-1.6\n1 0 obj\n<< /Linearized 1 >>\nendobj\n';
  const firstXref = head.length;
  const lin = Buffer.from(head + 'xref\n0 1\n0000000000 65535 f \ntrailer\n<< /Size 1 >>\n' + '2 0 obj\n(body)\nendobj\n'.repeat(20) +
    `startxref\n${firstXref}\n%%EOF\n`, 'latin1');
  assert.deepStrictEqual(verdict(lin), { ok: true }, 'a linearized-style pointer to the first-page xref');
  console.log('[ok] pdf integrity: complete (classic / xref stream / incremental / real LuaLaTeX) vs torn, padding, pointers, linearized');
}

// ADR-0046 D6 — a PDF caught mid-write SETTLES: complete files deliver at once, a torn file that finishes is waited
// for, a stable-but-incomplete one is delivered flagged, a still-changing one is left pending when a notification will
// follow (flagged when none will), and a change notification ends a wait early. Remote: a torn file costs its tail.
async function testPdfSettle() {
  const { makeDeckPdf, truncatePdf } = require('./fixtures/pdf-gen.js');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-settle-'));
  const p = path.join(dir, 'talk.pdf');
  const full = makeDeckPdf({ pages: 3 });
  const torn = truncatePdf(full, 0.5);
  let movedOnTear = null;
  try {
    process.env.VV_PDF_SETTLE_MS = '40';     // u = 40 ms: attempts at +40, +80, +160, +320 ms; stable after 240 ms

    // 1 — a complete PDF: read SYNCHRONOUSLY (the open's vv:content keeps its place before vv:tree)
    fs.writeFileSync(p, full);
    const r1 = content.readPdfLocal(p, { notifies: true });
    assert.ok(!(r1 instanceof Promise) && r1.bytes.equals(full) && !r1.incomplete, 'a complete PDF is read synchronously');

    // 2 — torn, finished 60 ms later: waited for, delivered complete
    fs.writeFileSync(p, torn);
    setTimeout(() => fs.writeFileSync(p, full), 60);
    const r2 = await content.readPdfLocal(p, { notifies: true });
    assert.ok(r2.bytes && r2.bytes.equals(full) && !r2.incomplete, 'a torn PDF that finishes is waited for, then delivered whole');

    // 3 — torn and never finished (a LaTeX run that died): STABLE after 6u → delivered flagged, with its bytes
    fs.writeFileSync(p, torn);
    const t3 = Date.now();
    const r3 = await content.readPdfLocal(p, { notifies: true });
    assert.strictEqual(r3.incomplete, 'no-eof', 'a stable-but-incomplete PDF is delivered flagged incomplete');
    assert.ok(r3.bytes.equals(torn), 'with the bytes as they stand');
    assert.ok(Date.now() - t3 >= 240, `only after it held still for 6u (${Date.now() - t3} ms)`);

    // 4 — still changing at every attempt: pending when a notification will follow; flagged when none will
    let n = 0;
    const churn = setInterval(() => { n += 1; fs.writeFileSync(p, truncatePdf(full, 0.3 + (n % 5) * 0.1)); }, 15);
    try {
      const r4 = await content.readPdfLocal(p, { notifies: () => true });
      assert.deepStrictEqual(r4, { pending: true }, 'a PDF still changing, with a notification coming: pending (nothing delivered)');
      const r4b = await content.readPdfLocal(p, { notifies: false });
      assert.ok(typeof r4b.incomplete === 'string' && r4b.incomplete.length > 0 && Buffer.isBuffer(r4b.bytes),
        `…and with no notification coming: delivered flagged (${r4b.incomplete})`);
    } finally {
      clearInterval(churn);
    }

    // 5 — a change notification ends a wait early: u = 400 ms, but the file is finished (and announced) at 20 ms
    process.env.VV_PDF_SETTLE_MS = '400';
    fs.writeFileSync(p, torn);
    let announce;
    const changed = () => new Promise((r) => { announce = r; });
    setTimeout(() => { fs.writeFileSync(p, full); announce(); }, 20);
    const t5 = Date.now();
    const r5 = await content.readPdfLocal(p, { notifies: true, changed });
    assert.ok(r5.bytes && r5.bytes.equals(full), 'the announced finish is read');
    assert.ok(Date.now() - t5 < 300, `at once, not at the first scheduled attempt (${Date.now() - t5} ms < 400)`);

    // 6 — REMOTE: one verified attempt at a torn file reads only its tail (≤ 1 KiB + slack — H5), and a complete
    // remote PDF reads byte-exact
    process.env.VV_PDF_SETTLE_MS = '40';
    const { startSftpServer } = require('./fixtures/ssh-server.js');
    const transport = require('../src/vinary/main/ssh_transport.js');
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-settle-home-'));
    fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
    const big = makeDeckPdf({ pages: 40, padBytes: 200000 });
    const srv = await startSftpServer({ password: 'st-pw', files: { 'torn.pdf': truncatePdf(big, 0.6), 'whole.pdf': big } });
    transport.configure({ homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
      promptHostKey: async () => true, promptSecret: async () => 'st-pw', onError: () => {} });
    try {
      const tornUri = srv.url('/torn.pdf');
      const st = await transport.remoteStat(tornUri);
      srv.resetStats();
      const a = await content._readPdfAttemptRemote(tornUri, st);
      assert.strictEqual(a.incomplete, 'no-eof', 'a torn remote PDF is judged from its tail');
      const moved = movedOnTear = srv.stats.byPath['/torn.pdf'].readBytes;
      assert.ok(moved <= 1088, `…which is all that crossed the network: ${moved} of ${st.size} bytes (H5)`);
      const w = await content.readPdfRemote(srv.url('/whole.pdf'), await transport.remoteStat(srv.url('/whole.pdf')), { notifies: false });
      assert.ok(w.bytes.equals(big) && !w.incomplete, 'a complete remote PDF reads byte-exact, verified');
      const r6 = await content.readPdfRemote(tornUri, st, { notifies: false });
      assert.strictEqual(r6.incomplete, 'no-eof', 'and a torn one that never finishes is delivered flagged');
    } finally {
      transport.closeAll();
      await srv.close();
      fs.rmSync(home, { recursive: true, force: true });
    }
    console.log(`[ok] pdf settle: complete at once (sync), a finishing tear waited for, stable-incomplete flagged, changing → pending / flagged, early wake, remote tail-only (H5: ${movedOnTear} bytes moved)`);
  } finally {
    delete process.env.VV_PDF_SETTLE_MS;
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

// ADR-0048 N7 — the pdfpc sidecar is read WITH its PDF: named as pdfpc names it (but only ever beside the PDF), read
// whole when present, refused unread past the 4 MiB cap, never blocking on a FIFO, and carried on a remote PDF's payload.
async function testPdfpcSidecar() {
  const { execFileSync } = require('child_process');
  const { makeDeckPdf } = require('./fixtures/pdf-gen.js');
  const cap = content.PDFPC_SIDECAR_MAX_BYTES;
  assert.strictEqual(cap, 4 * 1024 * 1024, 'the cap is 4 MiB (ADR-0048 §8.9)');

  // the name: the file's own name up to its last dot, then .pdfpc — never a dot in a directory name (pdfpc's quirk)
  const name = content.pdfpcSidecarPath;
  assert.strictEqual(name('/talks/talk.pdf'), '/talks/talk.pdfpc', 'talk.pdf → talk.pdfpc');
  assert.strictEqual(name('/talks/slides.v2.PDF'), '/talks/slides.v2.pdfpc', 'the last dot, in any case: slides.v2.PDF → slides.v2.pdfpc');
  assert.strictEqual(name('/a.b/talk'), '/a.b/talk.pdfpc', 'a name without a dot gets .pdfpc appended — not /a.pdfpc, outside its directory');
  assert.strictEqual(name('/dir/.pdf'), '/dir/.pdfpc', 'a dot-file named .pdf: the dot is its own (pdfpc agrees)');
  assert.strictEqual(name('ssh://u@host.example/srv/talk.pdf'), 'ssh://u@host.example/srv/talk.pdfpc', 'a remote URI keeps its authority');
  assert.strictEqual(name('ssh://host.example/talk'), 'ssh://host.example/talk.pdfpc', 'a dot in the host is not the extension');

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-pdfpc-'));
  try {
    const pdf = path.join(dir, 'talk.pdf');
    fs.writeFileSync(pdf, makeDeckPdf({ pages: 2 }));
    assert.strictEqual(content.readPdfpcSidecarLocal(pdf), null, 'no sidecar → null');

    const text = '{"pdfpcFormat": 2, "pages": [{"idx": 0, "label": "1", "overlay": 0, "note": "Grüße — ✓"}]}';
    fs.writeFileSync(path.join(dir, 'talk.pdfpc'), text);
    assert.deepStrictEqual(content.readPdfpcSidecarLocal(pdf), { path: path.join(dir, 'talk.pdfpc'), text },
      'a sidecar is read whole, as UTF-8');

    fs.writeFileSync(path.join(dir, 'talk.pdfpc'), Buffer.alloc(cap, 0x61));
    const atCap = content.readPdfpcSidecarLocal(pdf);
    assert.ok(atCap.text && atCap.text.length === cap, 'a sidecar of exactly the cap is read');

    fs.truncateSync(path.join(dir, 'talk.pdfpc'), cap + 1);   // sparse: one byte over, nothing written
    assert.deepStrictEqual(content.readPdfpcSidecarLocal(pdf), { path: path.join(dir, 'talk.pdfpc'), size: cap + 1, tooLarge: true },
      'one byte over the cap is refused unread, with its size');

    fs.rmSync(path.join(dir, 'talk.pdfpc'));
    fs.mkdirSync(path.join(dir, 'talk.pdfpc'));
    assert.strictEqual(content.readPdfpcSidecarLocal(pdf), null, 'a directory where the sidecar would be is no sidecar');
    fs.rmdirSync(path.join(dir, 'talk.pdfpc'));

    if (process.platform !== 'win32') {
      // a blocking open of a FIFO waits for a writer and stalls the whole thread — no timer here could fire — so the
      // read runs in a child given a deadline: a reader that blocks fails this check instead of hanging the smoke
      execFileSync('mkfifo', [path.join(dir, 'talk.pdfpc')]);
      const probe = `const c = require(${JSON.stringify(require.resolve('../src/vinary/main/content_service.js'))});`
        + `process.stdout.write(JSON.stringify(c.readPdfpcSidecarLocal(${JSON.stringify(pdf)})));`;
      let out;
      try {
        out = execFileSync(process.execPath, ['-e', probe], { timeout: 5000, encoding: 'utf8' });
      } catch (e) {
        assert.fail(`opening a FIFO where the sidecar would be must not wait for a writer (${e.code || e.signal || e.message})`);
      }
      assert.strictEqual(out, 'null', 'a FIFO where the sidecar would be is no sidecar, and opening it does not block');
      fs.rmSync(path.join(dir, 'talk.pdfpc'));
    }
    if (process.platform !== 'win32' && process.getuid && process.getuid() !== 0) {
      fs.writeFileSync(path.join(dir, 'talk.pdfpc'), text);
      fs.chmodSync(path.join(dir, 'talk.pdfpc'), 0o000);
      assert.deepStrictEqual(content.readPdfpcSidecarLocal(pdf), { path: path.join(dir, 'talk.pdfpc'), error: 'EACCES' },
        'a sidecar that cannot be read is reported, not thrown');
      fs.chmodSync(path.join(dir, 'talk.pdfpc'), 0o600);
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }

  // REMOTE: the PDF's payload carries its sidecar (read while the PDF is read); past the cap it is never downloaded
  const { startSftpServer } = require('./fixtures/ssh-server.js');
  const transport = require('../src/vinary/main/ssh_transport.js');
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-pdfpc-home-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  const sidecarText = '[duration]\n30\n[notes]\n### 1\nA legacy note\n';
  const srv = await startSftpServer({
    password: 'pc-pw',
    files: {
      'talk.pdf': makeDeckPdf({ pages: 2 }),
      'talk.pdfpc': sidecarText,
      'lone.pdf': makeDeckPdf({ pages: 2 }),
      'big.pdf': makeDeckPdf({ pages: 2 }),
      'big.pdfpc': Buffer.alloc(cap + 1, 0x61),
    },
  });
  transport.configure({ homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => 'pc-pw', onError: () => {} });
  try {
    const talk = await content.openRemoteUri(srv.url('/talk.pdf'), 'pdf');
    assert.deepStrictEqual(talk.sidecar, { path: srv.url('/talk.pdfpc'), text: sidecarText },
      'a remote PDF\'s payload carries the sidecar beside it');
    const lone = await content.openRemoteUri(srv.url('/lone.pdf'), 'pdf');
    assert.strictEqual(lone.sidecar, null, '…and null when there is none');
    srv.resetStats();
    const big = await content.readPdfpcSidecarRemote(srv.url('/big.pdf'));
    assert.deepStrictEqual(big, { path: srv.url('/big.pdfpc'), size: cap + 1, tooLarge: true }, 'a remote sidecar past the cap is refused…');
    const moved = (srv.stats.byPath['/big.pdfpc'] || { readBytes: 0 }).readBytes;
    assert.strictEqual(moved, 0, `…without a byte of it read (${moved})`);
  } finally {
    transport.closeAll();
    await srv.close();
    fs.rmSync(home, { recursive: true, force: true });
  }
  console.log('[ok] pdfpc sidecar: named beside its PDF, read whole, capped unread, FIFO-safe, unreadable reported, remote payload carries it');
}

main()
  .then(async () => {
    testPdfIntegrity();
    await testPdfSettle();
    await testPdfpcSidecar();
    console.log('content-service smoke OK');
  })
  .catch(err => {
    console.error(err && err.stack ? err.stack : err);
    process.exit(1);
  });
