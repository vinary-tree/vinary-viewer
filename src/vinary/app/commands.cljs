(ns vinary.app.commands
  "The command registry — the 'API' that keybindings and the command palette dispatch through. Each
   command is reified data {:id :title :category :dispatch|:handler :when :arg :prompt}; `run` resolves
   a command id against the current resolution context and dispatches the corresponding re-frame event.
   (Command pattern: invokers — keymap, palette, mouse — are decoupled from the actions.)"
  (:require [re-frame.core :as rf]))

;; predicate registry: ctx -> bool. The resolver assembles ctx from subs so predicates stay pure.
(def predicates
  {:always        (constantly true)
   :has-tabs      (fn [ctx] (pos? (count (:tabs ctx))))
   :can-back?     (fn [ctx] (boolean (:can-back? ctx)))
   :can-forward?  (fn [ctx] (boolean (:can-forward? ctx)))
   :find-visible? (fn [ctx] (boolean (:find-visible? ctx)))
   :palette-open? (fn [ctx] (boolean (:palette-open? ctx)))
   :not-in-input? (fn [ctx] (not (:in-input? ctx)))})

;; id -> command spec
(def registry
  {;; ---- Tabs ----
   :tab/next  {:id :tab/next  :title "Next tab"     :category "Tabs" :dispatch [:tab/next] :when :has-tabs}
   :tab/prev  {:id :tab/prev  :title "Previous tab" :category "Tabs" :dispatch [:tab/prev] :when :has-tabs}
   :tab/close {:id :tab/close :title "Close tab"    :category "Tabs" :dispatch [:tab/close-active] :when :has-tabs}
   ;; ---- File ----
   :file/open            {:id :file/open :title "Find file…" :category "File" :prompt :file}
   :file/open-dialog     {:id :file/open-dialog :title "Open file…" :category "File" :dispatch [:file/open-dialog]}
   :file/open-in-new-tab {:id :file/open-in-new-tab :title "Open file in new tab…" :category "File" :prompt :file}
   :file/reveal-in-tree  {:id :file/reveal-in-tree :title "Reveal in tree" :category "File" :dispatch [:sidebar/reveal]}
   :doc/reload           {:id :doc/reload :title "Reload" :category "File" :dispatch [:tab/reload]}
   ;; ---- Navigation ----
   :history/back      {:id :history/back :title "Back" :category "Navigation" :dispatch [:history/back] :when :can-back?}
   :history/forward   {:id :history/forward :title "Forward" :category "Navigation" :dispatch [:history/forward] :when :can-forward?}
   :nav/scroll-down   {:id :nav/scroll-down :title "Scroll down" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy 80}}
   :nav/scroll-up     {:id :nav/scroll-up :title "Scroll up" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy -80}}
   :nav/page-down     {:id :nav/page-down :title "Page down" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy :page}}
   :nav/page-up       {:id :nav/page-up :title "Page up" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy :-page}}
   :nav/half-page-down {:id :nav/half-page-down :title "Half page down" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy :half}}
   :nav/half-page-up  {:id :nav/half-page-up :title "Half page up" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy :-half}}
   :nav/scroll-top    {:id :nav/scroll-top :title "Go to top" :category "Navigation" :dispatch [:nav/scroll] :arg {:to :top}}
   :nav/scroll-bottom {:id :nav/scroll-bottom :title "Go to bottom" :category "Navigation" :dispatch [:nav/scroll] :arg {:to :bottom}}
   :focus/sidebar {:id :focus/sidebar :title "Focus sidebar" :category "Navigation" :dispatch [:nav/focus :tree]}
   :focus/content {:id :focus/content :title "Focus content" :category "Navigation" :dispatch [:nav/focus :content]}
   :focus/toggle  {:id :focus/toggle :title "Toggle focus" :category "Navigation" :dispatch [:nav/focus :toggle]}
   :tree/down {:id :tree/down :title "Tree down" :category "Navigation" :dispatch [:tree/move 1]}
   :tree/up   {:id :tree/up :title "Tree up" :category "Navigation" :dispatch [:tree/move -1]}
   :tree/open {:id :tree/open :title "Open tree selection" :category "Navigation" :dispatch [:tree/activate]}
   ;; ---- Search ----
   :search/start {:id :search/start :title "Find" :category "Search" :dispatch [:find/toggle]}
   :search/next  {:id :search/next :title "Find next" :category "Search" :dispatch [:find/cycle 1] :when :find-visible?}
   :search/prev  {:id :search/prev :title "Find previous" :category "Search" :dispatch [:find/cycle -1] :when :find-visible?}
   :search/close {:id :search/close :title "Close find" :category "Search" :dispatch [:find/close] :when :find-visible?}
   :palette/open  {:id :palette/open :title "Command palette" :category "Search" :dispatch [:palette/open {:source :command}]}
   :palette/files {:id :palette/files :title "Find file" :category "Search" :dispatch [:palette/open {:source :file}]}
   ;; ---- View ----
   :sidebar/toggle {:id :sidebar/toggle :title "Toggle sidebar" :category "View" :dispatch [:sidebar/toggle]}
   :theme/cycle    {:id :theme/cycle :title "Cycle theme" :category "View" :dispatch [:theme/cycle]}
   :theme/pick     {:id :theme/pick :title "Choose theme…" :category "View" :prompt :theme}
   :view/zoom-in    {:id :view/zoom-in :title "Zoom in" :category "View" :dispatch [:view/zoom 1]}
   :view/zoom-out   {:id :view/zoom-out :title "Zoom out" :category "View" :dispatch [:view/zoom -1]}
   :view/zoom-reset {:id :view/zoom-reset :title "Reset zoom" :category "View" :dispatch [:view/zoom 0]}
   :view/devtools   {:id :view/devtools :title "Developer tools" :category "View" :dispatch [:view/devtools]}
   ;; ---- Settings / Help ----
   :settings/open  {:id :settings/open :title "Preferences…" :category "Settings" :dispatch [:settings/open]}
   :about/open     {:id :about/open :title "About vinary-viewer" :category "Help" :dispatch [:about/open]}
   ;; ---- Mode (vim) ----
   :mode/normal  {:id :mode/normal :title "Normal mode" :category "Mode" :dispatch [:input/set-mode :normal]}
   :mode/insert  {:id :mode/insert :title "Insert mode" :category "Mode" :dispatch [:input/set-mode :insert]}
   :mode/visual  {:id :mode/visual :title "Visual mode" :category "Mode" :dispatch [:input/set-mode :visual]}
   :mode/ex      {:id :mode/ex :title "Command line" :category "Mode" :dispatch [:palette/open {:source :command :prefix ":"}]}
   :input/escape {:id :input/escape :title "Escape / cancel" :category "Mode" :dispatch [:input/escape]}})

