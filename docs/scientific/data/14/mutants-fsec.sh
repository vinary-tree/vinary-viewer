#!/usr/bin/env bash
# F″'s negative controls: each mutant of F″'s notes.cljs (the saved notes-fsec.cljs), compiled into the test build,
# against E1 alone; then F″ restored (compared byte for byte) and recompiled, and E1 run on it (the control).
set -u
J=${SCRATCH:?set SCRATCH to the scratch directory}; W=$J/wt-pairwork; L=$J/pairwork/fsec/mutants; mkdir -p $L
export TMPDIR=$J/tmpdir/pairwork
cd $W || exit 1
cmp -s $J/pairwork/notes-fsec.cljs $W/src/vinary/slides/notes.cljs || { echo "the tree is not the saved F″" > $L/summary.txt; exit 1; }
: > $L/summary.txt
for m in $(python3 -I $J/pairwork/mutate-fsec.py --list); do
  python3 -I $J/pairwork/mutate-fsec.py $J/pairwork/notes-fsec.cljs $W/src/vinary/slides/notes.cljs $m || { echo "$m MUTATION NOT APPLIED" >> $L/summary.txt; continue; }
  taskset -c 24-31 npx shadow-cljs compile test > $L/compile-$m.log 2>&1 || { echo "$m COMPILE FAILED" >> $L/summary.txt; continue; }
  taskset -c 24-31 node dist/test/test.js --test=vinary.slides.notes-signature-equivalence-test > $L/unit-$m.log 2>&1; rc=$?
  fails=$(grep -E '^(FAIL|ERROR) in' $L/unit-$m.log | sed -E 's/^(FAIL|ERROR) in \(([^)]*)\).*/\2/' | sort -u | tr '\n' ' ')
  echo "$m EXIT=$rc failing: ${fails:-none}" >> $L/summary.txt
done
cp $J/pairwork/notes-fsec.cljs $W/src/vinary/slides/notes.cljs
taskset -c 24-31 npx shadow-cljs compile test > $L/compile-restored.log 2>&1; rc=$?
cmp -s $J/pairwork/notes-fsec.cljs $W/src/vinary/slides/notes.cljs && echo "restored F″'s notes.cljs; recompiled EXIT=$rc" >> $L/summary.txt
taskset -c 24-31 node dist/test/test.js --test=vinary.slides.notes-signature-equivalence-test > $L/unit-control.log 2>&1
echo "control (F″ restored) EXIT=$? $(grep -E '^Ran|failures' $L/unit-control.log | tr '\n' ' ')" >> $L/summary.txt
echo DONE >> $L/summary.txt
