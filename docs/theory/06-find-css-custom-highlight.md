# Theory 06 — In-Page Find: Painting Ranges, and Flattening a Document to Search It

> **Where this fits.** Theory 03 listed in-page find among the UI state a refresh must *not* disturb, and
> noted it paints highlights without mutating the DOM. This document explains *how* — the **CSS Custom
> Highlight API**, which styles arbitrary **Range**s through `CSS.highlights` and `::highlight()` without
> inserting a single element — and then the harder half: *what a match even is* when the text you are
> searching has been shredded across hundreds of nodes by the renderer. We give the algorithms in
> literate-programming form, analyse their cost, and show how results re-enter the re-frame loop.

## 1. Two problems, not one

### 1.1 Highlight without touching the document

Classic in-page find implementations wrap each match in a `<mark>` element. That **mutates the DOM**, and
here it would be actively harmful:

- the document body is written as a single foreign HTML string via `innerHTML` (Theory 05) — splicing
  `<mark>` tags into it would fight that ownership and could desynchronise the body from `:doc/html`;
- inserting elements **shifts node boundaries**, which would break the heading `id` anchors the TOC and
  `:toc/scroll` rely on, and would have to be carefully *un*-spliced when the query changes.

The **CSS Custom Highlight API** (W3C) avoids all of this. You build **Range** objects (pure descriptions of
"from offset $`i`$ to offset $`j`$ in this text node"), collect them into a named **Highlight**, register it
in `CSS.highlights`, and style it with the `::highlight(name)` pseudo-element. **No element is inserted; no
text node is split.** The document tree is identical before and after; only its *painting* changes.

vinary-viewer registers two highlights: **`"vv-find"`** (all current matches) and **`"vv-find-current"`**
(the single focused match).

### 1.2 Find a match in text that has been shredded

The second problem is the one that actually determines whether find works. Consider the Markdown source
``the `needle` phrase``, and the same words in a PDF. What reaches the DOM is:

```html
<p>the <code>needle</code> phrase</p>                    <!-- 3 text nodes -->
<span>quick </span><span>brown fox…</span>               <!-- pdf.js: one span per text run -->
```

A matcher that searches each text node in isolation cannot find `the needle phrase` in the first, or
`quick brown` in the second. Rendered text is broken at **every** inline element — `<code>`, `<a>`, `<em>`,
every syntax-highlight token — and pdf.js emits one `<span>` per text run, so in a PDF *almost every*
multi-word query finds nothing. Wrapped source makes it worse: `<p>the quick\nbrown fox</p>` is one node,
but searching it for `quick brown` still fails, because the buffer contains a newline where the user typed a
space.

The fix is to stop searching the tree and start searching a **flattened, normalized buffer**, keeping a map
back to the nodes. The Highlight API supports Ranges that span nodes, so nothing downstream has to change.

Let

```math
D \;=\; \langle n_1, n_2, \dots, n_k \rangle
```

be the visible text nodes of the content pane in document order. We build a single string $`B`$ (the
**buffer**) and a partial function

```math
\lambda \;:\; \{0,\dots,|B|-1\} \;\rightharpoonup\; \{1,\dots,k\} \times \mathbb{N}
```

mapping each buffer index to the node and offset it came from. $`\lambda`$ is *partial* because $`B`$ also
contains **synthetic separators** that belong to no node. Everything below is the construction of $`B`$ and
$`\lambda`$, and the two invariants that make them safe to invert.

## 2. The three algorithms, literately

The pure core is `vinary.renderer.find-scan` — DOM-free, so every property below is a unit test rather than
a browser observation.

### 2.1 The token stream

The DOM shell walks the pane and emits, for each visible text node, a `:text` token; between them it emits
boundary markers:

| Token | Meaning | Contributes to $`B`$ |
|---|---|---|
| `{:kind :text :id i :s s}` | node $`i`$'s data | its non-whitespace runs, whitespace collapsed |
| `{:kind :soft}` | an inline-block boundary, or a `<br>` | one space |
| `{:kind :hard}` | a block boundary | one newline |

### 2.2 `build` — flatten to a buffer plus a segment table

> **Intent.** Fold the token stream into $`B`$ and $`\lambda`$. Collapse every whitespace run to one space;
> emit a separator only *between* real text, never at either end; and record a segment quad only where
> contiguity between buffer and node breaks.
>
> **Invariant (index alignment).** Within a segment $`(b, n, o, \ell)`$, buffer index $`b+d`$ corresponds to
> offset $`o+d`$ in node $`n`$ for every $`0 \le d < \ell`$. The delta is constant, which is what makes
> $`\lambda`$ a binary search rather than a table of size $`|B|`$.

