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
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
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
