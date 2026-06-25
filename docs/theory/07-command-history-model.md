# Theory 07 — The Command History Model

> **Where this fits.** Theory 01 named the **Command** pattern: re-frame events are
> reified requests, which is what makes them stackable and replayable. This
> document is the *application-level* payoff of that observation — the **navigation
> history**. Each document you visit is a reified Command on a stack; **Back** and
> **Forward** replay positions on that stack; and visiting a *new* document from a
> back position **truncates the forward branch**, exactly like a web browser. We
> derive the data structure, give the operations literately, prove the guards, and
> work a full example.

## 1. Navigation as reified Commands

When you open or activate a document, "go to this path" is an **intent**. The
Command pattern (Gamma, Helm, Johnson & Vlissides, 1994) says: *reify the intent as
data and record it*, so it can be stored, replayed, and undone. vinary-viewer does
precisely that — every navigation is recorded as a **path entry on a history
stack**, and Back/Forward are *replays* of earlier entries (a domain-level instance
of re-frame's own **time-travel/replay**, Theory 01 §4).

This means navigation history is **not** a side concern bolted onto tabs; it is the
Command pattern applied to "which document is in focus."

## 2. The data structure: `{:stack :idx}`

The entire history is two fields in `app-db` under `:ui/history`:

```clojure
;; initial value (vinary.app.db/default-db)
:history {:stack [] :idx -1}
```

- **`:stack`** — a vector of visited paths, **oldest first**. Index 0 is the
  earliest entry; the last index is the most recent.
- **`:idx`** — the **cursor**: the index of the *currently focused* entry within
  `:stack`. The sentinel `-1` means "before the first entry" (empty history).

The pair forms a classic **browser-style history**: a linear sequence with a cursor
that Back moves left and Forward moves right, and where a fresh navigation from a
non-end cursor discards everything to its right. The invariant maintained
everywhere is:

```
-1 ≤ idx ≤ (count stack) − 1      (and idx = -1 exactly when stack is empty)
```

> **Why a cursor instead of two stacks?** A common alternative is back-stack +
> forward-stack. The single-vector-plus-cursor form is equivalent but simpler to
> introspect (you can see the whole timeline and where you are in `window.__vvdb()`)
> and makes "truncate the forward branch" a single `take`/`conj` (§3.1) rather than
> clearing a separate stack.

## 3. The operations, literately

Three operations maintain the history: **record** (on navigation), **back**, and
**forward**. All three live in `vinary.app.events`.

### 3.1 `record-nav` — push, truncating the forward branch

> **Intent.** Record a navigation to `path`. If it equals the current entry, do
> nothing (no duplicate, no spurious truncation). Otherwise **drop everything after
> the cursor** and append `path`, moving the cursor to the new last entry.
>
> **Why truncate.** If you went Back and then navigate somewhere *new*, the old
> "forward" history is no longer reachable — it described a future you abandoned.
> Truncating it is the behaviour every browser has, and it keeps the timeline
> linear.

```
algorithm RECORD-NAV(db, path):
    {stack, idx} ← db.:ui/history
    if path = stack[idx]:                 ▷ same as current entry → no-op
        return db
    kept   ← take(idx + 1, stack)         ▷ entries up to AND INCLUDING the cursor
    stack′ ← conj(vec(kept), path)        ▷ append the new path (drops the forward branch)
    return assoc db.:ui/history ← {stack: stack′, idx: |stack′| − 1}
```

```clojure
;; vinary.app.events
(defn- record-nav [db path]
  (let [{:keys [stack idx]} (get-in db [:ui :history])]
    (if (= path (get stack idx))
      db
      (let [stack' (conj (vec (take (inc idx) stack)) path)]
        (assoc-in db [:ui :history] {:stack stack' :idx (dec (count stack'))})))))
```

The single line `stack′ ≔ (conj (vec (take (inc idx) stack)) path)` is the heart of
the model: `(take (inc idx) stack)` keeps entries `0..idx` (the cursor and its
past), `vec` makes it a vector, and `conj … path` appends the new entry — so any
entries at indices `> idx` (the abandoned forward branch) are dropped.

`record-nav` is invoked by **`nav-to`**, which is what every "focus this document"
path calls (open, tab-activate, content-received):

```clojure
(defn- nav-to [db path]
  (-> db (assoc-in [:ui :active-path] path) (record-nav path)))
```

Two events use it: `:tab/activate` (`reg-event-db … (nav-to db path)`) and
`:content/received` (which sets `:db (nav-to db path)`). Because `record-nav`
no-ops when `path` equals the current entry, a **live refresh of the active file
does not create a history entry** — re-receiving the same path is a no-op, which is
part of why refresh leaves navigation untouched (Theory 03 §3).

### 3.2 `:history/back` — move the cursor left

> **Intent.** If we can go back (`idx > 0`), decrement the cursor and set the
> active path to the entry now under it. Otherwise do nothing. **The stack is never
> modified** — Back is a pure cursor move.

```
algorithm BACK(db):
    {stack, idx} ← db.:ui/history
    if idx ≠ nil and idx > 0:
        return db with :ui/history.idx ← idx − 1
                   and :ui/active-path ← stack[idx − 1]
    return db
```

```clojure
(rf/reg-event-db
 :history/back
 (fn [db _]
   (let [{:keys [stack idx]} (get-in db [:ui :history])]
     (if (and idx (pos? idx))
       (-> db (assoc-in [:ui :history :idx] (dec idx))
              (assoc-in [:ui :active-path] (get stack (dec idx))))
       db))))
```

### 3.3 `:history/forward` — move the cursor right

> **Intent.** If we can go forward (`idx < (count stack) − 1`), increment the
> cursor and set the active path to the entry now under it. Otherwise do nothing.
> Again, the stack is untouched.

```
algorithm FORWARD(db):
    {stack, idx} ← db.:ui/history
    if idx ≠ nil and idx < |stack| − 1:
        return db with :ui/history.idx ← idx + 1
                   and :ui/active-path ← stack[idx + 1]
    return db
```

```clojure
(rf/reg-event-db
 :history/forward
 (fn [db _]
   (let [{:keys [stack idx]} (get-in db [:ui :history])]
     (if (and idx (< idx (dec (count stack))))
       (-> db (assoc-in [:ui :history :idx] (inc idx))
              (assoc-in [:ui :active-path] (get stack (inc idx))))
       db))))
```

## 4. The guards, and how the UI reads them

Back/forward are guarded so the cursor can never leave `[0, (count stack) − 1]`.
The *same* predicates drive the toolbar button states, so a disabled button and a
no-op event are guaranteed consistent:

```clojure
;; vinary.app.subs
(rf/reg-sub :history/can-back?
  (fn [db _] (let [{:keys [idx]} (get-in db [:ui :history])]
               (boolean (and idx (pos? idx))))))
(rf/reg-sub :history/can-forward?
  (fn [db _] (let [{:keys [stack idx]} (get-in db [:ui :history])]
               (boolean (and idx (< idx (dec (count stack))))))))
```

- **`:history/can-back?`** is true iff `idx > 0` — there is an earlier entry.
- **`:history/can-forward?`** is true iff `idx < (count stack) − 1` — there is a
  later entry.

The toolbar (`vinary.ui.views/toolbar`) renders the ← and → buttons `:disabled`
when the corresponding sub is false, and dispatches `[:history/back]` /
`[:history/forward]` on click. The same events are bound to **Alt+←** / **Alt+→**
globally (`vinary.renderer.core/keybindings!`, Theory 01 §5). So there are two
*triggers* (button, keybinding) for one *Command* — the textbook Command-pattern
decoupling of invoker from action.

## 5. A worked example: A → B → C → Back → D

This is the canonical sequence that demonstrates truncation. Watch `{:stack :idx}`
and the focused entry (shown `[…]`) at each step.

| Step | Action | `:stack` | `:idx` | Focused | Note |
|------|--------|----------|--------|---------|------|
| 0 | initial | `[]` | `-1` | — | empty; can-back? = can-forward? = false |
| 1 | visit **A** | `[A]` | `0` | `[A]` | `record-nav`: `A ≠ nil` → `(conj (vec (take 0 [])) A)` |
| 2 | visit **B** | `[A B]` | `1` | `[A B]` | `(conj (vec (take 1 [A])) B)` |
| 3 | visit **C** | `[A B C]` | `2` | `[A B C]` | `(conj (vec (take 2 [A B])) C)` |
| 4 | **Back** | `[A B C]` | `1` | `[A B] C` | cursor left; **stack unchanged**; can-forward? now true |
| 5 | visit **D** | `[A B D]` | `2` | `[A B D]` | `record-nav`: `(conj (vec (take 2 [A B C])) D)` → **C is dropped** |

Step 5 is the payoff: at the moment of step 4 the cursor was at index 1 (focused
**B**) with **C** sitting at index 2 as the "forward" entry. Navigating to **D**
calls `record-nav` with `idx = 1`, so `(take (inc 1) [A B C]) = (take 2 …) = [A B]`,
and `(conj [A B] D) = [A B D]`. The abandoned future **C** is gone; Forward from
the new state is disabled (`idx = 2 = (count stack) − 1`). This is exactly how a web
browser behaves when you go back and then follow a new link.

The Back operation as a sequence — guard, pure cursor move, sub recompute, body
swap (and note it never re-reads the file, because the target doc is already a
DataScript entity) — is below. Source:
[`../diagrams/seq-history.puml`](../diagrams/seq-history.puml).

```plantuml
!include ../diagrams/seq-history.puml
```

![Navigation history: Alt+← → cursor move → :doc/active → body swap](../diagrams/seq-history.puml)

## 6. Relationship to tabs and DataScript

History and tabs are **independent** concerns, joined only through `:active-path`:

- **Tabs** are the *open documents* (DataScript `:doc/open?` entities, the `:tabs`
  sub). They have an *order* (`:doc/order`) that is unrelated to *visit* order.
- **History** is the *visit timeline* of `:active-path` values. The same path can
  appear multiple times in `:stack` (you can revisit), whereas a path appears at
  most once as a tab.

Back/Forward set `:active-path`; the `:doc/active` sub then pulls *that* document
from DataScript (Theory 02). Because the document is already an open entity,
**navigating history never re-reads the file or re-renders Markdown** — it is a pure
`app-db` cursor move plus a sub recompute. (One consequence: closing a tab does not
prune the history stack; a history entry for a closed path would, on navigation,
set `:active-path` to a path with no open `:doc` entity, and `:doc/active` would
return `nil` — the content-view would show the "Rendering…"/empty state for it. The
history stack is intentionally a *navigation log*, not a *liveness index*.)

> **«Forthcoming (planned).»** The history is presently an **unbounded** vector. A
> natural future refinement is to bound it with a **ring buffer** (a fixed-capacity
> circular queue) so long sessions cannot grow it without limit; this is an
> internal change to `:stack`'s representation that would preserve the operations
> and guards above. Tracked in [`../design-decisions/`](../design-decisions/README.md).

## 7. Summary

- Navigation is the **Command** pattern at the application level: each visit is a
  reified entry on a history stack; Back/Forward **replay** entries.
- The structure is **`{:stack :idx}`** — a vector of visited paths (oldest first)
  and a cursor; `-1` means empty.
- **`record-nav`** appends a path while **truncating the forward branch** via
  `stack′ ≔ (conj (vec (take (inc idx) stack)) path)`, and **no-ops** when the path
  equals the current entry (so a refresh adds no entry).
- **`:history/back`** / **`:history/forward`** are **pure cursor moves** (the stack
  is never modified), guarded by `:history/can-back?` (`idx > 0`) /
  `:history/can-forward?` (`idx < count − 1`), which also drive the toolbar buttons;
  the same Commands are bound to **Alt+←/→**.
- The worked **A→B→C→Back→D** example shows the forward branch (**C**) being
  truncated, exactly like a web browser.
- History is **independent of tabs** (joined only by `:active-path`) and navigating
  it **never re-reads or re-renders** the target document. A **ring-buffer** bound
  is **planned**.

This completes the theory pillar. For the concrete wiring, continue to the
[architecture pillar](../architecture/01-overview.md) and the
[reference tables](../reference/events-effects-subs.md).

## References

- Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (1994). *Design Patterns.*
  Addison-Wesley. **ISBN 978-0201633610** (no DOI). — Command.
- re-frame documentation. <https://day8.github.io/re-frame/> — events as data;
  `reg-event-db`/`reg-sub`; time-travel/replay.
