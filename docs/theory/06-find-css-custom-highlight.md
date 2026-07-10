# Theory 06 — In-Page Find with the CSS Custom Highlight API

> **Where this fits.** Theory 03 listed in-page find among the UI state a refresh
> must *not* disturb, and noted it paints highlights without mutating the DOM. This
> document explains *how*: the **CSS Custom Highlight API**, which styles arbitrary
> **Range**s through `CSS.highlights` and `::highlight()` **without inserting a
> single element**. That property is exactly what lets find coexist with the
> imperative-`innerHTML` document body (Theory 05). We give the algorithms in
> literate-programming form, analyse their cost, and show how match counts re-enter
> the re-frame loop.

## 1. The problem: highlight without touching the document

Classic in-page find implementations wrap each match in a `<mark>` element. That
**mutates the DOM**, and here it would be actively harmful:

- the document body is written as a single foreign HTML string via `innerHTML`
  (Theory 05) — splicing `<mark>` tags into it would fight that ownership and could
  desynchronise the body from `:doc/html`;
- inserting elements **shifts node boundaries**, which would break the heading
  `id` anchors the TOC and `:toc/scroll` rely on, and would have to be carefully
  *un*-spliced when the query changes.

The **CSS Custom Highlight API** (W3C) avoids all of this. You build **Range**
objects (pure descriptions of "from offset *i* to offset *j* in this text node"),
collect them into a named **Highlight**, register it in `CSS.highlights`, and style
it with the `::highlight(name)` pseudo-element. **No element is inserted; no text
node is split.** The document tree is identical before and after; only its
*painting* changes.

vinary-viewer registers two highlights:

- **`"vv-find"`** — *all* current matches, styled by `::highlight(vv-find)`.
- **`"vv-find-current"`** — the *single focused* match, styled by
  `::highlight(vv-find-current)`.

## 2. The three algorithms, literately

The whole feature is three small functions in `vinary.renderer.find`. We present
each in literate-programming form: the intent and invariants in prose, then the
pseudocode, then the actual ClojureScript.

### 2.1 `collect-ranges` — find every match as a Range

> **Intent.** Given a root element and a query, return a vector of DOM Ranges, one
> per case-insensitive occurrence of the query, each lying **within a single text
> node**. Searching within single text nodes (rather than across the flattened
> document text) keeps each Range trivially constructible from one
> `(node, start, end)` triple and is sufficient for ordinary prose matches.
>
> **Invariant.** Every returned Range `r` satisfies
> `r.startContainer = r.endContainer` (a single text node) and
> `r.endOffset − r.startOffset = |query|`.

```
algorithm COLLECT-RANGES(root, query):
    q  ← lower-case(query)
    ql ← length(q)
    R  ← empty list
    if root = nil or ql = 0: return R
    walker ← TreeWalker(root, SHOW_TEXT)           ▷ visit only text nodes
    while node ← walker.nextNode():
        text ← lower-case(node.textContent)
        from ← 0
        loop:
            i ← indexOf(text, q, from)             ▷ next match at/after `from`
            if i < 0: break                        ▷ no more matches in this node
            r ← Range(); r.setStart(node, i); r.setEnd(node, i + ql)
            append r to R
            from ← i + ql                          ▷ continue past this match (non-overlapping)
    return R
```

```clojure
;; vinary.renderer.find (excerpt)
(defn- collect-ranges [root query]
  (let [q (str/lower-case query), ql (count q), ranges (array)]
    (when (and root (pos? ql))
      (let [walker (.createTreeWalker js/document root js/NodeFilter.SHOW_TEXT nil)]
        (loop []
          (let [node (.nextNode walker)]
            (when node
              (let [text (str/lower-case (or (.-textContent node) ""))]
                (loop [from 0]
                  (let [i (.indexOf text q from)]
                    (when (>= i 0)
                      (let [r (.createRange js/document)]
                        (.setStart r node i) (.setEnd r node (+ i ql))
                        (.push ranges r))
                      (recur (+ i ql))))))
              (recur))))))
    (vec ranges)))
```

The `from ← i + ql` step means matches are **non-overlapping** (e.g. searching
`"aa"` in `"aaaa"` yields two matches at offsets 0 and 2, not three). Matching is
**case-insensitive** because both the node text and the query are lower-cased
before `indexOf`.

### 2.2 `paint!` — register the highlights

> **Intent.** Given the ranges and the focused index, register *all* ranges under
> `"vv-find"` and the focused range under `"vv-find-current"`. This is the only
> step that touches `CSS.highlights`; it replaces any previous registration
> wholesale.
>
> **Guard.** Do nothing unless the API is `supported?` (§4).

