(ns vinary.terminal.caps
  "Terminal capability detection for the CLI/TUI: width, colour, truecolor, OSC-8 hyperlinks, and inline
   image graphics (sixel/kitty) — from `process.stdout` + environment, honouring the CLI flags and the
   `NO_COLOR` / `isatty` conventions so output degrades cleanly when piped. Pure of the IR — just env probing."
  (:require [clojure.string :as str]))

(defn- env [k] (some-> (aget js/process.env k) str))
(defn- stdout-tty? [] (boolean (and js/process.stdout (.-isTTY js/process.stdout))))

(defn detect
  "Resolve the effective render capabilities from CLI `opts` + the terminal environment. Returns
   {:width :color? :truecolor? :hyperlinks? :graphics}. `:graphics` is :kitty | :sixel | nil."
  [opts]
  (let [term      (or (env "TERM") "")
        colorterm (or (env "COLORTERM") "")
        color?    (and (not (:no-color opts))
                       (not (some? (env "NO_COLOR")))
                       (or (:force-color opts) (stdout-tty?)))
        truecolor? (and color? (or (str/includes? colorterm "truecolor") (str/includes? colorterm "24bit")))
        graphics   (when (and color? (not (:no-graphics opts)))
                     (cond
                       (or (some? (env "KITTY_WINDOW_ID")) (str/includes? term "kitty")) :kitty
                       (or (str/includes? term "sixel") (str/includes? term "foot")
                           (str/includes? term "wezterm") (str/includes? term "mlterm")
                           (str/includes? term "yaft") (some? (env "WEZTERM_PANE"))) :sixel
                       :else nil))
        hyperlinks? (and color? (stdout-tty?) (not (:no-hyperlinks opts)))
        width       (or (:width opts)
                        (when (and js/process.stdout (pos? (or (.-columns js/process.stdout) 0))) (.-columns js/process.stdout))
                        80)]
    {:width width :color? color? :truecolor? truecolor? :hyperlinks? hyperlinks? :graphics graphics}))
