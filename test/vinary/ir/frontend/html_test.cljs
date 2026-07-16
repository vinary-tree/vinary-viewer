(ns vinary.ir.frontend.html-test
  "Phase 4 / ADR-0029 — the HTML consistency FACET. A local .html document keeps rendering in the browser view,
   but its source also flows through the SHARED html->ir (rehype-raw + the one sanitize schema + rehype-slug)
   into the common IR, so its Contents outline is produced by the SAME ir.capability.toc/toc-of every other
   format uses — one slug policy, one outline path — replacing web-preload's ad-hoc querySelectorAll outline
   and giving HTML the shared content-pane navigation. DOM-free: this is the additive IR facet the GUI wiring
   consumes; the live page render is unchanged. (The office front-end already IS the shared html->ir, so HTML
   reuses it rather than duplicating a parse path.)"
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.office :as html-fe]
            [vinary.ir.capability.toc :as toc]))

(deftest html-outline-via-shared-spine
  (testing "an .html source → shared html->ir → toc-of yields the same {:level :text :id} outline as md/office,
            with rehype-slug ids (added post-sanitize) so Contents-click + scroll-spy can share one id space"
    (let [html "<h1>Intro</h1><p>body</p><h2>Details</h2><h2>More</h2>"]
      (is (= [{:level 1 :text "Intro"   :id "intro"}
              {:level 2 :text "Details" :id "details"}
              {:level 2 :text "More"    :id "more"}]
             (toc/toc-of (html-fe/html->ir html)))))))

(deftest html-facet-shares-the-sanitize-seam
  (testing "the HTML facet passes through the ONE sanitize schema (same G3 seam as every format): a heading
            survives with its slug id; a <script> is stripped from the IR"
    (let [ir (html-fe/html->ir "<h1>ok</h1><script>alert(1)</script>")]
      (is (= [{:level 1 :text "ok" :id "ok"}] (toc/toc-of ir)))
      (is (empty? (filter #(= "script" (:tag (node/node-meta %))) (node/preorder ir)))
          "sanitize removed the <script> node"))))
