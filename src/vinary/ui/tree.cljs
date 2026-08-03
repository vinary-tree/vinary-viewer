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
            [vinary.app.projects :as projects]
            [vinary.app.tree-state :as tree-state]
            [vinary.ui.icons :as icons]
            [vinary.ui.platform :as platform]
            [vinary.ui.text-input :as text-input]))

(defn- ctx!
  "An :on-context-menu handler that opens the themed context menu for a tree target (a file or directory)."
  [kind root path]
  (fn [^js e]
    (.preventDefault e) (.stopPropagation e)
    (rf/dispatch [:context-menu/show {:x (.-clientX e) :y (.-clientY e)
                                      :target {:kind kind :root root :path path}}])))

(defn- summary-click! [root directory open?]
  (fn [^js e]
    ;; Native <details> toggles immediately. Cancel it so a closed directory stays closed until main's
    ;; Promise resolves and one re-frame event commits BOTH its fresh subtree and its open state.
    (.preventDefault e)
    (.stopPropagation e)
    (rf/dispatch [(if open? :tree/collapse :tree/expand) root directory])))

(defn- display-name [root name]
  ;; Target-daemon tree paths are URI-encoded by segment so clicking always yields a valid ssh:// URI. Keep
  ;; that encoded identity in :file/:data-path, but show the actual filesystem name in the tree.
  (if (re-find #"(?i)^s(?:sh|ftp)://" (str root))
    (try (js/decodeURIComponent name) (catch :default _ name))
    name))

(defn- nodes->hiccup [children root active expanded expanding dir-prefix]
  (into [:<>]
        (for [[k v] (sort-by (fn [[k v]] [(if (:children v) 0 1)
                                           (str/lower-case (display-name root k))]) children)]
          ^{:key k}
          (if (:children v)
            (let [dpath    (projects/join-path dir-prefix k)
                  scope    [root dpath]
                  open?    (contains? expanded scope)
                  pending? (contains? expanding scope)]
              [:details.vv-dir {:open      open?
                                :data-root root
                                :data-path dpath}
               [:summary.vv-dir-name
                {:class           (when pending? "vv-dir-refreshing")
                 :aria-busy       (boolean pending?)
                 :on-click        (summary-click! root dpath open?)
                 :on-context-menu (ctx! :dir root dpath)}
                (icons/folder-icon) (display-name root k)]
               (nodes->hiccup (:children v) root active expanded expanding dpath)])
            (let [full (projects/join-path root (:file v))]
              ;; open (single click on Linux, double on Windows/macOS); Ctrl+click → new tab; right-click → menu
              [:a.vv-file {:class            (when (projects/same-path? full active) "vv-file-active")
                           :data-path        full
                           :title            full
                           :on-click         (fn [^js e]
                                               (when (or (.-ctrlKey e) (platform/single-click-open?))
                                                 (rf/dispatch [(if (.-ctrlKey e) :doc/open-new :doc/open) full])))
                           :on-double-click  (fn [^js e]
                                               (when-not (platform/single-click-open?)
                                                 (rf/dispatch [(if (.-ctrlKey e) :doc/open-new :doc/open) full])))
                           :on-context-menu  (ctx! :file root full)}
               (icons/file-icon k) (display-name root k)])))))

;; `nodes` and `filtered?` arrive already computed from the :tree/filtered subscription — folding paths
;; into a tree is a model question, and doing it here meant redoing it on every keystroke (ADR-0033).
(defn- project-tree [{:keys [root nodes]} active expanded expanding]
  (let [scope    [root root]
        open?    (contains? expanded scope)
        pending? (contains? expanding scope)]
    [:details.vv-project {:open open? :data-root root :data-path root}
     ;; :project (not :dir) — the header's menu can remove the project, which a directory node's cannot
     [:summary.vv-project-name
      {:class           (when pending? "vv-dir-refreshing")
       :aria-busy       (boolean pending?)
       :on-click        (summary-click! root root open?)
       :on-context-menu (ctx! :project root root)}
      (icons/folder-icon) (display-name root
                                       (last (remove str/blank?
                                                     (str/split (projects/normalized-path root) #"/"))))]
     (nodes->hiccup nodes root active expanded expanding root)]))

(defn- file-tree-view [_shown _active _projects _filter _open _expanding _expanded]
  (let [seen-path     (atom ::init)
        seen-expanded (atom ::init)]
    (r/create-class
     {:display-name           "vv-file-tree"
      :component-did-mount     (fn [this]
                                 (let [[_ _shown active _projects _filter _open _expanding expanded]
                                       (r/argv this)]
                                   (reset! seen-path active)
                                   (reset! seen-expanded expanded)
                                   (rf/dispatch [:tree/sync-expanded expanded])
                                   (rf/dispatch [:tree/reveal-active])))
      ;; Active-path changes are a second reactive trigger. Project arrival is not inferred here by comparing
      ;; render values: :tree/received explicitly schedules the same post-render effect in the event graph.
      :component-did-update    (fn [this]
                                 (let [[_ _shown active _projects _filter _open _expanding expanded]
                                       (r/argv this)]
                                   (when (not= active @seen-path)
                                     (reset! seen-path active)
                                     (rf/dispatch [:tree/reveal-active]))
                                   (when (not= expanded @seen-expanded)
                                     (reset! seen-expanded expanded)
                                     (rf/dispatch [:tree/sync-expanded expanded]))))
      :component-will-unmount  (fn [_] (rf/dispatch [:tree/sync-expanded #{}]))
      :reagent-render
      (fn [shown active projects filter-value _open expanding expanded]
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
           (for [p shown] ^{:key (:root p)} [project-tree p active expanded expanding])
           [:div.vv-sidebar-empty "No files open"])])})))

(defn file-tree []
  (let [shown     @(rf/subscribe [:tree/filtered])
        projects  @(rf/subscribe [:ui/projects])
        open      @(rf/subscribe [:ui/tree-open])
        expanding @(rf/subscribe [:ui/tree-expanding])
        expanded  (tree-state/effective-expanded projects shown open expanding)]
    [file-tree-view
     shown
     @(rf/subscribe [:ui/active-path])
     projects
     @(rf/subscribe [:ui/tree-filter])
     open
     expanding
     expanded]))