```
algorithm BUILD(tokens):
    out ← [] ; segs ← [] ; pos ← 0 ; pending ← none ; started? ← false
    exp-node ← nil ; exp-off ← 0                     ▷ the (node, offset) the next char must have
    for t in tokens:
        if t is :hard: if started?: pending ← hard                        ; continue
        if t is :soft: if started? and pending ≠ hard: pending ← soft     ; continue
        s ← t.s ; i ← 0
        while i < |s|:
            if WHITESPACE?(s[i]):
                j ← end of the whitespace run at i
                if started?:
                    if j − i = 1 and pending = none:
                        EMIT(" ", t.id, i, 1)        ▷ a LONE space/newline: keep node alignment
                    else if pending ≠ hard: pending ← soft
                i ← j
            else:
                if pending ≠ none:                   ▷ flush the separator; it belongs to no node
                    out.push(pending = hard ? "\n" : " ") ; pos ← pos+1 ; exp-node ← nil ; pending ← none
                j ← end of the non-whitespace run at i
                EMIT(s[i..j], t.id, i, j−i)
                i ← j ; started? ← true
    return {text: JOIN(out), segs}

procedure EMIT(chunk, id, off, consumed):
    if id = exp-node and off = exp-off: segs.last.len += consumed        ▷ extend the open quad
    else:                               segs.push(pos, id, off, consumed)
    out.push(SAFE-LOWER(chunk)) ; pos ← pos + |chunk|
    exp-node ← id ; exp-off ← off + consumed
```

Three things this buys, each with a consequence worth stating.

**A lone whitespace character keeps its node alignment.** It is emitted as a space *from the node's own
offset*, so the delta is unchanged and no new quad is needed. Since remark's HTML puts one newline inside a
`<p>` per source line wrap, this is the overwhelmingly common case — and it is why $`|\text{segs}|`$ stays
proportional to the number of nodes rather than to the number of words.

**A separator belongs to no node.** After flushing one, `exp-node` is cleared, so the next real text starts
a fresh quad. $`\lambda`$ is therefore undefined exactly on the separators — which is the point.

**`SAFE-LOWER` is length-preserving.** `"İ".toLowerCase()` is *two* UTF-16 units in JavaScript. Lower-casing
a whole chunk would shift every buffer index after such a character away from its node offset, silently
mis-highlighting the remainder of the document. So each code unit is lowered individually and kept as-is
whenever the result is not exactly one unit:

```math
\forall s:\quad |\mathrm{SAFE\text{-}LOWER}(s)| = |s|
```

### 2.3 `scan` — find the matches

> **Intent.** All **non-overlapping**, case-insensitive occurrences of the normalized query in $`B`$, as
> half-open index pairs, in buffer order.

```
algorithm SCAN(B, q):
    if q = "": return []
    R ← [] ; from ← 0
    loop:
        i ← indexOf(B, q, from)
        if i < 0: return R
        append [i, i+|q|] to R
        from ← i + max(1, |q|)                       ▷ non-overlapping
```

`normalize-query` lower-cases (safely), collapses whitespace runs to single spaces, and **trims**. That trim
is what makes the whole scheme sound:

```math
q \neq \varepsilon \;\Longrightarrow\; q_0 \notin \mathcal{W} \;\wedge\; q_{|q|-1} \notin \mathcal{W}
```

where $`\mathcal{W}`$ is the whitespace class. A non-blank query therefore neither begins nor ends on a
separator, and — because a normalized query can never *contain* a newline — a match may span a `:soft`
separator (a wrapped line, a pdf.js `<br>`) but **never** a `:hard` one (a paragraph, a table cell, a diff
column). Block containment falls out of query normalization rather than needing a separate check.

### 2.4 `locate` and `match-endpoints` — invert $`\lambda`$

> **Intent.** Map a buffer index back to $`(\text{node}, \text{offset})`$, or report that it falls on a
> separator.
>
> **Invariant (endpoint resolvability).** For every $`[s,e)`$ returned by `SCAN`, both
> $`\lambda(s)`$ and $`\lambda(e-1)`$ are defined.

