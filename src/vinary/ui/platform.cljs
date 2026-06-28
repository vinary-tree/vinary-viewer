(ns vinary.ui.platform
  "Tiny renderer-side OS detection for input conventions. Single- vs. double-click to OPEN follows the
   host OS: a single click opens files/directories on Linux, while Windows and macOS follow the
   double-click convention. Detected from the sandbox-safe navigator (no Node access needed).")

(defn- platform-string []
  (or (some-> (.-userAgentData js/navigator) .-platform)   ; modern: \"Linux\" | \"Windows\" | \"macOS\"
      (.-platform js/navigator)                            ; legacy:  \"Linux x86_64\" | \"Win32\" | \"MacIntel\"
      ""))

(def ^:private linux?   (boolean (re-find #"(?i)linux" (platform-string))))
(def ^:private windows? (boolean (re-find #"(?i)win"   (platform-string))))

(defn single-click-open?
  "True when a single click should open files/directories (the Linux convention); false on Windows and
   macOS, where opening requires a double click."
  []
  linux?)

(defn path-sep
  "The path separator to append after completing a directory: '\\\\' on Windows, else '/'. (Completion
   accepts either separator as a delimiter; this is only used when synthesizing a completed path.)"
  []
  (if windows? "\\" "/"))
