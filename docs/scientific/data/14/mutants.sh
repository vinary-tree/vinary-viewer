#!/usr/bin/env bash
# E1's negative controls: each mutant of the fixed notes.cljs, compiled into the test build, against E1 alone.
set -u
J=${SCRATCH:?set SCRATCH to the scratch directory}; W=$J/wt-pairwork; L=$J/pairwork/mutants; mkdir -p $L
export TMPDIR=$J/tmpdir/pairwork
cd $W || exit 1
: > $L/summary.txt
for m in mE1 mE2 mE3 mE4 mE5; do
  python3 -I $J/pairwork/mutate.py $J/pairwork/notes-fixed.cljs $W/src/vinary/slides/notes.cljs $m || { echo "$m MUTATION NOT APPLIED" >> $L/summary.txt; continue; }
  taskset -c 24-31 npx shadow-cljs compile test > $L/compile-$m.log 2>&1 || { echo "$m COMPILE FAILED" >> $L/summary.txt; continue; }
  taskset -c 24-31 node dist/test/test.js --test=vinary.slides.notes-signature-equivalence-test > $L/unit-$m.log 2>&1; rc=$?
  fails=$(grep -E '^(FAIL|ERROR) in' $L/unit-$m.log | sed -E 's/^(FAIL|ERROR) in \(([^)]*)\).*/\2/' | sort -u | tr '\n' ' ')
  echo "$m EXIT=$rc failing: ${fails:-none}" >> $L/summary.txt
done
cp $J/pairwork/notes-fixed.cljs $W/src/vinary/slides/notes.cljs
taskset -c 24-31 npx shadow-cljs compile test > $L/compile-restored.log 2>&1
cmp -s $J/pairwork/notes-fixed.cljs $W/src/vinary/slides/notes.cljs && echo "restored the fixed notes.cljs; recompiled EXIT=$?" >> $L/summary.txt
echo DONE >> $L/summary.txt
