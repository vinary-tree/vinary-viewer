# 0044 — One browser link-gesture family: background opens, middle-click close, web-view popup routing

- **Status:** Accepted
- **Date:** 2026-08-13
- **Deciders:** vinary-viewer maintainers

## Context

The app grew its link gestures one surface at a time, and they had drifted apart:

- **Preview content links** had middle-click → new tab since 2026-06-25 (a delegated `auxclick`
  handler on the content node), and Ctrl+click → new tab. Both opened the tab **focused**.
- **File-tree rows** (`a.vv-file`, which carry no `href`) had Ctrl+click → `:doc/open-new` and no
  middle-click handling at all: a middle-click on the tree did nothing whatsoever.
- **Directory-browser rows** and the **address-bar breadcrumbs** likewise ignored middle-click
  (the breadcrumbs' `href="#"` made it a denied popup request — a silent no-op).
- **Tabs** could not be closed with the middle button.
- **Links inside the browsed web view** were the sharpest edge: `setWindowOpenHandler` returned
  `{action: "allow"}` for every non-PDF popup, so a middle-click or `target=_blank` inside a page
  opened a **real native window** — outside the app's tab model, its chrome, and its navigation
  history.

The user reported this as "middle-click opens a new tab, including in the file tree — that feature
was lost, or its file-tree integration is broken." The archaeology says otherwise, and the
distinction matters for the fix: `git log -S auxclick` finds exactly two commits, neither of which
removed anything — the tree never had the gesture. What *was* broken is the promise:
`docs/features/07-navigation-history.md` documented "`Ctrl+click` or middle-click" as a global
behavior, which was true only inside the preview. So this ADR is not a restoration; it is the
generalization the docs already claimed.

A second, deliberate departure from the previous behavior: opening a new tab **focused** is not
what a browser does. Middle-click and Ctrl+click exist precisely so the reader can queue a
destination *without leaving the page they are reading*.

## Decision

**One gesture family, applied uniformly to every link-like surface, with a background-open
primitive underneath it.**

### The gesture table

Modes: `:same` (navigate in place) · `:new-background` (new tab, active tab unchanged) ·
`:new-focused` (new tab, focused).

| Surface | Plain | Ctrl+click | Ctrl+Shift+click | Middle | Shift+middle |
| --- | --- | --- | --- | --- | --- |
| Preview content links (`a[href]` in markdown/org/LaTeX/diff, batch + streaming) | `:tab/navigate` | `:tab/open-background` | `:tab/open` | `:tab/open-background` | `:tab/open` |
| Same-document anchors / TOC entries | `:toc/goto` — **all** gestures | ″ | ″ | ″ | ″ |
| File-tree file rows + ADR-0038 extras | `:doc/open` (single-click on Linux, double elsewhere) | `:tab/open-background` | `:doc/open-new` | `:tab/open-background` | `:doc/open-new` |
| Directory-browser rows + the `..` parent row | unchanged (`:dir/select` + open / `:nav/parent`) | `:tab/open-background` | `:doc/open-new` | `:tab/open-background` | `:doc/open-new` |
| Address-bar breadcrumbs | *(n/a — crumbs exist only while Ctrl is held)* | `:tab/navigate` | `:tab/open` | `:tab/open-background` | `:tab/open` |
| Links inside the web view | in-view navigation | disposition `background-tab` → background app tab | `foreground-tab`/`new-window`/other → focused app tab | background app tab | focused app tab |
| Tab strip `.vv-tab` + vertical Tabs panel `.vv-vtab` | activate | activate | activate | **`:tab/close`** | `:tab/close` |

**Always-new for the new gestures.** A Ctrl-/middle-clicked tree or directory row opens *another*
tab even when that file is already open — the browser's meaning of the gesture ("give me this in a
new tab"), and the same semantics preview links have always had (`:tab/open`). The
focus-existing-else-new behavior (`:doc/open-new`) is preserved under **Ctrl+Shift**, so nothing
that existed became unreachable.

### `nav/add-tab-background` — the primitive that was missing

Every previous new-tab path funnels through `nav/add-tab`, which sets `[:ui :active-tab]`; there
was no way to create a tab without focusing it. `add-tab-background` is `add-tab` minus that one
line (same `mk-tab` shape, same id counter). The event `:tab/open-background` uses it and
deliberately differs from `:tab/open`:

- no `:view-pos` cofx and no `nav/save-scroll` — the active view is not being left, so there is no
  leaving position to capture;
- no `[:scroll/restore]` — the fx is the content load alone (`load-fx` → `[:vv/open path]`), and
  the position restore happens through `:tab/activate` if and when the user visits the tab;
- with **no** active tab (a fresh window, every tab closed), "background" is meaningless and the
  event degenerates to `:tab/open` rather than leaving the window showing nothing.

Content arrives through the ordinary `vv:open → vv:content → :content/received` path, which is
keyed by `:doc/path` and has no active-tab dependency; retention keeps the file because
`nav/retained-file-paths` walks **every** tab. One consequence had to be fixed for background
opens to be honest: `:content/received` stored a fresh group doc's default facet only when the doc
was the *active* one, so a collocated group (e.g. `paper.tex` + `paper.pdf`) opened into a
background tab would later activate to a facet whose file had never been loaded. The fresh-facet
resolution is now scoped to the **owning tabs** (every tab holding that uri with no facet yet)
rather than the active one. The MRU / Open Recent recording stays active-only: a background open
enters Open Recent when it is actually visited.

### The web view stops opening native windows

`setWindowOpenHandler` now **denies every request** and routes the tab-worthy ones:

- a PDF keeps its existing behavior (routed into the app's pdf.js viewer on the owner tab);
- an `http(s)` target is relayed to the owner window's renderer over a new `vv:web-open-tab`
  channel carrying `{url, mode}`, where mode comes from Chromium's `details.disposition`
  (`background-tab` — what a middle-/Ctrl-click reports — → background; everything else → focused);
- anything else (`about:blank`, `javascript:`, `data:`, custom schemes) is refused outright.

The two decisions are pure functions in a new **`vinary.main.web-policy`** namespace with zero
Electron requires, so they are unit-tested in the node test build (the `vinary.main.doc-overrides`
precedent) instead of being reachable only through a live web view. Trade-off accepted: page
scripts calling `window.open` now receive `null`. That is strictly safer than the previous
`{action: "allow"}`, which let a page conjure a window outside the app's model entirely.

### Scope boundaries (deliberately unchanged)

Context-menu **"Open in new tab"** items stay focused (`:doc/open-new` / `:tab/open`) — a menu item
is an explicit action, not a gesture made while reading. Tree **directory summaries and project
headers** keep expand/collapse only (they are not links). **Same-document anchors** ignore the mode
entirely: a new tab scrolled to a heading of the document you are already reading is never what the
gesture meant. The multi-argument launch path (`:files/opened`) is untouched. New tabs **append at
the end** rather than next to their opener. No **Cmd** variant on macOS: the app's convention is
Ctrl everywhere, and adding a platform-conditional modifier belongs to a broader keymap decision.

### Handler placement — never window-level

Every handler is attached per-surface: the preview keeps its delegated listener on the content
node (and preventDefaults only when the event actually hits an `a[href]`), and the component
surfaces use reagent `:on-aux-click` props. A window-level `auxclick` handler was rejected: on
Linux, middle-click is the X11 PRIMARY-selection paste, and swallowing it globally would break
pasting into the find bar, the tree filter, and the address bar, as well as CodeMirror's own
middle-button conventions. `mouse-nav!` (buttons 3/4 → history back/forward) is untouched and
cannot collide — its `case` covers only those two buttons.

## Consequences

- Middle-click and Ctrl+click mean one thing everywhere: *queue this in the background*.
  Ctrl+Shift+click is the "open and go there" variant; plain click is unchanged.
- The previously focused preview Ctrl+click / middle-click now open in the background. This is a
  visible behavior change, and the one the browser convention demands.
- The file tree, directory browser, and breadcrumbs gained middle-click; the tab strip and Tabs
  panel gained middle-click-to-close (they share one `tab-item`, so both surfaces got it at once).
- A web page can no longer open a native window; its popups become ordinary app tabs, participating
  in the tab model, history, and retention like everything else. Documented in the threat model.
- Docs that promised middle-click globally are now true rather than aspirational.

## Verification

`nav_test` pins `add-tab-background` (appended, active untouched, id counter advanced, history
shape identical to `add-tab`, retained set includes its file, and a byte-identical tab vector to
`add-tab` differing only in focus). `web_policy_test` pins the disposition and url matrices.
`core_test`'s preview-navigation block pins `click-mode` and the `open-event` mode matrix,
including anchors ignoring the mode. The electron smoke suite drives all five surfaces plus both
tab representations with real DOM events in **both** build flavors, and `tree-e2e` proves the two
halves that only a real process can: a background tab's content genuinely travelling main's
`open!` → `vv:content` chain while the tab is unfocused, and the production
`setWindowOpenHandler` turning `window.open` and a real middle-click inside a web page into app
tabs with **no** new `BrowserWindow`.
