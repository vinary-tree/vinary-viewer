'use strict';

// Real-app, real-SSH end-to-end proof for ADR-0035. One Electron main process is deliberately both the source
// and target daemon, while every source operation still crosses an in-process ssh2 SFTP/direct-tcpip server:
//
//   renderer -> source service -> SFTP/SSH tunnel -> authenticated target endpoint -> chokidar/git
//            <- SFTP re-read/tree event <- authenticated event channel <- target watcher
//
// This covers the integration seams which the protocol smoke cannot: app ownership reconciliation, target-side
// expansion-scoped watchers, renderer project merging, content re-rendering, and independent ssh:// + sftp://
// tree namespaces. The SSH fixture exposes only this test's temporary home at its matching absolute paths, which
// models a normal OpenSSH SFTP namespace and satisfies the production namespace-identity handshake.

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { app, BrowserWindow } = require('electron');
const { startSftpServer } = require('./fixtures/ssh-server.js');
const { isLiveWindow } = require('./headless-window.js');

const ROOT = path.resolve(__dirname, '..');
const requestedHome = process.env.VV_REMOTE_EVENTS_E2E_HOME;
if (!requestedHome) throw new Error('VV_REMOTE_EVENTS_E2E_HOME must be supplied by the integration-test runner');
const HOME_DIR = fs.realpathSync(requestedHome);
const REPO = path.join(HOME_DIR, 'repo');
const DOC = path.join(REPO, 'docs', 'open.md');
const DESCRIPTOR = path.join(HOME_DIR, '.vinary-viewer', 'runtime', 'daemon-events.json');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const PROJECTS = '(function(){ var d=window.__vvdb(); return (d&&d.ui&&d.ui.projects)||[]; })()';

function fixture() {
  fs.mkdirSync(path.dirname(DOC), { recursive: true });
  fs.writeFileSync(DOC, '# REMOTE VERSION ONE\n');
  fs.writeFileSync(path.join(REPO, 'old-name.md'), '# old\n');
  fs.writeFileSync(path.join(REPO, 'space name.md'), '# encoded path\n');
  execFileSync('git', ['-c', 'init.defaultBranch=main', 'init', '-q'], { cwd: REPO });
  execFileSync('git', ['add', '.'], { cwd: REPO });
}

async function waitForWindow() {
  for (let i = 0; i < 300; i++) {
    const win = BrowserWindow.getAllWindows().find(isLiveWindow);
    if (win && !win.webContents.isLoading()) return win;
    await sleep(100);
  }
  throw new Error('no renderer window appeared');
}

async function until(wc, expr, pred, label, attempts = 180) {
  let last = null;
  for (let i = 0; i < attempts; i++) {
    last = await wc.executeJavaScript(expr);
    if (pred(last)) return last;
    await sleep(100);
  }
  throw new Error(`timed out waiting for: ${label}\n  last value = ${JSON.stringify(last, null, 2)}`);
}

const project = (projects, root) => projects.find((entry) => entry.root === root);
const open = (wc, uri) => wc.executeJavaScript(`window.__vvopen(${JSON.stringify(uri)}); true`);

