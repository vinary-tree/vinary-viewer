# F″'s negative controls (ledger: "H-PAIRWORK-FIX″ — design F″, pre-registered"): one mutation per name of the saved
# F″ notes.cljs (notes-fsec.cljs), written to the worktree. m″1–m″7 target the sort, the search, the groups and the
# text cap; m′1–m′8 and mE1, mE2, mE4 are F′'s, their lines adapted where by-height's changed (mE1, mE2).
import sys, pathlib
M = {
 "mpp1-lookup-first-unit": ("c   (compare s (aget strings mid))",
                            "c   (compare (subs s 0 1) (subs (aget strings mid) 0 1))"),
 "mpp2-sort-string-only": ("(or (neg? c) (and (zero? c) (<= (aget hs a) (aget hs b))))", "(not (pos? c))"),
 "mpp3-group-end-short": ("to   (aget starts (inc g))", "to   (dec (aget starts (inc g)))"),
 "mpp4-text-cap-strict": ("(<= (page-chars slide) max-signature-chars) (<= (page-chars note) max-signature-chars)",
                          "(< (page-chars slide) max-signature-chars) (< (page-chars note) max-signature-chars)"),
 "mpp5-text-cap-upright": ("(reduce (fn [n {s :str}] (if (string? s) (+ n (.-length s)) n)) 0 runs)",
                           "(reduce (fn [n {s :str :as r}] (if (and (string? s) (upright? r)) (+ n (.-length s)) n)) 0 runs)"),
 "mpp6-text-cap-slide": ("(<= (page-chars slide) max-signature-chars) (<= (page-chars note) max-signature-chars))",
                         "(<= (page-chars slide) max-signature-chars))"),
 "mpp7-merge-pass-short": ("(if (>= width n)", "(if (>= (* 2 width) n)"),
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
 "mE1-lower-bound-dropped": ("lo   (prefix-count gh from to hs 0)", "lo   from"),
 "mE2-strict-scale": ("hi   (prefix-count gh from to hs max-thumbnail-scale)",
                      "hi   (prefix-count gh from to hs (- max-thumbnail-scale 1e-15))"),
 "mE4-cap-off-by-one": ("(> m max-signature-matches) nil", "(>= m max-signature-matches) nil"),
}
if len(sys.argv) > 1 and sys.argv[-1] == "--list":
    print(" ".join(M)); sys.exit(0)
src, dst, name = sys.argv[1], sys.argv[2], sys.argv[3]
s = pathlib.Path(src).read_text()
old, new = M[name]
assert s.count(old) == 1, (name, s.count(old))
pathlib.Path(dst).write_text(s.replace(old, new))
