'use strict';

// Which git processes does the MAIN process start, and how? The instrument behind docs/scientific/10 §10's S3
// (the Files-filter stall): a synchronous git call blocks main's event loop for as long as git runs.
//
// install() wraps child_process.{spawn, spawnSync, execFile, execFileSync} on the shared module object. It must run
// BEFORE dist/main/main.js is required: main's ClojureScript calls `cp/spawn`, `cp/execFile`, … as property lookups
// at call time, so the wrappers see every call. Only git calls made BY MAIN are recorded, attributed by the caller's
// stack: a frame of main's code — source-mapped to `…/vinary/main/<ns>.cljs` (the dev build maps its frames, so a
// marker on the compiled `cljs-runtime/vinary.main.<ns>.js` name alone saw NOTHING: the first negative control
// counted 0 git calls while main was blocked 2.7 s in execFileSync), the compiled name itself, or the release
// build's dist/main/main.js. A harness's own fixture git commands therefore never count.
//
//   const census = require('./fixtures/spawn-census').install();
//   const from = census.mark();  …  census.summary(from)
//   → { git, sync, async, bySubcommand: {"ls-files": n, …}, peakListingsPerCwd }

const cp = require('child_process');
const path = require('path');

const MAIN_MARKERS = [
  `${path.sep}vinary${path.sep}main${path.sep}`,          // a source-mapped frame of main's code (dev build)
  `cljs-runtime${path.sep}vinary.main.`,                  // the compiled file, unmapped
  `${path.sep}dist${path.sep}main${path.sep}main.js`,     // the release build
];

let installed = null;

function install() {
  if (installed) return installed;
  const calls = [];
  const runningListings = new Map();      // cwd → `ls-files --cached` processes running now
  let peak = new Map();                   // cwd → the most of them at once, since the last mark
  const fromMain = () => {
    const stack = new Error().stack || '';
    return MAIN_MARKERS.some((m) => stack.includes(m));
  };
  const record = (mode, file, args, opts) => {
    if (String(file) !== 'git' || !fromMain()) return null;
    const argv = Array.from(args || [], String);
    const cwd = (opts && opts.cwd) || process.cwd();
    const call = { mode, sub: argv[0] || '', argv, cwd, start: Date.now(), end: null };
    calls.push(call);
    return call;
  };
  const listing = (call) => call && call.sub === 'ls-files' && call.argv.includes('--cached');
  const begin = (call) => {
    if (!listing(call)) return;
    const n = (runningListings.get(call.cwd) || 0) + 1;
    runningListings.set(call.cwd, n);
    peak.set(call.cwd, Math.max(peak.get(call.cwd) || 0, n));
  };
  const end = (call) => {
    if (!call) return;
    call.end = Date.now();
    if (listing(call)) runningListings.set(call.cwd, Math.max(0, (runningListings.get(call.cwd) || 1) - 1));
  };

  const { spawn, spawnSync, execFile, execFileSync } = cp;
  cp.spawn = function spawnCensus(file, args, opts) {
    const call = record('async', file, args, opts);
    begin(call);
    const child = spawn.apply(this, arguments);
    if (call) child.once('close', () => end(call));
    return child;
  };
  cp.execFile = function execFileCensus(file, args, opts) {
    const call = record('async', file, Array.isArray(args) ? args : [], typeof opts === 'object' ? opts : null);
    begin(call);
    const child = execFile.apply(this, arguments);
    if (call) child.once('close', () => end(call));
    return child;
  };
  cp.spawnSync = function spawnSyncCensus(file, args, opts) {
    const call = record('sync', file, args, opts);
    begin(call);
    try { return spawnSync.apply(this, arguments); } finally { end(call); }
  };
  cp.execFileSync = function execFileSyncCensus(file, args, opts) {
    const call = record('sync', file, Array.isArray(args) ? args : [], typeof opts === 'object' ? opts : null);
    begin(call);
    try { return execFileSync.apply(this, arguments); } finally { end(call); }
  };

  installed = {
    calls,
    /** A position to summarize from; also restarts the peak counters. */
    mark() { peak = new Map(); return calls.length; },
    /** Main's git calls since `from` (a mark()). */
    summary(from = 0) {
      const xs = calls.slice(from);
      const bySubcommand = {};
      for (const c of xs) bySubcommand[c.sub] = (bySubcommand[c.sub] || 0) + 1;
      return {
        git: xs.length,
        sync: xs.filter((c) => c.mode === 'sync').length,
        async: xs.filter((c) => c.mode === 'async').length,
        bySubcommand,
        peakListingsPerCwd: Math.max(0, ...peak.values()),
      };
    },
  };
  return installed;
}

module.exports = { install };
