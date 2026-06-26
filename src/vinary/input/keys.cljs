(ns vinary.input.keys
  "Key normalization: a js KeyboardEvent → a canonical chord token. Modifiers fold cross-platform
   (Ctrl OR ⌘ on macOS → \"C-\"; Alt/Option → \"M-\"; Shift → \"S-\" for named keys and for modified
   letters). Unmodified shifted printables keep their typed character. Tokens: \"g\", \"/\", \"?\",
   \"G\", \"left\", \"tab\", \"escape\", \"space\", \"C-x\", \"M-f\", \"S-tab\", \"C-M-S-g\"."
  (:require [clojure.string :as str]))

(def ^:private named
  {"ArrowLeft" "left" "ArrowRight" "right" "ArrowUp" "up" "ArrowDown" "down"
   "Escape" "escape" "Tab" "tab" " " "space" "Enter" "enter" "Backspace" "backspace"
   "Delete" "delete" "Home" "home" "End" "end" "PageUp" "prior" "PageDown" "next"})

(def ^:private named-bases (into #{} (vals named)))

(def ^:private modifier-keys
  #{"Control" "Shift" "Alt" "Meta" "Dead" "Process" "CapsLock" "AltGraph" "OS" "Fn" "Unidentified"})

(defn- named-base? [base]
  (or (contains? named-bases base) (boolean (re-matches #"f\d{1,2}" base))))

(defn- single-letter? [s]
  (boolean (re-matches #"[A-Za-z]" s)))

(defn mac? [] (boolean (re-find #"Mac" (or (.-platform js/navigator) ""))))

(defn event->chord
  "js KeyboardEvent → canonical token string, or nil for modifier-only/IME events."
  [^js e is-mac]
  (let [k (.-key e)]
    (when-not (or (nil? k) (contains? modifier-keys k))
      (let [ctrl      (or (.-ctrlKey e) (and is-mac (.-metaKey e)))
            meta      (.-altKey e)
            modified? (or ctrl meta)
            letter?   (single-letter? k)
            base      (cond
                        (contains? named k)         (named k)
                        (re-matches #"F\d{1,2}" k)  (str/lower-case k)
                        (and modified? letter?)     (str/lower-case k)
                        :else                       k)
            shift     (and (.-shiftKey e)
                           (or (named-base? base)
                               (and modified? letter?)))]
        (str (when ctrl "C-") (when meta "M-") (when shift "S-") base)))))

(defn bare-printable?
  "Is token a single printable char with no modifier (so it should reach a focused input)?"
  [token]
  (and (= 1 (count token)) (not (str/includes? token "-"))))

(defn normalize-token
  "Authored-token aliases → canonical (SPC→space, RET→enter, etc.). Applied to keymap keys at load."
  [token]
  (get {"SPC" "space" "RET" "enter" "TAB" "tab" "ESC" "escape"} token token))
