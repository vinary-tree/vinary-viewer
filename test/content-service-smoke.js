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
      'paper.pdf': '%PDF-1.4\n%fake pdf bytes\n',
      'src/greet.js': 'function greet(){ return "hello"; }',
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

    // Document↔PDF siblings over remote (both directions)
    const texWithPdf = await content.openRemoteUri(srv.url('/paper.tex'), 'latex');
    assert.ok(texWithPdf.pdfSibling && texWithPdf.pdfSibling.endsWith('/paper.pdf'), 'a remote .tex advertises its collocated .pdf');
    const pdfWithSrc = await content.openRemoteUri(srv.url('/paper.pdf'), 'pdf');
    assert.ok(pdfWithSrc.sourceSibling && pdfWithSrc.sourceSibling.endsWith('/paper.tex'), 'a remote .pdf advertises its collocated source');

    // remote diff on-disk enrichment (resolve a referenced file over SFTP, walking ancestors)
    const diffSrcs = await content.loadRemoteDiffSources(srv.url('/change.diff'), ['src/greet.js']);
    assert.ok(/hello/.test(diffSrcs['src/greet.js'] || ''), 'remote diff-source enrichment resolves a referenced file over SFTP');

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

    console.log('[ok] remote SSH: openRemoteUri + Doc↔PDF siblings + diff/asset + streaming/paging + mid-stream-drop partial');
  } finally {
    transport.closeAll();
    if (!srvClosed) await srv.close();
    fs.rmSync(home, { recursive: true, force: true });
  }
}

main()
  .then(() => {
    console.log('content-service smoke OK');
  })
  .catch(err => {
    console.error(err && err.stack ? err.stack : err);
    process.exit(1);
  });