```
algorithm LOCATE(segs, i):
    find the last quad (b, n, o, len) with b ≤ i          ▷ binary search
    if none: return ⊥
    return (i − b) < len ? (n, o + (i − b)) : ⊥           ▷ the explicit extent rejects separators
```

The explicit `len` is what makes the second clause possible. An earlier version bounded a quad by the *next*
quad's start, which silently attributed a separator to the node before it.

A Range's ends are then $`\lambda(s)`$ and $`\lambda(e-1)`$ with the offset incremented. Deriving the end
from the **last character** rather than from $`e`$ is deliberate: $`e`$ may sit on a separator or one past
the buffer, neither of which maps to a node.

### 2.5 What is searched at all

The walk prunes whole subtrees that are not rendered text: `<script>`, `<style>`, `<template>`, form
controls, SVG `<title>`/`<desc>`/`<metadata>`/`<defs>`, anything `[hidden]` or `aria-hidden="true"`, and
anything computing to `display:none` or `visibility:hidden`.

It also prunes **`<mjx-assistive-mml>`**, MathJax's screen-reader MathML duplicate of every equation. This
is the one content-specific rule in the subsystem and it cannot be inferred: MathJax hides that element with
`clip`, not `display:none`, so it has real layout boxes and `checkVisibility` reports it *visible*. Left in,
every equation's text matched twice and the cursor could land on a node the user cannot see.

Blocks that are merely off-screen under `content-visibility: auto` **are** searched — that is a rendering
optimisation, not a statement about the document.

Boundary classification (`:inline` / `:soft` / `:hard`) reads one computed style per element, with one
escape hatch for CSS **blockification**: an absolutely- or fixed-positioned element computes to
`display:block` whatever the author wrote, so a positioned `<span>` is classified by its tag instead. The two
cases that force this are opposite and both real — pdf.js text runs are `position:absolute` `<span>`s that
*must* fuse, while split-diff cells are `<span>`s in a `display:grid` row that *must not*.

## 3. Cost analysis

Let $`T = |B|`$ be the flattened text length, $`N`$ the number of visible text nodes, $`S`$ the number of
segment quads, $`M`$ the number of matches, and $`q`$ the query length.

| Step | Cost | Note |
|---|---|---|
| walk + `build` | $`O(T + N)`$ time, $`O(T + S)`$ space | one pass; $`S \in O(N)`$ |
| computed style | $`O(\Sigma)`$, $`\Sigma`$ = distinct element *shapes* | memoized by `parentTag\|parentClass\|tag\|class` |
| `scan` | $`O(T \cdot q)`$ worst case, $`O(T)`$ in practice | V8's `indexOf` is sublinear on real text |
| `locate` | $`O(\log S)`$, called $`2M`$ times | binary search over the quads |
| `paint!` | $`O(M)`$ | two `CSS.highlights.set` calls |

Total: $`O(T + M \log N)`$ — the same order as the single-node matcher it replaced, and **with a lower
per-keystroke constant**, because searching is debounced and the buffer depends only on the DOM, not on the
query.

The $`O(\Sigma)`$ row is not a footnote. The first version of this walk called `Element.checkVisibility()`
per element; on a 7 005-record streamed log that forced layout tens of thousands of times and pushed the
whole Electron smoke past its timeout. Folding visibility and boundary classification into one memoized
style resolution took the suite from a 240-second timeout back to under two minutes. The lesson is recorded
in [scientific/09 §8.1](../scientific/09-in-page-find-and-scroll-experiments.md): *a correctness rewrite of
a subsystem that walks the whole DOM is a performance change whether or not it is intended as one.*

## 4. Invalidation, and a second payoff of not mutating the DOM

Ranges are live DOM objects. When the document underneath them changes they become meaningless — or worse,
plausible-looking but detached, so the counter advances while the view does not move.

The shell stamps its Ranges with the content pane's `data-doc-key` and watches the pane with a
`MutationObserver`. Two situations, opposite responses:

| Situation | Response |
|---|---|
| the doc-key changed — a **different** document or facet | clear everything |
| the same document, changed nodes — a live refresh, a streamed append, a MathJax/mermaid post-pass, a PDF text layer arriving | **re-collect** for the stored query, clamping the cursor to the new count |

Here the no-DOM-mutation design pays a second time: **`CSS.highlights.set` is not a DOM mutation**, so
painting cannot re-trigger the observer. There is no feedback loop to break, and none had to be designed
around. Neither does rendering or un-rendering a `content-visibility` block, so scrolling a streamed
document does not thrash the flag either.

