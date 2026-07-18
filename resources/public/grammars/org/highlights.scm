; Org (.org) source highlighting for the View-Source pane. Capture names use vinary-viewer's style-map
; vocabulary (see renderer/syntax.cljs style-map) — NOT neovim's OrgHeadlineLevel* / bold.start names, which
; the app's CodeMirror highlighter does not recognise. Node/field references mirror those in
; nvim-orgmode/tree-sitter-org's own queries, so they stay valid against the built grammar. No #match?/#any-of?
; predicates are used (web-tree-sitter's captures do not auto-apply predicates, so a predicate-gated capture
; would over-match — e.g. colour every item's first word as a keyword).

; Headlines: the leading stars are the heading marker; the title text is the heading.
(headline (stars) @markup.heading.marker (item) @markup.heading)

; Tags  :tag1:tag2:
(tag) @label

; #+KEY: value directives (TITLE, AUTHOR, OPTIONS, …)
(directive name: (expr) @keyword (value)? @string)

; Blocks  #+begin_NAME … #+end_NAME — colour the block NAME (src / example / quote / export …) AND the
; #+begin_/#+end_ delimiters, so the whole `#+BEGIN_EXPORT` / `#+END_EXPORT` reads as one keyword macro like a
; `#+KEY:` directive (rather than only the bare NAME being coloured).
(block name: (expr) @keyword)
(block "#+begin_" @keyword "#+end_" @keyword)
(dynamic_block name: (expr) @keyword)
(dynamic_block "#+begin:" @keyword "#+end:" @keyword)

; Comments
(comment) @comment

; Property drawers (:PROPERTIES: :name: value :END:) and other drawers.
(property name: (expr) @property (value)? @string)
(drawer name: (expr) @label)

; Timestamps  <2024-01-01 Mon 09:00 +1w> / [2024-01-01]
(timestamp) @constant

; Footnote definitions  [fn:label] description
(fndef label: (expr) @label)

; List bullets and checkboxes.
(bullet) @markup.list
(checkbox) @markup.list

; Table horizontal ruler.
(hr) @punctuation

; Inline markup — capture the whole delimited expr so the text (not just the marker) is styled.
(paragraph [
 ((expr "*" "*") @markup.strong)
 ((expr "/" "/") @markup.italic)
 ((expr "_" "_") @markup.emphasis)
 ((expr "=" "=") @markup.raw)
 ((expr "~" "~") @markup.raw)
 ((expr "+" "+") @markup.strikethrough)
])
(item [
 ((expr "*" "*") @markup.strong)
 ((expr "/" "/") @markup.italic)
 ((expr "=" "=") @markup.raw)
 ((expr "~" "~") @markup.raw)
])
