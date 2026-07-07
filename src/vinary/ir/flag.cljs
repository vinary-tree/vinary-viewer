(ns vinary.ir.flag
  "The `:vv/ir` feature flag gating the common-IR rendering path during the migration. Two sources: a
   compile-time `goog-define` (`vinary.ir.flag/ir-default`, overridable via :closure-defines / CI) and the
   persisted runtime setting [:ui :settings :ir-enabled?]. The runtime setting wins when present; otherwise
   the compile-time default applies. Default false until each format's parity gate passes.")

(goog-define ^boolean ir-default false)

(defn enabled?
  "Is the IR path on? 0-arity = the compile-time default; 1-arity = the persisted setting overriding it
   (nil setting ⇒ fall back to the default)."
  ([] ir-default)
  ([setting] (if (some? setting) (boolean setting) ir-default)))
