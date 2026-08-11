'use strict';

// ADR-0040 blame + history smoke: pins the blame/--follow/-L argv contracts against a real
// hermetic repository (the git-tree-smoke.js idiom), including the two facts the renderer relies
// on — an uncommitted edit blames as the all-zero hash, and `git log -L … --no-patch` (git ≥ 2.42)
// emits records with NO patch text — plus source-binding regexes that keep the seams wired.

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const BLAME_NS = fs.readFileSync(path.join(ROOT, 'src', 'vinary', 'git', 'blame.cljs'), 'utf8');
const MAIN_GIT = fs.readFileSync(path.join(ROOT, 'src', 'vinary', 'main', 'git.cljs'), 'utf8');
const SERVICE = fs.readFileSync(path.join(ROOT, 'src', 'vinary', 'main', 'service.cljs'), 'utf8');
const SOURCE_VIEW = fs.readFileSync(path.join(ROOT, 'src', 'vinary', 'renderer', 'source_view.cljs'), 'utf8');
const PRELOAD = fs.readFileSync(path.join(ROOT, 'resources', 'preload.js'), 'utf8');

const ZERO = '0'.repeat(40);
const PRETTY_L = 'format:%x1e%H%x1f%P%x1f%an%x1f%ae%x1f%aI%x1f%cI%x1f%D%x1f%s';

function main() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-git-blame-smoke-'));
  const repo = path.join(tmp, 'repo');
  fs.mkdirSync(repo);
  const env = {
    ...process.env,
    HOME: tmp,
    XDG_CONFIG_HOME: path.join(tmp, 'config'),
    GIT_CONFIG_GLOBAL: path.join(tmp, 'gitconfig-none'),
    GIT_CONFIG_SYSTEM: '/dev/null',
    GIT_AUTHOR_NAME: 'Smoke', GIT_AUTHOR_EMAIL: 'smoke@test',
    GIT_COMMITTER_NAME: 'Smoke', GIT_COMMITTER_EMAIL: 'smoke@test',
  };
  const git = (args, cwd = repo) =>
    execFileSync('git', args, { cwd, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });

  try {
    // ---- fixture: two commits, a rename, an uncommitted edit ---------------------------------------
    git(['-c', 'init.defaultBranch=main', 'init', '-q']);
    fs.writeFileSync(path.join(repo, 'orig.txt'), 'alpha\nbeta\ngamma\n');
    git(['add', 'orig.txt']);
    git(['commit', '-q', '-m', 'add orig']);
    fs.writeFileSync(path.join(repo, 'orig.txt'), 'alpha\nBETA\ngamma\n');
    git(['commit', '-q', '-am', 'edit beta']);
    git(['mv', 'orig.txt', 'renamed.txt']);
    git(['commit', '-q', '-m', 'rename']);
    fs.appendFileSync(path.join(repo, 'renamed.txt'), 'delta-dirty\n');

    // ---- blame --line-porcelain: per-line headers, metadata, the zero hash for dirty lines --------
    const blameOut = git(['blame', '--line-porcelain', '--', path.join(repo, 'renamed.txt')]);
    const headers = blameOut.split('\n').filter((l) => /^[0-9a-f]{40} \d+ \d+/.test(l));
    assert.strictEqual(headers.length, 4, 'four blamed lines');
    assert.ok(headers.some((l) => l.startsWith(ZERO)),
      'the uncommitted line blames as the all-zero hash');
    assert.ok(/^author /m.test(blameOut) && /^author-time \d+/m.test(blameOut)
      && /^summary /m.test(blameOut),
      'line-porcelain carries the metadata keys the parser consumes');
    assert.ok(/^author Not Committed Yet$/m.test(blameOut) || headers.some((l) => l.startsWith(ZERO)),
      'dirty lines carry placeholder authorship');

    // ---- file history: --follow crosses the rename ------------------------------------------------
    const followOut = git(['log', '--follow', '--date-order', '--max-count=250', '--skip=0',
                           '--pretty=format:%x1e%H%x1f%s',
                           '--end-of-options', 'HEAD', '--', path.join(repo, 'renamed.txt')]);
    assert.strictEqual(followOut.split('\x1e').filter(Boolean).length, 3,
      '--follow sees the rename AND the pre-rename commits');

    // ---- line history: -L --no-patch emits clean %x1e records (git ≥ 2.42) ------------------------
    const version = git(['--version']).trim().match(/(\d+)\.(\d+)/);
    const lOut = git(['log', `-L1,2:renamed.txt`, '--no-patch', '-n', '500', `--pretty=${PRETTY_L}`]);
    const lRecords = lOut.split('\x1e').filter((r) => r.trim().length > 0);
    assert.ok(lRecords.length >= 2, `-L finds the commits touching lines 1-2 (got ${lRecords.length})`);
    if (Number(version[1]) > 2 || (Number(version[1]) === 2 && Number(version[2]) >= 42)) {
      assert.ok(!lOut.includes('diff --git'),
        'on git ≥ 2.42, --no-patch suppresses the -L patch output entirely');
    }
    lRecords.forEach((r) => {
      const first = r.replace(/^\n+/, '').split('\x1f')[0];
      assert.match(first, /^[0-9a-f]{40}$/, 'every -L record opens with the 40-hex hash after %x1e');
    });

    // ---- source bindings --------------------------------------------------------------------------
    assert.ok(/"blame"\s+"--line-porcelain"/.test(MAIN_GIT),
      'vinary.main.git must issue blame --line-porcelain');
    assert.ok(/"vv:git-blame"[\s\S]{0,80}git\/handle-blame/.test(SERVICE),
      'service.cljs must bind vv:git-blame to vinary.main.git');
    assert.ok(BLAME_NS.includes('zero-hash') && BLAME_NS.includes('author-mail'),
      'vinary.git.blame must parse the porcelain keys this smoke exercised');
    assert.ok(SOURCE_VIEW.includes('vv-blame-gutter') && SOURCE_VIEW.includes('"set-blame!"'),
      'the source view must mount the blame gutter behind its exported reconfigure seam');
    assert.ok(PRELOAD.includes('gitBlame'), 'preload.js must expose gitBlame');

    console.log('git blame/history smoke OK');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

try { main(); }
catch (err) { console.error(err && err.stack ? err.stack : err); process.exit(1); }
