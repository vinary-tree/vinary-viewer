'use strict';

// ADR-0039 git data-layer smoke: pins the exact plumbing contract vinary.git.log BUILDS and
// vinary.main.git EXECUTES, against a real hermetic repository — the git-tree-smoke.js idiom.
// What only this file can check: that the argv shapes actually behave as designed against real
// git (record discipline round-trips hostile-ish bodies, --date-order emits children first,
// rev-parse verification rejects option-shaped refs, cat-file blob content differs from a drifted
// working tree — the rev-aware-enrichment proof), and that the shipped sources still WIRE the
// layer (source-binding regexes; every seam is a silent no-op when unplugged).

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const LOG_NS = fs.readFileSync(path.join(ROOT, 'src', 'vinary', 'git', 'log.cljs'), 'utf8');
const MAIN_GIT = fs.readFileSync(path.join(ROOT, 'src', 'vinary', 'main', 'git.cljs'), 'utf8');
const SERVICE = fs.readFileSync(path.join(ROOT, 'src', 'vinary', 'main', 'service.cljs'), 'utf8');
const PRELOAD = fs.readFileSync(path.join(ROOT, 'resources', 'preload.js'), 'utf8');

// the nine-field record contract (vinary.git.log/pretty-format), spelled out on purpose:
// a drift in either place must fail loudly here.
const PRETTY = 'format:%H%x1f%P%x1f%an%x1f%ae%x1f%aI%x1f%cI%x1f%D%x1f%s%x1f%b%x00';
const SEP = '\u001f';
const TERM = '\u0000';
const EMPTY_TREE = '4b825dc642cb6eb9a060e54bf8d69288fbee4904';

