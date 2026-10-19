#!/usr/bin/env bash
# H-FLOOD / H-FAR, as registered: the reference (wt-ref, HEAD 4c7f35a: notes.cljs as at 2b78f18) and design F
# (wt-pairwork), release then dev, cases C, rand, flood and far; core 3; the load checked (< 12) before each arm.
set -u
J=${SCRATCH:?set SCRATCH to the scratch directory}; L=$J/pairwork/flood
quiet() { local t0=$(date +%s); until awk -v l="$(cut -d' ' -f1 /proc/loadavg)" 'BEGIN { exit !(l < 12) }'; do
  [ $(( $(date +%s) - t0 )) -gt 10800 ] && { echo "gave up waiting: $(cat /proc/loadavg)" >> $L/summary.txt; exit 1; }; sleep 20; done; }
for build in ref F; do
  case $build in ref) W=$J/wt-ref ;; F) W=$J/wt-pairwork ;; esac
  for arm in release dev; do
    quiet
    echo "$build $arm start $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
    ( cd $W && VV_BENCH_JSON=$L/$build-$arm.json taskset -c 3 node scripts/bench-note-signature.cjs --bundle=$arm \
        --only=C,rand,flood,far > $L/$build-$arm.txt 2>&1 )
    echo "$build $arm EXIT=$? end $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
  done
done
quiet
echo "probe start $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
taskset -c 3 node $L/probe-string-hash.cjs > $L/probe-string-hash.txt 2>&1
echo "probe EXIT=$? end $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
echo DONE >> $L/summary.txt
