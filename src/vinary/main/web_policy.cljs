(ns vinary.main.web-policy
  "Pure policy for web-view popup requests (ADR-0044). A link inside a browsed page can ask for a new
   window — target=_blank, window.open, or the browser gestures Chromium reports as a disposition. The
   app never grants a native window: `vinary.main.web`'s setWindowOpenHandler denies every request and
   relays the tab-worthy ones to the renderer, which opens them as ordinary app tabs.

   These two decisions are kept here, free of Electron requires, so they are unit-testable in the node
   test build (the vinary.main.doc-overrides precedent) rather than only reachable through a live view.")

(defn open-mode
  "Map a setWindowOpenHandler `disposition` onto the tab-open mode. Chromium reports a middle-click or
   Ctrl+click popup as \"background-tab\"; \"foreground-tab\" (Ctrl+Shift+click), \"new-window\",
   \"default\", \"save-to-disk\", \"other\" and a missing disposition all open focused — the same
   parity the in-app gesture family follows (ADR-0044)."
  [disposition]
  (if (= disposition "background-tab") :background :focused))

(defn tab-worthy-url?
  "Only an http(s) target becomes an app tab. Everything else — about:blank, javascript:, data:, file:,
   a custom scheme — is denied outright with no tab, closing the pre-ADR-0044 hole where a non-PDF
   popup was granted a real native window."
  [url]
  (boolean (and (string? url) (re-find #"(?i)^https?://" url))))
