#!/usr/bin/env bash
# H-COMBINED, as registered: X-prefix, X-equal, X-wide, X-mixed at every (T, M) of the rule, F″'s release bundle, in
# node and in Electron's node mode; core 3; the 1-minute load checked (< 12) before each arm.
set -u
J=${SCRATCH:?set SCRATCH to the scratch directory}; L=$J/pairwork/fsec/combined
E=${REPO:?set REPO to the repository}/node_modules/electron/dist/electron
FSEC=$J/wt-pairwork
quiet() { local t0=$(date +%s); until awk -v l="$(cut -d' ' -f1 /proc/loadavg)" 'BEGIN { exit !(l < 12) }'; do
  [ $(( $(date +%s) - t0 )) -gt 10800 ] && { echo "gave up waiting: $(cat /proc/loadavg)" >> $L/summary.txt; exit 1; }; sleep 20; done; }
arm() {   # name, runtime (node|electron), then the bench's own options
  local name=$1 rt=$2; shift 2
  quiet
  echo "$name start $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
  if [ "$rt" = electron ]; then
    ( cd $FSEC && ELECTRON_RUN_AS_NODE=1 VV_BENCH_JSON=$L/$name.json taskset -c 3 $E scripts/bench-note-signature.cjs \
        --bundle=release "$@" > $L/$name.txt 2>&1 )
  else
    ( cd $FSEC && VV_BENCH_JSON=$L/$name.json taskset -c 3 node scripts/bench-note-signature.cjs \
        --bundle=release "$@" > $L/$name.txt 2>&1 )
  fi
  echo "$name EXIT=$? end $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
}
{
  echo "# environment $(date -Is)"
  echo "cpu3 governor: $(cat /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor)"
  echo "F″ release bundle sha256: $(sha256sum $FSEC/dist/cli/vv-cli.js | cut -d' ' -f1)"
  echo "bench sha256: $(sha256sum $FSEC/scripts/bench-note-signature.cjs | cut -d' ' -f1)"
} > $L/environment.txt 2>&1
for T in 32768 65536 131072 262144 524288 1048576; do
  for M in 4096 8192 16384; do
    arm node-T$T-M$M     node     --only=X --chars=$T --matches=$M
    arm electron-T$T-M$M electron --only=X --chars=$T --matches=$M
  done
done
echo DONE >> $L/summary.txt
