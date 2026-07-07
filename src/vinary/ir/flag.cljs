(ns vinary.ir.flag
  "The `:vv/ir` feature flag gating the common-IR rendering path during the migration. Two sources: a
   compile-time `goog-define` (`vinary.ir.flag/ir-default`, overridable via :closure-defines / CI) and the
   persisted runtime setting [:ui :settings :ir-enabled?]. The runtime setting wins when present; otherwise
   the compile-time default applies. Default false until each format's parity gate passes.")

;; Default ON: the common-IR render path is now the default for Markdown (and office). It produces
;; byte-identical output to the legacy path (proven by ir.parity-test + the electron smoke), so the flip is
;; invisible; the setting remains as a per-user escape hatch, and :closure-defines can force it off in a build.
(goog-define ^boolean ir-default true)

(defn enabled?
  "Is the IR path on? 0-arity = the compile-time default; 1-arity = the persisted setting overriding it
   (nil setting ⇒ fall back to the default)."
  ([] ir-default)
  ([setting] (if (some? setting) (boolean setting) ir-default)))
