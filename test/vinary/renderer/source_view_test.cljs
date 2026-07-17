(ns vinary.renderer.source-view-test
  "Guards the renderer.cm ↔ renderer.source-view facade contract: every fn renderer.cm passes through MUST exist in
   the lazily-loaded chunk's `exports` table. A missing/renamed export would otherwise surface only as a runtime
   error in the split :renderer build (the DOM-free unit suite can't mount a real EditorView), so this static check
   is the cheapest guard against the two files drifting apart.

   Requiring renderer.source-view here also has a BUILD role: it places the ns in the :node-test build so
   renderer.cm's `shadow.lazy/loadable` macro can resolve its module at compile time. In the :renderer build the
   chunk is reached only through that loadable (a bare js* ref, not a :require) and lives in its own :source-view
   module entry; the node builds have no such module, so without this require `module-for-ns!` throws
   \"Could not find module for ns: vinary.renderer.source-view\" while macroexpanding renderer.cm."
  (:require [cljs.test :refer [deftest is testing]]
            [goog.object :as gobj]
            [vinary.renderer.source-view :as source-view]))

;; the exact string keys renderer.cm dereferences via (gobj/get @sv-module "…") — keep in sync with the
;; pass-throughs in renderer.cm and the `exports` table in renderer.source-view.
(def ^:private facade-exports
  ["create-source-view" "view-from-dom" "scroll-source-to-line!" "want-source-line!"
   "current-source-line" "current-viewport-line" "viewport-top-line" "selected-text"
   "selection-start" "pos-at-coords" "line-info-at"])

(deftest exports-cover-the-cm-facade
  (testing "renderer.source-view/exports provides every fn renderer.cm passes through"
    (doseq [k facade-exports]
      (is (fn? (gobj/get source-view/exports k))
          (str "renderer.source-view/exports is missing the \"" k "\" fn that renderer.cm calls")))))
