# F′'s negative controls (ledger: "H-PAIRWORK-FIX′ — E1 for F′ and its NEGATIVE CONTROLS"): one mutation per name of the
# saved F′ notes.cljs (notes-fprime.cljs), written to the worktree. mE3 is m′6 in F′ (the same line), so it is not
# repeated.
import sys, pathlib
src, dst, name = sys.argv[1], sys.argv[2], sys.argv[3]
M = {
 "m1-nan-equal-dropped": ("(or (== x y) (and (not (== x x)) (not (== y y))))", "(== x y)"),
 "m2-signed-zero-hashed": ("(aset bits 0 (if (== v 0) 0 v))", "(aset bits 0 v)"),
 "m3-nan-bits-hashed": ("(let [nan? (not (== v v))", "(let [nan? false"),
 "m4-chain-cut": ("(aset nexts n chain)", "(aset nexts n -1)"),
 "m5-equality-partial": ("""(and (same-index? (aget ca x) a) (same-index? (aget cb x) b)
                                                       (same-index? (aget cc x) c))""",
                         "(and (same-index? (aget ca x) a) (same-index? (aget cb x) b))"),
 "m6-dedup-off": ("""                               (== i (aget last cell))
                               (recur (inc k) n)

""", ""),
 "m7-tables-once": ("""  (let [tables (js/Int32Array. 6144)]
    (.getRandomValues (.-crypto js/globalThis) tables)
    tables))""", """  (or (.-vvTablesOnce js/globalThis)
      (let [tables (js/Int32Array. 6144)]
        (.getRandomValues (.-crypto js/globalThis) tables)
        (set! (.-vvTablesOnce js/globalThis) tables)
        tables)))"""),
 "m8-sweep-strict": ("(if (<= (/ (aget heights mid) hs) bound) (recur (inc mid) hi) (recur lo mid))",
                     "(if (< (/ (aget heights mid) hs) bound) (recur (inc mid) hi) (recur lo mid))"),
 "mE1-lower-bound-dropped": ("lo (prefix-count (aget g 0) hs 0)", "lo 0"),
 "mE2-strict-scale": ("hi (prefix-count (aget g 0) hs max-thumbnail-scale)",
                      "hi (prefix-count (aget g 0) hs (- max-thumbnail-scale 1e-15))"),
 "mE4-cap-off-by-one": ("(> m max-signature-matches) nil", "(>= m max-signature-matches) nil"),
}
if name == "--list":
    print(" ".join(M)); sys.exit(0)
s = pathlib.Path(src).read_text()
old, new = M[name]
assert s.count(old) == 1, (name, s.count(old))
pathlib.Path(dst).write_text(s.replace(old, new))
