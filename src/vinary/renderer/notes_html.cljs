(ns vinary.renderer.notes-html
  "A deck's TEXT NOTES as the presenter view shows them (ADR-0048 §4, Rendering). pdfpc renders a note as Markdown —
   Discount, raw HTML passed through to a WebKit view with JavaScript off — unless the deck says DisableMarkdown. A
   note here is Markdown when the deck carries pdfpc metadata and Markdown is not disabled (vinary.slides.notes
   :markdown?), rendered through the same trust boundary as a Markdown document: GFM, then rehype-raw and
   rehype-sanitize with the app's one schema (vinary.ir.backend.sanitize — GitHub's allowlist), so no script, style,
   event handler or javascript: URL survives (docs/security/threat-model.md). Any other note is plain text: a sticky
   note from a PDF reader is not Markdown.

   No slugs, no syntax highlighting, no math, no URL rewriting — a note is a few lines beside a slide, not a document,
   and its links open outside the app (the pane handles their clicks). DOM-free, so the node :test build runs it."
  (:require ["unified" :refer [unified]]
            ["remark-parse$default"     :as remark-parse]
            ["remark-gfm$default"       :as remark-gfm]
            ["remark-rehype$default"    :as remark-rehype]
            ["rehype-raw$default"       :as rehype-raw]
            ["rehype-sanitize$default"  :as rehype-sanitize]
            ["rehype-stringify$default" :as rehype-stringify]
            [vinary.ir.backend.sanitize :as sanitize]))

(defonce ^:private processor
  ;; built once, frozen: every note runs through the same configured pipeline
  (delay (-> (unified)
             (.use remark-parse)
             (.use remark-gfm)
             (.use remark-rehype #js {:allowDangerousHtml true})
             (.use rehype-raw)
             (.use rehype-sanitize sanitize/schema)
             (.use rehype-stringify)
             (.freeze))))

(defn markdown->html
  "The sanitized HTML of Markdown note `text` (a string; anything else is the empty note)."
  [text]
  (if (string? text)
    (str (.processSync ^js @processor text))
    ""))