async function run() {
  fixture();
  const server = await startSftpServer({
    dir: HOME_DIR,
    exposeAbsoluteRoot: HOME_DIR,
    realHome: HOME_DIR,
    allowNone: true,
  });

  const sshDir = path.join(HOME_DIR, '.ssh');
  fs.mkdirSync(sshDir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(path.join(sshDir, 'known_hosts'),
    `[127.0.0.1]:${server.port} ${server.hostKeyType} ${server.hostKeyB64}\n`, { mode: 0o600 });

  process.env.HOME = HOME_DIR;
  process.env.XDG_CONFIG_HOME = path.join(HOME_DIR, '.config');
  process.env.VV_DAEMON_EVENTS_DESCRIPTOR = DESCRIPTOR;
  process.env.VV_POOL = '0';
  process.argv.splice(1, process.argv.length - 1, __filename);
  require(path.join(ROOT, 'dist', 'main', 'main.js'));

  let failed = null;
  try {
    const win = await waitForWindow();
    const wc = win.webContents;
    await until(wc, 'typeof window.__vvopen === "function"', (value) => value === true, 'renderer test hooks');
    for (let i = 0; i < 100 && !fs.existsSync(DESCRIPTOR); i++) await sleep(50);
    assert.ok(fs.existsSync(DESCRIPTOR), 'target daemon descriptor was not published');

    const sshDoc = server.url(DOC);
    const sshRoot = server.url(REPO);
    await open(wc, sshDoc);
    await until(wc, `document.body.innerText.includes('REMOTE VERSION ONE')`, Boolean,
      'initial remote Markdown rendition');
    let projects = await until(wc, PROJECTS, (items) => Boolean(project(items, sshRoot)),
      'the ssh:// project tree');
    assert.ok(project(projects, sshRoot).files.includes('docs/open.md'), 'opened ssh:// file is in its tree');
    assert.ok(project(projects, sshRoot).files.includes('space%20name.md'), 'tree path segments are URI encoded');
    await until(wc,
      `Array.from(document.querySelectorAll('.vv-file')).some(x=>x.textContent.includes('space name.md'))`,
      Boolean, 'URI-decoded remote tree labels');

    const sshProjectSelector = `details.vv-project[data-root=${JSON.stringify(sshRoot)}]`;
    const sshDocs = `${sshRoot}/docs`;
    const sshDocsSelector = `details.vv-dir[data-root=${JSON.stringify(sshRoot)}]`;
    await until(wc, `Boolean(document.querySelector(${JSON.stringify(sshProjectSelector)})?.open)`, Boolean,
      'ssh project root expansion');
    await until(wc,
      `Array.from(document.querySelectorAll(${JSON.stringify(sshDocsSelector)}))`
        + `.some(x=>x.dataset.path===${JSON.stringify(sshDocs)}&&x.open)`,
      Boolean, 'active ssh document directory expansion');
    await sleep(700); // target chokidar ready reconciliation
    const manualSsh = await wc.executeJavaScript(
      `window.vv.refreshTree(${JSON.stringify({ root: sshRoot, path: sshRoot })})`);
    assert.strictEqual(manualSsh.root, sshRoot, 'manual remote root refresh preserves its ssh:// namespace');
    assert.ok(manualSsh.files.includes('docs/open.md'), 'manual remote root refresh returns target files');

    fs.writeFileSync(DOC, '# REMOTE VERSION TWO\n');
    await until(wc, `document.body.innerText.includes('REMOTE VERSION TWO')`, Boolean,
      'target content invalidation and source SFTP re-read');

    fs.writeFileSync(path.join(REPO, 'root-added.md'), '# root add\n');
    projects = await until(wc, PROJECTS,
      (items) => Boolean(project(items, sshRoot)?.files.includes('root-added.md')),
      'target root watcher add event');
    assert.ok(project(projects, sshRoot).files.includes('root-added.md'));

    fs.renameSync(path.join(REPO, 'old-name.md'), path.join(REPO, 'renamed.md'));
    projects = await until(wc, PROJECTS, (items) => {
      const files = project(items, sshRoot)?.files || [];
      return files.includes('renamed.md') && !files.includes('old-name.md');
    }, 'target root watcher rename event');
    assert.ok(project(projects, sshRoot).files.includes('renamed.md'));

    fs.writeFileSync(path.join(REPO, 'docs', 'nested-added.md'), '# nested add\n');
    projects = await until(wc, PROJECTS,
      (items) => Boolean(project(items, sshRoot)?.files.includes('docs/nested-added.md')),
      'target expanded-directory watcher add event');
    assert.ok(project(projects, sshRoot).files.includes('docs/nested-added.md'));

    const docsSummary = `Array.from(document.querySelectorAll(${JSON.stringify(sshDocsSelector)}))`
      + `.find(x=>x.dataset.path===${JSON.stringify(sshDocs)})?.querySelector(':scope > summary')`;
    await wc.executeJavaScript(`${docsSummary}.click(); true`);
    await until(wc,
      `!Array.from(document.querySelectorAll(${JSON.stringify(sshDocsSelector)}))`
        + `.find(x=>x.dataset.path===${JSON.stringify(sshDocs)})?.open`,
      Boolean, 'remote child directory collapse');
    await sleep(400);
    fs.writeFileSync(path.join(REPO, 'docs', 'while-collapsed.md'), '# collapsed\n');
    await sleep(900);
    projects = await wc.executeJavaScript(PROJECTS);
    assert.ok(!project(projects, sshRoot).files.includes('docs/while-collapsed.md'),
      'collapsed remote directories release target watchers');
    await wc.executeJavaScript(`${docsSummary}.click(); true`);
    projects = await until(wc, PROJECTS,
      (items) => Boolean(project(items, sshRoot)?.files.includes('docs/while-collapsed.md')),
      'remote child refresh on re-expansion');
    assert.ok(project(projects, sshRoot).files.includes('docs/while-collapsed.md'));

    const encodedFile = `${sshRoot}/space%20name.md`;
    await wc.executeJavaScript(`document.querySelector(${JSON.stringify(`.vv-file[data-path=${JSON.stringify(encodedFile)}]`)})?.click(); true`);
    await until(wc, `document.body.innerText.includes('encoded path')`, Boolean,
      'opening a URI-encoded remote tree row');
    await open(wc, sshDoc);
    await until(wc, `document.body.innerText.includes('REMOTE VERSION TWO')`, Boolean,
      'return to the watched ssh document');

    const sftpDoc = sshDoc.replace(/^ssh:/, 'sftp:');
    const sftpRoot = sshRoot.replace(/^ssh:/, 'sftp:');
    await open(wc, sftpDoc);
    projects = await until(wc, PROJECTS, (items) => Boolean(project(items, sftpRoot)),
      'the sftp:// project tree');
    assert.ok(project(projects, sftpRoot).files.includes('docs/open.md'), 'opened sftp:// file is in its tree');
    await until(wc,
      `Boolean(document.querySelector(${JSON.stringify(`details.vv-project[data-root=${JSON.stringify(sftpRoot)}]`)})?.open)`,
      Boolean, 'sftp project root expansion');
    await sleep(700);
    const manualAll = await wc.executeJavaScript('window.vv.refreshAllTrees()');
    assert.ok(manualAll.some((entry) => entry.root === sshRoot && entry.files.includes('docs/open.md')),
      'Refresh All includes the visible ssh:// project');
    assert.ok(manualAll.some((entry) => entry.root === sftpRoot && entry.files.includes('docs/open.md')),
      'Refresh All includes the visible sftp:// project');

    fs.writeFileSync(path.join(REPO, 'dual-scheme.md'), '# both schemes\n');
    projects = await until(wc, PROJECTS, (items) => {
      const sshFiles = project(items, sshRoot)?.files || [];
      const sftpFiles = project(items, sftpRoot)?.files || [];
      return sshFiles.includes('dual-scheme.md') && sftpFiles.includes('dual-scheme.md');
    }, 'independent ssh:// and sftp:// tree updates');
    assert.ok(project(projects, sshRoot).files.includes('dual-scheme.md'));
    assert.ok(project(projects, sftpRoot).files.includes('dual-scheme.md'));

    fs.unlinkSync(path.join(REPO, 'root-added.md'));
    projects = await until(wc, PROJECTS, (items) => {
      const sshFiles = project(items, sshRoot)?.files || [];
      const sftpFiles = project(items, sftpRoot)?.files || [];
      return !sshFiles.includes('root-added.md') && !sftpFiles.includes('root-added.md');
    }, 'target tree watcher delete event');
    assert.ok(!project(projects, sshRoot).files.includes('root-added.md'));

    console.log('[ok] remote-daemon-events-e2e: content + ssh/sftp trees + manual/scoped refresh passed');
  } catch (error) {
    failed = error;
    console.error(error && error.stack || error);
  } finally {
    server.destroyConnections();
    await server.close();
    process.exitCode = failed ? 1 : 0;
    app.quit();
  }
}

run().catch((error) => { console.error(error && error.stack || error); process.exitCode = 1; app.quit(); });