function main() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-git-log-smoke-'));
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
    // ---- fixture: root → (feature branch) → main commit → --no-ff merge, tagged --------------------
    git(['-c', 'init.defaultBranch=main', 'init', '-q']);
    fs.writeFileSync(path.join(repo, 'file.txt'), 'v1\n');
    git(['add', 'file.txt']);
    git(['commit', '-q', '-m', 'root: add file']);
    git(['checkout', '-q', '-b', 'feature']);
    fs.writeFileSync(path.join(repo, 'file.txt'), 'v2-feature\n');
    // a body with newlines AND a ", " (the refs separator) must round-trip inside ONE record
    git(['commit', '-q', '-am', 'feat: change on feature', '-m', 'body line 1, with comma\nbody line 2']);
    git(['checkout', '-q', 'main']);
    fs.writeFileSync(path.join(repo, 'other.txt'), 'main-side\n');
    git(['add', 'other.txt']);
    git(['commit', '-q', '-m', 'main: add other']);
    git(['merge', '-q', '--no-ff', '-m', 'merge feature', 'feature']);
    git(['tag', 'v1.0']);
    const rootSha = git(['rev-list', '--max-parents=0', 'HEAD']).trim();

    // ---- the log argv, byte for byte, against real git --------------------------------------------
    const out = git(['log', '--date-order', '--max-count=250', '--skip=0',
                     `--pretty=${PRETTY}`, '--end-of-options', 'HEAD']);
    const records = out.split(TERM).map((r) => r.replace(/^\n+/, '')).filter(Boolean);
    assert.strictEqual(records.length, 4, 'four commits in the window');
    const commits = records.map((r) => {
      const f = r.split(SEP);
      assert.strictEqual(f.length, 9, `every record has nine fields (got ${f.length})`);
      assert.match(f[0], /^[0-9a-f]{40}$/, 'field 0 is the 40-hex hash');
      return { hash: f[0], parents: f[1].split(' ').filter(Boolean), refs: f[6], subject: f[7], body: f[8] };
    });

    // children strictly before parents — the lane-assignment precondition --date-order buys
    const indexOf = (h) => commits.findIndex((c) => c.hash === h);
    commits.forEach((c, i) => c.parents.forEach((p) => {
      assert.ok(indexOf(p) > i, `parent ${p.slice(0, 7)} of ${c.hash.slice(0, 7)} appears after its child`);
    }));
    assert.strictEqual(commits[0].parents.length, 2, 'the merge commit heads the window with two parents');
    assert.strictEqual(commits[3].parents.length, 0, 'the root commit closes it with none');

    // record round-trip: multi-line body with a ", " stays one record; decorations parse per commit
    const feature = commits.find((c) => c.subject === 'feat: change on feature');
    assert.strictEqual(feature.body.replace(/\n+$/, ''), 'body line 1, with comma\nbody line 2',
      'a body with newlines and a comma-space survives the %x1f/%x00 discipline');
    assert.ok(commits[0].refs.includes('HEAD -> main') && commits[0].refs.includes('tag: v1.0'),
      '%D decorations carry the branch and the tag');

    // ---- branches: for-each-ref %1f (for-each-ref spells hex escapes %NN, not %xNN) ---------------
    const refsOut = git(['for-each-ref', '--format=%(refname)%1f%(refname:short)%1f%(HEAD)',
                         'refs/heads', 'refs/remotes', 'refs/tags']);
    const refLines = refsOut.split('\n').filter(Boolean).map((l) => l.split(SEP));
    assert.ok(refLines.every((l) => l.length === 3), 'every for-each-ref line has three fields');
    const mainRef = refLines.find((l) => l[0] === 'refs/heads/main');
    assert.strictEqual(mainRef[2], '*', 'the current branch carries the %(HEAD) star');
    assert.ok(refLines.some((l) => l[0] === 'refs/heads/feature'), 'the feature branch lists');
    assert.ok(refLines.some((l) => l[0] === 'refs/tags/v1.0'), 'the tag lists');
    assert.strictEqual(git(['rev-parse', '--abbrev-ref', 'HEAD']).trim(), 'main');

    // ---- rev-parse verification: the injection guard ----------------------------------------------
    assert.match(git(['rev-parse', '--verify', '--quiet', '--end-of-options', 'HEAD~1^{commit}']).trim(),
      /^[0-9a-f]{40}$/, 'a real ref verifies to a full sha');
    assert.throws(() => git(['rev-parse', '--verify', '--quiet', '--end-of-options', '--all^{commit}']),
      'an option-shaped ref must NOT verify');
    assert.throws(() => git(['rev-parse', '--verify', '--quiet', '--end-of-options', 'junk^{commit}']),
      'garbage must NOT verify');

    // ---- rev-aware enrichment proof (D6): blob content ≠ a drifted working tree -------------------
    fs.writeFileSync(path.join(repo, 'file.txt'), 'v3-uncommitted\n');
    assert.strictEqual(git(['cat-file', 'blob', `${rootSha}:file.txt`]), 'v1\n',
      'cat-file blob serves the file AS OF the revision');
    assert.notStrictEqual(git(['cat-file', 'blob', 'HEAD:file.txt']),
      fs.readFileSync(path.join(repo, 'file.txt'), 'utf8'),
      'the working tree has drifted from HEAD — on-disk enrichment would mis-align');

    // ---- the R4 root-commit path: empty-tree..root is a plain diff showing the full addition ------
    const rootDiff = git(['diff', '--no-color', '--no-ext-diff', '--end-of-options', EMPTY_TREE, rootSha]);
    assert.ok(rootDiff.includes('diff --git') && rootDiff.includes('+v1'),
      'diff against the empty tree renders a root commit as a pure addition');

    // ---- the empty-repo phrasing vinary.main.git greps for ----------------------------------------
    const emptyRepo = path.join(tmp, 'empty');
    fs.mkdirSync(emptyRepo);
    git(['-c', 'init.defaultBranch=main', 'init', '-q'], emptyRepo);
    let emptyErr = '';
    try { git(['log', '--date-order', '-n', '1', `--pretty=${PRETTY}`], emptyRepo); }
    catch (e) { emptyErr = String(e.stderr || e.message); }
    assert.ok(emptyErr.includes('does not have any commits'),
      `git's empty-repo phrasing must contain the sentinel main/git.cljs matches (got: ${emptyErr.trim()})`);

    // ---- source bindings: the layer must still be WIRED -------------------------------------------
    assert.ok(LOG_NS.includes('%H%x1f%P%x1f%an%x1f%ae%x1f%aI%x1f%cI%x1f%D%x1f%s%x1f%b%x00'),
      'vinary.git.log must build the nine-field pretty-format this smoke just exercised');
    assert.ok(/"log"\s+"--date-order"/.test(LOG_NS) && LOG_NS.includes('"--end-of-options"'),
      'vinary.git.log must keep --date-order and the --end-of-options fence');
    assert.ok(LOG_NS.includes('%(refname)%1f%(refname:short)%1f%(HEAD)'),
      'vinary.git.log must keep the for-each-ref %1f format');
    assert.ok(MAIN_GIT.includes(EMPTY_TREE), 'vinary.main.git must keep the R4 empty-tree fallback');
    assert.ok(MAIN_GIT.includes('does not have any commits'),
      'vinary.main.git must detect the empty-repo phrasing');
    assert.ok(/:git\s+\{:root\s+top\s+:from\s+from-sha\s+:to\s+to-sha\s+:range\?\s+\(boolean\s+\(and\s+from\s+\(not\s+parent\?\)\)\)\}/.test(MAIN_GIT),
      'vinary.main.git must register the :git override — incl. the ADR-0042 :range? endpoint-highlight flag — that drives rev-aware enrichment + adoption opt-out');
    assert.ok(MAIN_GIT.includes('"vv:git-changed"') && MAIN_GIT.includes(':depth 4'),
      'vinary.main.git must push vv:git-changed from the bounded refs watcher');
    assert.ok(/"vv:git-log"[\s\S]{0,80}git\/handle-log/.test(SERVICE)
      && /"vv:git-branches"[\s\S]{0,80}git\/handle-branches/.test(SERVICE)
      && /"vv:git-open-diff"[\s\S]{0,80}git\/handle-open-diff/.test(SERVICE)
      && /"vv:git-watch"[\s\S]{0,120}git\/sync-owner-roots!/.test(SERVICE),
      'service.cljs must bind all four vv:git-* channels to vinary.main.git');
    assert.ok(SERVICE.includes('(git/release-owner! id)') && SERVICE.includes('(git/forget-spill! path)'),
      'service.cljs must release watcher ownership and spill dedupe with retention/window lifecycle');
    assert.ok(['gitLog', 'gitBranches', 'gitOpenDiff', 'gitWatch', 'onGitChanged']
      .every((k) => PRELOAD.includes(k)),
      'preload.js must expose the five git bridge functions');
    assert.ok(/doc-overrides\/git-info\s+diffPath/.test(SERVICE)
      && SERVICE.includes('git/load-rev-sources'),
      'vv:load-diff-sources must branch to rev-aware enrichment for commit diffs (R8)');
    assert.ok(/"cat-file"\s+"blob"/.test(MAIN_GIT),
      'load-rev-sources must read blob content with cat-file plumbing (no textconv/filters)');

    console.log('git log/data-layer smoke OK');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

try { main(); }
catch (err) { console.error(err && err.stack ? err.stack : err); process.exit(1); }
