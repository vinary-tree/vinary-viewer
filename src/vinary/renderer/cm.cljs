(ns vinary.renderer.cm
  "Thin facade over the lazily-loaded source-view chunk (renderer.source-view). The heavy @codemirror editor
   packages live in their own build module (`:source-view`, depends-on `:main`) so they stay OUT of the renderer
   boot bundle; this ns loads that chunk on demand (ensure!) and passes calls through to its exported fns.
   renderer.core schedules a pool/idle preload (ensure! shortly after first paint) so warm — including hidden
   pool-window — source opens stay instant.

   Call discipline:
   - View-bound fns (create-source-view, line-info-at, pos-at-coords, selected-text, selection-start,
     viewport-top-line) are only ever invoked on an already-mounted EditorView, which implies the chunk is loaded,
     so they deref the module directly.
   - Boot-reachable fns (view-from-dom, current-source-line, current-viewport-line, scroll-source-to-line!) may run
     before any source view — hence any chunk — exists, so they are guarded with (ready?); a nil result matches the
     old \"no source view mounted\" no-op.
   - want-source-line! (a preview→source jump requested BEFORE toggling to source) is buffered here when the chunk
     is not yet loaded and flushed into the source-view on load, so the jump is never lost."
  (:require [shadow.lazy :as lazy]
            [goog.object :as gobj]))

;; handle to the lazily-loaded source-view module (named sv-module, not `mod`/`chunk`, which shadow cljs.core vars)
(def ^:private sv-module (lazy/loadable vinary.renderer.source-view/exports))

;; a preview→source jump line requested (via want-source-line!) BEFORE the chunk loaded — flushed into the
;; source-view's own pending atom once ensure! resolves, so create-source-view consumes it exactly as it would a
;; same-window jump (see renderer.source-view/pending-source-line, renderer.scroll want!→apply!).
(defonce ^:private pending-want-line (atom nil))

(defn ready?
  "True once the source-view chunk has loaded (its exports are dereferenceable)."
  []
  (lazy/ready? sv-module))

(defn ensure!
  "Load the source-view chunk (idempotent — the module loads once). Returns a Promise of its exports and, on
   resolve, flushes any preview→source jump that was buffered while the chunk was still loading."
  []
  (-> (lazy/load sv-module)
      (.then (fn [exports]
               (when-let [l @pending-want-line]
                 (reset! pending-want-line nil)
                 ((gobj/get exports "want-source-line!") l))
               exports))))

;; ---- pass-throughs to the loaded chunk ----
;; View-bound: only ever called on an existing EditorView (⇒ the chunk is loaded), so deref @sv-module directly.
(defn create-source-view [^js parent text grammar] ((gobj/get @sv-module "create-source-view") parent text grammar))
(defn line-info-at      [^js view pos] ((gobj/get @sv-module "line-info-at") view pos))
(defn pos-at-coords     [^js view x y] ((gobj/get @sv-module "pos-at-coords") view x y))
(defn selected-text     [^js view]     ((gobj/get @sv-module "selected-text") view))
(defn selection-start   [^js view]     ((gobj/get @sv-module "selection-start") view))
(defn viewport-top-line [^js view]     ((gobj/get @sv-module "viewport-top-line") view))

;; Boot-reachable (may be called before a source view — hence any chunk — exists): guard with ready? so a deref
;; never throws; a nil result matches the old "no source view mounted" no-op.
(defn view-from-dom          [^js node] (when (ready?) ((gobj/get @sv-module "view-from-dom") node)))
(defn current-source-line    []         (when (ready?) ((gobj/get @sv-module "current-source-line"))))
(defn current-viewport-line  []         (when (ready?) ((gobj/get @sv-module "current-viewport-line"))))
(defn scroll-source-to-line! [line]     (when (ready?) ((gobj/get @sv-module "scroll-source-to-line!") line)))

(defn want-source-line!
  "Stash a preview→source jump line. If the chunk is loaded, hand it straight to the source-view; otherwise buffer
   it here (ensure! flushes it on load), so a jump requested before the first source open is not lost."
  [line]
  (if (ready?)
    ((gobj/get @sv-module "want-source-line!") line)
    (reset! pending-want-line line)))