(defn allowed?
  "Does command id's :when predicate pass for ctx? (no :when ⇒ always)."
  [id ctx]
  (let [spec (get registry id)
        pred (get predicates (:when spec) (:always predicates))]
    (boolean (pred ctx))))

(defn run
  "Resolve command id against ctx and dispatch. Returns true if it consumed the key (dispatched or the
   gate passed), false if the :when gate failed (so the key can pass through)."
  ([id ctx] (run id ctx nil))
  ([id ctx args]
   (let [spec (get registry id)]
     (cond
       (nil? spec)             false
       (not (allowed? id ctx)) false
       (:handler spec)         (do (when-let [ev ((:handler spec) ctx)] (rf/dispatch ev)) true)
       (:dispatch spec)        (let [ev (cond
                                          (seq args)            (into (:dispatch spec) args)
                                          (contains? spec :arg) (conj (:dispatch spec) (:arg spec))
                                          :else                 (:dispatch spec))]
                                 (rf/dispatch ev) true)
       (:prompt spec)          (do (rf/dispatch [:palette/open {:source (:prompt spec)}]) true)
       :else                   false))))

(defn all-visible
  "All command specs whose :when passes for ctx (populates the command palette)."
  [ctx]
  (->> (vals registry) (filter #(allowed? (:id %) ctx)) (sort-by :title)))
