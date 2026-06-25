# 0005 — Model absent attributes as absence (omit/retract), never nil datoms

- **Status:** Accepted
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

A `:doc` entity carries optional attributes: `:doc/text` (present for markdown/text, **absent** for
images), `:doc/html` (present once rendered), `:doc/error` (present only when a read/render failed).
A document's lifecycle flips these on and off — e.g. a file that errored, then was fixed, should **lose**
its `:doc/error`.

The hard constraint: **DataScript rejects `nil` values.** You cannot assert `[:db/add eid :doc/text
nil]` to mean "this doc has no text." So "no value" has to be represented some other way.

## Decision

Represent "no value" as **the datom not existing** — omit the attribute when transacting, and
**retract** it when it should go away. Never store a `nil`.

The content event (`vinary.app.events/:content/received`) builds the transaction with `cond->`, adding
`:doc/text` **only when it is truthy**, and retracts a stale error explicitly:

```clojure
;; vinary.app.events/:content/received (essence)
(let [cur-err (and eid (ds/doc-attr snap path :doc/error))
      base    (cond-> {:doc/path path :doc/kind kind :doc/open? true :doc/order order}
                text (assoc :doc/text text))                ; omit :doc/text for images (nil text)
      tx      (cond-> [base]
                cur-err (conj [:db/retract eid :doc/error cur-err]))]  ; clear a stale error
  …)
```

Reads then treat **absence as the empty/none case**: `ds/active-doc` `d/pull`s
`[:doc/path :doc/kind :doc/html :doc/error]`, and a missing key is simply not in the result map; the
view's Strategy checks `(:doc/error doc)` / `(:doc/html doc)` truthiness
(`vinary.ui.views/content-view`).

## Consequences

- **No `nil` datoms ever reach DataScript**, so transactions never fail on a nil value.
- **State transitions are explicit and correct.** A document that errored and then loaded successfully
  has its `:doc/error` **retracted** in the same transaction that adds the new content, so the error
  view does not linger. (The Strategy precedence shows error before html, so a stale error would
  otherwise mask a good render.)
- **Queries are simple.** "Has text?" / "has html?" / "has error?" reduce to truthiness checks on a
  pulled map; absence and falsy collapse to the same "none" branch the views already handle.
- **Images fit naturally.** Images carry **no** `:doc/text`; the renderer displays them by `file://`
  path, and nothing has to invent a placeholder text value.

## Alternatives considered

- **Store sentinel values** (e.g. `:doc/error ""` for "no error"). Rejected: it pollutes the data with
  fake values, forces every reader to special-case the sentinel, and is easy to get wrong (is `""` an
  error message or "no error"?). Absence is unambiguous.
- **A boolean "has-error?" flag alongside the message.** Rejected: redundant state that can drift out of
  sync with the message; retracting the single `:doc/error` datom is atomic and cannot desync.

## Trade-offs

- We accept that each optional attribute needs a tiny bit of **transaction discipline** — `cond->` to
  omit, and an explicit `:db/retract` to clear — instead of blindly `assoc`-ing every field. In return
  the database holds only meaningful datoms, reads treat absence as the natural empty case, and
  DataScript's no-nil rule is satisfied by construction. This discipline lives in one place
  (`:content/received`), so it is cheap to maintain.
