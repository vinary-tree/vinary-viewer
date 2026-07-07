(ns vinary.ir.flag
  "RETIRED (ADR-0017). This was the `:vv/ir` migration flag that gated the common-IR render path during the
   phased cutover from the legacy string renderer. The common IR is now the UNCONDITIONAL render path for
   Markdown and office (render-ir / render-office-ir in vinary.renderer.markdown), so the flag no longer gates
   anything and nothing requires this namespace. The definitions are kept #_-disabled below for reference
   rather than deleted (per the repo's comment-don't-delete rule).")

#_(goog-define ^boolean ir-default true)

#_(defn enabled?
    "Was: is the IR path on? The runtime setting (arg) won when present; otherwise the compile-time default."
    ([] ir-default)
    ([setting] (if (some? setting) (boolean setting) ir-default)))