```
algorithm PAINT(ranges, idx):
    if not supported(): return
    all ← new Highlight()
    cur ← new Highlight()
    for each r in ranges: all.add(r)
    if ranges ≠ ∅ and idx < |ranges|: cur.add(ranges[idx])
    CSS.highlights.set("vv-find",         all)
    CSS.highlights.set("vv-find-current", cur)
```

```clojure
(defn- paint! [ranges idx]
  (when (supported?)
    (let [all (js/Highlight.), cur (js/Highlight.)]
      (doseq [r ranges] (.add all r))
      (when (and (seq ranges) (< idx (count ranges))) (.add cur (nth ranges idx)))
      (.set (.-highlights js/CSS) "vv-find" all)
      (.set (.-highlights js/CSS) "vv-find-current" cur))))
```

### 2.3 `search!` and `cycle!` — run a query, move the cursor

> **Intent (`search!`).** Recompute matches for a query, paint them, focus and
> scroll the **first**, and **return the count** (so the caller can put it in
> `app-db`). A blank query clears everything and returns 0.

```
algorithm SEARCH(query):
    if blank(query): CLEAR(); return 0
    R ← COLLECT-RANGES(content-root(), query)
    state ← {ranges: R, idx: 0}
    PAINT(R, 0)
    SCROLL-TO(R, 0)                                 ▷ scrollIntoView {block: center}
    return |R|
```

> **Intent (`cycle!`).** Move the focused match by `dir` (+1 next, −1 previous),
> **wrapping** at the ends, repaint the current highlight, scroll to it, and return
> the **1-based** index (0 if there are no matches). The wrap is modular:
> $`\mathit{idx} := (\mathit{idx} + \mathit{dir}) \bmod n`$.

```
algorithm CYCLE(dir):
    {R, idx} ← state
    n ← |R|
    if n = 0: return 0
    idx′ ← (idx + dir) mod n                        ▷ wraps: −1 at 0 → n−1; +1 at n−1 → 0
    state.idx ← idx′
    PAINT(R, idx′); SCROLL-TO(R, idx′)
    return idx′ + 1                                 ▷ 1-based for display "idx/n"
```

```clojure
(defn search! [query]
  (if (str/blank? query)
    (do (clear!) 0)
    (let [ranges (collect-ranges (content-root) query)]
      (reset! state {:ranges ranges :idx 0})
      (paint! ranges 0) (scroll-to! ranges 0)
      (count ranges))))

(defn cycle! [dir]
  (let [{:keys [ranges idx]} @state, n (count ranges)]
    (if (pos? n)
      (let [idx' (mod (+ idx dir) n)]
        (swap! state assoc :idx idx')
        (paint! ranges idx') (scroll-to! ranges idx')
        (inc idx'))
      0)))
```

Internally the cursor is **0-based** (`idx`), but `cycle!` and the count event
expose it **1-based** (`inc idx'`), which is what the find bar shows as `"idx/n"`.
Scrolling uses `scrollIntoView {block: "center" behavior: "smooth"}` on the matched
node's parent element, so the focused match lands centred.

## 3. Cost analysis

Let `T` be the total length of visible text under the content root and `M` the
number of matches.

- **`collect-ranges`** visits every text node once via the `TreeWalker` and runs
  `indexOf` along each node's text. Across all nodes the scanning is linear in the
  text length, and it emits one Range per match, so its cost is
  $`O(T + M) = O(T)`$ (since $`M \leq T`$). We summarise this as **`O(total_text_length)`**.
- **`paint!`** adds `M` ranges to a Highlight and does two `CSS.highlights.set`
  calls: `O(M)`.
- **`cycle!`** is `O(1)` arithmetic plus an `O(M)` repaint of the current
  highlight (it re-`paint!`s; the dominant cost is re-adding the `M` ranges to
  `"vv-find"`). The cursor move itself is constant time: $`\mathit{idx} := (\mathit{idx} + \mathit{dir}) \bmod n`$.

So a fresh search is **linear in the document's text length** and cycling is
effectively constant for the user. There is no super-linear blow-up regardless of
match count, because matches are non-overlapping and each costs `O(1)` to record.

## 4. Graceful degradation

The CSS Custom Highlight API is relatively recent, so every entry point is guarded
by a feature check, and the feature **degrades to a no-op** rather than erroring or
falling back to DOM mutation:

```clojure
(defn- supported? [] (and (exists? js/CSS) (.-highlights js/CSS) (exists? js/Highlight)))
```

