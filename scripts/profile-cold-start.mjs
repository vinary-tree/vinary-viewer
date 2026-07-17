#!/usr/bin/env node
// Cold-start profiler: launch the GUI with VV_PROFILE=1 on a set of representative fixtures, N times each,
// parse the `[vv-profile] <proc> <name> <ms>` marks (main + renderer share a wall-clock origin), and report
// the median ms-from-entry of each phase and the total time-to-content. Data-driven baseline for the
// cold-start optimization ledger (docs/optimization/cold-start/ledger.md).
//
//   node scripts/profile-cold-start.mjs [--runs N] [--keep] [fixture ...]
//
// With no fixtures it profiles a built-in set (empty, markdown+math, org, latex, source, pdf). Needs a built
// renderer+main (`npm run compile`) and, headless, xvfb (auto-detected).
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ELECTRON = path.join(ROOT, 'node_modules', '.bin', 'electron');
const PHASES = ['entry', 'ready', 'window', 'eval', 'init', 'paint', 'did-finish-load', 'open-sent', 'received', 'rendered'];

const argv = process.argv.slice(2);
let runs = 5;
let keep = false;
const fixtureArgs = [];
for (let i = 0; i < argv.length; i += 1) {
  if (argv[i] === '--runs') { runs = parseInt(argv[++i], 10); }
  else if (argv[i] === '--keep') { keep = true; }
  else fixtureArgs.push(argv[i]);
}

const hasXvfb = spawnSync('sh', ['-c', 'command -v xvfb-run'], { stdio: 'ignore' }).status === 0;
const headless = !process.env.DISPLAY || hasXvfb;

// Track every spawned process group so we can tear them ALL down even if this harness is killed (an outer
// `timeout`, Ctrl-C, an uncaught error) — a detached child's group outlives the harness otherwise.
const groups = new Set();
const killGroup = (pid) => { try { process.kill(-pid, 'SIGKILL'); } catch {} };
const killAll = () => { for (const pid of groups) killGroup(pid); groups.clear(); };
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(sig, () => { killAll(); process.exit(1); });
process.on('exit', killAll);

// ── built-in fixtures (a temp dir, cleaned unless --keep) ──
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-profile-'));
const write = (name, body) => { const p = path.join(tmp, name); fs.writeFileSync(p, body); return p; };
const builtin = fixtureArgs.length ? fixtureArgs : [
  write('empty.md', '# Empty\n'),
  write('math.md', '# Math\n\nInline $x^2+1$ and display:\n\n$$\\int_0^1 x\\,dx = \\tfrac12$$\n\n' + 'Lorem ipsum dolor sit amet. '.repeat(40)),
  write('doc.org', '#+TITLE: Org\n\n* Heading\n\nText with $a^2+b^2=c^2$ and *bold*.\n'),
  write('doc.tex', '\\documentclass{article}\\begin{document}\\section{Intro}\nText $E=mc^2$.\n\\end{document}\n'),
  write('code.py', 'def f(x):\n    return x * x\n\n' + 'y = f(1)\n'.repeat(40)),
  fs.existsSync(path.join(ROOT, 'test', 'fixtures', 'smoke.pdf')) ? path.join(ROOT, 'test', 'fixtures', 'smoke.pdf') : write('plain.txt', 'plain\n'),
];

function launch(fixture) {
  return new Promise((resolve) => {
    // NO extra electron flags before the app path: `--no-sandbox` would land at process.argv[1] and shift the
    // app-path/document parsing (doc-uris treats argv[1] as the app path) — matching the real `vv` launcher,
    // which runs `electron "$REPO" "$@"`.
    const base = [ROOT, fixture];
    const [cmd, args] = headless
      ? ['xvfb-run', ['-a', '--server-args=-screen 0 1600x1200x24', ELECTRON, ...base]]
      : [ELECTRON, base];
    // Force X11 so Electron renders INTO the xvfb display instead of the host Wayland compositor (otherwise the
    // windows appear on the real screen and outlive the xvfb-run wrapper). Mirrors test/electron-smoke.js.
    const env = { ...process.env, VV_PROFILE: '1', ELECTRON_DISABLE_SECURITY_WARNINGS: '1',
      ELECTRON_OZONE_PLATFORM_HINT: 'x11', GDK_BACKEND: 'x11', XDG_SESSION_TYPE: 'x11' };
    delete env.WAYLAND_DISPLAY;
    const child = spawn(cmd, args, { env, detached: true });   // own process group → kill the whole tree
    groups.add(child.pid);
    const marks = {};
    let settled = false;
    const done = () => {
      if (settled) return; settled = true;
      killGroup(child.pid); groups.delete(child.pid);
      resolve(marks);
    };
    const onData = (buf) => {
      for (const line of buf.toString().split('\n')) {
        const m = line.match(/\[vv-profile\]\s+(\w+)\s+([\w-]+)\s+([\d.]+)/);
        if (m && !(m[2] in marks)) marks[m[2]] = Number(m[3]);   // first occurrence wins
      }
      if ('rendered' in marks) setTimeout(done, 150);
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', done);
    setTimeout(done, 30000);   // hard cap
  });
}

const median = (xs) => { const s = [...xs].sort((a, b) => a - b); return s.length ? s[Math.floor(s.length / 2)] : NaN; };
const pad = (s, n) => String(s).padEnd(n);
const padl = (s, n) => String(s).padStart(n);

console.log(`cold-start profile — ${runs} run(s)/fixture, ${headless ? 'headless (xvfb)' : 'DISPLAY'}\n`);
const summary = [];
for (const fixture of builtin) {
  const perPhase = Object.fromEntries(PHASES.map((p) => [p, []]));
  for (let r = 0; r < runs; r += 1) {
    const marks = await launch(fixture);   // eslint-disable-line no-await-in-loop
    const t0 = marks.entry;
    if (t0 == null) { console.warn(`  ! ${path.basename(fixture)} run ${r}: no marks (build missing?)`); continue; }
    for (const p of PHASES) if (marks[p] != null) perPhase[p].push(marks[p] - t0);
  }
  const meds = Object.fromEntries(PHASES.map((p) => [p, median(perPhase[p])]));
  summary.push([path.basename(fixture), meds]);
  console.log(`── ${path.basename(fixture)} ──`);
  console.log('  ' + PHASES.map((p) => pad(p, 15)).join(''));
  console.log('  ' + PHASES.map((p) => pad(Number.isNaN(meds[p]) ? '·' : `${meds[p].toFixed(0)}ms`, 15)).join(''));
  console.log(`  → time-to-content (entry→rendered): ${Number.isNaN(meds.rendered) ? '·' : meds.rendered.toFixed(0) + 'ms'}\n`);
}

console.log('=== time-to-content (entry→rendered), median ms ===');
for (const [name, meds] of summary) console.log(`  ${pad(name, 14)} ${padl(Number.isNaN(meds.rendered) ? '·' : meds.rendered.toFixed(0), 6)} ms`);

if (!keep) fs.rmSync(tmp, { recursive: true, force: true });
process.exit(0);
