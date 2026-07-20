# File tree and filter

![The sidebar file tree, narrowed by a live filter](../screenshots/git-file-tree.png)

*The sidebar file tree, narrowed by a live filter.*

**Status: Available now.**

---

## 1 · What it is

When you open a file, vinary-viewer shows a **sidebar tree** of the project it belongs to — the Files
tab. A project is one of two things:

- a **git repository**, listed with `git ls-files --cached --others --exclude-standard`, so it shows
  everything in the repo **except** `.gitignore`d clutter — tracked files **plus**
  untracked-but-not-ignored ones. A file you just created, including the one you opened, shows up
  immediately, while build output and `node_modules` stay out;
- for a file that belongs to **no** repository, its **containing directory**, walked directly. This is a
  *synthetic* root: an inference rather than a fact, which is why it behaves slightly differently when
  roots overlap (§4). Scratch notes in `/tmp`, a downloaded PDF, a standalone `.org` file — all are
  navigable.

Several projects can be open at once: the sidebar keeps **one collapsible tree per project**, rooted at
the project directory's name. Folders are collapsible (native HTML `<details>`), files are clickable to
open in a tab, and a **filter box** at the top narrows **across all projects** to files whose path
matches what you type (folders containing a match are force-expanded so the matches are visible).

The tree is a convenience for navigating a docs/source repository — or a directory of notes — without
leaving the previewer: open one file, then jump around from the sidebar.

---

## 2 · How to use it

1. Open any file, e.g. `vv docs/README.md` from within a checked-out project, or `vv ~/notes/todo.md`
   from a directory that is not a repo.
2. The sidebar shows the project (its top folder name as the header) as a collapsible tree.
3. **Open a file:** click its entry. It opens in a tab (or activates an existing tab). Ctrl+click opens
   it in a new tab.
4. **Collapse/expand a folder:** click the folder name (it is a native `<details>`/`<summary>`).
5. **Filter:** type in the *Filter files…* box. The tree shrinks to matching files across every open
   project, and every folder on the path to a match is expanded so you can see them.
6. **Remove a project:** right-click a project header → **Remove from Files**. It returns if you open a
   file under it again.

**Example.** Open `vv src/vinary/main/core.cljs`. The sidebar shows the whole repo. Type `theme` into
the filter; the tree collapses to just the files whose path contains "theme" (e.g.
`resources/public/css/themes/spacemacs-dark.css`), with their parent folders opened. Clear the box to
restore the full tree.

> If `git` is unavailable, a file inside a repository still gets a tree — it simply falls back to the
> directory walk, as a file outside a repository does.

---

## 3 · How it works internally

### MAIN process: find the project, list its files

`src/vinary/main/service.cljs` tries git first. It shells out twice — once to find the repository root,
once to list its tracked **and untracked-but-not-ignored** files:

```clojure
(defn- git [args cwd]
  (try
    (str/trim (cp/execFileSync "git" (clj->js args)
                               (clj->js {:cwd cwd :encoding "utf8"
                                         :maxBuffer (* 64 1024 1024) :stdio ["ignore" "pipe" "ignore"]})))
    (catch :default _ nil)))

(defn- repo-tree [file-path]
  (let [dir  (path/dirname file-path)
        root (git ["rev-parse" "--show-toplevel"] dir)]
    (when (and root (not (str/blank? root)))
      (let [out   (git ["ls-files" "--cached" "--others" "--exclude-standard"] root)
            files (when out (vec (remove str/blank? (str/split out #"\n"))))]
        {:root root :files (or files [])}))))
```

Terms:

- **`execFileSync "git" …`** — runs `git` with an argument *array* (not a shell string), so paths
  with spaces/quotes are safe and there is no shell-injection surface. `:stdio ["ignore" "pipe"
  "ignore"]` ignores stdin/stderr and captures stdout. `:maxBuffer (* 64 1024 1024)` allows up to
  64 MiB of `ls-files` output (large monorepos). Any failure (`git` missing, not a repo) is caught
  and returns `nil`.
- **`git rev-parse --show-toplevel`** — prints the absolute path of the repository root that
  contains `dir` (the directory of the open file). This is how the tree is rooted at the repo, not
  at the file's folder.
- **`git ls-files --cached --others --exclude-standard`** — prints every file worth navigating, one
  per line, as **repo-relative** paths: `--cached` lists tracked files, `--others` lists untracked
  files, and `--exclude-standard` drops anything matched by `.gitignore` / `.git/info/exclude` / the
  global excludes. `--cached` and `--others` are disjoint, so there are no duplicates to remove.

When `repo-tree` returns `nil`, the **synthetic root** takes over
([ADR-0030](../design-decisions/0030-fallback-project-roots.md)):

