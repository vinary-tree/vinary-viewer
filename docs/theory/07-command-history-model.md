# Theory 07. Command History Model

Navigation history is a Command/Memento-style model over per-tab history entries.
Each tab owns its own timeline.

---

## 1. Entry shape

```clojure
{:uri "/abs/path/doc.md"
 :scroll 320}
```

A tab stores:

```clojure
{:hist {:stack [{:uri "/a.md" :scroll 0}
                {:uri "/b.md" :scroll 320}]
        :idx 1}}
```

The cursor `idx` points at the current entry. Back and Forward move the cursor;
new navigation from a non-final cursor truncates the forward branch.

---

## 2. Operations

| Operation | Behavior |
|-----------|----------|
| Navigate active tab | Save current scroll, truncate forward branch, append `{uri, scroll 0}`, set current URI. |
| Back | Save current scroll, decrement `idx`, return target URI and target scroll. |
| Forward | Save current scroll, increment `idx`, return target URI and target scroll. |
| Activate tab | Save leaving tab scroll and restore target tab's current entry scroll. |

The implementation lives in `vinary.app.nav`; event handlers in
`vinary.app.events` add effects for loading local files and applying scroll
restore.

---

## 3. Retention consequence

History entries are ownership roots. A local file reachable from a background tab
history remains retained, watched, and cached. Closing or navigating away releases
a file only when no open tab history can reach it.

---

## 4. Browser semantics

The model intentionally matches browser history:

```text
A -> B -> C
Back to B
Navigate to D
history becomes A -> B -> D
```

The abandoned forward entry `C` is dropped.

---

## 5. References

- Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (1994). *Design Patterns:
  Elements of Reusable Object-Oriented Software.* Addison-Wesley. ISBN
  978-0201633610.
- ADR-0010: [../design-decisions/0010-bounded-content-retention-and-render-metadata.md](../design-decisions/0010-bounded-content-retention-and-render-metadata.md)
