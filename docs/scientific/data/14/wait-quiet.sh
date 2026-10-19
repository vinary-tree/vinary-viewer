#!/usr/bin/env bash
# Waits for a quiet machine, then runs, in order and alone: the H-LOAD find-e2e re-run (wt-split), and the
# after-fix H-PAIRWORK bench (wt-pairwork), dev then release, on core 3.
set -u
J=${SCRATCH:?set SCRATCH to the scratch directory}; L=$J/pairwork/quiet
load1() { cut -d' ' -f1 /proc/loadavg; }
t0=$(date +%s)
until awk -v l="$(load1)" 'BEGIN { exit !(l < 12) }'; do
  if [ $(( $(date +%s) - t0 )) -gt 10800 ]; then echo "gave up waiting after 3 h: load $(cat /proc/loadavg)" >> $L/summary.txt; exit 1; fi
  sleep 30
done
echo "quiet at $(date -Is): load $(cat /proc/loadavg)" >> $L/summary.txt
mkdir -p $J/tmpdir/splitf3-d
( cd $J/wt-split && TMPDIR=$J/tmpdir/splitf3-d taskset -c 12-23 timeout 1200 node scripts/run-electron-headless.mjs \
    --width=1400 --height=1000 electron --no-sandbox test/find-e2e.js > $L/find-e2e.log 2>&1 )
echo "find-e2e EXIT=$? at $(date -Is); load $(cat /proc/loadavg)" >> $L/summary.txt
cd $J/wt-pairwork || exit 1
echo "bench start $(date -Is); load $(cat /proc/loadavg)" >> $L/summary.txt
VV_BENCH_JSON=$L/fixed-dev.json taskset -c 3 node scripts/bench-note-signature.cjs > $L/fixed-dev.txt 2>&1
echo "bench dev EXIT=$?" >> $L/summary.txt
VV_BENCH_JSON=$L/fixed-release.json taskset -c 3 node scripts/bench-note-signature.cjs --bundle=release > $L/fixed-release.txt 2>&1
echo "bench release EXIT=$? at $(date -Is); load $(cat /proc/loadavg)" >> $L/summary.txt
echo DONE >> $L/summary.txt