```clojure
(defn- send-tree! [^js wc file-path]
  (when-let [t (or (repo-tree file-path) (dir-walk/dir-tree file-path (directory? file-path)))]
    (.send wc "vv:tree" (clj->js t))))
```

`dir-walk/dir-tree` adopts the file's containing directory — or, when a **directory** is what was
opened, that directory itself, since `path/dirname` of `/notes` is `/` and the filesystem root is never
a project. It then walks it (below) and marks the result `:synthetic? true`. The root is `realpathSync`ed:
`git rev-parse --show-toplevel` resolves symlinks and `path/dirname` does not, and since the renderer
dedupes roots by exact string equality, the same directory reached two ways would otherwise appear twice.

`send-tree!` is called from `open!`, so the tree arrives alongside the document's content. The `vv:tree`
channel is part of the IPC contract ([reference/ipc-channels.md](../reference/ipc-channels.md)).

### MAIN process: the synthetic root's walk

`src/vinary/main/dir-walk.cljs` is the non-git counterpart of `git ls-files`. A repository is
**self-delimiting** (its root bounds it) and **self-filtering** (`--exclude-standard` consults
`.gitignore`); a plain directory is neither, so the walk supplies both properties itself — bounded to
depth 6 and 5000 entries, skipping hidden directories (one rule covering `.git`, `.venv`, `.cache`,
`.next`, `.tox`) and the heavy ones (`node_modules`, `target`, `dist`, `build`, `out`, `__pycache__`).

It is **breadth-first**, which is the one non-obvious choice: when a cap is hit the user gets a useful
*shallow* tree rather than one arbitrarily deep branch. Hidden **files** are kept — `git ls-files
--exclude-standard` lists `.gitignore`, and parity is the goal. Symlinks are `stat`ed through to their
target and listed when that target is a file, but a symlinked **directory** is never descended into,
since a cycle (`ln -s . self`) would not terminate.

The namespace is deliberately **Electron-free** — `service.cljs` pulls in electron, chokidar, and the
content service and so cannot be required outside Electron, whereas `dir-walk` needs only `fs`/`path`.
That is what lets the `:node-test` build exercise the **real** walk against real temporary directories
in [`test/vinary/main/dir_walk_test.cljs`](../../test/vinary/main/dir_walk_test.cljs), rather than a
hand-copied mirror of it.

### RENDERER: store the tree

`src/vinary/renderer/core.cljs` routes `vv:tree` into a re-frame event, and the event folds it into the
project list:

```clojure
(rf/reg-event-db
 :tree/received
 (fn [db [_ entry]]
   (update-in db [:ui :projects] projects/merge-project entry)))
```

`[:ui :projects]` is a **vector** of `{:root :files :synthetic?}`, one entry per open project. Each
project's flat `:files` vector is kept as-is; the nesting is computed in the view (a derived shape), so
there is no second copy to maintain. The merge rules — which decide whether an arriving root joins the
sidebar, refreshes an entry already there, or is swallowed by one that overlaps it — live in the pure
`vinary.app.projects` namespace and are covered in §4.

### RENDERER: fold flat paths into a nested tree

`src/vinary/ui/tree.cljs` turns each project's flat root-relative paths into a nested map with one
`assoc-in` per file:

```clojure
(defn- build-tree [files]
  (reduce (fn [acc f]
            (let [parts (str/split f #"/")
                  ks    (concat (interpose :children parts) [:file])]
              (assoc-in acc ks f)))
          {} files))
```

How the key path is built, by example. For the file `src/vinary/main/core.cljs`:

- `parts` = `["src" "vinary" "main" "core.cljs"]`.
- `(interpose :children parts)` = `["src" :children "vinary" :children "main" :children "core.cljs"]`.
- `ks` = that, with `:file` appended:
  `["src" :children "vinary" :children "main" :children "core.cljs" :file]`.
- `(assoc-in acc ks f)` writes the **full path string** at that leaf under a `:file` key.

So a **folder node** is a map that has a `:children` sub-map, and a **file node** is a map that
has a `:file` string (the original full root-relative path). Two files in the same folder merge
naturally because `assoc-in` shares the common prefix of the key path. The result is a tree like:

```
{"src" {:children
        {"vinary" {:children
                   {"main" {:children
                            {"core.cljs"    {:file "src/vinary/main/core.cljs"}
                             "service.cljs" {:file "src/vinary/main/service.cljs"}}}}}}}}
```

### RENDERER: render nodes to collapsible hiccup

`nodes->hiccup` walks the nested map and emits native `<details>` for folders and `<a>` for files
(abridged — the full version also wires the right-click context menu and platform-dependent
single/double-click opening):