## 5. Why it composes with `innerHTML`

This is the architectural payoff and the reason find lives where it does. The document body is owned
imperatively (`innerHTML`, Theory 05); find *describes* positions in that body without *changing* it.
Therefore:

- **find and the body are orthogonal.** A live refresh rewrites `:doc/html` → `innerHTML`; the refresh never
  has to know find exists. The invariant from Theory 03 — that a refresh writes only `:doc/*` — is untouched,
  because painting highlights is not a `:doc/*` write; it is DOM-side state in the finder's own atom plus
  `CSS.highlights`.
- **no boundary bookkeeping.** Because nothing is spliced, there is nothing to un-splice when the query
  changes — `clear!` deletes two named highlights and resets the atom.

## 6. Graceful degradation

Every entry point is guarded:

```clojure
(defn- supported? [] (and (exists? js/CSS) (.-highlights js/CSS) (exists? js/Highlight)))
```

On an engine without the API, find **paints nothing** rather than erroring or falling back to DOM mutation.
The bar still opens, accepts a query, and reports counts — the count comes from the scan, which needs no
highlight API. The document body is therefore *never* altered, on any platform.

## 7. How find re-enters the re-frame loop

`search!` and `cycle!` are renderer-side; they are reached **only through effects**, and their results
re-enter as events, so the core stays pure (Theory 01/04).

```clojure
(rf/reg-fx :find/search
           (fn [{:keys [q gen]}]
             (-> (pdf-cache/ensure-active!)
                 (.then (fn [_] (rf/dispatch [:find/result (assoc (finder/search! q) :gen gen)]))))))
(rf/reg-fx :find/cycle (fn [{:keys [dir gen]}]
                         (rf/dispatch [:find/result (assoc (finder/cycle! dir) :gen gen)])))
```

Both carry a **generation**. Searching is asynchronous — `ensure-active!` first materializes a PDF's text
layers or drains a streamed document, so that find covers the whole document rather than the rendered
prefix — which means two searches can be in flight and an earlier, shorter query's reply can land last.
`:find/result` drops any reply whose generation is not current. The same counter collapses the input
debounce, so one mechanism serves both concerns.

Both effects report `{:count :idx}` rather than a single scalar, because **cycling can change the count**:
reconciling stale Ranges against a changed document is part of cycling, and a count-only / index-only event
pair could not express it.

## 8. Summary

- In-page find paints matches with the **CSS Custom Highlight API** — `Range`s collected into named
  `Highlight`s and styled by `::highlight(…)`, **without inserting any DOM elements**.
- Matching flattens the visible text into a normalized buffer $`B`$ plus a partial map $`\lambda`$ back to
  nodes, so a match may **span nodes** — necessary for `<code>`-straddling phrases in Markdown and for
  essentially every multi-word query in a PDF.
- Block containment is a *consequence* of query normalization (trimming) rather than a separate rule:
  separators can never be a match endpoint, and a normalized query can never contain a newline.
- Cost is $`O(T + M \log N)`$, with computed-style work bounded by the number of distinct element
  **shapes**, not elements.
- Invalidation distinguishes "different document" (clear) from "same document, changed nodes"
  (re-collect) — and cannot feed back on itself, because painting is not a mutation.
- Results re-enter through `:find/result` with a **generation**, which drops stale asynchronous replies and
  doubles as the debounce.

Next: [Theory 07 — the Command history model](07-command-history-model.md).

## References

- W3C. *CSS Custom Highlight API Module Level 1.* <https://www.w3.org/TR/css-highlight-api-1/> —
  `Highlight`, `CSS.highlights`, `::highlight()` (W3C spec; no DOI).
- MDN. "Range," "TreeWalker," "CSS Custom Highlight API," "content-visibility."
  <https://developer.mozilla.org/> — DOM Range and TreeWalker semantics.
- W3C. *CSS Display Module Level 3*, §2.7 "Blockification". <https://www.w3.org/TR/css-display-3/> — why an
  absolutely-positioned `<span>` computes to `display:block`.
- Unicode Consortium. *Unicode Standard Annex #21: Case Mappings.* <https://www.unicode.org/reports/tr21/> —
  why lower-casing is not length-preserving in general.
- re-frame documentation. <https://day8.github.io/re-frame/> — the effect → event re-entry used to bank
  results into `app-db`.
