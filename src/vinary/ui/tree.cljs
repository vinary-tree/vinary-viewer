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
            [vinary.ui.platform :as platform]))

(defn- build-tree
  "Flat repo-relative paths → nested map. A folder node has :children; a file node has :file."
  [files]
  (reduce (fn [acc f]
            (let [parts (str/split f #"/")
                  ks    (concat (interpose :children parts) [:file])]
              (assoc-in acc ks f)))
          {} files))

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

(defn- project-tree [{:keys [root files]} active ql]
  (let [shown (cond->> files ql (filter #(str/includes? (str/lower-case %) ql)))]
    (when (seq shown)
      [:details.vv-project {:open true}
       ;; :project (not :dir) — the header's menu can remove the project, which a directory node's cannot
       [:summary.vv-project-name {:on-context-menu (ctx! :project root)} (icons/folder-icon) (last (str/split root #"/"))]
       (nodes->hiccup (build-tree shown) root active (boolean ql) root)])))

(defn- reveal-active!
  "Expand the ancestor <details> of the active file (additive — never collapses other folders) and scroll
   it into view. Reagent leaves an imperatively-set <details>.open alone when the hiccup omits :open."
  [^js root-el]
  (when root-el
    (when-let [^js a (.querySelector root-el ".vv-file-active")]
      (loop [el (.-parentNode a)]
        (when (and el (not (identical? el root-el)))
          (when (= "DETAILS" (.-tagName el)) (set! (.-open el) true))
          (recur (.-parentNode el))))
      (.scrollIntoView a #js {:block "nearest"}))))

(defn file-tree []
  (let [root-el (atom nil)]
    (r/create-class
     {:display-name           "vv-file-tree"
      :component-did-mount     (fn [_] (reveal-active! @root-el))
      :component-did-update    (fn [_] (reveal-active! @root-el))   ; re-renders on every active-path change
      :reagent-render
      (fn []
        (let [projects @(rf/subscribe [:ui/projects])
              active   @(rf/subscribe [:ui/active-path])
              q        @(rf/subscribe [:ui/tree-filter])
              ql       (some-> q str/trim str/lower-case not-empty)]
          [:div.vv-tree {:ref (fn [el] (reset! root-el el))}
           [:input.vv-tree-filter
            {:placeholder "Filter files…"
             :value       (or q "")
             :on-focus    #(rf/dispatch [:input/set-in-input true])
             :on-blur     #(rf/dispatch [:input/set-in-input false])
             :on-change   #(rf/dispatch [:tree/filter (.. % -target -value)])}]
           (if (seq projects)
             (for [p projects] ^{:key (:root p)} [project-tree p active ql])
             [:div.vv-sidebar-empty "No files open"])]))})))