```clojure
(defn- nodes->hiccup [children root active open? dir-prefix]
  (into [:<>]
        (for [[k v] (sort-by (fn [[k v]] [(if (:children v) 0 1) (str/lower-case k)]) children)]
          ^{:key k}
          (if (:children v)
            (let [dpath (str dir-prefix "/" k)]
              [:details.vv-dir (when open? {:open true})
               [:summary.vv-dir-name {:on-context-menu (ctx! :dir dpath)} (icons/folder-icon) k]
               (nodes->hiccup (:children v) root active open? dpath)])
            (let [full (str root "/" (:file v))]
              [:a.vv-file {:class    (when (= full active) "vv-file-active")
                           :title    full
                           :on-click #(rf/dispatch [:doc/open full])}
               (icons/file-icon k) k])))))
```

Details:

- **Sort key `[(if (:children v) 0 1) (str/lower-case k)]`** — folders (`0`) sort before files
  (`1`), then alphabetically (case-insensitively). This gives the familiar "folders first, then
  files, A→Z" ordering.
- **`[:details.vv-dir (when open? {:open true}) …]`** — a native collapsible disclosure. When
  `open?` is true (set during filtering, below) the folder renders expanded (`:open true`);
  otherwise it starts collapsed and the user toggles it. Using `<details>` means the
  collapse/expand state is handled by the browser — no extra re-frame state per folder.
- **`full` path** — the click target is reconstructed as `root + "/" + :file` (an absolute path),
  because MAIN's `open!`/`close!` and the `:doc/path` identity use absolute paths. Clicking
  dispatches `[:doc/open full]`, which sends `vv:open` to MAIN ([feature 02](02-multi-tab-previews.md)).
- **`.vv-file-active`** — highlights the entry for the currently active document.

### RENDERER: one tree per project, and the filter

`file-tree` reads every project, the active path, and the filter string; `project-tree` renders one
project, narrowing its flat file list *before* building the nested tree:

```clojure
(defn- project-tree [{:keys [root files]} active ql]
  (let [shown (cond->> files ql (filter #(str/includes? (str/lower-case %) ql)))]
    (when (seq shown)
      [:details.vv-project {:open true}
       [:summary.vv-project-name {:on-context-menu (ctx! :project root)}
        (icons/folder-icon) (last (str/split root #"/"))]
       (nodes->hiccup (build-tree shown) root active (boolean ql) root)])))

(defn file-tree []
  ;; … (a Reagent class whose :component-did-update calls reveal-active!)
  (let [projects @(rf/subscribe [:ui/projects])
        active   @(rf/subscribe [:ui/active-path])
        q        @(rf/subscribe [:ui/tree-filter])
        ql       (some-> q str/trim str/lower-case not-empty)]
    [:div.vv-tree
     [:input.vv-tree-filter
      {:placeholder "Filter files…"
       :value       (or q "")
       :on-change   #(rf/dispatch [:tree/filter (.. % -target -value)])}]
     (if (seq projects)
       (for [p projects] ^{:key (:root p)} [project-tree p active ql])
       [:div.vv-sidebar-empty "No files open"])]))
```

- **`ql`** — the normalized query: trimmed, lower-cased, and `not-empty` (so a blank or
  whitespace-only filter becomes `nil`, i.e. "no filter"). Defined before use so the rest of the
  function can branch on it.
- **`shown` via `cond->>`** — when `ql` is non-nil, keep only paths whose lower-cased form
  `str/includes?` the query. This is a plain case-insensitive substring match over the full
  root-relative path, so typing `theme` matches `resources/public/css/themes/…`.
- **`(when (seq shown) …)`** — a project with no matches renders nothing, so filtering naturally
  hides whole projects rather than leaving empty headers behind.
- **`(boolean ql)` as the `open?` argument** — when a filter is active, every folder is rendered
  expanded, so the matching files (which may be deep) are immediately visible. With no filter,
  folders start collapsed.
- **`:project` (not `:dir`) on the header** — the project header's context menu can **Remove from
  Files**, which a directory node's cannot; the distinct target kind is what selects that menu.
- **`reveal-active!`** — on every activation the active file's ancestor `<details>` are expanded
  (additively — never collapsing others) and it is scrolled into view.

Because filtering removes non-matching *files* from the flat list before `build-tree`, folders
that end up with no children simply do not appear — there is no separate "prune empty folders"
pass.

---

## 4 · Overlapping roots: why synthetic roots yield

Exact-root dedup is not enough once roots can be inferred. Opening `/notes/a.md` adopts `/notes`;
opening `/notes/sub/c.md` would then adopt `/notes/sub` — a second tree showing files the first already
shows. `vinary.app.projects/merge-project` resolves overlaps with three rules:

1. a root **already present** is replaced **in place**, so re-opening a file refreshes its tree without
   reordering the sidebar;
