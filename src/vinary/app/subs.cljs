(ns vinary.app.subs
  "re-frame subscriptions — the Observer graph. UI slices read app-db; document slices read the DataScript
   content cache (listing [:ds/rev] so they recompute per transaction). The browser-tab model lives in
   app-db ([:ui :tabs]/[:ui :active-tab]); reads go through vinary.app.nav."
  (:require [re-frame.core :as rf]
            [vinary.app.ds :as ds]
            [vinary.app.nav :as nav]
            [vinary.app.uri :as uri]
            [vinary.app.zoom :as zoom]
            [vinary.input.keymaps-registry :as registry]))

(rf/reg-sub :ds/rev         (fn [db _] (:ds/rev db)))
(rf/reg-sub :ui/theme       (fn [db _] (get-in db [:ui :theme])))
(rf/reg-sub :ui/tree-filter (fn [db _] (get-in db [:ui :tree-filter])))
(rf/reg-sub :ui/find           (fn [db _] (get-in db [:ui :find])))
(rf/reg-sub :pdf/view-state    (fn [db _] (get-in db [:ui :pdf])))
(rf/reg-sub :view/zoom-percent (fn [db _] (zoom/percent db)))   ; live zoom % for the active surface (zoom bar)
(rf/reg-sub :view/pdf-active?  (fn [db _] (= :pdf (zoom/context db))))  ; gates the PDF-only View-menu items
(rf/reg-sub :ui/active-heading (fn [db _] (get-in db [:ui :active-heading])))
(rf/reg-sub :ui/sidebar-visible? (fn [db _] (get-in db [:ui :sidebar-visible?])))
(rf/reg-sub :ui/sidebar-width  (fn [db _] (get-in db [:ui :sidebar-width])))
(rf/reg-sub :ui/sidebar-tab    (fn [db _] (get-in db [:ui :sidebar-tab])))
(rf/reg-sub :ui/projects       (fn [db _] (get-in db [:ui :projects])))
(rf/reg-sub :ui/tree-selected  (fn [db _] (get-in db [:ui :tree-selected])))
(rf/reg-sub :ui/dir-selected   (fn [db _] (get-in db [:ui :dir-selected])))
(rf/reg-sub :ui/menu           (fn [db _] (get-in db [:ui :menu])))
(rf/reg-sub :ui/menu-submenu   (fn [db _] (get-in db [:ui :menu-submenu])))
(rf/reg-sub :ui/menu-focus     (fn [db _] (get-in db [:ui :menu-focus])))
(rf/reg-sub :ui/menu-submenu-focus (fn [db _] (get-in db [:ui :menu-submenu-focus])))
(rf/reg-sub :ui/access-keys-active? (fn [db _] (get-in db [:ui :access-keys-active?])))
(rf/reg-sub :ui/settings       (fn [db _] (get-in db [:ui :settings])))
(rf/reg-sub :ui/settings-open? (fn [db _] (get-in db [:ui :settings-open?])))
(rf/reg-sub :ui/about-open?    (fn [db _] (get-in db [:ui :about-open?])))
(rf/reg-sub :ui/app-info       (fn [db _] (get-in db [:ui :app-info])))
(rf/reg-sub :ui/context-menu   (fn [db _] (get-in db [:ui :context-menu])))
(rf/reg-sub :ui/hover-link     (fn [db _] (get-in db [:ui :hover-link])))
(rf/reg-sub :ui/ctrl-held?     (fn [db _] (get-in db [:ui :ctrl-held?])))
(rf/reg-sub :ui/tab-drop       (fn [db _] (get-in db [:ui :tab-drop])))
(rf/reg-sub :ui/recent         (fn [db _] (get-in db [:ui :recent])))
(rf/reg-sub :ui/recent-files   (fn [db _] (get-in db [:ui :recent :recent-files])))
(rf/reg-sub :ui/web-history    (fn [db _] (get-in db [:ui :recent :web-history])))
(rf/reg-sub :ui/overlay-open?
            ;; any blocking DOM overlay (menu / context menu / palette / modal dialog) is open — the
            ;; native web view hides while this is true so the overlay isn't painted beneath it.
            ;; (PDFs now render in-renderer via pdf.js — ADR 0013 — so only the web view is native.)
            (fn [db _]
              (boolean (or (get-in db [:ui :menu])
                           (get-in db [:ui :context-menu])
                           (get-in db [:ui :settings-open?])
                           (get-in db [:ui :about-open?])
                           (get-in db [:ui :kbedit :open?])
                           (get-in db [:ui :extensions-open?])
                           (get-in db [:ui :passwords :open?])
                           (get-in db [:ui :passwords :save-prompt])
                           (get-in db [:ui :palette :open?])))))
