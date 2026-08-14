(ns vinary.ui.preview-navigation
  "Navigation events for links activated from preview surfaces, plus the gesture decode shared by every
   link-like surface (ADR-0044).

   Preview navigation is browser-like: same-tab activation changes the current tab's history, while
   explicit new-tab activation opens a separate tab. This is intentionally different from CONTEXT-MENU
   or palette selection, where opening an already-open document may focus its existing tab.

   The gesture family (browser parity): plain = same tab; Ctrl+click and middle-click = a new tab in the
   BACKGROUND (the reader stays put); Ctrl+Shift+click and Shift+middle-click = a new tab, focused."
  (:require [clojure.string :as str]))

(defn link-kind
  "Return the link target kind for either a raw vinary.app.link target or a preview context-menu target."
  [target]
  (or (:link-kind target) (:kind target)))

(defn new-tab? [target]
  (contains? #{:http :file :dir} (link-kind target)))

(defn click-mode
  "Decode a left-click's modifier state into the ADR-0044 open mode: Ctrl+Shift → :new-focused,
   Ctrl → :new-background, otherwise :same. Shared by every link-like surface (preview links, file-tree
   rows, directory-browser rows) so one gesture means one thing everywhere."
  [ctrl? shift?]
  (cond
    (and ctrl? shift?) :new-focused
    ctrl?              :new-background
    :else              :same))

(defn open-event
  "Return the re-frame event for opening a preview target in `mode` (:same | :new-background |
   :new-focused). A same-document anchor ignores the mode entirely — a new tab scrolled to a heading of
   a document you are already reading is never what the gesture meant."
  [target mode]
  (let [kind (link-kind target)
        path (:path target)]
    (when-not (str/blank? path)
      (case kind
        :anchor [:toc/goto path]
        ;; a directory now opens in-pane (the directory browser) just like a file, pushing history
        (:http :file :dir) [(case mode
                              :new-background :tab/open-background
                              :new-focused    :tab/open
                              :tab/navigate)
                            path]
        nil))))
