(ns vinary.renderer.diff-view
  "The diff view's collapse applier (ADR-0037). The per-file `<details class=\"vv-diff-file\">` wrappers
   live inside an innerHTML-rendered body (ADR-0003), which is rebuilt WHOLESALE on every live-refresh
   remount and split-enrichment swap — so their open flags cannot be DOM-owned. The per-tab collapsed set
   (nav :diff-collapsed) is the single source of truth, and this applier projects it onto whatever DOM is
   currently mounted: called synchronously by markdown-body right after set-inner! (before paint — no
   expanded-flash, and before the rAF'd scroll restore measures the layout) and by the
   :diff/apply-collapsed fx on every state change (toggle / collapse-all / expand-all / TOC auto-expand)."
  )

(defn apply-collapsed!
  "Set every `details.vv-diff-file`'s open flag under `root` (default: the content pane) from the
   collapsed-id set. Idempotent; a cheap no-op on non-diff bodies (one empty querySelectorAll). Each
   file's id is read from its child `summary.vv-diff-file-head` — the one canonical DOM id location —
   so the nested, uncontrolled `vv-diff-gap` details (their summaries carry a different class) are
   never touched."
  ([collapsed] (apply-collapsed! (.querySelector js/document ".vv-content") collapsed))
  ([^js root collapsed]
   (when root
     (let [nodes (.querySelectorAll root "details.vv-diff-file")]
       (dotimes [i (.-length nodes)]
         (let [^js d (aget nodes i)
               id    (some-> (.querySelector d "summary.vv-diff-file-head") .-id)]
           (set! (.-open d) (not (contains? (or collapsed #{}) id)))))))))
