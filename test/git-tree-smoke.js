'use strict';

// Proves the v0.2 sidebar file-tree fix end-to-end at the seam the fix touches:
// repo-tree (src/vinary/main/service.cljs) lists the repo with
//   git ls-files --cached --others --exclude-standard
// so a newly-created, uncommitted, not-.gitignore'd file (the one you just opened) APPEARS,
// while .gitignore'd clutter does NOT.
//
// Why a Node harness (not electron-smoke / a cljs -test): electron-smoke.js mocks vv:open and
// injects content via state.contentByPath / vv:open-files, so the real main open!/send-tree!/
// repo-tree never runs. The compiled main (dist/main/main.js) auto-invokes vinary.main.core/main
// on require (boots Electron, touches electron.app), so it can't be required in plain Node.
// repo-tree is private and entangled with electron/chokidar/content_service. So we exercise the
// exact git command repo-tree issues against a throwaway repo (the content-service-smoke pattern)
// and additionally assert service.cljs still issues that command.
//
// Run: node test/git-tree-smoke.js   (also wired into `npm test`).

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const SERVICE = path.join(ROOT, 'src', 'vinary', 'main', 'service.cljs');

// Hermetic git: ignore host global/system config (so a global core.excludesFile can't perturb
// --exclude-standard) and give commits a deterministic identity. HOME is repointed at the temp dir
// so ~/.gitconfig and ~/.config/git/ignore never leak in.
function gitEnv(tmp) {
  return {
    ...process.env,
    HOME: tmp,
    GIT_CONFIG_GLOBAL: path.join(tmp, 'no-global-gitconfig'),
    GIT_CONFIG_SYSTEM: path.join(tmp, 'no-system-gitconfig'),
    GIT_CONFIG_NOSYSTEM: '1',
    GIT_TERMINAL_PROMPT: '0'
  };
}

// The exact repo-tree invocation contract (mirrors the `git` helper in service.cljs).
function git(args, cwd, env) {
  return execFileSync('git', args, {
    cwd, env, encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore']
  });
}
// repo-tree parses stdout as (remove str/blank? (str/split (str/trim out) #"\n")).
function listFiles(args, cwd, env) {
  return new Set(git(args, cwd, env).trim().split('\n').filter(Boolean));
}
function run(cmd, cwd, env) {                 // setup commands: swallow stdout/stderr
  execFileSync('git', cmd, { cwd, env, stdio: ['ignore', 'ignore', 'ignore'] });
}

function main() {
  const tmp = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-git-tree-')));
  try {
    const env = gitEnv(tmp);
    const persist = path.join(tmp, 'docs', 'persistence');   // mirrors the real bug's docs/persistence/
    fs.mkdirSync(persist, { recursive: true });

    fs.writeFileSync(path.join(tmp, 'README.md'), '# temp repo\n');            // tracked
    fs.writeFileSync(path.join(persist, 'tracked-note.md'), 'committed\n');     // tracked (nested)
    fs.writeFileSync(path.join(tmp, '.gitignore'), '*.log\n');                  // ignore rule
    fs.writeFileSync(path.join(tmp, 'ignored.log'), 'noise\n');                 // must be EXCLUDED
    fs.writeFileSync(path.join(persist, 'fresh.md'), 'brand new, uncommitted\n'); // untracked → must APPEAR

    run(['-c', 'init.defaultBranch=main', 'init', '-q'], tmp, env);  // -c is a top-level git option → before the subcommand
    run(['add', 'README.md', 'docs/persistence/tracked-note.md'], tmp, env);
    run(['-c', 'user.email=vv@example.com', '-c', 'user.name=VV', '-c', 'commit.gpgsign=false',
         'commit', '-q', '-m', 'init'], tmp, env);

    // repo-tree resolves the root from the OPEN FILE's directory (a nested subdir here) …
    const root = git(['rev-parse', '--show-toplevel'], persist, env).trim();
    assert.strictEqual(fs.realpathSync(root), tmp,
      'rev-parse --show-toplevel resolves the repo root from a nested file');

    // … then lists the repo with the FIXED flags.
    const NEW = ['ls-files', '--cached', '--others', '--exclude-standard'];
    const files = listFiles(NEW, root, env);

    assert.ok(files.has('README.md'), 'tracked file still listed');
    assert.ok(files.has('docs/persistence/tracked-note.md'), 'nested tracked file still listed');
    assert.ok(files.has('docs/persistence/fresh.md'), 'untracked-not-ignored file APPEARS (the fix)');
    assert.ok(!files.has('ignored.log'), ".gitignore'd file must NOT appear");

    // before/after regression proof: the OLD command (tracked-only) MISSED the untracked file
    const old = listFiles(['ls-files'], root, env);
    assert.ok(old.has('docs/persistence/tracked-note.md'), 'old command listed tracked files');
    assert.ok(!old.has('docs/persistence/fresh.md'),
      'old `git ls-files` omitted the untracked file — this was the bug');

    // --cached and --others are disjoint → no duplicates (repo-tree does no de-dup)
    const rawLines = git(NEW, root, env).trim().split('\n').filter(Boolean);
    assert.strictEqual(rawLines.length, files.size, '--cached/--others disjoint: no duplicate paths');

    // bind this behavioral test to the shipped code: repo-tree must still issue these exact flags
    const src = fs.readFileSync(SERVICE, 'utf8');
    assert.ok(/\[\s*"ls-files"\s+"--cached"\s+"--others"\s+"--exclude-standard"\s*\]/.test(src),
      'service.cljs repo-tree must call git with --cached --others --exclude-standard');

    // …and that a file in NO repo still gets a tree: send-tree! must fall back to the synthetic root.
    // The fallback's own behavior is covered against real directories by the node :test build
    // (test/vinary/main/dir_walk_test.cljs); what can only be checked here is that main still WIRES it,
    // since repo-tree returning nil is silent — the sidebar would simply go missing again.
    assert.ok(/\(or\s+\(repo-tree\s+file-path\)\s+\(dir-walk\/dir-tree\s+file-path/.test(src),
      'service.cljs send-tree! must fall back to dir-walk/dir-tree when there is no repo');

    console.log('git file-tree smoke OK');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

try { main(); }
catch (err) { console.error(err && err.stack ? err.stack : err); process.exit(1); }