`paint!` and `clear!` both early-return when `supported?` is false. The consequence
is important and deliberate: **if the API is unavailable, find simply paints
nothing — it never mutates the document as a fallback.** The find bar still opens,
accepts a query, and reports counts (the count comes from `collect-ranges`, which
does not need the highlight API); only the *visual* highlighting is absent. The
document body is therefore *never* altered on any platform.

## 5. Why it composes with `innerHTML`

This is the architectural payoff and the reason find lives where it does. The
document body is owned imperatively (`innerHTML`, Theory 05); find paints over it
with Ranges that **describe** positions in that body without **changing** it.
Therefore:

- **find and the body are orthogonal.** A live refresh rewrites `:doc/html` →
  `innerHTML`; find's highlights are derived from the *current* DOM text and can be
  re-run, but the refresh never has to know find exists. (After a refresh that
  changes the text, the existing Ranges may no longer match; re-running the query
  recomputes them. The *invariant* from Theory 03 — that the refresh writes only
  `:doc/*` — is untouched, because painting highlights is not a `:doc/*` write; it
  is DOM-side state in `vinary.renderer.find`'s `state` atom plus `CSS.highlights`.)
- **no boundary bookkeeping.** Because nothing is spliced, there is nothing to
  un-splice when the query changes — `clear!` just deletes the two named highlights
  and resets the atom.

## 6. How find re-enters the re-frame loop

`search!` and `cycle!` are *renderer-side* (DOM) functions; they are reached
**only through effects**, and their results re-enter the loop as events, so the
core stays pure (Theory 01/04). The round trip:

```clojure
;; effects (vinary.app.fx)
(rf/reg-fx :find/run   (fn [q]   (rf/dispatch [:find/count (finder/search! q)])))
(rf/reg-fx :find/cycle (fn [dir] (rf/dispatch [:find/idx   (finder/cycle!  dir)])))
(rf/reg-fx :find/clear (fn [_]   (finder/clear!)))

;; events that bank the results into app-db :ui/find (vinary.app.events)
(rf/reg-event-db :find/count (fn [db [_ n]] (-> db (assoc-in [:ui :find :count] n)
                                                   (assoc-in [:ui :find :idx] (if (pos? n) 1 0)))))
(rf/reg-event-db :find/idx   (fn [db [_ i]] (assoc-in db [:ui :find :idx] i)))
```

So the **count** computed by the DOM search is dispatched as `[:find/count n]` and
stored in `app-db` `:ui/find :count`, with `:idx` set to 1 when there are matches
(0 otherwise). The find bar renders `"idx/count"` from that slice (Theory: the find
feature). The full open→type→run→count→cycle handshake is below. Source:
[`../diagrams/seq-find.puml`](../diagrams/seq-find.puml).

![In-page find sequence: Ctrl+F → type → :find/run → count → cycle](../diagrams/seq-find.svg)

And the find bar's own lifecycle — `Hidden → Visible(empty) → Visible(matches: n)`
with the cycle self-loops and `Escape → Hidden` — is the state machine below.
Source: [`../diagrams/state-find.puml`](../diagrams/state-find.puml).

![Find-bar state machine](../diagrams/state-find.svg)

## 7. Summary

- In-page find paints matches with the **CSS Custom Highlight API**: **Range**s
  collected into named **Highlight**s (`"vv-find"`, `"vv-find-current"`) and styled
  by `::highlight(...)`, **without inserting any DOM elements**.
- The three algorithms — `collect-ranges` (TreeWalker + case-insensitive
  `indexOf`, non-overlapping, within single text nodes), `paint!`, and
  `cycle!`/`search!` — were given literately; the cursor wraps via
  $`\mathit{idx} := (\mathit{idx} + \mathit{dir}) \bmod n`$ (0-based internally, 1-based for display).
- A fresh search is **`O(total_text_length)`**; cycling is effectively `O(1)` for
  the user.
- The feature **degrades to a no-op** when unsupported (guarded by `supported?`)
  and **never mutates the document**, which is exactly why it **composes with the
  imperative `innerHTML` body**.
- Results re-enter the loop via the `:find/*` effects and the `[:find/count]` /
  `[:find/idx]` events, which bank the count/index into `app-db` `:ui/find`.

Next: [Theory 07 — the Command history model](07-command-history-model.md).

## References

- W3C. *CSS Custom Highlight API Module Level 1.*
  <https://www.w3.org/TR/css-highlight-api-1/> — `Highlight`, `CSS.highlights`,
  `::highlight()` (W3C spec; no DOI).
- MDN. "Range," "TreeWalker," "CSS Custom Highlight API."
  <https://developer.mozilla.org/> — DOM Range and TreeWalker semantics.
- re-frame documentation. <https://day8.github.io/re-frame/> — the effect →
  event re-entry used to bank counts into `app-db`.