(rf/reg-sub :ui/re-frame-10x-open? (fn [db _] (get-in db [:ui :re-frame-10x-open?])))
(rf/reg-sub :ui/hints          (fn [db _] (get-in db [:ui :hints])))

;; ---- keymap-set registry (Settings ▸ Key Bindings + the editor) ----
(rf/reg-sub :keymaps/active-id (fn [db _] (registry/active-id db)))
(rf/reg-sub :keymaps/set-rows
            (fn [db _]
              (let [active (registry/active-id db)]
                (mapv (fn [id] {:id id :name (registry/display-name db id) :selected? (= id active)})
                      (registry/set-ids db)))))
;; ---- key-binding editor ----
(rf/reg-sub :kbedit/open?    (fn [db _] (get-in db [:ui :kbedit :open?])))
(rf/reg-sub :kbedit/sel      (fn [db _] (get-in db [:ui :kbedit :sel])))
(rf/reg-sub :kbedit/editing  (fn [db _] (get-in db [:ui :kbedit :editing])))
(rf/reg-sub :kbedit/capture  (fn [db _] (get-in db [:ui :kbedit :capture])))
(rf/reg-sub :kbedit/ctx      (fn [db _] (get-in db [:ui :kbedit :ctx])))
(rf/reg-sub :kbedit/can-undo? (fn [db _] (boolean (seq (get-in db [:ui :kbedit :undo])))))
(rf/reg-sub :kbedit/can-redo? (fn [db _] (boolean (seq (get-in db [:ui :kbedit :redo])))))
(rf/reg-sub :kbedit/sets
            (fn [db _]
              (let [active (registry/active-id db) sel (get-in db [:ui :kbedit :sel])]
                (vec (concat
                      (mapv (fn [{:keys [id name]}]
                              {:id id :name name :builtin? true :custom? false
                               :active? (= id active) :focused? (= id sel)}) registry/builtins)
                      (mapv (fn [id]
                              {:id id :name id :builtin? false :custom? true
                               :active? (= id active) :focused? (= id sel)}) (registry/order db)))))))
(rf/reg-sub :kbedit/focused
            (fn [db _]
              (let [sel (get-in db [:ui :kbedit :sel])]
                {:id sel :name (registry/display-name db sel) :builtin? (registry/builtin? sel)
                 :custom? (registry/custom? db sel) :modal? (and sel (registry/modal? db sel))
                 :default-mode (and sel (registry/default-mode db sel))})))
(rf/reg-sub :kbedit/action-index
            (fn [db _] (when-let [sel (get-in db [:ui :kbedit :sel])] (registry/action-index db sel))))

(rf/reg-sub :input/mode        (fn [db _] (get-in db [:ui :input :mode])))
(rf/reg-sub :input/pending     (fn [db _] (get-in db [:ui :input :sequence])))
(rf/reg-sub :input/in-input?   (fn [db _] (get-in db [:ui :input :in-input?])))
(rf/reg-sub :palette/state     (fn [db _] (get-in db [:ui :palette])))
(rf/reg-sub :ui/uri-complete     (fn [db _] (get-in db [:ui :uri-complete])))
(rf/reg-sub :ui/extensions-open? (fn [db _] (get-in db [:ui :extensions-open?])))
(rf/reg-sub :ui/extensions       (fn [db _] (get-in db [:ui :extensions])))
(rf/reg-sub :ui/adblock          (fn [db _] (get-in db [:ui :adblock])))
(rf/reg-sub :ui/passwords        (fn [db _] (get-in db [:ui :passwords])))

;; ---- the browser-tab model (app-db) ----
(rf/reg-sub :ui/tabs          (fn [db _] (nav/tabs db)))
(rf/reg-sub :ui/active-tab-id (fn [db _] (nav/active-id db)))
(rf/reg-sub :ui/active-tab    (fn [db _] (nav/active-tab db)))
(rf/reg-sub :ui/active-uri    (fn [db _] (nav/active-uri db)))
(rf/reg-sub :ui/active-view-source? (fn [db _] (nav/view-source? db)))
(rf/reg-sub :ui/active-path   (fn [db _] (nav/active-path db)))    ; the active uri iff it is a local file
(rf/reg-sub :history/can-back?    (fn [db _] (nav/can-back? db)))
(rf/reg-sub :history/can-forward? (fn [db _] (nav/can-forward? db)))

;; the tab strip reads the app-db tabs
(rf/reg-sub :tabs :<- [:ui/tabs] (fn [tabs _] tabs))

;; the active document: the cached DataScript doc for the active tab's local-file uri
(rf/reg-sub
 :doc/active
 :<- [:ds/rev] :<- [:ui/active-path]
 (fn [[_rev path] _] (when path (ds/active-doc (ds/snapshot) path))))

