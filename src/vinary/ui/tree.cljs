(ns vinary.ui.tree
  "The file-tree (the sidebar's Files tab). Main sends {:root :files :synthetic?} per open project — a git
   repository, or (:synthetic?) the containing directory of a file that belongs to none (ADR-0030). We keep
   one collapsible tree per project (rooted at the project-directory name) and fold each project's flat
   root-relative paths into a nested folder/file tree of native <details>. A filter narrows across all
   projects. Left-click navigates the active tab; Ctrl+click opens a new tab. On every activation the
   active file's ancestor folders auto-expand and it scrolls into view (reveal-active!, additive)."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [vinary.ui.icons :as icons]
            [vinary.ui.platform :as platform]
            [vinary.ui.text-input :as text-input]
            [vinary.renderer.tree-reveal :as tree-reveal]))

(defn- ctx!
  "An :on-context-menu handler that opens the themed context menu for a tree target (a file or directory)."
  [kind path]
  (fn [^js e]
    (.preventDefault e) (.stopPropagation e)
    (rf/dispatch [:context-menu/show {:x (.-clientX e) :y (.-clientY e) :target {:kind kind :path path}}])))

(defn- nodes->hiccup [children root active open? dir-prefix]
  (into [:<>]
        (for [[k v] (sort-by (fn [[k v]] [(if (:children v) 0 1) (str/lower-case k)]) children)]
          ^{:key k}
          (if (:children v)
            (let [dpath (str dir-prefix "/" k)]
              [:details.vv-dir (when open? {:open true})
               [:summary.vv-dir-name {:on-context-menu (ctx! :dir dpath)} (icons/folder-icon) k]
               (nodes->hiccup (:children v) root active open? dpath)])
            (let [full (str root "/" (:file v))]
              ;; open (single click on Linux, double on Windows/macOS); Ctrl+click → new tab; right-click → menu
              [:a.vv-file {:class            (when (= full active) "vv-file-active")
                           :data-path        full
                           :title            full
                           :on-click         (fn [^js e]
                                               (when (or (.-ctrlKey e) (platform/single-click-open?))
                                                 (rf/dispatch [(if (.-ctrlKey e) :doc/open-new :doc/open) full])))
                           :on-double-click  (fn [^js e]
                                               (when-not (platform/single-click-open?)
                                                 (rf/dispatch [(if (.-ctrlKey e) :doc/open-new :doc/open) full])))
                           :on-context-menu  (ctx! :file full)}
               (icons/file-icon k) k])))))

;; `nodes` and `filtered?` arrive already computed from the :tree/filtered subscription — folding paths
;; into a tree is a model question, and doing it here meant redoing it on every keystroke (ADR-0033).
(defn- project-tree [{:keys [root nodes filtered?]} active]
  [:details.vv-project {:open true}
   ;; :project (not :dir) — the header's menu can remove the project, which a directory node's cannot
   [:summary.vv-project-name {:on-context-menu (ctx! :project root)} (icons/folder-icon) (last (str/split root #"/"))]
   (nodes->hiccup nodes root active filtered? root)])

(defn- file-tree-view [_shown _active _projects _filter]
  (let [seen-path (atom ::init)]
    (r/create-class
     {:display-name           "vv-file-tree"
      :component-did-mount     (fn [this]
                                 (let [[_ _shown active] (r/argv this)]
                                   (reset! seen-path active)
                                   (tree-reveal/schedule!)))
      ;; Active-path changes are a second reactive trigger. Project arrival is not inferred here by comparing
      ;; render values: :tree/received explicitly schedules the same post-render effect in the event graph.
      :component-did-update    (fn [this]
                                 (let [[_ _shown active] (r/argv this)]
                                   (when (not= active @seen-path)
                                     (reset! seen-path active)
                                     (tree-reveal/schedule!))))
      :reagent-render
      (fn [shown active projects filter-value]
        [:div.vv-tree
         ;; async-input: the filter's DOM value is owned locally, so a re-render carrying a debounced
         ;; (and therefore older) query can never take a character back out of the box. This field is
         ;; where that defect was caught red-handed — typing `views` produced `vews`, with the tracer
         ;; recording the write as `vi` → `v` (ADR-0033, docs/scientific/10).
         [text-input/async-input
          {:value     (or filter-value "")
           :on-change #(rf/dispatch [:tree/filter %])
           ;; :in-input? is no longer mirrored per-component — one document-level focusin/focusout
           ;; tracker owns it (renderer.core/focus-tracker!) and the keymap resolver derives its own
           ;; from document.activeElement. Hand-mirroring leaked: this input unmounts when the sidebar
           ;; collapses, and Chromium fires no blur for a removed element, so the flag stuck true and
           ;; swallowed every bare-key binding. ADR-0032.
           :attrs     {:class "vv-tree-filter" :placeholder "Filter files…"}}]
         (if (seq projects)
           (for [p shown] ^{:key (:root p)} [project-tree p active])
           [:div.vv-sidebar-empty "No files open"])])})))

(defn file-tree []
  [file-tree-view
   @(rf/subscribe [:tree/filtered])
   @(rf/subscribe [:ui/active-path])
   @(rf/subscribe [:ui/projects])
   @(rf/subscribe [:ui/tree-filter])])
