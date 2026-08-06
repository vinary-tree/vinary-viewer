(ns vinary.renderer.diff-view
  "The diff view's collapse applier (ADR-0037). The per-file `<details class=\"vv-diff-file\">` wrappers
   live inside an innerHTML-rendered body (ADR-0003), which is rebuilt WHOLESALE on every live-refresh
   remount and split-enrichment swap — so their open flags cannot be DOM-owned. The per-tab collapsed set
   (nav :diff-collapsed) is the single source of truth, and this applier projects it onto whatever DOM is
   currently mounted: called synchronously by markdown-body right after set-inner! (before paint — no
   expanded-flash, and before the rAF'd scroll restore measures the layout) and by the
   :diff/apply-collapsed fx on every state change (toggle / collapse-all / expand-all / TOC auto-expand).

   This namespace also owns the renderer-only enhancement of the pure diff model with tree-sitter token
   segments, and projects main-resolved file targets onto the header's inert anchor placeholders."
  (:require [clojure.string :as str]
            [vinary.renderer.syntax :as syntax]
            [vinary.app.link :as link]))

(defn- side-line-refs
  "The visible source lines belonging to `side`, in display order, with their stable paths into a parsed file.
   Context belongs to both sides; deletes only to old, inserts only to new."
  [file side]
  (vec
   (for [[hi hunk] (map-indexed vector (:hunks file))
         [li line] (map-indexed vector (:lines hunk))
         :when (case side :old (not= :insert (:kind line)) :new (not= :delete (:kind line)))]
     {:line-path [:hunks hi :lines li]
      :text (:text line)})))

(defn- highlight-side [path refs]
  (if (and path (seq refs))
    (syntax/highlight-path-lines path (mapv :text refs))
    (js/Promise.resolve nil)))

(defn highlight-model
  "Return a Promise of `model` with fixed-class syntax segments attached to every visible hunk line as
   `:old-syntax` / `:new-syntax`. Each file side is parsed as one logical buffer, so multiline captures span
   displayed lines without parsing every line independently. Old and new paths deliberately resolve grammars
   separately (a rename can change language). Missing/failed grammars leave the parsed model unchanged."
  [model]
  (let [jobs
        (into-array
         (map-indexed
          (fn [fi file]
            (let [old-refs (side-line-refs file :old)
                  new-refs (side-line-refs file :new)]
              (-> (js/Promise.all
                   #js [(highlight-side (:old-path file) old-refs)
                        (highlight-side (:new-path file) new-refs)])
                  (.then (fn [results]
                           {:file-index fi
                            :old-refs old-refs :old-lines (aget results 0)
                            :new-refs new-refs :new-lines (aget results 1)})))))
          (:files model)))]
    (-> (js/Promise.all jobs)
        (.then
         (fn [results]
           (reduce
            (fn [m {:keys [file-index old-refs old-lines new-refs new-lines]}]
              (let [attach (fn [acc side refs lines]
                             (if (seq lines)
                               (reduce (fn [a [ref segments]]
                                         (assoc-in a (into [:files file-index] (conj (:line-path ref) side)) segments))
                                       acc
                                       (map vector refs lines))
                               acc))]
                (-> m
                    (attach :old-syntax old-refs old-lines)
                    (attach :new-syntax new-refs new-lines))))
            model
            (array-seq results)))))))

(defn highlight-sources
  "Highlight each resolved full NEW-file source for both of that file section's path grammars. The Split
   renderer uses the new grammar for new cells and the old grammar for unchanged old cells; changed/deleted old
   cells still use hunk syntax because the complete old file is unavailable. Returns Promise<{new-path
   {:old line-segments :new line-segments}}>."
  [model sources]
  (let [jobs
        (into-array
         (keep (fn [{:keys [old-path new-path]}]
                 (when-let [source (and new-path (get sources new-path))]
                   (let [lines (str/split source #"\n" -1)]
                     (-> (js/Promise.all
                          #js [(if old-path (syntax/highlight-path-lines old-path lines) (js/Promise.resolve nil))
                               (syntax/highlight-path-lines new-path lines)])
                         (.then (fn [results]
                                  [new-path {:old (aget results 0) :new (aget results 1)}]))))))
               (:files model)))]
    (-> (js/Promise.all jobs)
        (.then (fn [results] (into {} (array-seq results)))))))

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

(defn apply-targets!
  "Activate only the diff-header path placeholders whose diff-relative path main resolved to an existing local
   or SSH/SFTP file. Removing stale href/title attributes is as important as adding them: live refresh can make
   a formerly-resolved path disappear. The target path itself comes from main; diff bytes only choose the lookup
   key carried in the escaped `data-vv-diff-path` attribute."
  ([targets] (apply-targets! (.querySelector js/document ".vv-content") targets))
  ([^js root targets]
   (when root
     (let [nodes (.querySelectorAll root "a.vv-diff-file-path[data-vv-diff-path]")]
       (dotimes [i (.-length nodes)]
         (let [^js a (aget nodes i)
               rel   (.getAttribute a "data-vv-diff-path")
               target (get (or targets {}) rel)
               href   (some-> target link/href-for-path)]
           (if href
             (do (.setAttribute a "href" href)
                 (.setAttribute a "title" (str "Open " target)))
             (do (.removeAttribute a "href")
                 (.removeAttribute a "title")))))))))