;; the active document's kind ("markdown" / "org" / "latex" / "source" / "pdf" / …) — gates the "Go to preview" jump item
(rf/reg-sub :doc/kind :<- [:doc/active] (fn [doc _] (:doc/kind doc)))

;; the active document's collocated exported PDF (absolute path), or nil — enables the Document↔PDF switch
(rf/reg-sub :doc/pdf-sibling :<- [:doc/active] (fn [doc _] (:doc/pdf-sibling doc)))

;; the reverse: an opened PDF's collocated previewable SOURCE (absolute path), or nil — enables PDF→Doc (the
;; toolbar's [Doc | PDF] on a PDF, where "Doc" navigates the tab to the rendered source)
(rf/reg-sub :doc/source-sibling :<- [:doc/active] (fn [doc _] (:doc/source-sibling doc)))

;; the EFFECTIVE diff view of the active doc (:unified | :split); split is opt-in, so the default is :unified
(rf/reg-sub :ui/active-diff-view :<- [:ui/active-tab] (fn [tab _] (nav/effective-diff-view (:diff-view tab))))

;; the EFFECTIVE representation of the active doc: :pdf or :document. Only :pdf when a sibling PDF exists AND
;; either the user chose it on this tab or the collocated-default preference (default :pdf) says so.
(rf/reg-sub
 :ui/active-representation
 :<- [:ui/active-tab] :<- [:doc/active] :<- [:ui/settings]
 (fn [[tab doc settings] _]
   (nav/effective-representation (:representation tab)
                                 (boolean (:doc/pdf-sibling doc))
                                 (get settings :collocated-default :pdf))))

;; whether a collocated sibling PDF's bytes are cached yet (content-view shows a loading note until then)
(rf/reg-sub :pdf/sibling-loaded (fn [db _] (get-in db [:ui :pdf-sibling-loaded] #{})))

;; the persisted default representation for docs that have a collocated PDF (Settings toggle reads this)
(rf/reg-sub :ui/collocated-default :<- [:ui/settings] (fn [s _] (get s :collocated-default :pdf)))

(rf/reg-sub :ui/web-toc (fn [db _] (get-in db [:ui :web-toc])))
(rf/reg-sub :pdf/reflow? (fn [db _] (boolean (get-in db [:ui :pdf :reflow?]))))

;; the Contents/TOC outline of the active document: the web view's headings for an HTTP page; else, in the
;; SOURCE view, the tree-sitter source outline (:doc/source-toc — `L<line>` ids for CodeMirror nav) when present;
;; else the preview's rendered headings (:doc/toc — rehype slug ids for DOM nav). Selecting by active view keeps
;; the two id-spaces from clobbering each other, so each view's Contents both displays AND navigates correctly.
(defn active-toc
  "Select the Contents outline for the active view (pure — the :doc/toc sub is this over its inputs). HTTP page →
   the web view's headings; else source-active with a source outline present → the SOURCE outline (`L<line>` ids);
   else the PREVIEW outline (slug ids). The two outlines live in separate attrs so neither clobbers the other;
   this picks the one whose id-space matches the active view. Falling back to the preview outline (not blank) when
   the source outline has not arrived avoids an empty Contents flash on source-view mount."
  [http? source? web-toc preview-toc source-toc]
  (cond
    http?                          (vec (or web-toc []))
    (and source? (seq source-toc)) (vec source-toc)
    :else                          (vec (or preview-toc []))))

(rf/reg-sub
 :doc/toc
 :<- [:ui/active-uri] :<- [:doc/active] :<- [:ui/web-toc] :<- [:ui/active-view-source?]
 (fn [[active-uri doc web-toc source?] _]
   (active-toc (uri/http? active-uri) source? web-toc (:doc/toc doc) (:doc/source-toc doc))))

;; the active document streams (bounded-memory incremental render) rather than rendering whole
(rf/reg-sub :doc/streaming? :<- [:doc/active] (fn [doc _] (boolean (:doc/streaming? doc))))
;; stream completion in [0,1] (nil until the first batch), for the progress affordance
(rf/reg-sub :doc/stream-progress :<- [:doc/active] (fn [doc _] (:doc/stream-progress doc)))
;; a non-fatal note shown in the stream progress strip (e.g. a remote SSH connection dropped mid-stream)
(rf/reg-sub :doc/stream-note :<- [:doc/active] (fn [doc _] (:doc/stream-note doc)))

;; SSH: the pending auth-prompt request (non-secret) and the last connection error
(rf/reg-sub :ui/ssh-prompt (fn [db _] (get-in db [:ui :ssh-prompt])))
(rf/reg-sub :ui/ssh-error  (fn [db _] (get-in db [:ui :ssh-error])))
