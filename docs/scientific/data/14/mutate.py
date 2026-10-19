import sys, pathlib
src, dst, name = sys.argv[1], sys.argv[2], sys.argv[3]
s = pathlib.Path(src).read_text()
M = {
 "mE1": ("lo (prefix-count (aget g 0) hs 0)", "lo 0"),
 "mE2": ("hi (prefix-count (aget g 0) hs max-thumbnail-scale)", "hi (prefix-count (aget g 0) hs (- max-thumbnail-scale 1e-15))"),
 "mE3": ("""                            (== i (aget last cell))
                            (recur (inc k) n)

""", ""),
 "mE4": ("(> m max-signature-matches) nil", "(>= m max-signature-matches) nil"),
 "mE5": ("(+ (* (+ a 65536) 17179869184) (* (+ b 65536) 131072) (+ c 65536))",
         "(+ (* (+ a 65536) 4294967296) (* (+ b 65536) 65536) (+ c 65536))"),
}
old, new = M[name]
assert s.count(old) == 1, (name, s.count(old))
pathlib.Path(dst).write_text(s.replace(old, new))
