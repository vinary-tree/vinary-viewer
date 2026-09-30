'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Worker } = require('node:worker_threads');

const root = path.resolve(__dirname, '..');
const script = path.join(root, 'resources/public/js/find-worker.js');
const wasm = path.join(root, 'resources/public/js/find-wasm.wasm');

function next(worker, type) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { cleanup(); reject(new Error(`timed out waiting for ${type}`)); }, 15000);
    const onMessage = (message) => {
      if (message.type === 'error') { cleanup(); reject(new Error(message.message)); }
      else if (message.type === type) { cleanup(); resolve(message); }
    };
    const onError = (error) => { cleanup(); reject(error); };
    function cleanup() {
      clearTimeout(timer);
      worker.off('message', onMessage);
      worker.off('error', onError);
    }
    worker.on('message', onMessage);
    worker.on('error', onError);
  });
}

async function run() {
  const bootstrap = `
    const fs = require('node:fs');
    const { parentPort, workerData } = require('node:worker_threads');
    globalThis.self = { postMessage: (message) => parentPort.postMessage(message) };
    parentPort.on('message', (data) => self.onmessage({ data }));
    const source = fs.readFileSync(workerData.script);
    import('data:text/javascript;base64,' + source.toString('base64'))
      .then(() => parentPort.postMessage({ type: 'booted' }))
      .catch((error) => parentPort.postMessage({ type: 'error', message: error.stack }));
  `;
  const worker = new Worker(bootstrap, { eval: true, workerData: { script } });
  try {
    await next(worker, 'booted');
    const bytes = fs.readFileSync(wasm);
    const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    const ready = next(worker, 'ready');
    worker.postMessage({ type: 'init', wasm: buffer }, [buffer]);
    await ready;

    const text = 'The phone rings.\nA fone is heard.\nfoo foo\nBTrue padding\n';
    const indexed = next(worker, 'indexed');
    worker.postMessage({ type: 'index', docId: 1, kind: 'prose', text });
    const index = await indexed;
    assert.ok(index.lexemes >= 8);

    let requestId = 1;
    async function search(query, mode, phonetic = false) {
      const result = next(worker, 'results');
      worker.postMessage({ type: 'search', requestId: requestId++, docId: 1,
        query, mode, phonetic });
      return (await result).results;
    }
    assert.equal((await search('BTru padding', 'approximate')).length, 1);
    assert.equal((await search('padding BTru', 'approximate')).length, 1);
    assert.equal((await search('foo foo', 'approximate')).length, 1);
    assert.equal((await search('foo foo foo', 'approximate')).length, 0);
    const fzf = await search('Bre', 'fzf');
    assert.equal(fzf.length, 1);
    assert.deepEqual(fzf[0].spans.map(([start]) => start - text.indexOf('BTrue')), [0, 2, 4]);
    assert.equal((await search('fone', 'approximate', false)).length, 1);
    const phonetic = await search('fone', 'approximate', true);
    assert.equal(phonetic.length, 2);
    assert.ok(phonetic.some((result) => result.kind === 'sound-alike'));

    const sourceIndexed = next(worker, 'indexed');
    worker.postMessage({ type: 'index', docId: 2, kind: 'source', text });
    await sourceIndexed;
    const sourcePhonetic = next(worker, 'results');
    worker.postMessage({ type: 'search', requestId: requestId++, docId: 2,
      query: 'fone', mode: 'approximate', phonetic: true });
    assert.equal((await sourcePhonetic).results.length, 1);

    const denseIndexed = next(worker, 'indexed');
    worker.postMessage({ type: 'index', docId: 3, kind: 'source', text: 'foo '.repeat(100) });
    await denseIndexed;
    const dense = next(worker, 'results');
    worker.postMessage({ type: 'search', requestId: requestId++, docId: 3,
      query: 'foo foo foo foo', mode: 'approximate', phonetic: false });
    assert.equal((await dense).results.length, 1);
    console.log('find-worker-smoke: shared dictionary, edits, FZF, multi-term, phonetic pass');
  } finally { await worker.terminate(); }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
