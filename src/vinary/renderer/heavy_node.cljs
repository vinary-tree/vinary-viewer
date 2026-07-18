(ns vinary.renderer.heavy-node
  "EAGER (non-lazy) population of the shared renderer.heavy-registry for the NODE targets (:cli / :tui /
   :node-test), which cannot use shadow.lazy (browser-only). It requires renderer.heavy-engine directly (a static
   :require edge, so unified-latex — and, from the Org split, uniorg — are bundled into the node build exactly as
   they were before the split) and re-exposes its install!. cli.core / tui.core call (heavy-node/install!) at
   startup, and the Org node-tests call it on load, so the DOM-free pipeline (markdown-pipeline / cli.render)
   resolves latex->html and the org-pipeline under Node just as before.

   NO renderer-build ns requires this (or heavy-engine directly): the renderer reaches heavy-engine ONLY through
   the renderer.heavy-lazy shadow.lazy/loadable, so heavy-engine + unified-latex/uniorg code-split out of the
   renderer boot bundle. And this ns pulls in NO shadow.lazy, so the node bundles stay browser-API-free."
  (:require [vinary.renderer.heavy-engine :as engine]))

(defn install!
  "Populate the shared heavy registry eagerly (delegates to heavy-engine/install!). Idempotent; call once at node
   startup, before any document renders."
  []
  (engine/install!))