2. a synthetic root covered by a known **synthetic** root does not become a second tree; its freshly
   walked subtree is **merged into** that root, re-based onto it (`/notes/sub`'s `c.md` becomes
   `/notes`'s `sub/c.md`);
3. a synthetic root covered by a **git** root is **dropped** — git re-lists the whole repository on every
   open of its own, so it is never stale;
4. otherwise it is appended, and any **synthetic** root it now covers is **absorbed** — the broader view
   wins, so `/notes/sub` followed by `/notes` leaves one tree rather than two overlapping ones.

Rule 2 **merges** rather than discards for a concrete reason: `git ls-files --others` guarantees that the
file you just opened is in the tree, and a synthetic root must not be staler than that. Dropping the
update meant a file created *after* its ancestor directory was walked never appeared. Because the
incoming walk is a complete listing of that subtree, the merge also removes files deleted since.

Containment only ever removes **synthetic** roots. The asymmetry is the whole point: **a git root is a
fact; a synthetic root is an inference.** A git repository nested inside a directory you happen to be
browsing is a project in its own right, and survives being enclosed.

Containment is compared on **segment boundaries**, so `/a/bc` is *not* under `/a/b` — a bare
`starts-with?` would silently merge sibling directories that share a prefix.

---

## 5 · Design notes / trade-offs

- **Why `git ls-files` rather than reading the directory, when there is a repo?** With `--cached
  --others --exclude-standard` it gives precisely the files worth navigating — tracked **plus**
  untracked-but-not-ignored — while still ignoring build output, `node_modules`, and anything
  `.gitignore`d, for free by reusing git's own ignore engine. It is fast even on large repos; the
  working-tree walk that `--others` adds is bounded (the heavy ignored dirs are pruned first) and
  blocks the main process only briefly — the same synchronous-`execFileSync` trade-off noted below.
- **Why walk the directory at all, rather than showing nothing?** Because the failure was silent and
  ambiguous: the sidebar read "No files open" while a document was plainly open. Reusing the same
  sidebar for non-repo directories means the Files tab behaves the same way whether or not a folder
  happens to be a checkout. See [ADR-0030](../design-decisions/0030-fallback-project-roots.md) for the
  alternatives weighed (list only the opened file; immediate children only; exact-root dedup only).
- **Why `<details>`/`<summary>` for folders?** Native disclosure widgets keep the open/closed
  state in the DOM, so vinary-viewer does not have to track a per-folder expansion map in app-db.
  Less state, less to keep in sync.
- **Why filter the flat list, not the nested tree?** Filtering strings is trivial and unambiguous;
  pruning a nested tree would require a recursive walk that keeps ancestors of matches. Narrowing
  the flat list and rebuilding gets the same result with simpler code.
- **Trade-off — substring filter, not fuzzy.** The filter is a plain `includes?`, not a fuzzy
  matcher. It is predictable and cheap; a fuzzy/ranked filter is a possible enhancement. (The command
  palette's file finder, which reads the same `[:ui :projects]`, is the fuzzy counterpart.)
- **Trade-off — the synthetic walk is capped.** Depth 6 and 5000 entries. A very large or very deep
  directory is truncated rather than listed in full; breadth-first ordering makes the truncation
  degrade gracefully. A repository has no such cap, because git bounds it for us.
- **Trade-off — projects accumulate.** Opening files from several projects keeps all of their trees in
  the sidebar for the session. **Remove from Files** on a project header is the way back; nothing is
  persisted either way.
- **`execFileSync` is synchronous.** Like the file reads in [feature 01](01-live-refresh.md),
  the git calls block the main process briefly. Acceptable for interactive open; recorded as a
  trade-off. The synthetic walk is likewise synchronous, and bounded for the same reason.

The relevant cross-process decision — that the renderer reaches `git` and the filesystem only through
the main process over the Mediator IPC seam — is recorded in
[ADR-0009 Mediator IPC over point-to-point](../design-decisions/0009-mediator-ipc-over-point-to-point.md).
See the [ADR index](../design-decisions/README.md) for the full list.

---

## 6 · Diagram

- **Sequence — building and rendering the tree:** [`../diagrams/seq-tree.puml`](../diagrams/seq-tree.puml)
  (written by the architecture pillar). Open file → MAIN `git rev-parse` + `git ls-files --cached --others
  --exclude-standard`, **or** the synthetic directory walk when there is no repo →
  `vv:tree {:root :files :synthetic?}` → `:tree/received` (merge into the project list) → `build-tree`
  (flat → nested) → `nodes->hiccup` (`<details>`/`<a>`), with the filter branch narrowing the flat list
  and force-expanding folders.

![File-tree sequence](../diagrams/seq-tree.svg)

Palette: **slate** = MAIN/Node-IO (the `git` calls and the directory walk), **amber** = the IPC seam
(`vv:tree`), **blue-violet** = `app-db` (`:ui/projects`, `:ui/tree-filter`), **teal** = the renderer UI
(the tree view). See [`../diagrams/_vv-theme.iuml`](../diagrams/_vv-theme.iuml).
